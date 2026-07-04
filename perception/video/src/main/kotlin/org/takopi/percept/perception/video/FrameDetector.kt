package org.takopi.percept.perception.video

/**
 * Detector abstraction of §3.3 — MediaPipe EfficientDet-Lite0 is the v1
 * default; a LiteRT YOLOv8n can be swapped in behind this interface.
 */
interface FrameDetector : AutoCloseable {
    /** extractionRunId recorded in event provenance for this detector build. */
    val extractionRunId: String

    fun detect(nv21: ByteArray, width: Int, height: Int): List<VideoDetection>

    override fun close() {}
}
