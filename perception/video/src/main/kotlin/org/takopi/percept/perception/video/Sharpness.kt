package org.takopi.percept.perception.video

/**
 * Integer sharpness score for a luminance plane: mean SQUARED horizontal +
 * vertical gradient over a subsampled grid (Tenengrad-style). Squaring
 * matters: motion blur spreads an edge without changing its total variation,
 * so mean |gradient| is blur-invariant while mean gradient² collapses.
 * Higher = sharper; only comparisons matter, not absolute values.
 */
object Sharpness {
    fun luminanceSharpness(
        data: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        step: Int = 4,
    ): Int {
        require(width > 1 && height > 1) { "width and height must exceed 1" }
        require(rowStride >= width) { "rowStride too small for width" }
        require(step >= 1) { "step must be >= 1" }

        var sum = 0L
        var count = 0L
        var y = 0
        while (y < height - 1) {
            val row = y * rowStride
            val nextRow = (y + 1) * rowStride
            var x = 0
            while (x < width - 1) {
                val here = data[row + x].toInt() and 0xFF
                val right = data[row + x + 1].toInt() and 0xFF
                val below = data[nextRow + x].toInt() and 0xFF
                val dx = (here - right).toLong()
                val dy = (here - below).toLong()
                sum += dx * dx + dy * dy
                count += 1
                x += step
            }
            y += step
        }
        if (count == 0L) return 0
        return (sum / count).toInt()
    }
}
