package org.takopi.percept.perception.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharpnessTest {
    private fun plane(width: Int, height: Int, value: (x: Int, y: Int) -> Int): ByteArray =
        ByteArray(width * height) { i -> value(i % width, i / width).toByte() }

    @Test
    fun uniformImageScoresZero() {
        val flat = plane(16, 16) { _, _ -> 128 }
        assertEquals(0, Sharpness.luminanceSharpness(flat, 16, 16, rowStride = 16, step = 1))
    }

    @Test
    fun sharpEdgesOutscoreSmoothGradient() {
        // Checkerboard = maximal local gradients; ramp = gentle gradients.
        val checker = plane(16, 16) { x, y -> if ((x + y) % 2 == 0) 0 else 255 }
        val ramp = plane(16, 16) { x, _ -> x * 16 }
        val sharp = Sharpness.luminanceSharpness(checker, 16, 16, rowStride = 16, step = 1)
        val smooth = Sharpness.luminanceSharpness(ramp, 16, 16, rowStride = 16, step = 1)
        assertTrue("sharp=$sharp smooth=$smooth", sharp > smooth * 5)
    }

    @Test
    fun blurringReducesTheScore() {
        // A hard vertical edge vs the same edge linearly smeared over 8 px —
        // exactly what motion blur does to it.
        val hard = plane(32, 8) { x, _ -> if (x < 16) 0 else 255 }
        val smeared = plane(32, 8) { x, _ ->
            when {
                x < 12 -> 0
                x >= 20 -> 255
                else -> (x - 12) * 32
            }
        }
        val hardScore = Sharpness.luminanceSharpness(hard, 32, 8, rowStride = 32, step = 1)
        val smearScore = Sharpness.luminanceSharpness(smeared, 32, 8, rowStride = 32, step = 1)
        assertTrue("hard=$hardScore smeared=$smearScore", hardScore > smearScore * 2)
    }

    @Test
    fun subsamplingKeepsRelativeOrdering() {
        // 1 px checker so every immediate neighbor differs regardless of
        // where the subsample grid lands.
        val checker = plane(64, 64) { x, y -> if ((x + y) % 2 == 0) 0 else 255 }
        val flat = plane(64, 64) { _, _ -> 200 }
        assertTrue(
            Sharpness.luminanceSharpness(checker, 64, 64, rowStride = 64, step = 4) >
                Sharpness.luminanceSharpness(flat, 64, 64, rowStride = 64, step = 4),
        )
    }
}
