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
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

fun main(args: Array<String>) {
    val outputDir = args.firstOrNull()?.let(Paths::get)
        ?: Paths.get("core", "trace", "build", "parity-fixture-bundle")
    ParityFixtureExporter.export(outputDir)
    println("exported parity fixture bundle to $outputDir")
}

object ParityFixtureExporter {
    fun export(outputDir: Path) {
        outputDir.toFile().deleteRecursively()
        outputDir.createDirectories()

        val da = FileDA(outputDir)
        val index = MemoryEventIndex()
        val ingestor = EventIngestor(da, index)
        val pointerLines = mutableListOf<String>()

        fun record(event: IngestedEvent) {
            pointerLines += canonicalBytes(event.pointer).toString(StandardCharsets.UTF_8)
        }

        val root = ingestor.ingestEvent(
            rawPayload = cMap(
                "kind" to CString("raw-payload"),
                "schema" to CString("perception-session-v0.1"),
                "device" to CString("moto-g84-5g"),
                "sessionId" to CString("sess-parity-0001"),
                "monotonicAnchorNanos" to CLong(123456789000),
                "observedAt" to CString(observedAt(0)),
            ),
            observedAt = observedAt(0),
            actorPath = listOf("device", "moto-g84-5g", "app", "percept"),
            channelPath = listOf("perception", "sess-parity-0001", "session"),
            valueKind = "session-start",
            preview = "session sess-parity-0001 start",
            provenance = androidProvenance(),
        )
        record(root)

        for (trackId in 1..49) {
            val observedAt = observedAt(trackId)
            val tStartNanos = (trackId - 1) * 1_000_000_000L
            val tEndNanos = trackId * 1_000_000_000L
            val label = if (trackId % 3 == 0) "cup" else "person"
            val event = ingestor.ingestEvent(
                rawPayload = cMap(
                    "kind" to CString("raw-payload"),
                    "schema" to CString("perception-track-segment-v0.1"),
                    "sessionId" to CString("sess-parity-0001"),
                    "trackId" to CLong(trackId.toLong()),
                    "label" to CString(label),
                    "labelSpace" to CString("coco-80"),
                    "scorePerMille" to CLong((700 + trackId).toLong()),
                    "tStartNanos" to CLong(tStartNanos),
                    "tEndNanos" to CLong(tEndNanos),
                    "frameCount" to CLong((20 + trackId).toLong()),
                    "boxFirst" to cList(
                        CLong((100 + trackId).toLong()),
                        CLong(80),
                        CLong((220 + trackId).toLong()),
                        CLong(420),
                    ),
                    "boxLast" to cList(
                        CLong((110 + trackId).toLong()),
                        CLong(84),
                        CLong((230 + trackId).toLong()),
                        CLong(424),
                    ),
                    "observedAt" to CString(observedAt),
                ),
                observedAt = observedAt,
                actorPath = listOf("device", "moto-g84-5g", "camera", "0"),
                channelPath = listOf("perception", "sess-parity-0001", "video"),
                valueKind = "track-segment",
                parentEventIds = listOf(root.eventId),
                rootEventId = root.eventId,
                provenance = cMap(
                    "source" to CString("android-percept"),
                    "observedBy" to CString("percept-app"),
                    "ingestionPipeline" to CString("event-trace-v0"),
                    "extractionRunId" to CString("synthetic-detector-v0@jvm"),
                ),
            )
            record(event)
        }

        outputDir.resolve("pointers.jsonl").writeText(
            pointerLines.joinToString(separator = "\n", postfix = "\n"),
            StandardCharsets.UTF_8,
        )
    }

    private fun observedAt(secondOffset: Int): String =
        "2026-07-04T12:00:%02dZ".format(secondOffset)

    private fun androidProvenance() = cMap(
        "source" to CString("android-percept"),
        "observedBy" to CString("percept-app"),
        "ingestionPipeline" to CString("event-trace-v0"),
    )
}
