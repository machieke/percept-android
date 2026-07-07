package org.takopi.percept.core.trace

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.takopi.percept.core.canonical.CList
import org.takopi.percept.core.canonical.CLong
import org.takopi.percept.core.canonical.CMap
import org.takopi.percept.core.canonical.CString
import org.takopi.percept.core.canonical.canonicalBytes
import org.takopi.percept.core.canonical.cidForBytes
import org.takopi.percept.core.da.MemoryDA
import java.nio.charset.StandardCharsets
import java.time.Instant

class PerceptionSessionTest {
    private val anchorEpochMillis = Instant.parse("2026-07-04T12:00:00Z").toEpochMilli()

    private fun config() = PerceptionSessionConfig(
        deviceId = "moto-g84-5g",
        sessionId = "sess-funnel",
        detectorRunId = "fake-detector-v0@jvm",
        sceneGateRunId = "fake-scene-gate-v0@jvm",
        audioTaggerRunId = "fake-yamnet-v0@jvm",
        asrRunId = "fake-asr-v0@jvm",
    )

    private fun counters(tEndNanos: Long) = PerceptionRunCounters(
        tEndNanos = tEndNanos,
        framesProcessed = 100,
        droppedFrames = 2,
        audioRingBufferOverruns = 0,
        thermalThrottleEvents = 0,
    )

    @Test
    fun funnelsAllEventKindsWithCorrectParentingAndPaths() = runTest {
        val da = MemoryDA()
        val index = MemoryEventIndex()
        val ingested = mutableListOf<IngestedEvent>()
        val session = PerceptionSession(
            da = da,
            index = index,
            config = config(),
            timeBase = SessionTimeBase(0, anchorEpochMillis),
            onEventIngested = ingested::add,
        )

        val root = session.start(this)
        val keyframe = "fake-jpeg".toByteArray(StandardCharsets.UTF_8)
        assertTrue(
            session.trySubmit(
                PerceptionEvent.SceneChange(
                    sceneIndex = 0,
                    tNanos = 1_000_000_000L,
                    gateMetricPerMille = 1000,
                    keyframeJpeg = keyframe,
                ),
            ),
        )
        assertTrue(
            session.trySubmit(
                PerceptionEvent.TrackSegment(
                    trackId = 1,
                    label = "person",
                    labelSpace = "coco-80",
                    scorePerMille = 874,
                    tStartNanos = 1_500_000_000L,
                    tEndNanos = 41_500_000_000L,
                    frameCount = 412,
                    boxFirst = listOf(120, 88, 340, 460),
                    boxLast = listOf(180, 92, 400, 470),
                ),
            ),
        )
        assertTrue(
            session.trySubmit(
                PerceptionEvent.AudioTagSegment(
                    label = "Music",
                    labelSpace = "audioset",
                    scorePerMille = 640,
                    tStartNanos = 2_000_000_000L,
                    tEndNanos = 9_000_000_000L,
                ),
            ),
        )
        assertTrue(
            session.trySubmit(
                PerceptionEvent.AsrSegment(
                    text = "hello from the funnel test",
                    langHint = "en",
                    tStartNanos = 3_000_000_000L,
                    tEndNanos = 8_000_000_000L,
                    avgLogProbMicro = -180_000,
                ),
            ),
        )
        val chunkBytes = "fake-opus".toByteArray(StandardCharsets.UTF_8)
        assertTrue(
            session.trySubmit(
                PerceptionEvent.AudioChunk(
                    chunkIndex = 0,
                    tStartNanos = 0,
                    tEndNanos = 60_000_000_000L,
                    sampleRate = 16_000,
                    sampleCount = 960_000,
                    contentType = "audio/ogg; codecs=opus",
                    codecId = "oggopus-test",
                    encoded = chunkBytes,
                ),
            ),
        )
        advanceUntilIdle()
        val stop = session.stop(counters(tEndNanos = 60_000_000_000L))

        assertEquals(7, ingested.size)
        val (start, scene, track, tag, asr) = ingested
        val chunk = ingested[5]
        assertEquals(root.eventId, start.eventId)

        assertEquals("audio-chunk", chunk.pointer.requireString("valueKind"))
        assertEquals(listOf(root.eventId), chunk.pointer.stringListOf("parentEventIds"))
        val chunkCid = cidForBytes(chunkBytes)
        assertEquals(listOf(chunkCid), chunk.pointer.stringListOf("outputArtifactIds"))
        assertTrue(da.has(chunkCid))
        assertEquals("raw", da.stat(chunkCid).codec)
        assertEquals("2026-07-04T12:01:00Z", chunk.envelope.timeMap().requireString("iso"))

        assertEquals("session-start", start.pointer.requireString("valueKind"))
        assertNull(start.envelope.causalMap().stringOrNull("rootEventId"))
        assertEquals(root.eventId, start.pointer.requireString("rootEventId"))

        assertEquals("scene-change", scene.pointer.requireString("valueKind"))
        assertEquals(listOf(root.eventId), scene.pointer.stringListOf("parentEventIds"))
        val keyframeCid = cidForBytes(keyframe)
        assertEquals(listOf(keyframeCid), scene.pointer.stringListOf("outputArtifactIds"))
        assertTrue(da.has(keyframeCid))
        assertEquals("raw", da.stat(keyframeCid).codec)

        assertEquals("track-segment", track.pointer.requireString("valueKind"))
        assertEquals(
            listOf(root.eventId, scene.eventId),
            track.pointer.stringListOf("parentEventIds"),
        )
        assertEquals(
            listOf("perception", "sess-funnel", "video"),
            track.pointer.stringListOf("channelPath"),
        )
        assertEquals(
            listOf("device", "moto-g84-5g", "camera", "0"),
            track.pointer.stringListOf("actorPath"),
        )
        assertEquals(
            "2026-07-04T12:00:41Z",
            track.envelope.timeMap().requireString("iso"),
        )

        assertEquals("audio-tag-segment", tag.pointer.requireString("valueKind"))
        assertEquals(listOf(root.eventId), tag.pointer.stringListOf("parentEventIds"))
        assertEquals(
            listOf("device", "moto-g84-5g", "microphone", "0"),
            tag.pointer.stringListOf("actorPath"),
        )

        assertEquals("asr-segment", asr.pointer.requireString("valueKind"))
        assertEquals(
            "hello from the funnel test",
            asr.envelope.valueMap().requireString("preview"),
        )
        // Non-ASR events must not carry a preview key at all (§0.3).
        assertNull(track.envelope.valueMap().entries["preview"])

        assertEquals("session-stop", stop.pointer.requireString("valueKind"))
        assertEquals(listOf(root.eventId), stop.pointer.stringListOf("parentEventIds"))
        assertEquals("2026-07-04T12:01:00Z", stop.envelope.timeMap().requireString("iso"))

        // Every event's eventCid must be re-derivable from its canonical envelope bytes.
        for (event in ingested + stop) {
            assertEquals(event.eventCid, cidForBytes(canonicalBytes(event.envelope)))
            assertTrue(da.verify(event.eventCid).ok)
            assertTrue(da.verify(event.payloadCid).ok)
        }

        assertEquals(
            PerceptionSessionStats(eventsIngested = 7, eventsDropped = 0),
            session.stats(),
        )
    }

    @Test
    fun locationFixIngestsWithIntegerCoordinates() = runTest {
        val da = MemoryDA()
        val ingested = mutableListOf<IngestedEvent>()
        val session = PerceptionSession(
            da = da,
            index = MemoryEventIndex(),
            config = config(),
            timeBase = SessionTimeBase(0, anchorEpochMillis),
            onEventIngested = ingested::add,
        )
        val root = session.start(this)
        session.trySubmit(
            PerceptionEvent.LocationFix(
                tNanos = 12_000_000_000L,
                latE7 = 508_512_345L,
                lonE7 = 43_512_345L,
                accuracyCm = 850,
                altitudeCm = 4_200,
                provider = "gps",
                speedCmPerS = 340,
                bearingCentiDeg = 27_350,
            ),
        )
        session.trySubmit(
            PerceptionEvent.MotionSegment(
                state = "moving",
                tStartNanos = 10_000_000_000L,
                tEndNanos = 20_000_000_000L,
                rmsAccelCmS2 = 85,
                peakAccelCmS2 = 240,
            ),
        )
        session.trySubmit(
            PerceptionEvent.LocationFix(
                tNanos = 80_000_000_000L,
                latE7 = 508_512_400L,
                lonE7 = 43_512_400L,
                accuracyCm = 900,
                altitudeCm = null,
                provider = "network",
            ),
        )
        advanceUntilIdle()
        session.stop(counters(tEndNanos = 90_000_000_000L))

        val fixes = ingested.filter { it.pointer.requireString("valueKind") == "location-fix" }
        assertEquals(2, fixes.size)
        assertEquals(listOf(root.eventId), fixes[0].pointer.stringListOf("parentEventIds"))
        assertEquals(
            listOf("perception", "sess-funnel", "location"),
            fixes[0].pointer.stringListOf("channelPath"),
        )
        assertEquals(
            listOf("device", "moto-g84-5g", "location", "0"),
            fixes[0].pointer.stringListOf("actorPath"),
        )
        val withAltitude = da.getBytes(fixes[0].payloadCid).toString(StandardCharsets.UTF_8)
        assertTrue(withAltitude.contains("\"latE7\":508512345"))
        assertTrue(withAltitude.contains("\"accuracyCm\":850"))
        assertTrue(withAltitude.contains("\"altitudeCm\":4200"))
        assertTrue(withAltitude.contains("\"speedCmPerS\":340"))
        assertTrue(withAltitude.contains("\"bearingCentiDeg\":27350"))
        // Optional keys entirely absent when unknown (§0.3 presence rule).
        val withoutAltitude = da.getBytes(fixes[1].payloadCid).toString(StandardCharsets.UTF_8)
        assertFalse(withoutAltitude.contains("altitudeCm"))
        assertFalse(withoutAltitude.contains("speedCmPerS"))

        val motion = ingested.single { it.pointer.requireString("valueKind") == "motion-segment" }
        assertEquals(
            listOf("device", "moto-g84-5g", "imu", "0"),
            motion.pointer.stringListOf("actorPath"),
        )
        assertEquals(
            listOf("perception", "sess-funnel", "motion"),
            motion.pointer.stringListOf("channelPath"),
        )
        val motionPayload = da.getBytes(motion.payloadCid).toString(StandardCharsets.UTF_8)
        assertTrue(motionPayload.contains("\"state\":\"moving\""))
        assertTrue(motionPayload.contains("\"rmsAccelCmS2\":85"))
        assertTrue(motionPayload.contains("\"peakAccelCmS2\":240"))
    }

    @Test
    fun trackSegmentBeforeAnySceneParentsToRootOnly() = runTest {
        val ingested = mutableListOf<IngestedEvent>()
        val session = PerceptionSession(
            da = MemoryDA(),
            index = MemoryEventIndex(),
            config = config(),
            timeBase = SessionTimeBase(0, anchorEpochMillis),
            onEventIngested = ingested::add,
        )
        val root = session.start(this)
        session.trySubmit(
            PerceptionEvent.TrackSegment(
                trackId = 1,
                label = "cup",
                labelSpace = "coco-80",
                scorePerMille = 500,
                tStartNanos = 0,
                tEndNanos = 1_000_000_000L,
                frameCount = 10,
                boxFirst = listOf(0, 0, 10, 10),
                boxLast = listOf(0, 0, 10, 10),
            ),
        )
        advanceUntilIdle()
        session.stop(counters(tEndNanos = 2_000_000_000L))

        val track = ingested.single { it.pointer.requireString("valueKind") == "track-segment" }
        assertEquals(listOf(root.eventId), track.pointer.stringListOf("parentEventIds"))
    }

    @Test
    fun countsDropsWhenChannelIsFullAndReportsEmittedCount() = runTest {
        val da = MemoryDA()
        val session = PerceptionSession(
            da = da,
            index = MemoryEventIndex(),
            config = config(),
            timeBase = SessionTimeBase(0, anchorEpochMillis),
            capacity = 1,
        )
        session.start(this)
        // Consumer has not run yet (StandardTestDispatcher), so only one event buffers.
        val tag = PerceptionEvent.AudioTagSegment(
            label = "Music",
            labelSpace = "audioset",
            scorePerMille = 640,
            tStartNanos = 0,
            tEndNanos = 1_000_000_000L,
        )
        assertTrue(session.trySubmit(tag))
        assertFalse(session.trySubmit(tag.copy(tStartNanos = 1, tEndNanos = 1_000_000_001L)))
        assertFalse(session.trySubmit(tag.copy(tStartNanos = 2, tEndNanos = 1_000_000_002L)))
        advanceUntilIdle()
        val stop = session.stop(counters(tEndNanos = 2_000_000_000L))

        assertEquals(
            PerceptionSessionStats(eventsIngested = 3, eventsDropped = 2),
            session.stats(),
        )
        // The canonical stop payload records start + the one funneled event + stop.
        val stopPayload = da.getBytes(stop.payloadCid).toString(StandardCharsets.UTF_8)
        assertTrue(stopPayload.contains("\"eventsEmitted\":3"))
    }

    private fun CMap.requireString(key: String): String {
        val value = entries[key]
        check(value is CString) { "missing string field: $key" }
        return value.value
    }

    private fun CMap.stringListOf(key: String): List<String> {
        val value = entries[key]
        check(value is CList) { "missing list field: $key" }
        return value.values.map { (it as CString).value }
    }

    private fun CMap.causalMap(): CMap = entries["causal"] as CMap

    private fun CMap.timeMap(): CMap = entries["time"] as CMap

    private fun CMap.valueMap(): CMap = entries["value"] as CMap

    private operator fun <T> List<T>.component4(): T = get(3)

    private operator fun <T> List<T>.component5(): T = get(4)
}
