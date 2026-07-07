package org.takopi.percept.perception.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.takopi.percept.core.trace.PerceptionEvent
import org.takopi.percept.core.trace.TraceSink

private class RecordingSink : TraceSink {
    val events = mutableListOf<PerceptionEvent>()

    override fun trySubmit(event: PerceptionEvent): Boolean {
        events += event
        return true
    }

    inline fun <reified T : PerceptionEvent> ofType(): List<T> = events.filterIsInstance<T>()
}

class VideoPerceptionEngineTest {
    private val flatHistogram = LuminanceHistogram(IntArray(8) { 100 })
    private val brightHistogram = LuminanceHistogram(IntArray(8) { index -> if (index == 7) 800 else 0 })

    private fun detection(label: String, box: PixelBox, scorePerMille: Int = 800) =
        VideoDetection(label = label, labelSpace = "coco-80", scorePerMille = scorePerMille, box = box)

    private fun frame(
        tNanos: Long,
        detections: List<VideoDetection>,
        histogram: LuminanceHistogram = flatHistogram,
        keyframe: () -> ByteArray = { "jpeg".toByteArray() },
        sharpness: Int = 0,
    ) = FrameObservation(tNanos, detections, histogram, keyframe, sharpness)

    @Test
    fun firstFrameEmitsSceneChangeWithKeyframe() {
        val sink = RecordingSink()
        val engine = VideoPerceptionEngine(sink, keyframeSelectionFrames = 0)
        var keyframeEncodes = 0

        engine.onFrame(
            frame(
                tNanos = 0,
                detections = listOf(detection("person", PixelBox(10, 10, 50, 90))),
                keyframe = {
                    keyframeEncodes += 1
                    byteArrayOf(1, 2, 3)
                },
            ),
        )

        val scenes = sink.ofType<PerceptionEvent.SceneChange>()
        assertEquals(1, scenes.size)
        assertEquals(0, scenes.single().sceneIndex)
        assertEquals(1000, scenes.single().gateMetricPerMille)
        assertTrue(scenes.single().keyframeJpeg.contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(1, keyframeEncodes)
    }

    @Test
    fun keyframeEncoderRunsOnlyWhenGateFires() {
        val sink = RecordingSink()
        val engine = VideoPerceptionEngine(sink, keyframeSelectionFrames = 0)
        var keyframeEncodes = 0
        val steady = listOf(detection("person", PixelBox(10, 10, 50, 90)))

        repeat(5) { index ->
            engine.onFrame(
                frame(
                    tNanos = index * 100_000_000L,
                    detections = steady,
                    keyframe = {
                        keyframeEncodes += 1
                        byteArrayOf(0)
                    },
                ),
            )
        }

        // Only the FIRST_FRAME scene change should have encoded a keyframe.
        assertEquals(1, keyframeEncodes)
        assertEquals(1, sink.ofType<PerceptionEvent.SceneChange>().size)
    }

    @Test
    fun luminanceJumpEmitsSecondSceneChange() {
        val sink = RecordingSink()
        val engine = VideoPerceptionEngine(
            sink,
            gate = SceneChangeGate(minIntervalNanos = 0),
            keyframeSelectionFrames = 0,
        )
        val steady = listOf(detection("person", PixelBox(10, 10, 50, 90)))

        engine.onFrame(frame(0, steady, flatHistogram))
        engine.onFrame(frame(100_000_000L, steady, brightHistogram))

        val scenes = sink.ofType<PerceptionEvent.SceneChange>()
        assertEquals(2, scenes.size)
        assertEquals(1, scenes[1].sceneIndex)
        assertEquals(100_000_000L, scenes[1].tNanos)
    }

    @Test
    fun trackClosesAfterMissedFrameLimitAndMapsFields() {
        val sink = RecordingSink()
        val engine = VideoPerceptionEngine(
            sink,
            tracker = IouTrackAggregator(missedFrameLimit = 3),
        )
        val box1 = PixelBox(100, 80, 200, 300)
        val box2 = PixelBox(105, 82, 205, 302)

        engine.onFrame(frame(1_000_000_000L, listOf(detection("person", box1, scorePerMille = 700))))
        engine.onFrame(frame(2_000_000_000L, listOf(detection("person", box2, scorePerMille = 900))))
        repeat(3) { index ->
            engine.onFrame(frame(3_000_000_000L + index * 1_000_000_000L, emptyList()))
        }

        val tracks = sink.ofType<PerceptionEvent.TrackSegment>()
        assertEquals(1, tracks.size)
        val track = tracks.single()
        assertEquals(1L, track.trackId)
        assertEquals("person", track.label)
        assertEquals(900, track.scorePerMille)
        assertEquals(1_000_000_000L, track.tStartNanos)
        assertEquals(2_000_000_000L, track.tEndNanos)
        assertEquals(2L, track.frameCount)
        assertEquals(listOf(100, 80, 200, 300), track.boxFirst)
        assertEquals(listOf(105, 82, 205, 302), track.boxLast)
    }

    @Test
    fun finishFlushesOpenTracksAndReturnsCounters() {
        val sink = RecordingSink()
        val engine = VideoPerceptionEngine(sink)

        engine.onFrame(frame(0, listOf(detection("cup", PixelBox(0, 0, 40, 40)))))
        engine.onFrame(frame(100_000_000L, listOf(detection("cup", PixelBox(2, 1, 42, 41)))))
        engine.onFrameDropped()
        engine.onFrameDropped()
        val counters = engine.finish()

        assertEquals(1, sink.ofType<PerceptionEvent.TrackSegment>().size)
        assertEquals(
            VideoRunCounters(framesProcessed = 2, droppedFrames = 2, lastFrameTNanos = 100_000_000L),
            counters,
        )
    }

    @Test
    fun shortFlickerTracksAreFilteredWhenConfigured() {
        val sink = RecordingSink()
        val engine = VideoPerceptionEngine(
            sink,
            tracker = IouTrackAggregator(missedFrameLimit = 1),
            keyframeSelectionFrames = 0,
            minTrackDurationNanos = 1_000_000_000L,
        )
        val box = PixelBox(10, 10, 50, 90)
        // One-frame flicker: closes with zero duration -> filtered.
        engine.onFrame(frame(0, listOf(detection("cup", box))))
        engine.onFrame(frame(100_000_000L, emptyList()))
        // Durable track: 2 s -> survives the filter.
        engine.onFrame(frame(1_000_000_000L, listOf(detection("person", box))))
        engine.onFrame(frame(3_000_000_000L, listOf(detection("person", box))))
        engine.finish()

        val tracks = sink.ofType<PerceptionEvent.TrackSegment>()
        assertEquals(1, tracks.size)
        assertEquals("person", tracks.single().label)
    }

    @Test
    fun pendingScenePicksSharpestFrameInSelectionWindow() {
        val sink = RecordingSink()
        val engine = VideoPerceptionEngine(sink, keyframeSelectionFrames = 3)
        val steady = listOf(detection("person", PixelBox(10, 10, 50, 90)))

        engine.onFrame(frame(0, steady, keyframe = { byteArrayOf(0) }, sharpness = 10))
        assertTrue(sink.ofType<PerceptionEvent.SceneChange>().isEmpty())
        engine.onFrame(frame(100_000_000L, steady, keyframe = { byteArrayOf(1) }, sharpness = 50))
        engine.onFrame(frame(200_000_000L, steady, keyframe = { byteArrayOf(2) }, sharpness = 30))
        assertTrue(sink.ofType<PerceptionEvent.SceneChange>().isEmpty())
        engine.onFrame(frame(300_000_000L, steady, keyframe = { byteArrayOf(3) }, sharpness = 20))

        val scene = sink.ofType<PerceptionEvent.SceneChange>().single()
        // Scene timing stays the gate moment; keyframe comes from the sharpest frame.
        assertEquals(0L, scene.tNanos)
        assertTrue(scene.keyframeJpeg.contentEquals(byteArrayOf(1)))
    }

    @Test
    fun pendingSceneFlushesOnFinishWithBestSoFar() {
        val sink = RecordingSink()
        val engine = VideoPerceptionEngine(sink, keyframeSelectionFrames = 8)
        val steady = listOf(detection("person", PixelBox(10, 10, 50, 90)))

        engine.onFrame(frame(0, steady, keyframe = { byteArrayOf(0) }, sharpness = 5))
        engine.onFrame(frame(100_000_000L, steady, keyframe = { byteArrayOf(1) }, sharpness = 9))
        engine.finish()

        val scene = sink.ofType<PerceptionEvent.SceneChange>().single()
        assertTrue(scene.keyframeJpeg.contentEquals(byteArrayOf(1)))
    }

    @Test
    fun newSceneFlushesThePendingOneFirst() {
        val sink = RecordingSink()
        val engine = VideoPerceptionEngine(
            sink,
            gate = SceneChangeGate(signatureHoldFrames = 1, minIntervalNanos = 0),
            keyframeSelectionFrames = 8,
        )
        val person = listOf(detection("person", PixelBox(10, 10, 50, 90)))

        engine.onFrame(frame(0, person, keyframe = { byteArrayOf(0) }, sharpness = 7))
        // Detection set changes: second scene fires while the first is pending.
        engine.onFrame(frame(100_000_000L, emptyList(), keyframe = { byteArrayOf(1) }, sharpness = 3))
        engine.finish()

        val scenes = sink.ofType<PerceptionEvent.SceneChange>()
        assertEquals(2, scenes.size)
        assertEquals(0, scenes[0].sceneIndex)
        assertTrue(scenes[0].keyframeJpeg.contentEquals(byteArrayOf(0)))
        assertTrue(scenes[1].keyframeJpeg.contentEquals(byteArrayOf(1)))
    }

    @Test
    fun frameArrivingAfterFinishIsSilentlyIgnored() {
        val sink = RecordingSink()
        val engine = VideoPerceptionEngine(sink, keyframeSelectionFrames = 0)
        engine.onFrame(frame(0, listOf(detection("person", PixelBox(10, 10, 50, 90)))))
        val counters = engine.finish()

        engine.onFrame(frame(100_000_000L, emptyList()))

        assertEquals(1L, counters.framesProcessed)
        assertEquals(1, sink.ofType<PerceptionEvent.SceneChange>().size)
    }

    @Test
    fun longLivedTrackEmitsHeartbeatSegmentAndContinues() {
        val sink = RecordingSink()
        val engine = VideoPerceptionEngine(
            sink,
            tracker = IouTrackAggregator(heartbeatNanos = 30_000_000_000L),
        )
        val box = PixelBox(10, 10, 50, 90)

        engine.onFrame(frame(0, listOf(detection("person", box))))
        engine.onFrame(frame(31_000_000_000L, listOf(detection("person", box))))
        engine.finish()

        val tracks = sink.ofType<PerceptionEvent.TrackSegment>()
        assertEquals(2, tracks.size)
        assertEquals(0L, tracks[0].tStartNanos)
        assertEquals(31_000_000_000L, tracks[0].tEndNanos)
        // Flush segment continues the same trackId from the heartbeat point.
        assertEquals(tracks[0].trackId, tracks[1].trackId)
        assertEquals(31_000_000_000L, tracks[1].tStartNanos)
    }
}
