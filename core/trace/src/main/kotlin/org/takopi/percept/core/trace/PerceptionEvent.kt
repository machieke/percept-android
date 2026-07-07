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

    /**
     * A GPS/network location fix. Coordinates are integers per the no-float
     * rule: degrees ×1e7 (~1 cm resolution), accuracy in centimeters.
     * Low-rate by design — the capture side gates on movement/time.
     */
    data class LocationFix(
        val tNanos: Long,
        val latE7: Long,
        val lonE7: Long,
        val accuracyCm: Long,
        val altitudeCm: Long?,
        val provider: String,
        /** Doppler-measured ground speed (never accelerometer-integrated). */
        val speedCmPerS: Long? = null,
        /** Bearing in centi-degrees [0, 36000). */
        val bearingCentiDeg: Long? = null,
    ) : PerceptionEvent {
        init {
            require(tNanos >= 0) { "tNanos must be non-negative" }
            require(latE7 in -900_000_000..900_000_000) { "latE7 out of range" }
            require(lonE7 in -1_800_000_000..1_800_000_000) { "lonE7 out of range" }
            require(accuracyCm >= 0) { "accuracyCm must be non-negative" }
            require(provider.isNotBlank()) { "provider must not be blank" }
            speedCmPerS?.let { require(it >= 0) { "speedCmPerS must be non-negative" } }
            bearingCentiDeg?.let { require(it in 0..35_999) { "bearingCentiDeg out of range" } }
        }
    }

    /**
     * Run-length motion state from the IMU: how the device was moving, not
     * how fast — integrating accelerometers into velocity drifts into
     * nonsense within seconds; real speed comes from GPS Doppler on
     * [LocationFix]. Acceleration values are linear (gravity-removed), in
     * integer cm/s².
     */
    data class MotionSegment(
        val state: String,
        val tStartNanos: Long,
        val tEndNanos: Long,
        val rmsAccelCmS2: Long,
        val peakAccelCmS2: Long,
    ) : PerceptionEvent {
        init {
            require(state.isNotBlank()) { "state must not be blank" }
            require(tStartNanos >= 0) { "tStartNanos must be non-negative" }
            require(tEndNanos >= tStartNanos) { "tEndNanos must be >= tStartNanos" }
            require(rmsAccelCmS2 >= 0) { "rmsAccelCmS2 must be non-negative" }
            require(peakAccelCmS2 >= rmsAccelCmS2) { "peak must be >= rms" }
        }
    }

    /**
     * A compressed slice of the session's full audio, stored as a raw DA
     * artifact (like keyframes) so bundles carry the complete episodic
     * record for server-side processing.
     */
    class AudioChunk(
        val chunkIndex: Int,
        val tStartNanos: Long,
        val tEndNanos: Long,
        val sampleRate: Int,
        val sampleCount: Long,
        val contentType: String,
        val codecId: String,
        val encoded: ByteArray,
    ) : PerceptionEvent {
        init {
            require(chunkIndex >= 0) { "chunkIndex must be non-negative" }
            require(tStartNanos >= 0) { "tStartNanos must be non-negative" }
            require(tEndNanos >= tStartNanos) { "tEndNanos must be >= tStartNanos" }
            require(sampleRate > 0) { "sampleRate must be positive" }
            require(sampleCount > 0) { "sampleCount must be positive" }
            require(contentType.isNotBlank()) { "contentType must not be blank" }
            require(codecId.isNotBlank()) { "codecId must not be blank" }
            require(encoded.isNotEmpty()) { "encoded audio must not be empty" }
        }
    }
}

/** Single funnel every perception thread writes into (§3.2). */
interface TraceSink {
    /** Non-blocking submit; returns false when the ingestion channel is full. */
    fun trySubmit(event: PerceptionEvent): Boolean
}
