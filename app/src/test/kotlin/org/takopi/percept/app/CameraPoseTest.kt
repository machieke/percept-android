package org.takopi.percept.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPoseMathTest {
    // Row-major device→world matrices (world: X east, Y north, Z up).

    @Test
    fun phoneFlatOnTableScreenUpPointsCameraStraightDown() {
        // Identity: device axes == world axes; back camera looks along -Z.
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val pose = CameraPoseMath.fromRotationMatrix(identity)
        assertEquals(-9_000L, pose.elevationCentiDeg)
    }

    @Test
    fun uprightPortraitFacingNorthIsLevelAndNorth() {
        // Device pitched up 90°: device Y → world Z (up), device Z → world -Y
        // (screen faces south, back camera faces north).
        val r = floatArrayOf(
            1f, 0f, 0f,
            0f, 0f, -1f,
            0f, 1f, 0f,
        )
        val pose = CameraPoseMath.fromRotationMatrix(r)
        assertEquals(0L, pose.elevationCentiDeg)
        assertEquals(0L, pose.azimuthCentiDeg) // north
    }

    @Test
    fun uprightFacingEastReads90Degrees() {
        // Additionally yawed so the camera looks east (proper rotation:
        // device X→south, Y→up, Z→west; det = +1).
        val r = floatArrayOf(
            0f, 0f, -1f,
            -1f, 0f, 0f,
            0f, 1f, 0f,
        )
        val pose = CameraPoseMath.fromRotationMatrix(r)
        assertEquals(0L, pose.elevationCentiDeg)
        assertEquals(9_000L, pose.azimuthCentiDeg) // east
    }
}

class CameraPoseGateTest {
    @Test
    fun firstPoseEmitsThenSmallDriftSuppressed() {
        val gate = CameraPoseGate(minAngleCentiDeg = 1_500, minSpacingNanos = 0)
        assertTrue(gate.shouldEmit(0, 0, 18_000))
        assertFalse(gate.shouldEmit(1_000_000_000L, 500, 18_500))
        assertTrue(gate.shouldEmit(2_000_000_000L, 2_000, 18_500))
    }

    @Test
    fun azimuthWrapIsHandled() {
        assertEquals(1_000L, CameraPoseGate.azimuthDelta(35_800, 800))
        assertEquals(0L, CameraPoseGate.azimuthDelta(0, 0))
        assertEquals(18_000L, CameraPoseGate.azimuthDelta(0, 18_000))
        val gate = CameraPoseGate(minAngleCentiDeg = 1_500, minSpacingNanos = 0)
        gate.shouldEmit(0, 0, 35_900)
        // 3.5° across the north wrap: 35900 -> 200 is only 3° apart... 300 units
        assertFalse(gate.shouldEmit(1, 0, 200))
        assertTrue(gate.shouldEmit(2, 0, 1_500))
    }

    @Test
    fun rateFloorCapsSwayDrivenEmission() {
        val gate = CameraPoseGate(minAngleCentiDeg = 1_500, minSpacingNanos = 5_000_000_000L)
        assertTrue(gate.shouldEmit(0, 0, 0))
        // Wild swings within 5 s stay suppressed regardless of angle.
        assertFalse(gate.shouldEmit(1_000_000_000L, 6_000, 9_000))
        assertFalse(gate.shouldEmit(4_900_000_000L, -6_000, 27_000))
        assertTrue(gate.shouldEmit(5_000_000_000L, -6_000, 27_000))
    }

    @Test
    fun heartbeatEmitsWhenStationary() {
        val gate = CameraPoseGate(minAngleCentiDeg = 1_500, minIntervalNanos = 60_000_000_000L)
        gate.shouldEmit(0, 0, 0)
        assertFalse(gate.shouldEmit(59_000_000_000L, 0, 0))
        assertTrue(gate.shouldEmit(60_000_000_000L, 0, 0))
    }
}
