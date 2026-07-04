package org.takopi.percept.perception.video

import kotlin.math.abs

/**
 * §3.5 / risk 3: camera HAL timestamps are expected to be in the
 * `elapsedRealtimeNanos` domain on this device class, but that is asserted at
 * runtime rather than assumed — when the image timestamp is implausibly far
 * from the receipt time, fall back to receipt time.
 */
object FrameTimestamps {
    const val MAX_CLOCK_SKEW_NANOS: Long = 5_000_000_000L

    fun resolveFrameElapsedNanos(
        imageTimestampNanos: Long,
        receiptElapsedNanos: Long,
        maxSkewNanos: Long = MAX_CLOCK_SKEW_NANOS,
    ): FrameTimestampResolution {
        val inDomain = abs(receiptElapsedNanos - imageTimestampNanos) <= maxSkewNanos
        return FrameTimestampResolution(
            elapsedNanos = if (inDomain) imageTimestampNanos else receiptElapsedNanos,
            usedFallback = !inDomain,
        )
    }
}

data class FrameTimestampResolution(
    val elapsedNanos: Long,
    val usedFallback: Boolean,
)
