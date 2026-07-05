package org.takopi.percept.perception.video

/**
 * NV21 → ARGB_8888 conversion in integer BT.601 math. Replaces the
 * JPEG-encode/decode round trip in the detector path, which dominated the
 * per-frame budget on device; the optional subsample factor lets the caller
 * convert straight to the detector's working resolution.
 */
object YuvToRgb {
    fun nv21ToArgb(nv21: ByteArray, width: Int, height: Int, subsample: Int = 1): IntArray {
        require(width > 0 && height > 0) { "width and height must be positive" }
        require(width % 2 == 0 && height % 2 == 0) { "NV21 needs even dimensions" }
        require(subsample >= 1) { "subsample must be >= 1" }
        require(nv21.size >= width * height * 3 / 2) { "buffer too small for dimensions" }

        val outWidth = width / subsample
        val outHeight = height / subsample
        val frameSize = width * height
        val out = IntArray(outWidth * outHeight)
        var outIndex = 0
        for (outY in 0 until outHeight) {
            val y = outY * subsample
            val yRow = y * width
            val uvRow = frameSize + (y shr 1) * width
            for (outX in 0 until outWidth) {
                val x = outX * subsample
                val luma = nv21[yRow + x].toInt() and 0xFF
                val uvIndex = uvRow + (x and -2)
                val v = (nv21[uvIndex].toInt() and 0xFF) - 128
                val u = (nv21[uvIndex + 1].toInt() and 0xFF) - 128
                // Fixed-point BT.601: R=Y+1.402V, G=Y-0.344U-0.714V, B=Y+1.772U
                val r = (luma + ((1436 * v) shr 10)).coerceIn(0, 255)
                val g = (luma - ((352 * u + 731 * v) shr 10)).coerceIn(0, 255)
                val b = (luma + ((1815 * u) shr 10)).coerceIn(0, 255)
                out[outIndex++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }
}
