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

/**
 * Detection-set + luminance scene gate with hysteresis. Marginal detections
 * flicker in and out frame to frame, so a changed detection set only fires
 * after holding for [signatureHoldFrames] consecutive frames, and no two
 * scene changes fire within [minIntervalNanos] (a real Moto G84 session
 * produced 32 scene changes in 48 frames without this).
 */
class SceneChangeGate(
    private val luminanceThresholdPerMille: Int = 250,
    private val signatureHoldFrames: Int = 3,
    private val minIntervalNanos: Long = 2_000_000_000L,
) {
    init {
        require(luminanceThresholdPerMille in 0..1000) {
            "luminanceThresholdPerMille must be in 0..1000"
        }
        require(signatureHoldFrames >= 1) { "signatureHoldFrames must be >= 1" }
        require(minIntervalNanos >= 0) { "minIntervalNanos must be non-negative" }
    }

    private var lastHistogram: LuminanceHistogram? = null
    private var emittedSignature: Set<String>? = null
    private var candidateSignature: Set<String>? = null
    private var candidateFrames = 0
    private var lastSceneTNanos = 0L
    private var nextSceneIndex = 0

    fun process(frame: SceneGateFrame): SceneChange? {
        val previousHistogram = lastHistogram
        lastHistogram = frame.histogram
        val signature = detectionSetSignature(frame.detections)

        if (emittedSignature == null) {
            emittedSignature = signature
            return sceneChange(frame.tNanos, 1000, SceneChangeReason.FIRST_FRAME)
        }

        val cooledDown = frame.tNanos - lastSceneTNanos >= minIntervalNanos

        if (previousHistogram != null && cooledDown) {
            val distance = l1DistancePerMille(previousHistogram, frame.histogram)
            if (distance >= luminanceThresholdPerMille) {
                // The view changed wholesale; adopt the current detection set
                // so it does not immediately re-fire as a set change.
                emittedSignature = signature
                clearCandidate()
                return sceneChange(frame.tNanos, distance, SceneChangeReason.LUMINANCE_DISTANCE)
            }
        }

        if (signature == emittedSignature) {
            clearCandidate()
            return null
        }
        if (signature == candidateSignature) {
            candidateFrames += 1
        } else {
            candidateSignature = signature
            candidateFrames = 1
        }
        if (candidateFrames >= signatureHoldFrames && cooledDown) {
            emittedSignature = signature
            clearCandidate()
            return sceneChange(frame.tNanos, 1000, SceneChangeReason.DETECTION_SET_CHANGE)
        }
        return null
    }

    private fun clearCandidate() {
        candidateSignature = null
        candidateFrames = 0
    }

    private fun sceneChange(tNanos: Long, metricPerMille: Int, reason: SceneChangeReason): SceneChange {
        lastSceneTNanos = tNanos
        return SceneChange(
            sceneIndex = nextSceneIndex++,
            tNanos = tNanos,
            metricPerMille = metricPerMille.coerceIn(0, 1000),
            reason = reason,
        )
    }

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
