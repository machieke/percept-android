package org.takopi.percept.core.trace

/**
 * Aggregated perception outputs accepted by [TraceSink]. These are the atomic
 * event granularity from §2 — never per-frame observations.
 */
sealed interface PerceptionEvent {
    class SceneChange(
        val sceneIndex: Int,
        val tNanos: Long,
        val gateMetricPerMille: Int,
        val keyframeJpeg: ByteArray?,
    ) : PerceptionEvent {
        init {
            require(sceneIndex >= 0) { "sceneIndex must be non-negative" }
            require(tNanos >= 0) { "tNanos must be non-negative" }
            require(gateMetricPerMille in 0..1000) { "gateMetricPerMille must be in 0..1000" }
        }
    }

    data class TrackSegment(
        val trackId: Long,
        val label: String,
        val labelSpace: String,
        val scorePerMille: Int,
        val tStartNanos: Long,
        val tEndNanos: Long,
        val frameCount: Long,
        val boxFirst: List<Int>,
        val boxLast: List<Int>,
    ) : PerceptionEvent {
        init {
            require(trackId > 0) { "trackId must be positive" }
            require(scorePerMille in 0..1000) { "scorePerMille must be in 0..1000" }
            require(tStartNanos >= 0) { "tStartNanos must be non-negative" }
            require(tEndNanos >= tStartNanos) { "tEndNanos must be >= tStartNanos" }
            require(frameCount > 0) { "frameCount must be positive" }
            require(boxFirst.size == 4) { "boxFirst must be [x1, y1, x2, y2]" }
            require(boxLast.size == 4) { "boxLast must be [x1, y1, x2, y2]" }
        }
    }

    data class AudioTagSegment(
        val label: String,
        val labelSpace: String,
        val scorePerMille: Int,
        val tStartNanos: Long,
        val tEndNanos: Long,
    ) : PerceptionEvent {
        init {
            require(scorePerMille in 0..1000) { "scorePerMille must be in 0..1000" }
            require(tStartNanos >= 0) { "tStartNanos must be non-negative" }
            require(tEndNanos >= tStartNanos) { "tEndNanos must be >= tStartNanos" }
        }
    }

    data class AsrSegment(
        val text: String,
        val langHint: String,
        val tStartNanos: Long,
        val tEndNanos: Long,
        val avgLogProbMicro: Long,
    ) : PerceptionEvent {
        init {
            require(text.isNotEmpty()) { "text must not be empty" }
            require(tStartNanos >= 0) { "tStartNanos must be non-negative" }
            require(tEndNanos >= tStartNanos) { "tEndNanos must be >= tStartNanos" }
        }
    }
}

/** Single funnel every perception thread writes into (§3.2). */
interface TraceSink {
    /** Non-blocking submit; returns false when the ingestion channel is full. */
    fun trySubmit(event: PerceptionEvent): Boolean
}
