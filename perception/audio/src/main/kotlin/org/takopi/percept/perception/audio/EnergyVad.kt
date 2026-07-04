package org.takopi.percept.perception.audio

class EnergyVad(
    private val thresholdPerMille: Int,
) {
    init {
        require(thresholdPerMille in 0..1000) { "thresholdPerMille must be in 0..1000" }
    }

    fun isSpeech(samples: ShortArray): Boolean =
        meanAbsoluteLevelPerMille(samples) >= thresholdPerMille
}

fun meanAbsoluteLevelPerMille(samples: ShortArray): Int {
    if (samples.isEmpty()) return 0
    val sum = samples.sumOf { kotlin.math.abs(it.toInt()).toLong() }
    return ((sum * 1000L) / (samples.size.toLong() * Short.MAX_VALUE.toLong())).toInt()
        .coerceIn(0, 1000)
}
