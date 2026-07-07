package org.takopi.percept.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LightGateTest {
    @Test
    fun firstReadingEmitsThenSmallFlickerSuppressed() {
        val gate = LightGate(minRatio = 2.0)
        assertTrue(gate.shouldEmit(0, 400_000))          // 400 lx office
        assertFalse(gate.shouldEmit(1_000_000_000L, 500_000))
        assertFalse(gate.shouldEmit(2_000_000_000L, 300_000))
    }

    @Test
    fun orderOfMagnitudeChangesEmit() {
        val gate = LightGate(minRatio = 2.0)
        gate.shouldEmit(0, 400_000)
        assertTrue(gate.shouldEmit(1_000_000_000L, 90_000))     // lights dimmed
        assertTrue(gate.shouldEmit(2_000_000_000L, 50_000_000)) // stepped outside
    }

    @Test
    fun darknessTransitionsAreStable() {
        val gate = LightGate(minRatio = 2.0)
        gate.shouldEmit(0, 0)   // pitch dark
        // 0 -> 0.5 lx: smoothed ratio (1500/1000) below 2, stays quiet.
        assertFalse(gate.shouldEmit(1_000_000_000L, 500))
        // 0 -> 5 lx clearly emits.
        assertTrue(gate.shouldEmit(2_000_000_000L, 5_000))
    }

    @Test
    fun heartbeatEmitsWhenStable() {
        val gate = LightGate(minRatio = 2.0, minIntervalNanos = 120_000_000_000L)
        gate.shouldEmit(0, 400_000)
        assertFalse(gate.shouldEmit(119_000_000_000L, 400_000))
        assertTrue(gate.shouldEmit(120_000_000_000L, 400_000))
    }
}

class PowerGateTest {
    @Test
    fun chargingFlipAndBandChangesEmit() {
        val gate = PowerGate(pctBand = 5)
        assertTrue(gate.shouldEmit(0, charging = false, batteryPct = 63))
        assertFalse(gate.shouldEmit(1, charging = false, batteryPct = 64)) // same 60-64 band
        assertTrue(gate.shouldEmit(2, charging = false, batteryPct = 65)) // next band
        assertTrue(gate.shouldEmit(3, charging = true, batteryPct = 65))  // plugged in
        assertFalse(gate.shouldEmit(4, charging = true, batteryPct = 66))
    }

    @Test
    fun heartbeatEmitsWhenNothingChanges() {
        val gate = PowerGate(minIntervalNanos = 600_000_000_000L)
        gate.shouldEmit(0, charging = false, batteryPct = 50)
        assertFalse(gate.shouldEmit(599_000_000_000L, false, 50))
        assertTrue(gate.shouldEmit(600_000_000_000L, false, 50))
    }
}
