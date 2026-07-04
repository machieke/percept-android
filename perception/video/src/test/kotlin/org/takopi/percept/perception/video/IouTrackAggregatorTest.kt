package org.takopi.percept.perception.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IouTrackAggregatorTest {
    @Test
    fun closesTrackAfterMissedFrameLimit() {
        val tracker = IouTrackAggregator(missedFrameLimit = 3, heartbeatNanos = 30_000_000_000L)

        assertTrue(tracker.process(frame(0, detection(x1 = 100))).isEmpty())
        assertTrue(tracker.process(frame(1, detection(x1 = 104))).isEmpty())
        assertTrue(tracker.process(frame(2)).isEmpty())
        assertTrue(tracker.process(frame(3)).isEmpty())
        val closed = tracker.process(frame(4))

        assertEquals(1, closed.size)
        assertEquals(1, closed.single().trackId)
        assertEquals(TrackCloseReason.MISSED_LIMIT, closed.single().closeReason)
        assertEquals(2, closed.single().frameCount)
        assertEquals(PixelBox(100, 80, 220, 420), closed.single().boxFirst)
        assertEquals(PixelBox(104, 80, 224, 420), closed.single().boxLast)
    }

    @Test
    fun emitsHeartbeatForLongLivedTrackAndKeepsSameTrackId() {
        val tracker = IouTrackAggregator(missedFrameLimit = 3, heartbeatNanos = 2_000_000_000L)

        assertTrue(tracker.process(frame(0, detection(x1 = 100))).isEmpty())
        assertTrue(tracker.process(frame(1, detection(x1 = 102))).isEmpty())
        val heartbeat = tracker.process(frame(2, detection(x1 = 104)))
        val flushed = tracker.flush()

        assertEquals(1, heartbeat.size)
        assertEquals(TrackCloseReason.HEARTBEAT, heartbeat.single().closeReason)
        assertEquals(1, heartbeat.single().trackId)
        assertEquals(1, flushed.single().trackId)
        assertEquals(TrackCloseReason.FLUSH, flushed.single().closeReason)
    }

    private fun frame(second: Long, vararg detections: VideoDetection): VideoFrameDetections =
        VideoFrameDetections(
            frameIndex = second,
            tNanos = second * 1_000_000_000L,
            detections = detections.toList(),
        )

    private fun detection(x1: Int): VideoDetection =
        VideoDetection(
            label = "person",
            labelSpace = "coco-80",
            scorePerMille = 800,
            box = PixelBox(x1, 80, x1 + 120, 420),
        )
}
