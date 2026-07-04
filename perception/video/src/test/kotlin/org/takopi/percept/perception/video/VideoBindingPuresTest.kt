package org.takopi.percept.perception.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LuminanceHistogramsTest {
    @Test
    fun binsPixelsByLuminanceRange() {
        // 2x2 image: 0, 64, 128, 255 with 4 bins of width 64.
        val data = byteArrayOf(0, 64, 128.toByte(), 255.toByte())
        val histogram = LuminanceHistograms.fromLuminancePlane(
            data = data,
            width = 2,
            height = 2,
            rowStride = 2,
            bins = 4,
        )
        assertArrayEquals(intArrayOf(1, 1, 1, 1), histogram.bins)
    }

    @Test
    fun honorsRowAndPixelStrides() {
        // Two rows of width 2 with rowStride 4 and pixelStride 2: only
        // offsets 0, 2, 4, 6 are pixels; padding bytes are 127 decoys.
        val data = byteArrayOf(0, 127, 0, 127, 255.toByte(), 127, 255.toByte(), 127)
        val histogram = LuminanceHistograms.fromLuminancePlane(
            data = data,
            width = 2,
            height = 2,
            rowStride = 4,
            pixelStride = 2,
            bins = 2,
        )
        assertArrayEquals(intArrayOf(2, 2), histogram.bins)
    }
}

class FrameTimestampsTest {
    @Test
    fun timestampWithinSkewIsUsedDirectly() {
        val resolution = FrameTimestamps.resolveFrameElapsedNanos(
            imageTimestampNanos = 1_000_000_000L,
            receiptElapsedNanos = 1_050_000_000L,
        )
        assertEquals(1_000_000_000L, resolution.elapsedNanos)
        assertFalse(resolution.usedFallback)
    }

    @Test
    fun foreignClockBaseFallsBackToReceiptTime() {
        // e.g. HAL emitting CLOCK_MONOTONIC while device is long-booted.
        val resolution = FrameTimestamps.resolveFrameElapsedNanos(
            imageTimestampNanos = 12_345L,
            receiptElapsedNanos = 900_000_000_000L,
        )
        assertEquals(900_000_000_000L, resolution.elapsedNanos)
        assertTrue(resolution.usedFallback)
    }
}

class FrameRateGovernorTest {
    @Test
    fun enforcesTargetFpsWhenNominal() {
        val governor = FrameRateGovernor(targetFps = 10)
        assertTrue(governor.shouldProcess(0, ThermalLevel.NOMINAL))
        // 50 ms later: too soon for 10 fps.
        assertFalse(governor.shouldProcess(50_000_000L, ThermalLevel.NOMINAL))
        assertTrue(governor.shouldProcess(100_000_000L, ThermalLevel.NOMINAL))
    }

    @Test
    fun severeHalvesFrameRate() {
        val governor = FrameRateGovernor(targetFps = 10)
        assertTrue(governor.shouldProcess(0, ThermalLevel.SEVERE))
        assertFalse(governor.shouldProcess(100_000_000L, ThermalLevel.SEVERE))
        assertTrue(governor.shouldProcess(200_000_000L, ThermalLevel.SEVERE))
    }

    @Test
    fun criticalSuspendsVideoEntirely() {
        val governor = FrameRateGovernor(targetFps = 10)
        assertFalse(governor.shouldProcess(0, ThermalLevel.CRITICAL))
        assertFalse(governor.shouldProcess(1_000_000_000L, ThermalLevel.CRITICAL))
        // Recovery resumes processing.
        assertTrue(governor.shouldProcess(2_000_000_000L, ThermalLevel.NOMINAL))
    }

    @Test
    fun countsThrottleTransitionsOnce() {
        val governor = FrameRateGovernor(targetFps = 10)
        governor.shouldProcess(0, ThermalLevel.NOMINAL)
        governor.shouldProcess(100_000_000L, ThermalLevel.SEVERE)
        governor.shouldProcess(200_000_000L, ThermalLevel.SEVERE)
        governor.shouldProcess(300_000_000L, ThermalLevel.NOMINAL)
        governor.shouldProcess(400_000_000L, ThermalLevel.CRITICAL)
        assertEquals(2L, governor.thermalThrottleEvents)
    }
}

class Nv21Test {
    @Test
    fun packsLumaThenInterleavedVu() {
        // 2x2 frame; Y plane has rowStride 3 with one padding byte per row.
        val y = byteArrayOf(10, 20, 0, 30, 40, 0)
        val u = byteArrayOf(50)
        val v = byteArrayOf(60)
        val nv21 = Nv21.fromPlanes(
            width = 2,
            height = 2,
            yPlane = y,
            yRowStride = 3,
            yPixelStride = 1,
            uPlane = u,
            vPlane = v,
            chromaRowStride = 1,
            chromaPixelStride = 1,
        )
        assertArrayEquals(byteArrayOf(10, 20, 30, 40, 60, 50), nv21)
    }

    @Test
    fun handlesInterleavedChromaPixelStride() {
        // Semi-planar source: u and v views over the same interleaved buffer
        // region, pixelStride 2 — the common YUV_420_888 camera layout.
        val y = ByteArray(16) { index -> index.toByte() }
        val interleavedU = byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0)
        val interleavedV = byteArrayOf(9, 0, 8, 0, 7, 0, 6, 0)
        val nv21 = Nv21.fromPlanes(
            width = 4,
            height = 4,
            yPlane = y,
            yRowStride = 4,
            yPixelStride = 1,
            uPlane = interleavedU,
            vPlane = interleavedV,
            chromaRowStride = 4,
            chromaPixelStride = 2,
        )
        val expected = y + byteArrayOf(9, 1, 8, 2, 7, 3, 6, 4)
        assertArrayEquals(expected, nv21)
    }
}
