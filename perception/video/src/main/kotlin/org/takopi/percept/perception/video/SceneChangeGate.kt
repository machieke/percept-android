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
    SUBJECT_PRESENT,
}

/**
 * Detection-set + luminance scene gate with hysteresis. Marginal detections
 * flicker in and out frame to frame, so a changed detection set only fires
 * after holding for [signatureHoldFrames] consecutive frames, and no two
 * scene changes fire within [minIntervalNanos] (a real Moto G84 session
 * produced 32 scene changes in 48 frames without this).
 *
 * Subject-triggered capture: the set/luminance gate keys on the *background*
 * changing, so a dog playing in a static room, or a face at a still gathering,
 * never triggers a keyframe — the phone tracks the subject at frame rate but
 * saves no frame containing it, and downstream identification starves. So
 * while a large, high-confidence [subjectLabels] detection fills the frame,
 * fire a keyframe every [subjectIntervalNanos] regardless of background, so
 * the trace holds frames that actually contain the subject.
 */
class SceneChangeGate(
    private val luminanceThresholdPerMille: Int = 250,
    private val signatureHoldFrames: Int = 3,
    private val minIntervalNanos: Long = 2_000_000_000L,
    private val subjectLabels: Set<String> = DEFAULT_SUBJECT_LABELS,
    private val subjectMinScorePerMille: Int = 500,
    /** Box area (detector model space, ~640x480) above which a subject is
     *  "filling the frame" — ~6% of a 640x480 frame. */
    private val subjectMinAreaPx: Long = 18_000L,
    private val subjectIntervalNanos: Long = 2_000_000_000L,
    /** COCO confabulates outside its distribution (a tree scores 'elephant', a
     *  blob 'dog') and there is no VLM on-device to catch it — but such
     *  false positives flicker or slide out of frame, while a real subject is
     *  detected continuously. Require a salient subject of the SAME label to
     *  persist this many consecutive frames before it triggers a capture. */
    private val subjectHoldFrames: Int = 4,
) {
    init {
        require(luminanceThresholdPerMille in 0..1000) {
            "luminanceThresholdPerMille must be in 0..1000"
        }
        require(signatureHoldFrames >= 1) { "signatureHoldFrames must be >= 1" }
        require(minIntervalNanos >= 0) { "minIntervalNanos must be non-negative" }
        require(subjectMinScorePerMille in 0..1000) { "subjectMinScorePerMille must be in 0..1000" }
        require(subjectMinAreaPx >= 0) { "subjectMinAreaPx must be non-negative" }
        require(subjectIntervalNanos >= 0) { "subjectIntervalNanos must be non-negative" }
        require(subjectHoldFrames >= 1) { "subjectHoldFrames must be >= 1" }
    }

    private var lastHistogram: LuminanceHistogram? = null
    private var emittedSignature: Set<String>? = null
    private var candidateSignature: Set<String>? = null
    private var candidateFrames = 0
    private var lastSceneTNanos = 0L
    private var lastSubjectTNanos = Long.MIN_VALUE
    private var salientSubjectLabel: String? = null
    private var salientSubjectFrames = 0
    private var nextSceneIndex = 0

    fun process(frame: SceneGateFrame): SceneChange? {
        val previousHistogram = lastHistogram
        lastHistogram = frame.histogram
        val signature = detectionSetSignature(frame.detections)

        // Track how long the same salient subject has persisted — a real
        // subject holds across frames, a COCO false positive does not.
        val salient = salientSubjectLabel(frame.detections)
        if (salient != null && salient == salientSubjectLabel) {
            salientSubjectFrames += 1
        } else {
            salientSubjectLabel = salient
            salientSubjectFrames = if (salient != null) 1 else 0
        }

        if (emittedSignature == null) {
            emittedSignature = signature
            lastSubjectTNanos = frame.tNanos
            return sceneChange(frame.tNanos, 1000, SceneChangeReason.FIRST_FRAME)
        }

        val cooledDown = frame.tNanos - lastSceneTNanos >= minIntervalNanos

        // A salient subject has filled the frame for long enough to be real:
        // capture it periodically even when the background (and thus the
        // set/luminance gate) is unchanged.
        if (cooledDown && salientSubjectFrames >= subjectHoldFrames &&
            frame.tNanos - lastSubjectTNanos >= subjectIntervalNanos
        ) {
            lastSubjectTNanos = frame.tNanos
            return sceneChange(frame.tNanos, 1000, SceneChangeReason.SUBJECT_PRESENT)
        }

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

    /** Label of the largest subject detection that clears the score and area
     *  floors, or null when none — the largest wins so the persistence tracker
     *  follows the dominant subject rather than flickering between two. */
    private fun salientSubjectLabel(detections: List<VideoDetection>): String? =
        detections
            .filter {
                it.label in subjectLabels &&
                    it.scorePerMille >= subjectMinScorePerMille &&
                    it.box.area >= subjectMinAreaPx
            }
            .maxByOrNull { it.box.area }
            ?.label

    companion object {
        /** COCO classes worth an identity: people, pets/wildlife, vehicles. */
        val DEFAULT_SUBJECT_LABELS: Set<String> = setOf(
            "person",
            "cat", "dog", "bird", "horse", "sheep", "cow", "bear", "elephant", "zebra", "giraffe",
            "car", "truck", "bus", "motorcycle", "bicycle",
        )
    }
}

fun l1DistancePerMille(a: LuminanceHistogram, b: LuminanceHistogram): Int {
    require(a.bins.size == b.bins.size) { "histograms must have the same bin count" }
    val scale = maxOf(a.total, b.total)
    if (scale <= 0) return 0
    val l1 = a.bins.indices.sumOf { index -> kotlin.math.abs(a.bins[index] - b.bins[index]).toLong() }
    return ((l1 * 1000L) / (2L * scale)).toInt().coerceIn(0, 1000)
}
