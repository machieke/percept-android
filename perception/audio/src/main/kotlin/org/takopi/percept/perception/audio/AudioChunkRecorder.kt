package org.takopi.percept.perception.audio

import org.takopi.percept.core.trace.PerceptionEvent
import org.takopi.percept.core.trace.TraceSink
import java.util.ArrayDeque

/**
 * Buffers the session's full PCM stream into fixed-duration chunks and
 * submits each as a compressed [PerceptionEvent.AudioChunk] artifact — the
 * complete episodic audio record, dispatched with the trace bundles.
 *
 * [append] is called from the capture thread and only accumulates;
 * [encodeReady]/[finish] run encoding on the processing thread.
 */
class AudioChunkRecorder(
    private val sink: TraceSink,
    private val encoder: AudioChunkEncoder,
    private val sampleRate: Int = AudioPerceptionEngine.DEFAULT_SAMPLE_RATE,
    private val startTNanos: Long = 0,
    chunkSeconds: Int = DEFAULT_CHUNK_SECONDS,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(startTNanos >= 0) { "startTNanos must be non-negative" }
        require(chunkSeconds > 0) { "chunkSeconds must be positive" }
    }

    private val chunkSamples = chunkSeconds * sampleRate
    private val lock = Any()
    private var current = ShortArray(chunkSamples)
    private var currentFill = 0
    private var samplesConsumed = 0L
    private var nextChunkIndex = 0
    private val readyChunks = ArrayDeque<PendingChunk>()

    private class PendingChunk(
        val chunkIndex: Int,
        val startSample: Long,
        val samples: ShortArray,
    )

    /** Capture thread: cheap copy only, never encodes. */
    fun append(samples: ShortArray) {
        synchronized(lock) {
            var offset = 0
            while (offset < samples.size) {
                val length = minOf(samples.size - offset, chunkSamples - currentFill)
                System.arraycopy(samples, offset, current, currentFill, length)
                currentFill += length
                offset += length
                if (currentFill == chunkSamples) {
                    rotateLocked()
                }
            }
        }
    }

    /** Processing thread: encode and submit every completed chunk. */
    fun encodeReady() {
        while (true) {
            val pending = synchronized(lock) { readyChunks.pollFirst() } ?: return
            submit(pending)
        }
    }

    /** Flush the partial tail chunk and encode everything left. */
    fun finish() {
        synchronized(lock) {
            if (currentFill > 0) {
                rotateLocked()
            }
        }
        encodeReady()
    }

    private fun rotateLocked() {
        readyChunks.addLast(
            PendingChunk(
                chunkIndex = nextChunkIndex++,
                startSample = samplesConsumed,
                samples = current.copyOf(currentFill),
            ),
        )
        samplesConsumed += currentFill
        currentFill = 0
    }

    private fun submit(pending: PendingChunk) {
        val encoded = encoder.encode(pending.samples, sampleRate)
        sink.trySubmit(
            PerceptionEvent.AudioChunk(
                chunkIndex = pending.chunkIndex,
                tStartNanos = sampleTNanos(pending.startSample),
                tEndNanos = sampleTNanos(pending.startSample + pending.samples.size),
                sampleRate = sampleRate,
                sampleCount = pending.samples.size.toLong(),
                contentType = encoder.contentType,
                codecId = encoder.codecId,
                encoded = encoded,
            ),
        )
    }

    private fun sampleTNanos(sampleIndex: Long): Long =
        startTNanos + sampleIndex * 1_000_000_000L / sampleRate

    companion object {
        const val DEFAULT_CHUNK_SECONDS: Int = 60
    }
}
