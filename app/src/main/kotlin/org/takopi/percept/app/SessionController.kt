package org.takopi.percept.app

import android.content.Context
import android.os.SystemClock
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.takopi.percept.core.da.FileDA
import org.takopi.percept.core.index.EventPointerDatabase
import org.takopi.percept.core.index.RoomEventIndex
import org.takopi.percept.core.trace.IngestedEvent
import org.takopi.percept.core.trace.PerceptionSession
import org.takopi.percept.core.trace.PerceptionSessionConfig
import org.takopi.percept.core.trace.SessionTimeBase
import org.takopi.percept.dispatch.BundleDispatchCoordinator
import org.takopi.percept.dispatch.BundleDispatchRequest
import org.takopi.percept.dispatch.BundleExportResult
import org.takopi.percept.dispatch.BundleUploadDestination
import org.takopi.percept.dispatch.BundleUploadDispatchResult
import org.takopi.percept.dispatch.BundleUploadWorker
import java.net.URL
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class TickerEntry(
    val valueKind: String,
    val eventIdPrefix: String,
    val observedAtIso: String,
    val preview: String?,
)

data class SessionUiState(
    val running: Boolean = false,
    val sessionId: String? = null,
    val lastSessionId: String? = null,
    val eventsIngested: Long = 0,
    val eventsDropped: Long = 0,
    val recentEvents: List<TickerEntry> = emptyList(),
    val lastExportPath: String? = null,
    val lastUploadStatus: String? = null,
)

/**
 * Owns the schema-facing side of recording: DA store, Room index, the
 * ingestion funnel, and bundle dispatch. The device-facing side comes in as a
 * [PerceptionRig] so all of this is host-testable with a fake rig.
 */
class SessionController(
    private val appContext: Context,
    private val settings: PerceptSettings = PerceptSettings(appContext),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val databaseFactory: (Context) -> EventPointerDatabase = { context ->
        Room.databaseBuilder(context, EventPointerDatabase::class.java, "event-pointers.db")
            .build()
    },
    private val monotonicNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
) {
    private val stateFlow = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = stateFlow

    val daRoot: Path = appContext.filesDir.toPath().resolve("da")

    private val da by lazy { FileDA(daRoot) }
    private val database by lazy { databaseFactory(appContext) }
    private val index by lazy { RoomEventIndex(database) }
    private val coordinator by lazy { BundleDispatchCoordinator(index) }

    private var session: PerceptionSession? = null
    private var rig: PerceptionRig? = null
    private var bundleSequence = 0

    @Synchronized
    fun startSession(rig: PerceptionRig) {
        check(session == null) { "session already running" }
        val timeBase = SessionTimeBase(monotonicNanos(), wallClockMillis())
        val sessionId = newSessionId()
        val config = PerceptionSessionConfig(
            deviceId = settings.deviceId,
            sessionId = sessionId,
            detectorRunId = rig.detectorRunId,
            sceneGateRunId = rig.sceneGateRunId,
            audioTaggerRunId = rig.audioTaggerRunId,
            asrRunId = rig.asrRunId,
        )
        val newSession = PerceptionSession(
            da = da,
            index = index,
            config = config,
            timeBase = timeBase,
            onEventIngested = ::onEventIngested,
        )
        stateFlow.update {
            it.copy(
                running = true,
                sessionId = sessionId,
                eventsIngested = 0,
                eventsDropped = 0,
                recentEvents = emptyList(),
            )
        }
        newSession.start(scope)
        rig.start(newSession, timeBase)
        session = newSession
        this.rig = rig
    }

    fun stopSession(onStopped: (() -> Unit)? = null) {
        scope.launch {
            stopSessionAndWait()
            onStopped?.invoke()
        }
    }

    suspend fun stopSessionAndWait() {
        val (activeSession, activeRig) = synchronized(this) {
            val pair = session to rig
            session = null
            rig = null
            pair
        }
        if (activeSession == null || activeRig == null) return
        val counters = activeRig.stop()
        activeSession.stop(counters)
        stateFlow.update {
            it.copy(
                running = false,
                sessionId = null,
                lastSessionId = it.sessionId ?: it.lastSessionId,
                eventsDropped = activeSession.stats().eventsDropped,
            )
        }
        scheduleBackgroundUploadIfConfigured()
    }

    private fun scheduleBackgroundUploadIfConfigured() {
        val endpoint = settings.endpointUrl
        if (endpoint.isBlank()) return
        BundleUploadWorker.schedulePeriodic(
            context = appContext,
            endpoint = endpoint,
            token = settings.bearerToken.ifBlank { null },
            daRoot = daRoot.toString(),
            outputRoot = exportRoot().toString(),
        )
    }

    /** v1a local export: bundle zip under external files for adb pull / share. */
    suspend fun exportLastSessionBundle(): BundleExportResult? {
        val sessionId = stateFlow.value.lastSessionId ?: stateFlow.value.sessionId ?: return null
        return withContext(Dispatchers.IO) {
            val result = coordinator.exportPendingLocal(
                BundleDispatchRequest(
                    sessionId = sessionId,
                    sequence = nextSequence(),
                    sourceDaRoot = daRoot,
                    outputRoot = exportRoot(),
                ),
            )
            stateFlow.update { it.copy(lastExportPath = result.zipPath.toString()) }
            result
        }
    }

    /** v1b: export retryable events and PUT them to the configured endpoint. */
    suspend fun exportAndUpload(): BundleUploadDispatchResult? {
        val sessionId = stateFlow.value.lastSessionId ?: stateFlow.value.sessionId ?: return null
        val endpoint = settings.endpointUrl
        if (endpoint.isBlank()) {
            stateFlow.update { it.copy(lastUploadStatus = "no endpoint configured") }
            return null
        }
        return withContext(Dispatchers.IO) {
            val sequence = nextSequence()
            val result = coordinator.exportAndUploadPending(
                BundleDispatchRequest(
                    sessionId = sessionId,
                    sequence = sequence,
                    sourceDaRoot = daRoot,
                    outputRoot = exportRoot(),
                ),
                BundleUploadDestination(
                    url = URL(endpoint.trimEnd('/') + "/bundles/" + "$sessionId-$sequence"),
                    bearerToken = settings.bearerToken.ifBlank { null },
                ),
            )
            stateFlow.update {
                it.copy(
                    lastUploadStatus = result.upload?.let { upload ->
                        "HTTP ${upload.statusCode}, acked ${result.ackedEvents}"
                    } ?: "nothing to upload",
                )
            }
            result
        }
    }

    @Synchronized
    private fun nextSequence(): Int {
        bundleSequence += 1
        return bundleSequence
    }

    private fun exportRoot(): Path =
        (appContext.getExternalFilesDir(null) ?: appContext.filesDir)
            .toPath()
            .resolve("percept-bundles")

    private fun onEventIngested(event: IngestedEvent) {
        val entry = TickerEntry(
            valueKind = event.pointer.entryString("valueKind"),
            eventIdPrefix = event.eventId.take(EVENT_ID_PREFIX_CHARS),
            observedAtIso = event.envelope.timeIso(),
            preview = event.envelope.previewOrNull(),
        )
        stateFlow.update {
            it.copy(
                eventsIngested = it.eventsIngested + 1,
                recentEvents = (listOf(entry) + it.recentEvents).take(TICKER_LIMIT),
            )
        }
    }

    private fun newSessionId(): String {
        val format = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return "sess-" + format.format(Date(wallClockMillis()))
    }

    companion object {
        const val TICKER_LIMIT: Int = 20
        const val EVENT_ID_PREFIX_CHARS: Int = 18
    }
}

private fun org.takopi.percept.core.canonical.CMap.entryString(key: String): String =
    (entries[key] as? org.takopi.percept.core.canonical.CString)?.value ?: "?"

private fun org.takopi.percept.core.canonical.CMap.timeIso(): String =
    ((entries["time"] as? org.takopi.percept.core.canonical.CMap)
        ?.entries?.get("iso") as? org.takopi.percept.core.canonical.CString)?.value ?: "?"

private fun org.takopi.percept.core.canonical.CMap.previewOrNull(): String? =
    ((entries["value"] as? org.takopi.percept.core.canonical.CMap)
        ?.entries?.get("preview") as? org.takopi.percept.core.canonical.CString)?.value
