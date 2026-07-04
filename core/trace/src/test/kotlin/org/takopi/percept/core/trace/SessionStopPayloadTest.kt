package org.takopi.percept.core.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.takopi.percept.core.canonical.canonicalBytes
import java.nio.charset.StandardCharsets

class SessionStopPayloadTest {
    @Test
    fun buildsCanonicalIntegerCounterPayload() {
        val payload = sessionStopPayload(
            sessionId = "sess-0001",
            tStartNanos = 0,
            tEndNanos = 300_000_000_000,
            observedAt = "2026-07-04T12:05:00Z",
            counters = SessionStopCounters(
                framesProcessed = 2400,
                eventsEmitted = 42,
                droppedFrames = 7,
                audioRingBufferOverruns = 0,
                thermalThrottleEvents = 1,
            ),
        )

        assertEquals(
            """{"counters":{"audioRingBufferOverruns":0,"droppedFrames":7,"eventsEmitted":42,"framesProcessed":2400,"thermalThrottleEvents":1},"kind":"raw-payload","observedAt":"2026-07-04T12:05:00Z","schema":"perception-session-stop-v0.1","sessionId":"sess-0001","tEndNanos":300000000000,"tStartNanos":0}""",
            canonicalBytes(payload).toString(StandardCharsets.UTF_8),
        )
    }

    @Test
    fun rejectsNegativeCountersAndBackwardTiming() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionStopCounters(
                framesProcessed = -1,
                eventsEmitted = 0,
                droppedFrames = 0,
                audioRingBufferOverruns = 0,
                thermalThrottleEvents = 0,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            sessionStopPayload(
                sessionId = "sess-0001",
                tStartNanos = 2,
                tEndNanos = 1,
                observedAt = "2026-07-04T12:05:00Z",
                counters = SessionStopCounters(
                    framesProcessed = 0,
                    eventsEmitted = 0,
                    droppedFrames = 0,
                    audioRingBufferOverruns = 0,
                    thermalThrottleEvents = 0,
                ),
            )
        }
    }
}
