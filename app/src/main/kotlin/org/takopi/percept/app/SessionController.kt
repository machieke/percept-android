package org.takopi.percept.app

import android.content.Context
import android.os.SystemClock
import androidx.room.Room
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.takopi.percept.core.da.FileDA
import org.takopi.percept.core.index.EventPointerDatabase
import org.takopi.percept.core.index.RoomEventIndex
import org.takopi.percept.core.trace.IngestedEvent
import org.takopi.percept.core.trace.PerceptionRunCounters
import org.takopi.percept.core.trace.PerceptionSession
import org.takopi.percept.core.trace.PerceptionSessionConfig
import org.takopi.percept.core.trace.SessionTimeBase
import org.takopi.percept.dispatch.BundleDispatchCoordinator
import org.takopi.percept.dispatch.BundleDispatchRequest
import org.takopi.percept.dispatch.BundleExportResult
import org.takopi.percept.dispatch.BundleUploadDestination
import org.takopi.percept.dispatch.BundleUploadDispatchResult
import org.takopi.percept.dispatch.BundleUploadWorker
import org.takopi.percept.dispatch.BundleUploader
import org.takopi.percept.dispatch.DaRetentionCandidateCollector
import org.takopi.percept.dispatch.FileRetentionEvictor
import org.takopi.percept.dispatch.HttpBundleUploader
import org.takopi.percept.dispatch.LiveEventStreamer
import org.takopi.percept.dispatch.RetentionPlanner
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
    /** Teardown in progress: recording has ended but the rig is still
     *  releasing the camera, so a new session must not start yet. */
    val stopping: Boolean = false,
    val sessionId: String? = null,
    val lastSessionId: String? = null,
    val eventsIngested: Long = 0,
    val eventsDropped: Long = 0,
    val recentEvents: List<TickerEntry> = emptyList(),
    val lastExportPath: String? = null,
    val lastUploadStatus: String? = null,
    val lastError: String? = null,
)

/**
 * Owns the schema-facing side of recording: DA store, Room index, the
 * ingestion funnel, and bundle dispatch. The device-facing side comes in as a
 * [PerceptionRig] so all of this is host-testable with a fake rig.
 */
class SessionController(
    private val appContext: Context,
    private val settings: PerceptSettings = PerceptSettings(appContext),
    externalScope: CoroutineScope? = null,
    private val databaseFactory: (Context) -> EventPointerDatabase = { context ->
        Room.databaseBuilder(context, EventPointerDatabase::class.java, "event-pointers.db")
            .build()
    },
    private val monotonicNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val uploader: BundleUploader = HttpBundleUploader(),
    private val autoDispatchIntervalMillis: Long = DEFAULT_AUTO_DISPATCH_MILLIS,
    private val retentionCapBytes: Long = BundleUploadWorker.LEAN_CAP_BYTES,
) {
    private val stateFlow = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = stateFlow

    // Uncaught failures (e.g. in the ingestion consumer) must surface in the
    // UI, not take down the process.
    private val scope: CoroutineScope = externalScope ?: CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            stateFlow.update { it.copy(lastError = "background failure: ${throwable.message}") }
        },
    )

    val daRoot: Path = appContext.filesDir.toPath().resolve("da")

    private val da by lazy { FileDA(daRoot) }
    private val database by lazy { databaseFactory(appContext) }
    private val index by lazy { RoomEventIndex(database) }
    private val coordinator by lazy { BundleDispatchCoordinator(index, uploader = uploader) }

    private var session: PerceptionSession? = null
    private var rig: PerceptionRig? = null
    private var activeTimeBase: SessionTimeBase? = null
    private var autoDispatchJob: Job? = null
    private var liveStreamer: LiveEventStreamer? = null
    private var starting = false
    private var bundleSequence = 0

    /** Non-fatal problems reported by the rig (e.g. detector failures). */
    fun reportRigError(message: String) {
        stateFlow.update { it.copy(lastError = message) }
    }

    /**
     * Fire-and-forget start for UI/service callers. Everything heavy — rig
     * construction (model loading), DA writes, Room inserts — runs off the
     * main thread; Room forbids main-thread access and model init takes
     * seconds. Failures land in [SessionUiState.lastError], never a crash.
     */
    fun startSession(rigFactory: () -> PerceptionRig) {
        scope.launch {
            try {
                startSessionAndWait(rigFactory())
            } catch (t: Throwable) {
                stateFlow.update {
                    it.copy(running = false, sessionId = null, lastError = "start failed: ${t.message}")
                }
            }
        }
    }

    fun startSessionAndWait(rig: PerceptionRig) {
        synchronized(this) {
            check(session == null && !starting) { "session already running" }
            // Refuse to start while a previous session's rig is still
            // releasing the camera, or the two rigs fight over it.
            check(!stateFlow.value.stopping) { "previous session still stopping" }
            // Reserve the slot before the slow work so double-taps can't race.
            starting = true
        }
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
        // Start streaming before session-start ingests so the root event
        // reaches the memory server live too.
        if (settings.endpointUrl.isNotBlank()) {
            liveStreamer = LiveEventStreamer(
                da = da,
                index = index,
                endpointUrl = settings.endpointUrl,
                bearerToken = settings.bearerToken.ifBlank { null },
                onError = ::reportRigError,
            ).also { it.start(scope) }
        }
        try {
            stateFlow.update {
                it.copy(
                    running = true,
                    sessionId = sessionId,
                    eventsIngested = 0,
                    eventsDropped = 0,
                    recentEvents = emptyList(),
                    lastError = null,
                )
            }
            newSession.start(scope)
            rig.start(newSession, timeBase)
        } catch (t: Throwable) {
            synchronized(this) {
                starting = false
            }
            liveStreamer?.let { streamer -> scope.launch { streamer.stop() } }
            liveStreamer = null
            throw t
        }
        synchronized(this) {
            session = newSession
            this.rig = rig
            activeTimeBase = timeBase
            starting = false
        }
        // Continuous accumulation: dispatch mid-session so long recordings
        // reach the memory server as they happen, not only at stop.
        if (settings.endpointUrl.isNotBlank()) {
            autoDispatchJob = scope.launch {
                while (true) {
                    delay(autoDispatchIntervalMillis)
                    runCatching { exportAndUpload() }
                }
            }
        }
    }

    fun stopSession(onStopped: (() -> Unit)? = null) {
        scope.launch {
            try {
                stopSessionAndWait()
            } catch (t: Throwable) {
                stateFlow.update {
                    it.copy(running = false, sessionId = null, lastError = "stop failed: ${t.message}")
                }
            }
            onStopped?.invoke()
        }
    }

    suspend fun stopSessionAndWait() {
        autoDispatchJob?.cancel()
        autoDispatchJob = null
        val (activeSession, activeRig) = synchronized(this) {
            val pair = session to rig
            session = null
            rig = null
            pair
        }
        val timeBase = synchronized(this) { activeTimeBase }
        if (activeSession == null || activeRig == null) {
            liveStreamer?.close()
            liveStreamer = null
            return
        }
        // Flip the UI to stopped immediately: the session is logically over
        // the moment stop is pressed, and teardown below (rig shutdown, the
        // session-stop write, streamer drain) can take a while on a flaky
        // network — it must not freeze the buttons.
        stateFlow.update {
            it.copy(
                running = false,
                stopping = true,
                sessionId = null,
                lastSessionId = it.sessionId ?: it.lastSessionId,
            )
        }
        // The session-stop event must be written even when capture teardown
        // fails; fall back to zeroed counters and surface the rig error.
        val counters = try {
            activeRig.stop()
        } catch (t: Throwable) {
            stateFlow.update { it.copy(lastError = "rig stop failed: ${t.message}") }
            PerceptionRunCounters(
                tEndNanos = timeBase?.let { it.elapsedNanos(monotonicNanos()) } ?: 0,
                framesProcessed = 0,
                droppedFrames = 0,
                audioRingBufferOverruns = 0,
                thermalThrottleEvents = 0,
            )
        }
        activeSession.stop(counters)
        // Close the streamer only after session-stop is ingested, so it too
        // streams; close is non-blocking — undelivered events stay PENDING
        // for bundle backfill and drain in the background.
        liveStreamer?.close()
        liveStreamer = null
        stateFlow.update {
            it.copy(stopping = false, eventsDropped = activeSession.stats().eventsDropped)
        }
        scheduleBackgroundUploadIfConfigured()
    }

    private fun scheduleBackgroundUploadIfConfigured() {
        val endpoint = settings.endpointUrl
        if (endpoint.isBlank()) return
        // Immediately flush this session's remainder, then keep the hourly
        // sweeper alive for anything a dead network leaves behind.
        BundleUploadWorker.enqueueImmediate(
            context = appContext,
            endpoint = endpoint,
            token = settings.bearerToken.ifBlank { null },
            daRoot = daRoot.toString(),
            outputRoot = exportRoot().toString(),
        )
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
            if (result.ackedEvents > 0) {
                // Lean buffer: shed acked artifacts beyond the local cap.
                val candidates = DaRetentionCandidateCollector(index).collect(daRoot)
                val plan = RetentionPlanner().planEviction(candidates, capBytes = retentionCapBytes)
                FileRetentionEvictor().evict(plan.evict)
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

    /** Newest exported zip on disk; survives app restarts unlike UI state. */
    fun latestBundleZipPath(): String? =
        exportRoot().toFile()
            .listFiles { file -> file.isFile && file.name.endsWith(".zip") }
            ?.maxByOrNull { it.lastModified() }
            ?.absolutePath

    private fun onEventIngested(event: IngestedEvent) {
        liveStreamer?.trySubmit(
            LiveEventStreamer.StreamedEvent(
                eventId = event.eventId,
                pointerJson = org.takopi.percept.core.canonical.canonicalBytes(event.pointer)
                    .toString(Charsets.UTF_8),
                objectCids = listOf(event.eventCid, event.payloadCid) +
                    event.pointer.cidList("outputArtifactIds"),
            ),
        )
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
        const val DEFAULT_AUTO_DISPATCH_MILLIS: Long = 5L * 60L * 1000L
    }
}

private fun org.takopi.percept.core.canonical.CMap.entryString(key: String): String =
    (entries[key] as? org.takopi.percept.core.canonical.CString)?.value ?: "?"

private fun org.takopi.percept.core.canonical.CMap.cidList(key: String): List<String> =
    (entries[key] as? org.takopi.percept.core.canonical.CList)?.values
        ?.mapNotNull { (it as? org.takopi.percept.core.canonical.CString)?.value }
        .orEmpty()

private fun org.takopi.percept.core.canonical.CMap.timeIso(): String =
    ((entries["time"] as? org.takopi.percept.core.canonical.CMap)
        ?.entries?.get("iso") as? org.takopi.percept.core.canonical.CString)?.value ?: "?"

private fun org.takopi.percept.core.canonical.CMap.previewOrNull(): String? =
    ((entries["value"] as? org.takopi.percept.core.canonical.CMap)
        ?.entries?.get("preview") as? org.takopi.percept.core.canonical.CString)?.value
