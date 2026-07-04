package org.takopi.percept.core.trace

import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/**
 * Anchor pair tying the monotonic clock to wall time for one session (§3.5).
 *
 * All payload timing is monotonic nanos since [monotonicAnchorNanos]; envelope
 * `observedAt` strings are derived here and truncated to whole seconds so the
 * envelope stays schema-pure.
 */
class SessionTimeBase(
    val monotonicAnchorNanos: Long,
    val anchorEpochMillis: Long,
) {
    init {
        require(monotonicAnchorNanos >= 0) { "monotonicAnchorNanos must be non-negative" }
        require(anchorEpochMillis >= 0) { "anchorEpochMillis must be non-negative" }
    }

    fun elapsedNanos(monotonicNanos: Long): Long {
        require(monotonicNanos >= monotonicAnchorNanos) {
            "monotonicNanos must be >= monotonicAnchorNanos"
        }
        return monotonicNanos - monotonicAnchorNanos
    }

    fun observedAtIso(tNanos: Long): String {
        require(tNanos >= 0) { "tNanos must be non-negative" }
        val epochSeconds = Math.floorDiv(anchorEpochMillis + tNanos / 1_000_000L, 1000L)
        val time = Instant.ofEpochSecond(epochSeconds).atOffset(ZoneOffset.UTC)
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
}
