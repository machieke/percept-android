package org.takopi.percept.core.trace

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class SessionTimeBaseTest {
    private val anchorEpochMillis = Instant.parse("2026-07-04T12:00:00Z").toEpochMilli()

    @Test
    fun anchorMapsToAnchorWallClock() {
        val timeBase = SessionTimeBase(123_456_789_000L, anchorEpochMillis)
        assertEquals("2026-07-04T12:00:00Z", timeBase.observedAtIso(0))
    }

    @Test
    fun subSecondNanosTruncateToWholeSeconds() {
        val timeBase = SessionTimeBase(0, anchorEpochMillis)
        assertEquals("2026-07-04T12:00:41Z", timeBase.observedAtIso(41_999_999_999L))
        assertEquals("2026-07-04T12:00:42Z", timeBase.observedAtIso(42_000_000_000L))
    }

    @Test
    fun subSecondAnchorCombinesWithElapsedBeforeTruncation() {
        val timeBase = SessionTimeBase(0, anchorEpochMillis + 900)
        assertEquals("2026-07-04T12:00:01Z", timeBase.observedAtIso(200_000_000L))
        assertEquals("2026-07-04T12:00:00Z", timeBase.observedAtIso(50_000_000L))
    }

    @Test
    fun rollsAcrossMinuteHourAndDayBoundaries() {
        val timeBase = SessionTimeBase(0, anchorEpochMillis)
        assertEquals("2026-07-04T12:01:00Z", timeBase.observedAtIso(60_000_000_000L))
        assertEquals("2026-07-04T13:00:00Z", timeBase.observedAtIso(3_600_000_000_000L))
        assertEquals("2026-07-05T00:00:00Z", timeBase.observedAtIso(43_200_000_000_000L))
    }

    @Test
    fun elapsedNanosSubtractsAnchor() {
        val timeBase = SessionTimeBase(1_000_000L, anchorEpochMillis)
        assertEquals(41_500_000_000L, timeBase.elapsedNanos(41_501_000_000L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun elapsedNanosRejectsPreAnchorTimestamps() {
        SessionTimeBase(1_000_000L, anchorEpochMillis).elapsedNanos(999_999L)
    }
}
