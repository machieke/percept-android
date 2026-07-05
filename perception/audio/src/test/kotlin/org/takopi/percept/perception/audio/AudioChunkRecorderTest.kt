package org.takopi.percept.perception.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.takopi.percept.core.trace.PerceptionEvent
import org.takopi.percept.core.trace.TraceSink

private class ChunkRecordingSink : TraceSink {
    val chunks = mutableListOf<PerceptionEvent.AudioChunk>()

    override fun trySubmit(event: org.takopi.percept.core.trace.PerceptionEvent): Boolean {
        chunks.add(event as PerceptionEvent.AudioChunk)
        return true
    }
}

/** Reversible fake "codec": bytes are the PCM itself, so tests can verify content. */
private class IdentityEncoder : AudioChunkEncoder {
    override val contentType = "audio/L16;rate=16000;channels=1"
    override val codecId = "identity-test"
    var encodeCalls = 0

    override fun encode(samples: ShortArray, sampleRate: Int): ByteArray {
        encodeCalls += 1
        return PcmChunkEncoder().encode(samples, sampleRate)
    }
}

class AudioChunkRecorderTest {
    @Test
    fun rotatesFullChunksAndFlushesPartialTail() {
        val sink = ChunkRecordingSink()
        val encoder = IdentityEncoder()
        val recorder = AudioChunkRecorder(
            sink = sink,
            encoder = encoder,
            sampleRate = 16_000,
            chunkSeconds = 1,
        )

        // 2.5 s appended in odd-sized pieces spanning chunk boundaries.
        recorder.append(ShortArray(10_000) { 1 })
        recorder.append(ShortArray(20_000) { 2 })
        recorder.append(ShortArray(10_000) { 3 })
        recorder.encodeReady()
        assertEquals(2, sink.chunks.size)
        recorder.finish()

        assertEquals(3, sink.chunks.size)
        val (first, second, tail) = sink.chunks
        assertEquals(0, first.chunkIndex)
        assertEquals(0L, first.tStartNanos)
        assertEquals(1_000_000_000L, first.tEndNanos)
        assertEquals(16_000L, first.sampleCount)
        assertEquals(1, second.chunkIndex)
        assertEquals(1_000_000_000L, second.tStartNanos)
        assertEquals(2_000_000_000L, second.tEndNanos)
        assertEquals(2, tail.chunkIndex)
        assertEquals(2_000_000_000L, tail.tStartNanos)
        assertEquals(2_500_000_000L, tail.tEndNanos)
        assertEquals(8_000L, tail.sampleCount)
        assertEquals("identity-test", tail.codecId)

        // Sample content crosses boundaries intact: chunk 0 = 10k of 1s + 6k of 2s.
        val chunk0 = ShortArray(16_000)
        java.nio.ByteBuffer.wrap(first.encoded)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(chunk0)
        assertArrayEquals(ShortArray(10_000) { 1 } + ShortArray(6_000) { 2 }, chunk0)
    }

    @Test
    fun appendNeverEncodesOnCallerThread() {
        val sink = ChunkRecordingSink()
        val encoder = IdentityEncoder()
        val recorder = AudioChunkRecorder(sink, encoder, chunkSeconds = 1)

        recorder.append(ShortArray(64_000))

        assertEquals(0, encoder.encodeCalls)
        assertTrue(sink.chunks.isEmpty())
        recorder.encodeReady()
        assertEquals(4, encoder.encodeCalls)
    }

    @Test
    fun startTNanosOffsetsChunkTimestamps() {
        val sink = ChunkRecordingSink()
        val recorder = AudioChunkRecorder(
            sink = sink,
            encoder = IdentityEncoder(),
            startTNanos = 5_000_000_000L,
            chunkSeconds = 1,
        )
        recorder.append(ShortArray(16_000))
        recorder.finish()

        assertEquals(5_000_000_000L, sink.chunks.single().tStartNanos)
        assertEquals(6_000_000_000L, sink.chunks.single().tEndNanos)
    }

    @Test
    fun emptySessionEmitsNoChunks() {
        val sink = ChunkRecordingSink()
        AudioChunkRecorder(sink, IdentityEncoder()).finish()
        assertTrue(sink.chunks.isEmpty())
    }
}
