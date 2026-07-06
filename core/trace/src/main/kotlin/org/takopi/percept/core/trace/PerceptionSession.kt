package org.takopi.percept.core.trace

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.takopi.percept.core.canonical.CLong
import org.takopi.percept.core.canonical.CMap
import org.takopi.percept.core.canonical.CString
import org.takopi.percept.core.canonical.cMap
import org.takopi.percept.core.canonical.stringList
import org.takopi.percept.core.da.DAStore
import java.util.concurrent.atomic.AtomicLong

data class PerceptionSessionConfig(
    val deviceId: String,
    val sessionId: String,
    val cameraActorId: String = "0",
    val microphoneActorId: String = "0",
    val detectorRunId: String,
    val sceneGateRunId: String,
    val audioTaggerRunId: String,
    val asrRunId: String,
) {
    init {
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
    }
}

data class PerceptionRunCounters(
    val tEndNanos: Long,
    val framesProcessed: Long,
    val droppedFrames: Long,
    val audioRingBufferOverruns: Long,
    val thermalThrottleEvents: Long,
    /** Diagnostic counters merged into the stop payload (e.g. ASR timing). */
    val extraCounters: Map<String, Long> = emptyMap(),
)

data class PerceptionSessionStats(
    val eventsIngested: Long,
    val eventsDropped: Long,
)

/**
 * The single ingestion funnel of §3.2: perception threads [trySubmit] events
 * into a bounded channel; one consumer coroutine serializes all DA and index
 * writes, so no locking subtleties exist downstream.
 */
class PerceptionSession(
    private val da: DAStore,
    index: EventIndex,
    private val config: PerceptionSessionConfig,
    private val timeBase: SessionTimeBase,
    capacity: Int = DEFAULT_CAPACITY,
    private val onEventIngested: ((IngestedEvent) -> Unit)? = null,
) : TraceSink {
    private val ingestor = EventIngestor(da, index)
    private val channel = Channel<PerceptionEvent>(capacity)
    private val eventsIngested = AtomicLong(0)
    private val eventsDropped = AtomicLong(0)
    private var consumerJob: Job? = null
    private var stopped = false

    @Volatile
    private var rootEventIdOrNull: String? = null

    // Consumer-coroutine state only; scene-scoped parenting per §2.
    private var currentSceneEventId: String? = null

    val rootEventId: String
        get() = checkNotNull(rootEventIdOrNull) { "session not started" }

    fun stats(): PerceptionSessionStats =
        PerceptionSessionStats(eventsIngested.get(), eventsDropped.get())

    fun start(scope: CoroutineScope): IngestedEvent {
        check(rootEventIdOrNull == null) { "session already started" }
        val started = ingestor.ingestEvent(
            rawPayload = cMap(
                "kind" to CString("raw-payload"),
                "schema" to CString("perception-session-v0.1"),
                "device" to CString(config.deviceId),
                "sessionId" to CString(config.sessionId),
                "monotonicAnchorNanos" to CLong(timeBase.monotonicAnchorNanos),
                "observedAt" to CString(timeBase.observedAtIso(0)),
                "models" to stringList(
                    listOf(config.detectorRunId, config.audioTaggerRunId, config.asrRunId),
                ),
            ),
            observedAt = timeBase.observedAtIso(0),
            actorPath = appActorPath(),
            channelPath = channelPath("session"),
            valueKind = "session-start",
            preview = "session ${config.sessionId} start",
            provenance = provenance(null),
        )
        rootEventIdOrNull = started.eventId
        recordIngested(started)
        consumerJob = scope.launch {
            for (event in channel) {
                ingest(event)
            }
        }
        return started
    }

    override fun trySubmit(event: PerceptionEvent): Boolean {
        val accepted = channel.trySend(event).isSuccess
        if (!accepted) {
            eventsDropped.incrementAndGet()
        }
        return accepted
    }

    /** Closes the funnel, drains queued events, then ingests session-stop. */
    suspend fun stop(counters: PerceptionRunCounters): IngestedEvent {
        val root = rootEventId
        check(!stopped) { "session already stopped" }
        stopped = true
        channel.close()
        consumerJob?.join()
        val observedAt = timeBase.observedAtIso(counters.tEndNanos)
        val stop = ingestor.ingestEvent(
            rawPayload = sessionStopPayload(
                sessionId = config.sessionId,
                tStartNanos = 0,
                tEndNanos = counters.tEndNanos,
                observedAt = observedAt,
                counters = SessionStopCounters(
                    framesProcessed = counters.framesProcessed,
                    // Includes session-start, every funneled event, and this stop event.
                    eventsEmitted = eventsIngested.get() + 1,
                    droppedFrames = counters.droppedFrames,
                    audioRingBufferOverruns = counters.audioRingBufferOverruns,
                    thermalThrottleEvents = counters.thermalThrottleEvents,
                ),
                extraCounters = counters.extraCounters,
            ),
            observedAt = observedAt,
            actorPath = appActorPath(),
            channelPath = channelPath("session"),
            valueKind = "session-stop",
            preview = "session ${config.sessionId} stop",
            parentEventIds = listOf(root),
            rootEventId = root,
            provenance = provenance(null),
        )
        recordIngested(stop)
        return stop
    }

    private fun ingest(event: PerceptionEvent) {
        val root = rootEventId
        val ingested = when (event) {
            is PerceptionEvent.SceneChange -> {
                val keyframeCid = event.keyframeJpeg?.let { da.putBytes(it, codec = "raw") }
                val scene = ingestor.ingestEvent(
                    rawPayload = cMap(
                        "kind" to CString("raw-payload"),
                        "schema" to CString("perception-scene-v0.1"),
                        "sessionId" to CString(config.sessionId),
                        "sceneIndex" to CLong(event.sceneIndex.toLong()),
                        "tNanos" to CLong(event.tNanos),
                        "gateMetricPerMille" to CLong(event.gateMetricPerMille.toLong()),
                        "observedAt" to CString(timeBase.observedAtIso(event.tNanos)),
                    ),
                    observedAt = timeBase.observedAtIso(event.tNanos),
                    actorPath = cameraActorPath(),
                    channelPath = channelPath("video"),
                    valueKind = "scene-change",
                    parentEventIds = listOf(root),
                    rootEventId = root,
                    outputArtifactIds = listOfNotNull(keyframeCid),
                    provenance = provenance(config.sceneGateRunId),
                )
                currentSceneEventId = scene.eventId
                scene
            }

            is PerceptionEvent.TrackSegment -> ingestor.ingestEvent(
                rawPayload = cMap(
                    "kind" to CString("raw-payload"),
                    "schema" to CString("perception-track-segment-v0.1"),
                    "sessionId" to CString(config.sessionId),
                    "trackId" to CLong(event.trackId),
                    "label" to CString(event.label),
                    "labelSpace" to CString(event.labelSpace),
                    "scorePerMille" to CLong(event.scorePerMille.toLong()),
                    "tStartNanos" to CLong(event.tStartNanos),
                    "tEndNanos" to CLong(event.tEndNanos),
                    "frameCount" to CLong(event.frameCount),
                    "boxFirst" to intList(event.boxFirst),
                    "boxLast" to intList(event.boxLast),
                    "observedAt" to CString(timeBase.observedAtIso(event.tEndNanos)),
                ),
                observedAt = timeBase.observedAtIso(event.tEndNanos),
                actorPath = cameraActorPath(),
                channelPath = channelPath("video"),
                valueKind = "track-segment",
                parentEventIds = listOfNotNull(root, currentSceneEventId),
                rootEventId = root,
                provenance = provenance(config.detectorRunId),
            )

            is PerceptionEvent.AudioTagSegment -> ingestor.ingestEvent(
                rawPayload = cMap(
                    "kind" to CString("raw-payload"),
                    "schema" to CString("perception-audio-tag-v0.1"),
                    "sessionId" to CString(config.sessionId),
                    "label" to CString(event.label),
                    "labelSpace" to CString(event.labelSpace),
                    "scorePerMille" to CLong(event.scorePerMille.toLong()),
                    "tStartNanos" to CLong(event.tStartNanos),
                    "tEndNanos" to CLong(event.tEndNanos),
                    "observedAt" to CString(timeBase.observedAtIso(event.tEndNanos)),
                ),
                observedAt = timeBase.observedAtIso(event.tEndNanos),
                actorPath = microphoneActorPath(),
                channelPath = channelPath("audio"),
                valueKind = "audio-tag-segment",
                parentEventIds = listOf(root),
                rootEventId = root,
                provenance = provenance(config.audioTaggerRunId),
            )

            is PerceptionEvent.LocationFix -> ingestor.ingestEvent(
                rawPayload = run {
                    val entries = linkedMapOf<String, org.takopi.percept.core.canonical.CanonicalValue>(
                        "kind" to CString("raw-payload"),
                        "schema" to CString("perception-location-v0.1"),
                        "sessionId" to CString(config.sessionId),
                        "tNanos" to CLong(event.tNanos),
                        "latE7" to CLong(event.latE7),
                        "lonE7" to CLong(event.lonE7),
                        "accuracyCm" to CLong(event.accuracyCm),
                        "provider" to CString(event.provider),
                        "observedAt" to CString(timeBase.observedAtIso(event.tNanos)),
                    )
                    // Optional key: absent entirely when unknown (presence
                    // changes canonical bytes).
                    event.altitudeCm?.let { entries["altitudeCm"] = CLong(it) }
                    org.takopi.percept.core.canonical.CMap(entries)
                },
                observedAt = timeBase.observedAtIso(event.tNanos),
                actorPath = listOf("device", config.deviceId, "location", "0"),
                channelPath = channelPath("location"),
                valueKind = "location-fix",
                parentEventIds = listOf(root),
                rootEventId = root,
                provenance = provenance(null),
            )

            is PerceptionEvent.AudioChunk -> {
                val audioCid = da.putBytes(event.encoded, codec = "raw")
                ingestor.ingestEvent(
                    rawPayload = cMap(
                        "kind" to CString("raw-payload"),
                        "schema" to CString("perception-audio-chunk-v0.1"),
                        "sessionId" to CString(config.sessionId),
                        "chunkIndex" to CLong(event.chunkIndex.toLong()),
                        "tStartNanos" to CLong(event.tStartNanos),
                        "tEndNanos" to CLong(event.tEndNanos),
                        "sampleRate" to CLong(event.sampleRate.toLong()),
                        "sampleCount" to CLong(event.sampleCount),
                        "contentType" to CString(event.contentType),
                        "codec" to CString(event.codecId),
                        "sizeBytes" to CLong(event.encoded.size.toLong()),
                        "observedAt" to CString(timeBase.observedAtIso(event.tEndNanos)),
                    ),
                    observedAt = timeBase.observedAtIso(event.tEndNanos),
                    actorPath = microphoneActorPath(),
                    channelPath = channelPath("audio"),
                    valueKind = "audio-chunk",
                    parentEventIds = listOf(root),
                    rootEventId = root,
                    outputArtifactIds = listOf(audioCid),
                    provenance = provenance(null),
                )
            }

            is PerceptionEvent.AsrSegment -> ingestor.ingestEvent(
                rawPayload = cMap(
                    "kind" to CString("raw-payload"),
                    "schema" to CString("perception-asr-v0.1"),
                    "sessionId" to CString(config.sessionId),
                    "text" to CString(event.text),
                    "langHint" to CString(event.langHint),
                    "tStartNanos" to CLong(event.tStartNanos),
                    "tEndNanos" to CLong(event.tEndNanos),
                    "avgLogProbMicro" to CLong(event.avgLogProbMicro),
                    "observedAt" to CString(timeBase.observedAtIso(event.tEndNanos)),
                ),
                observedAt = timeBase.observedAtIso(event.tEndNanos),
                actorPath = microphoneActorPath(),
                channelPath = channelPath("audio"),
                valueKind = "asr-segment",
                preview = event.text.take(PREVIEW_MAX_CHARS),
                parentEventIds = listOf(root),
                rootEventId = root,
                provenance = provenance(config.asrRunId),
            )
        }
        recordIngested(ingested)
    }

    private fun recordIngested(event: IngestedEvent) {
        eventsIngested.incrementAndGet()
        onEventIngested?.invoke(event)
    }

    private fun appActorPath(): List<String> =
        listOf("device", config.deviceId, "app", "percept")

    private fun cameraActorPath(): List<String> =
        listOf("device", config.deviceId, "camera", config.cameraActorId)

    private fun microphoneActorPath(): List<String> =
        listOf("device", config.deviceId, "microphone", config.microphoneActorId)

    private fun channelPath(modality: String): List<String> =
        listOf("perception", config.sessionId, modality)

    private fun provenance(extractionRunId: String?): CMap = if (extractionRunId == null) {
        cMap(
            "source" to CString("android-percept"),
            "observedBy" to CString("percept-app"),
            "ingestionPipeline" to CString("event-trace-v0"),
        )
    } else {
        cMap(
            "source" to CString("android-percept"),
            "observedBy" to CString("percept-app"),
            "ingestionPipeline" to CString("event-trace-v0"),
            "extractionRunId" to CString(extractionRunId),
        )
    }

    private fun intList(values: List<Int>) =
        org.takopi.percept.core.canonical.CList(values.map { CLong(it.toLong()) })

    companion object {
        const val DEFAULT_CAPACITY: Int = 256
        const val PREVIEW_MAX_CHARS: Int = 160
    }
}
