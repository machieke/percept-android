package org.takopi.percept.perception.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyVadTest {
    @Test
    fun computesIntegerMeanAbsoluteLevelAndAppliesThreshold() {
        val samples = shortArrayOf(0, Short.MAX_VALUE, (-Short.MAX_VALUE).toShort(), 0)

        assertEquals(500, meanAbsoluteLevelPerMille(samples))
        assertTrue(EnergyVad(thresholdPerMille = 400).isSpeech(samples))
        assertFalse(EnergyVad(thresholdPerMille = 600).isSpeech(samples))
    }
}
