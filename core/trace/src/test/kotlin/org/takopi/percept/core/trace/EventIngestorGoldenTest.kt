package org.takopi.percept.core.trace

import org.junit.Assert.assertEquals
import org.junit.Test
import org.takopi.percept.core.canonical.CLong
import org.takopi.percept.core.canonical.CString
import org.takopi.percept.core.canonical.cList
import org.takopi.percept.core.canonical.cMap
import org.takopi.percept.core.canonical.canonicalBytes
import org.takopi.percept.core.da.MemoryDA
import java.nio.charset.StandardCharsets

class EventIngestorGoldenTest {
    @Test
    fun sessionStartRootMatchesGoldenVector() {
        val ingestor = EventIngestor(MemoryDA(), MemoryEventIndex())

        val event = ingestor.ingestEvent(
            rawPayload = sessionStartPayload(),
            observedAt = "2026-07-04T12:00:00Z",
            actorPath = listOf("device", "moto-g84-5g", "app", "percept"),
            channelPath = listOf("perception", "sess-0001", "session"),
            valueKind = "session-start",
            preview = "session sess-0001 start",
            provenance = androidProvenance(),
        )

        assertEquals(
            "cidv0-local-sha256:6561e92f73fd964ebb5eae0c0f2c312c0b81346dc0993396e138625ef980fb03",
            event.payloadCid,
        )
        assertEquals(
            "event:2fff7fdfbcc67384067405f951944995ab1242f17d58f821e1f63f8a7f31726b",
            event.eventId,
        )
        assertEquals(
            "cidv0-local-sha256:2fff7fdfbcc67384067405f951944995ab1242f17d58f821e1f63f8a7f31726b",
            event.eventCid,
        )
    }

    @Test
    fun trackSegmentChildMatchesGoldenVectorAndCanonicalEnvelope() {
        val index = MemoryEventIndex()
        val ingestor = EventIngestor(MemoryDA(), index)
        val root = ingestor.ingestEvent(
            rawPayload = sessionStartPayload(),
            observedAt = "2026-07-04T12:00:00Z",
            actorPath = listOf("device", "moto-g84-5g", "app", "percept"),
            channelPath = listOf("perception", "sess-0001", "session"),
            valueKind = "session-start",
            preview = "session sess-0001 start",
            provenance = androidProvenance(),
        )

        val event = ingestor.ingestEvent(
            rawPayload = cMap(
                "kind" to CString("raw-payload"),
                "schema" to CString("perception-track-segment-v0.1"),
                "sessionId" to CString("sess-0001"),
                "trackId" to CLong(7),
                "label" to CString("person"),
                "labelSpace" to CString("coco-80"),
                "scorePerMille" to CLong(874),
                "tStartNanos" to CLong(1500000000),
                "tEndNanos" to CLong(41500000000),
                "frameCount" to CLong(412),
                "boxFirst" to cList(CLong(120), CLong(88), CLong(340), CLong(460)),
                "boxLast" to cList(CLong(180), CLong(92), CLong(400), CLong(470)),
                "observedAt" to CString("2026-07-04T12:00:41Z"),
            ),
            observedAt = "2026-07-04T12:00:41Z",
            actorPath = listOf("device", "moto-g84-5g", "camera", "0"),
            channelPath = listOf("perception", "sess-0001", "video"),
            valueKind = "track-segment",
            parentEventIds = listOf(root.eventId),
            rootEventId = root.eventId,
            provenance = cMap(
                "source" to CString("android-percept"),
                "observedBy" to CString("percept-app"),
                "ingestionPipeline" to CString("event-trace-v0"),
                "extractionRunId" to CString("yolov8n-int8-320@litert-gpu-v1"),
            ),
        )

        assertEquals(
            "cidv0-local-sha256:d090e40e8a30d21980c60e95d4cb368e7c0f94e2667469d0142f0aaf39892fdb",
            event.payloadCid,
        )
        assertEquals(
            "event:a5a4f6db55fd7abf26756ed062316486d51ebe294895d2ea1a31c5509b51019f",
            event.eventId,
        )
        assertEquals(
            "cidv0-local-sha256:a5a4f6db55fd7abf26756ed062316486d51ebe294895d2ea1a31c5509b51019f",
            event.eventCid,
        )
        assertEquals(
            """{"actorPath":["device","moto-g84-5g","camera","0"],"causal":{"inputEventIds":[],"outputArtifactIds":[],"parentEventIds":["event:2fff7fdfbcc67384067405f951944995ab1242f17d58f821e1f63f8a7f31726b"],"rootEventId":"event:2fff7fdfbcc67384067405f951944995ab1242f17d58f821e1f63f8a7f31726b"},"channelPath":["perception","sess-0001","video"],"kind":"event-trace","provenance":{"extractionRunId":"yolov8n-int8-320@litert-gpu-v1","ingestionPipeline":"event-trace-v0","observedBy":"percept-app","source":"android-percept"},"schema":"event-trace-v0.1","time":{"day":4,"hour":12,"iso":"2026-07-04T12:00:41Z","minute":0,"month":7,"second":41,"year":2026},"value":{"contentType":"application/json","kind":"track-segment","payloadCid":"cidv0-local-sha256:d090e40e8a30d21980c60e95d4cb368e7c0f94e2667469d0142f0aaf39892fdb"}}""",
            canonicalBytes(event.envelope).toString(StandardCharsets.UTF_8),
        )
    }

    private fun sessionStartPayload() = cMap(
        "kind" to CString("raw-payload"),
        "schema" to CString("perception-session-v0.1"),
        "device" to CString("moto-g84-5g"),
        "sessionId" to CString("sess-0001"),
        "monotonicAnchorNanos" to CLong(123456789000),
        "observedAt" to CString("2026-07-04T12:00:00Z"),
    )

    private fun androidProvenance() = cMap(
        "source" to CString("android-percept"),
        "observedBy" to CString("percept-app"),
        "ingestionPipeline" to CString("event-trace-v0"),
    )
}
