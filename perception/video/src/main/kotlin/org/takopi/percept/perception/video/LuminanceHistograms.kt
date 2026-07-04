package org.takopi.percept.perception.video

object LuminanceHistograms {
    const val DEFAULT_BINS: Int = 32

    /**
     * Builds a histogram from a YUV luminance (Y) plane. Works directly on the
     * plane buffer with its row/pixel strides so no Android types are needed.
     */
    fun fromLuminancePlane(
        data: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int = 1,
        bins: Int = DEFAULT_BINS,
    ): LuminanceHistogram {
        require(width > 0 && height > 0) { "width and height must be positive" }
        require(rowStride >= width * pixelStride) { "rowStride too small for width" }
        require(pixelStride > 0) { "pixelStride must be positive" }
        require(bins in 1..256) { "bins must be in 1..256" }

        val counts = IntArray(bins)
        for (row in 0 until height) {
            val rowStart = row * rowStride
            for (col in 0 until width) {
                val luma = data[rowStart + col * pixelStride].toInt() and 0xFF
                counts[luma * bins / 256] += 1
            }
        }
        return LuminanceHistogram(counts)
    }
}
