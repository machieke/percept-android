package org.takopi.percept.perception.video

/**
 * YUV_420_888 → NV21 repacking over primitive buffers, so the byte math is
 * host-testable; the ImageProxy plumbing lives in [PerceptFrameAnalyzer].
 */
object Nv21 {
    fun fromPlanes(
        width: Int,
        height: Int,
        yPlane: ByteArray,
        yRowStride: Int,
        yPixelStride: Int,
        uPlane: ByteArray,
        vPlane: ByteArray,
        chromaRowStride: Int,
        chromaPixelStride: Int,
    ): ByteArray {
        require(width > 0 && height > 0) { "width and height must be positive" }
        require(width % 2 == 0 && height % 2 == 0) { "NV21 needs even dimensions" }

        val out = ByteArray(width * height * 3 / 2)
        var offset = 0
        for (row in 0 until height) {
            val rowStart = row * yRowStride
            for (col in 0 until width) {
                out[offset++] = yPlane[rowStart + col * yPixelStride]
            }
        }
        // NV21 interleaves VU pairs at quarter chroma resolution.
        for (row in 0 until height / 2) {
            val rowStart = row * chromaRowStride
            for (col in 0 until width / 2) {
                val chromaIndex = rowStart + col * chromaPixelStride
                out[offset++] = vPlane[chromaIndex]
                out[offset++] = uPlane[chromaIndex]
            }
        }
        return out
    }
}
