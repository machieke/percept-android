package org.takopi.percept.perception.video

data class PixelBox(
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
) {
    init {
        require(x2 >= x1) { "x2 must be >= x1" }
        require(y2 >= y1) { "y2 must be >= y1" }
    }

    val area: Int
        get() = (x2 - x1) * (y2 - y1)

    fun iouPerMille(other: PixelBox): Int {
        val ix1 = maxOf(x1, other.x1)
        val iy1 = maxOf(y1, other.y1)
        val ix2 = minOf(x2, other.x2)
        val iy2 = minOf(y2, other.y2)
        val intersection = maxOf(0, ix2 - ix1) * maxOf(0, iy2 - iy1)
        val union = area + other.area - intersection
        if (union <= 0) return 0
        return ((intersection.toLong() * 1000L) / union).toInt()
    }
}

data class VideoDetection(
    val label: String,
    val labelSpace: String,
    val scorePerMille: Int,
    val box: PixelBox,
) {
    init {
        require(scorePerMille in 0..1000) { "scorePerMille must be in 0..1000" }
    }
}

data class VideoFrameDetections(
    val frameIndex: Long,
    val tNanos: Long,
    val detections: List<VideoDetection>,
)

data class TrackSegment(
    val trackId: Int,
    val label: String,
    val labelSpace: String,
    val scorePerMille: Int,
    val tStartNanos: Long,
    val tEndNanos: Long,
    val frameCount: Int,
    val boxFirst: PixelBox,
    val boxLast: PixelBox,
    val closeReason: TrackCloseReason,
)

enum class TrackCloseReason {
    MISSED_LIMIT,
    HEARTBEAT,
    FLUSH,
}
