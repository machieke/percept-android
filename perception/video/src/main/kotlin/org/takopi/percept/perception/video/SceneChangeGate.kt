package org.takopi.percept.perception.video

data class LuminanceHistogram(
    val bins: IntArray,
) {
    init {
        require(bins.isNotEmpty()) { "histogram must have bins" }
        require(bins.all { it >= 0 }) { "histogram bins must be non-negative" }
    }

    val total: Int
        get() = bins.sum()

    override fun equals(other: Any?): Boolean =
        other is LuminanceHistogram && bins.contentEquals(other.bins)

    override fun hashCode(): Int = bins.contentHashCode()
}

data class SceneGateFrame(
    val tNanos: Long,
    val histogram: LuminanceHistogram,
    val detections: List<VideoDetection>,
)

data class SceneChange(
    val sceneIndex: Int,
    val tNanos: Long,
    val metricPerMille: Int,
    val reason: SceneChangeReason,
)

enum class SceneChangeReason {
    FIRST_FRAME,
    DETECTION_SET_CHANGE,
    LUMINANCE_DISTANCE,
}

class SceneChangeGate(
    private val luminanceThresholdPerMille: Int = 250,
) {
    init {
        require(luminanceThresholdPerMille in 0..1000) {
            "luminanceThresholdPerMille must be in 0..1000"
        }
    }

    private var lastHistogram: LuminanceHistogram? = null
    private var lastDetectionSignature: Set<String>? = null
    private var nextSceneIndex = 0

    fun process(frame: SceneGateFrame): SceneChange? {
        val previousHistogram = lastHistogram
        val previousSignature = lastDetectionSignature
        val signature = detectionSetSignature(frame.detections)

        lastHistogram = frame.histogram
        lastDetectionSignature = signature

        if (previousHistogram == null || previousSignature == null) {
            return sceneChange(frame.tNanos, 1000, SceneChangeReason.FIRST_FRAME)
        }

        if (signature != previousSignature) {
            return sceneChange(frame.tNanos, 1000, SceneChangeReason.DETECTION_SET_CHANGE)
        }

        val distance = l1DistancePerMille(previousHistogram, frame.histogram)
        if (distance >= luminanceThresholdPerMille) {
            return sceneChange(frame.tNanos, distance, SceneChangeReason.LUMINANCE_DISTANCE)
        }

        return null
    }

    private fun sceneChange(tNanos: Long, metricPerMille: Int, reason: SceneChangeReason): SceneChange =
        SceneChange(
            sceneIndex = nextSceneIndex++,
            tNanos = tNanos,
            metricPerMille = metricPerMille.coerceIn(0, 1000),
            reason = reason,
        )

    private fun detectionSetSignature(detections: List<VideoDetection>): Set<String> =
        detections
            .groupingBy { "${it.labelSpace}:${it.label}" }
            .eachCount()
            .mapTo(sortedSetOf()) { (labelKey, count) -> "$labelKey:$count" }
}

fun l1DistancePerMille(a: LuminanceHistogram, b: LuminanceHistogram): Int {
    require(a.bins.size == b.bins.size) { "histograms must have the same bin count" }
    val scale = maxOf(a.total, b.total)
    if (scale <= 0) return 0
    val l1 = a.bins.indices.sumOf { index -> kotlin.math.abs(a.bins[index] - b.bins[index]).toLong() }
    return ((l1 * 1000L) / (2L * scale)).toInt().coerceIn(0, 1000)
}
