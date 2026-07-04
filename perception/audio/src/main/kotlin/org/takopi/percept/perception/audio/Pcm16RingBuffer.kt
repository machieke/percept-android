package org.takopi.percept.perception.audio

class Pcm16RingBuffer(
    private val capacitySamples: Int,
) {
    init {
        require(capacitySamples > 0) { "capacitySamples must be positive" }
    }

    private val buffer = ShortArray(capacitySamples)
    private var totalWritten: Long = 0

    val writeIndex: Long
        get() = totalWritten

    fun append(samples: ShortArray): Long {
        val startIndex = totalWritten
        for (sample in samples) {
            buffer[(totalWritten % capacitySamples).toInt()] = sample
            totalWritten += 1
        }
        return startIndex
    }

    fun readFrom(startIndex: Long, maxSamples: Int): PcmReadResult {
        require(startIndex >= 0) { "startIndex must be non-negative" }
        require(maxSamples >= 0) { "maxSamples must be non-negative" }

        val earliestAvailable = maxOf(0L, totalWritten - capacitySamples)
        val actualStart = maxOf(startIndex, earliestAvailable)
        val available = (totalWritten - actualStart).coerceAtLeast(0L)
        val count = minOf(maxSamples.toLong(), available).toInt()
        val out = ShortArray(count)
        for (offset in 0 until count) {
            out[offset] = buffer[((actualStart + offset) % capacitySamples).toInt()]
        }
        return PcmReadResult(
            samples = out,
            nextIndex = actualStart + count,
            overflowed = startIndex < earliestAvailable,
        )
    }
}

data class PcmReadResult(
    val samples: ShortArray,
    val nextIndex: Long,
    val overflowed: Boolean,
) {
    override fun equals(other: Any?): Boolean =
        other is PcmReadResult &&
            samples.contentEquals(other.samples) &&
            nextIndex == other.nextIndex &&
            overflowed == other.overflowed

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + nextIndex.hashCode()
        result = 31 * result + overflowed.hashCode()
        return result
    }
}
