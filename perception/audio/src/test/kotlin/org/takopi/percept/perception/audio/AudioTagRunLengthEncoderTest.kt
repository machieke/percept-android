package org.takopi.percept.perception.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioTagRunLengthEncoderTest {
    @Test
    fun closesRunsOnLabelChangeSilenceAndFlush() {
        val encoder = AudioTagRunLengthEncoder(thresholdPerMille = 300)

        assertNull(encoder.process(frame("Music", 500, 0)))
        assertNull(encoder.process(frame("Music", 700, 1)))

        val music = encoder.process(frame("Dog", 800, 2))
        assertEquals("Music", music?.label)
        assertEquals(600, music?.scorePerMille)
        assertEquals(2, music?.frameCount)

        val dog = encoder.process(frame("Dog", 100, 3))
        assertEquals("Dog", dog?.label)
        assertEquals(800, dog?.scorePerMille)
        assertNull(encoder.closeOpen())
    }

    @Test
    fun suppressesSpeechClassWhenAsrIsActive() {
        val encoder = AudioTagRunLengthEncoder(thresholdPerMille = 300)

        assertNull(encoder.process(frame("Speech", 700, 0, asrActive = true)))
        assertNull(encoder.closeOpen())
    }

    private fun frame(
        label: String,
        scorePerMille: Int,
        index: Long,
        asrActive: Boolean = false,
    ): AudioTagFrame =
        AudioTagFrame(
            label = label,
            scorePerMille = scorePerMille,
            tStartNanos = index * 500_000_000L,
            tEndNanos = (index + 1) * 500_000_000L,
            asrActive = asrActive,
        )
}
