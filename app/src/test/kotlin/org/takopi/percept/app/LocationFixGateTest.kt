package org.takopi.percept.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationFixGateTest {
    // Brussels-ish. 10 m of latitude ≈ 898 E7-units.
    private val lat = 508_500_000L
    private val lon = 43_500_000L

    @Test
    fun firstFixAlwaysEmits() {
        assertTrue(LocationFixGate().shouldEmit(0, lat, lon))
    }

    @Test
    fun smallJitterIsSuppressed() {
        val gate = LocationFixGate(minDistanceMeters = 10.0)
        gate.shouldEmit(0, lat, lon)
        // ~2 m north.
        assertFalse(gate.shouldEmit(1_000_000_000L, lat + 180, lon))
    }

    @Test
    fun movementBeyondThresholdEmits() {
        val gate = LocationFixGate(minDistanceMeters = 10.0)
        gate.shouldEmit(0, lat, lon)
        // ~20 m north.
        assertTrue(gate.shouldEmit(1_000_000_000L, lat + 1_800, lon))
    }

    @Test
    fun stationaryHeartbeatEmitsAfterInterval() {
        val gate = LocationFixGate(minDistanceMeters = 10.0, minIntervalNanos = 60_000_000_000L)
        gate.shouldEmit(0, lat, lon)
        assertFalse(gate.shouldEmit(59_000_000_000L, lat, lon))
        assertTrue(gate.shouldEmit(60_000_000_000L, lat, lon))
        // Interval resets after each emission.
        assertFalse(gate.shouldEmit(61_000_000_000L, lat, lon))
    }

    @Test
    fun distanceApproximationIsSane() {
        // 0.001 degrees of latitude = 10_000 E7-units ≈ 111.3 m.
        val d = LocationFixGate.distanceMeters(lat, lon, lat + 10_000, lon)
        assertEquals(111.3, d, 1.0)
        // Longitude shrinks with cos(lat): at ~50.85°N a degree of longitude
        // is ~70 km, so 0.001° ≈ 70 m.
        val dLon = LocationFixGate.distanceMeters(lat, lon, lat, lon + 10_000)
        assertEquals(70.3, dLon, 1.5)
    }
}
