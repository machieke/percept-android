package org.takopi.percept.perception.audio

import org.takopi.percept.core.trace.PerceptionEvent
import org.takopi.percept.core.trace.TraceSink

/** Streaming ASR backend (§3.4); offsets are relative to the window start. */
interface AsrEngine {
    fun transcribe(samples: ShortArray, sampleRate: Int): List<AsrWindowSegment>
}

data class AsrWindowSegment(
    val text: String,
    val startOffsetNanos: Long,
    val endOffsetNanos: Long,
    val avgLogProbMicro: Long,
    val langHint: String = "en",
) {
    init {
        require(startOffsetNanos >= 0) { "startOffsetNanos must be non-negative" }
        require(endOffsetNanos >= startOffsetNanos) { "endOffsetNanos must be >= startOffsetNanos" }
    }
}

/** Audio tag backend (YAMNet-class); returns the top-1 score or null when idle. */
interface AudioTagger {
    fun classifyTop(samples: ShortArray, sampleRate: Int): AudioTagScore?
}

data class AudioTagScore(
    val label: String,
    val labelSpace: String = "audioset",
    val scorePerMille: Int,
) {
    init {
        require(scorePerMille in 0..1000) { "scorePerMille must be in 0..1000" }
    }
}

data class AudioRunCounters(
    val ringBufferOverruns: Long,
    val appendedSamples: Long,
    val lastProcessedTNanos: Long,
    val asrWindowsProcessed: Long,
    val asrWindowsTranscribed: Long,
    val asrWindowsSkipped: Long,
    val asrTranscribeMillis: Long,
)

/**
 * §3.4 pipeline glue: one PCM16 stream fanned out from a ring buffer to two
 * consumers — VAD-gated ASR windows (5 s window, 1 s overlap) and audio-tag
 * frames (0.975 s, 0.5 s hop) run-length encoded into segments.
 *
 * [append] is safe from the capture thread; [processAvailable]/[finish] must
 * be called from a single processing thread.
 */
class AudioPerceptionEngine(
    private val sink: TraceSink,
    private val asr: AsrEngine,
    private val tagger: AudioTagger,
    private val vad: EnergyVad = EnergyVad(DEFAULT_VAD_THRESHOLD_PER_MILLE),
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    private val startTNanos: Long = 0,
    asrWindowSeconds: Int = 5,
    asrOverlapSeconds: Int = 1,
    private val tagFrameSamples: Int = DEFAULT_TAG_FRAME_SAMPLES,
    private val tagHopSamples: Int = DEFAULT_TAG_HOP_SAMPLES,
    ringCapacitySamples: Int = sampleRate * 30,
    private val rle: AudioTagRunLengthEncoder = AudioTagRunLengthEncoder(),
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(startTNanos >= 0) { "startTNanos must be non-negative" }
        require(asrWindowSeconds > asrOverlapSeconds) { "window must exceed overlap" }
        require(asrOverlapSeconds >= 0) { "overlap must be non-negative" }
        require(tagFrameSamples > 0) { "tagFrameSamples must be positive" }
        require(tagHopSamples in 1..tagFrameSamples) { "tagHopSamples must be in 1..tagFrameSamples" }
    }

    private val ring = Pcm16RingBuffer(ringCapacitySamples)
    private val asrWindowSamples = asrWindowSeconds * sampleRate
    private val asrStrideSamples = (asrWindowSeconds - asrOverlapSeconds) * sampleRate

    private var asrCursor = 0L
    private var tagCursor = 0L
    private var asrCoveredUntilSample = 0L
    private var lastAsrEmittedEndNanos = 0L
    private var overruns = 0L
    private var asrWindowsProcessed = 0L
    private var asrWindowsTranscribed = 0L
    private var asrWindowsSkipped = 0L
    private var asrTranscribeNanos = 0L
    private var finished = false

    // Sample ranges of speech-active ASR windows, for Speech-tag suppression.
    private val speechActiveRanges = ArrayDeque<LongRange>()

    /** Called from the capture thread; never blocks. */
    fun append(samples: ShortArray) {
        ring.append(samples)
    }

    /** Drains every complete ASR window and every ASR-covered tag frame. */
    fun processAvailable() {
        check(!finished) { "engine already finished" }
        drain(final = false)
    }

    /** Processes the tail (including tag frames past the last full ASR window). */
    fun finish(): AudioRunCounters {
        check(!finished) { "engine already finished" }
        drain(final = true)
        rle.closeOpen()?.let(::submitTagSegment)
        finished = true
        val appended = ring.writeIndex
        return AudioRunCounters(
            ringBufferOverruns = overruns,
            appendedSamples = appended,
            lastProcessedTNanos = sampleTNanos(appended),
            asrWindowsProcessed = asrWindowsProcessed,
            asrWindowsTranscribed = asrWindowsTranscribed,
            asrWindowsSkipped = asrWindowsSkipped,
            asrTranscribeMillis = asrTranscribeNanos / 1_000_000L,
        )
    }

    private fun drain(final: Boolean) {
        if (final) {
            // Whisper on the device can run far slower than realtime (first
            // Moto G84 session: ~31 s per 5 s window); stop must be prompt,
            // so pending windows are skipped and counted, not transcribed.
            while (ring.writeIndex >= asrCursor + asrWindowSamples) {
                asrWindowsSkipped += 1
                asrCoveredUntilSample = asrCursor + asrWindowSamples
                asrCursor += asrStrideSamples
            }
        } else {
            // ASR first so tag frames in the same region observe asrActive.
            while (processNextAsrWindow()) Unit
        }
        while (processNextTagFrame(final)) Unit
    }

    private fun processNextAsrWindow(): Boolean {
        if (ring.writeIndex < asrCursor + asrWindowSamples) return false
        // When transcription cannot keep up with capture, jump toward the
        // freshest complete window: transcripts stay current and sparse
        // instead of arriving minutes late, and the deficit is counted.
        // Exactly one queued stride is normal batching, not lag.
        val behind = (ring.writeIndex - asrWindowSamples) - asrCursor
        if (behind > asrStrideSamples) {
            val skipped = behind / asrStrideSamples
            asrWindowsSkipped += skipped
            asrCursor += skipped * asrStrideSamples
        }
        val read = ring.readFrom(asrCursor, asrWindowSamples)
        val actualStart = read.nextIndex - read.samples.size
        if (read.overflowed) {
            overruns += 1
        }
        asrWindowsProcessed += 1
        if (vad.isSpeech(read.samples)) {
            asrWindowsTranscribed += 1
            speechActiveRanges.addLast(actualStart until actualStart + read.samples.size)
            val windowStartNanos = sampleTNanos(actualStart)
            val transcribeStart = System.nanoTime()
            val segments = asr.transcribe(read.samples, sampleRate)
            asrTranscribeNanos += System.nanoTime() - transcribeStart
            for (segment in segments) {
                submitAsrSegment(windowStartNanos, segment)
            }
        }
        asrCoveredUntilSample = actualStart + read.samples.size
        asrCursor = actualStart + asrStrideSamples
        return true
    }

    private fun processNextTagFrame(final: Boolean): Boolean {
        val frameEnd = tagCursor + tagFrameSamples
        if (ring.writeIndex < frameEnd) return false
        // Wait for ASR coverage so Speech suppression sees the final asrActive
        // verdict for this region; on finish, process the uncovered tail too.
        if (!final && frameEnd > asrCoveredUntilSample) return false

        val read = ring.readFrom(tagCursor, tagFrameSamples)
        val actualStart = read.nextIndex - read.samples.size
        if (read.overflowed) {
            overruns += 1
        }
        val frameStartNanos = sampleTNanos(actualStart)
        val frameEndNanos = sampleTNanos(actualStart + read.samples.size)
        val top = tagger.classifyTop(read.samples, sampleRate)
        val closed = if (top == null) {
            rle.closeOpen()
        } else {
            rle.process(
                AudioTagFrame(
                    label = top.label,
                    labelSpace = top.labelSpace,
                    scorePerMille = top.scorePerMille,
                    tStartNanos = frameStartNanos,
                    tEndNanos = frameEndNanos,
                    asrActive = isSpeechActive(actualStart, actualStart + read.samples.size),
                ),
            )
        }
        closed?.let(::submitTagSegment)
        tagCursor = actualStart + tagHopSamples
        return true
    }

    private fun isSpeechActive(startSample: Long, endSample: Long): Boolean {
        while (speechActiveRanges.isNotEmpty() && speechActiveRanges.first().last < tagCursor) {
            speechActiveRanges.removeFirst()
        }
        return speechActiveRanges.any { range ->
            startSample <= range.last && endSample - 1 >= range.first
        }
    }

    private fun submitAsrSegment(windowStartNanos: Long, segment: AsrWindowSegment) {
        val tEndNanos = windowStartNanos + segment.endOffsetNanos
        // Overlapping windows re-transcribe the 1 s seam; drop fully re-emitted
        // segments and clamp partial overlaps to keep segments disjoint.
        if (tEndNanos <= lastAsrEmittedEndNanos) return
        val tStartNanos = maxOf(windowStartNanos + segment.startOffsetNanos, lastAsrEmittedEndNanos)
        if (segment.text.isEmpty()) return
        sink.trySubmit(
            PerceptionEvent.AsrSegment(
                text = segment.text,
                langHint = segment.langHint,
                tStartNanos = tStartNanos,
                tEndNanos = tEndNanos,
                avgLogProbMicro = segment.avgLogProbMicro,
            ),
        )
        lastAsrEmittedEndNanos = tEndNanos
    }

    private fun submitTagSegment(segment: AudioTagSegment) {
        sink.trySubmit(
            PerceptionEvent.AudioTagSegment(
                label = segment.label,
                labelSpace = segment.labelSpace,
                scorePerMille = segment.scorePerMille,
                tStartNanos = segment.tStartNanos,
                tEndNanos = segment.tEndNanos,
            ),
        )
    }

    private fun sampleTNanos(sampleIndex: Long): Long =
        startTNanos + sampleIndex * 1_000_000_000L / sampleRate

    companion object {
        const val DEFAULT_SAMPLE_RATE: Int = 16_000

        /**
         * Mean-abs per-mille of full scale. Device sessions showed YAMNet
         * hearing Speech while every ASR window failed this gate at 20 and
         * then at 5 — raw phone-mic levels are very low — so the gate is a
         * near-floor sanity check and VOICE_RECOGNITION AGC does the rest.
         */
        const val DEFAULT_VAD_THRESHOLD_PER_MILLE: Int = 2

        /** 0.975 s YAMNet frame at 16 kHz. */
        const val DEFAULT_TAG_FRAME_SAMPLES: Int = 15_600

        /** 0.5 s hop at 16 kHz. */
        const val DEFAULT_TAG_HOP_SAMPLES: Int = 8_000
    }
}
