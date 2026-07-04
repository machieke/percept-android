package org.takopi.percept.core.trace

import org.takopi.percept.core.canonical.CLong
import org.takopi.percept.core.canonical.CString
import org.takopi.percept.core.canonical.cList
import org.takopi.percept.core.canonical.cMap
import org.takopi.percept.core.canonical.canonicalBytes
import org.takopi.percept.core.da.FileDA
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

fun main(args: Array<String>) {
    val outputDir = args.firstOrNull()?.let(Paths::get)
        ?: Paths.get("core", "trace", "build", "synthetic-m6-bundle")
    SyntheticM6BundleExporter.export(outputDir)
    println("exported synthetic M6 bundle to $outputDir")
}

object SyntheticM6BundleExporter {
    const val SESSION_ID: String = "sess-synthetic-m6"
    const val EXPECTED_EVENT_COUNT: Int = 72
    const val EXPECTED_SESSION_EVENTS: Int = 2
    const val EXPECTED_VIDEO_EVENTS: Int = 40
    const val EXPECTED_AUDIO_EVENTS: Int = 30

    fun export(outputDir: Path) {
        outputDir.toFile().deleteRecursively()
        outputDir.createDirectories()

        val da = FileDA(outputDir)
        val index = MemoryEventIndex()
        val ingestor = EventIngestor(da, index)
        val pointerLines = mutableListOf<String>()

        fun record(event: IngestedEvent): IngestedEvent {
            pointerLines += canonicalBytes(event.pointer).toString(StandardCharsets.UTF_8)
            return event
        }

        val root = record(
            ingestor.ingestEvent(
                rawPayload = cMap(
                    "kind" to CString("raw-payload"),
                    "schema" to CString("perception-session-v0.1"),
                    "device" to CString("moto-g84-5g"),
                    "sessionId" to CString(SESSION_ID),
                    "monotonicAnchorNanos" to CLong(123456789000),
                    "observedAt" to CString(observedAt(0)),
                    "models" to cList(
                        CString("synthetic-detector-v0@jvm"),
                        CString("synthetic-yamnet-v0@jvm"),
                        CString("synthetic-asr-v0@jvm"),
                    ),
                ),
                observedAt = observedAt(0),
                actorPath = listOf("device", "moto-g84-5g", "app", "percept"),
                channelPath = sessionChannelPath(),
                valueKind = "session-start",
                preview = "session $SESSION_ID start",
                provenance = androidProvenance(),
            ),
        )

        val sceneEventIds = mutableListOf<String>()
        for (sceneIndex in 0 until 10) {
            val secondOffset = sceneIndex * 30
            val keyframeCid = da.putBytes(
                "synthetic-keyframe:$SESSION_ID:$sceneIndex".toByteArray(StandardCharsets.UTF_8),
                codec = "raw",
            )
            val scene = record(
                ingestor.ingestEvent(
                    rawPayload = cMap(
                        "kind" to CString("raw-payload"),
                        "schema" to CString("perception-scene-v0.1"),
                        "sessionId" to CString(SESSION_ID),
                        "sceneIndex" to CLong(sceneIndex.toLong()),
                        "tNanos" to CLong(secondOffset.secondsToNanos()),
                        "gateMetricPerMille" to CLong((600 + sceneIndex * 11).toLong()),
                        "observedAt" to CString(observedAt(secondOffset)),
                    ),
                    observedAt = observedAt(secondOffset),
                    actorPath = cameraActorPath(),
                    channelPath = videoChannelPath(),
                    valueKind = "scene-change",
                    parentEventIds = listOf(root.eventId),
                    rootEventId = root.eventId,
                    outputArtifactIds = listOf(keyframeCid),
                    provenance = androidProvenance("synthetic-scene-gate-v0@jvm"),
                ),
            )
            sceneEventIds += scene.eventId
        }

        for (trackIndex in 0 until 30) {
            val sceneIndex = (trackIndex / 3).coerceAtMost(sceneEventIds.lastIndex)
            val startSecond = trackIndex * 10 + 1
            val endSecond = (startSecond + 8).coerceAtMost(299)
            val label = when (trackIndex % 4) {
                0 -> "person"
                1 -> "cup"
                2 -> "chair"
                else -> "phone"
            }
            record(
                ingestor.ingestEvent(
                    rawPayload = cMap(
                        "kind" to CString("raw-payload"),
                        "schema" to CString("perception-track-segment-v0.1"),
                        "sessionId" to CString(SESSION_ID),
                        "trackId" to CLong((trackIndex + 1).toLong()),
                        "label" to CString(label),
                        "labelSpace" to CString("coco-80"),
                        "scorePerMille" to CLong((720 + trackIndex * 3).toLong()),
                        "tStartNanos" to CLong(startSecond.secondsToNanos()),
                        "tEndNanos" to CLong(endSecond.secondsToNanos()),
                        "frameCount" to CLong((80 + trackIndex).toLong()),
                        "boxFirst" to cList(
                            CLong((90 + trackIndex).toLong()),
                            CLong(72),
                            CLong((230 + trackIndex).toLong()),
                            CLong(390),
                        ),
                        "boxLast" to cList(
                            CLong((110 + trackIndex).toLong()),
                            CLong(78),
                            CLong((250 + trackIndex).toLong()),
                            CLong(402),
                        ),
                        "observedAt" to CString(observedAt(endSecond)),
                    ),
                    observedAt = observedAt(endSecond),
                    actorPath = cameraActorPath(),
                    channelPath = videoChannelPath(),
                    valueKind = "track-segment",
                    parentEventIds = listOf(root.eventId, sceneEventIds[sceneIndex]),
                    rootEventId = root.eventId,
                    provenance = androidProvenance("synthetic-detector-v0@jvm"),
                ),
            )
        }

        for (tagIndex in 0 until 20) {
            val startSecond = tagIndex * 15 + 2
            val endSecond = (startSecond + 9).coerceAtMost(299)
            val label = when (tagIndex % 5) {
                0 -> "Speech"
                1 -> "Music"
                2 -> "Typing"
                3 -> "Door"
                else -> "Vehicle"
            }
            record(
                ingestor.ingestEvent(
                    rawPayload = cMap(
                        "kind" to CString("raw-payload"),
                        "schema" to CString("perception-audio-tag-v0.1"),
                        "sessionId" to CString(SESSION_ID),
                        "label" to CString(label),
                        "labelSpace" to CString("audioset"),
                        "scorePerMille" to CLong((650 + tagIndex * 4).toLong()),
                        "tStartNanos" to CLong(startSecond.secondsToNanos()),
                        "tEndNanos" to CLong(endSecond.secondsToNanos()),
                        "observedAt" to CString(observedAt(endSecond)),
                    ),
                    observedAt = observedAt(endSecond),
                    actorPath = microphoneActorPath(),
                    channelPath = audioChannelPath(),
                    valueKind = "audio-tag-segment",
                    parentEventIds = listOf(root.eventId),
                    rootEventId = root.eventId,
                    provenance = androidProvenance("synthetic-yamnet-v0@jvm"),
                ),
            )
        }

        for (asrIndex in 0 until 10) {
            val startSecond = asrIndex * 30 + 12
            val endSecond = (startSecond + 6).coerceAtMost(299)
            val text = "synthetic transcript segment ${asrIndex + 1} for $SESSION_ID"
            record(
                ingestor.ingestEvent(
                    rawPayload = cMap(
                        "kind" to CString("raw-payload"),
                        "schema" to CString("perception-asr-v0.1"),
                        "sessionId" to CString(SESSION_ID),
                        "text" to CString(text),
                        "langHint" to CString("en"),
                        "tStartNanos" to CLong(startSecond.secondsToNanos()),
                        "tEndNanos" to CLong(endSecond.secondsToNanos()),
                        "avgLogProbMicro" to CLong(-180_000),
                        "observedAt" to CString(observedAt(endSecond)),
                    ),
                    observedAt = observedAt(endSecond),
                    actorPath = microphoneActorPath(),
                    channelPath = audioChannelPath(),
                    valueKind = "asr-segment",
                    preview = text.take(160),
                    parentEventIds = listOf(root.eventId),
                    rootEventId = root.eventId,
                    provenance = androidProvenance("synthetic-asr-v0@jvm"),
                ),
            )
        }

        record(
            ingestor.ingestEvent(
                rawPayload = sessionStopPayload(
                    sessionId = SESSION_ID,
                    tStartNanos = 0,
                    tEndNanos = 300.secondsToNanos(),
                    observedAt = observedAt(300),
                    counters = SessionStopCounters(
                        framesProcessed = 2_400,
                        eventsEmitted = EXPECTED_EVENT_COUNT.toLong(),
                        droppedFrames = 12,
                        audioRingBufferOverruns = 0,
                        thermalThrottleEvents = 1,
                    ),
                ),
                observedAt = observedAt(300),
                actorPath = listOf("device", "moto-g84-5g", "app", "percept"),
                channelPath = sessionChannelPath(),
                valueKind = "session-stop",
                preview = "session $SESSION_ID stop",
                parentEventIds = listOf(root.eventId),
                rootEventId = root.eventId,
                provenance = androidProvenance(),
            ),
        )

        require(pointerLines.size == EXPECTED_EVENT_COUNT) {
            "expected $EXPECTED_EVENT_COUNT events, got ${pointerLines.size}"
        }
        outputDir.resolve("pointers.jsonl").writeText(
            pointerLines.joinToString(separator = "\n", postfix = "\n"),
            StandardCharsets.UTF_8,
        )
    }

    private fun sessionChannelPath(): List<String> =
        listOf("perception", SESSION_ID, "session")

    private fun videoChannelPath(): List<String> =
        listOf("perception", SESSION_ID, "video")

    private fun audioChannelPath(): List<String> =
        listOf("perception", SESSION_ID, "audio")

    private fun cameraActorPath(): List<String> =
        listOf("device", "moto-g84-5g", "camera", "0")

    private fun microphoneActorPath(): List<String> =
        listOf("device", "moto-g84-5g", "microphone", "0")

    private fun androidProvenance(extractionRunId: String? = null) = if (extractionRunId == null) {
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

    private fun observedAt(secondOffset: Int): String {
        val time = OffsetDateTime.of(2026, 7, 4, 12, 0, 0, 0, ZoneOffset.UTC)
            .plusSeconds(secondOffset.toLong())
        return "%04d-%02d-%02dT%02d:%02d:%02dZ".format(
            Locale.US,
            time.year,
            time.monthValue,
            time.dayOfMonth,
            time.hour,
            time.minute,
            time.second,
        )
    }

    private fun Int.secondsToNanos(): Long = toLong() * 1_000_000_000L
}
