package org.takopi.percept.perception.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YuvToRgbTest {
    /** 2x2 NV21 frame with uniform Y and one VU pair. */
    private fun nv21(y: Int, v: Int, u: Int): ByteArray = byteArrayOf(
        y.toByte(), y.toByte(), y.toByte(), y.toByte(),
        v.toByte(), u.toByte(),
    )

    @Test
    fun neutralChromaIsGray() {
        val argb = YuvToRgb.nv21ToArgb(nv21(y = 128, v = 128, u = 128), 2, 2)
        assertEquals(4, argb.size)
        argb.forEach { pixel -> assertEquals(0xFF808080.toInt(), pixel) }
    }

    @Test
    fun redChromaProducesRedPixels() {
        // Y/U/V of pure red in BT.601: (76, 84, 255).
        val argb = YuvToRgb.nv21ToArgb(nv21(y = 76, v = 255, u = 84), 2, 2)
        val pixel = argb[0]
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        assertTrue("r=$r", r > 245)
        assertTrue("g=$g", g < 10)
        assertTrue("b=$b", b < 10)
    }

    @Test
    fun subsamplePicksEveryOtherPixel() {
        // 4x4 Y plane 0..15, neutral chroma.
        val y = ByteArray(16) { it.toByte() }
        val uv = ByteArray(8) { 128.toByte() }
        val argb = YuvToRgb.nv21ToArgb(y + uv, 4, 4, subsample = 2)
        assertEquals(4, argb.size)
        // Pixels (0,0), (2,0), (0,2), (2,2) → luma 0, 2, 8, 10.
        val lumas = argb.map { it and 0xFF }
        assertEquals(listOf(0, 2, 8, 10), lumas)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUndersizedBuffer() {
        YuvToRgb.nv21ToArgb(ByteArray(5), 2, 2)
    }
}
