package org.takopi.percept.perception.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Pcm16RingBufferTest {
    @Test
    fun supportsIndependentConsumersAndReportsOverflow() {
        val buffer = Pcm16RingBuffer(capacitySamples = 5)
        val consumerA = buffer.append(shortArrayOf(1, 2, 3))
        val consumerB = buffer.writeIndex

        buffer.append(shortArrayOf(4, 5, 6, 7))

        val aRead = buffer.readFrom(consumerA, maxSamples = 10)
        assertTrue(aRead.overflowed)
        assertArrayEquals(shortArrayOf(3, 4, 5, 6, 7), aRead.samples)
        assertEquals(7, aRead.nextIndex)

        val bRead = buffer.readFrom(consumerB, maxSamples = 2)
        assertFalse(bRead.overflowed)
        assertArrayEquals(shortArrayOf(4, 5), bRead.samples)
        assertEquals(5, bRead.nextIndex)
    }
}
