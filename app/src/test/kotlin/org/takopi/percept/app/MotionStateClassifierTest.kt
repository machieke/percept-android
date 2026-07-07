package org.takopi.percept.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionStateClassifierTest {
    private fun classifier() = MotionStateClassifier(
        windowNanos = 1_000_000_000L,
        movingThresholdCmS2 = 40,
    )

    /** Feed one sample per 100 ms of the given magnitude across [seconds]. */
    private fun feed(
        c: MotionStateClassifier,
        fromSecond: Long,
        seconds: Int,
        magnitude: Long,
    ): List<MotionStateClassifier.Segment> {
        val out = mutableListOf<MotionStateClassifier.Segment>()
        for (i in 0 until seconds * 10) {
            val t = fromSecond * 1_000_000_000L + i * 100_000_000L
            c.onSample(t, magnitude)?.let(out::add)
        }
        return out
    }

    @Test
    fun stateChangeClosesTheEarlierRun() {
        val c = classifier()
        val fromStill = feed(c, 0, 3, magnitude = 10)
        assertTrue(fromStill.isEmpty())
        val fromMoving = feed(c, 3, 3, magnitude = 120)

        val closed = fromMoving.single()
        assertEquals(MotionStateClassifier.STATE_STILL, closed.state)
        assertEquals(0L, closed.tStartNanos)
        assertTrue(closed.tEndNanos in 2_000_000_000L..4_500_000_000L)
        // The boundary window mixes the transition sample in; the run must
        // still classify below the moving threshold.
        assertTrue("rms=${closed.rmsAccelCmS2}", closed.rmsAccelCmS2 in 10..39)

        val open = c.finish()
        assertEquals(MotionStateClassifier.STATE_MOVING, open!!.state)
        assertEquals(120L, open.rmsAccelCmS2)
    }

    @Test
    fun singleStateSessionEmitsOnlyAtFinish() {
        val c = classifier()
        assertTrue(feed(c, 0, 5, magnitude = 15).isEmpty())
        val segment = c.finish()
        assertEquals(MotionStateClassifier.STATE_STILL, segment!!.state)
        assertTrue(segment.tEndNanos >= 4_000_000_000L)
    }

    @Test
    fun peakSurvivesAcrossWindowsWithinARun()
    {
        val c = classifier()
        c.onSample(0, 100)
        c.onSample(500_000_000L, 300)
        feed(c, 1, 3, magnitude = 100)
        val segment = c.finish()
        assertEquals(MotionStateClassifier.STATE_MOVING, segment!!.state)
        assertEquals(300L, segment.peakAccelCmS2)
        assertTrue(segment.rmsAccelCmS2 in 100..300)
    }

    @Test
    fun noSamplesMeansNoSegment() {
        assertNull(classifier().finish())
    }
}
