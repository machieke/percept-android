package org.takopi.percept.perception.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.takopi.percept.core.trace.PerceptionEvent
import org.takopi.percept.core.trace.TraceSink

private class RecordingSink : TraceSink {
    val events = mutableListOf<PerceptionEvent>()

    override fun trySubmit(event: PerceptionEvent): Boolean {
        events += event
        return true
    }

    inline fun <reified T : PerceptionEvent> ofType(): List<T> = events.filterIsInstance<T>()
}

private class FakeAsr(
    private val segmentsForCall: (Int) -> List<AsrWindowSegment>,
) : AsrEngine {
    var calls = 0

    override fun transcribe(samples: ShortArray, sampleRate: Int): List<AsrWindowSegment> =
        segmentsForCall(calls++)
}

/**
 * Level-band tagger: >= 500 per-mille reads as Speech, >= 50 as Music,
 * silence as nothing. Amplitudes in tests are chosen to land in one band.
 */
private class LevelBandTagger : AudioTagger {
    override fun classifyTop(samples: ShortArray, sampleRate: Int): AudioTagScore? {
        val level = meanAbsoluteLevelPerMille(samples)
        return when {
            level >= 500 -> AudioTagScore(label = "Speech", scorePerMille = 800)
            level >= 50 -> AudioTagScore(label = "Music", scorePerMille = 700)
            else -> null
        }
    }
}

class AudioPerceptionEngineTest {
    private val speechAmplitude: Short = 25_000 // ~762 per-mille
    private val musicAmplitude: Short = 2_000 // ~61 per-mille

    private fun pcm(seconds: Double, amplitude: Short, sampleRate: Int = 16_000): ShortArray =
        ShortArray((seconds * sampleRate).toInt()) { amplitude }

    private fun engine(
        sink: RecordingSink,
        asr: AsrEngine,
        vadThresholdPerMille: Int = 500,
    ) = AudioPerceptionEngine(
        sink = sink,
        asr = asr,
        tagger = LevelBandTagger(),
        vad = EnergyVad(vadThresholdPerMille),
    )

    @Test
    fun musicRunClosesIntoSingleTagSegment() {
        val sink = RecordingSink()
        val asr = FakeAsr { emptyList() }
        val engine = engine(sink, asr)

        engine.append(pcm(4.0, musicAmplitude))
        engine.processAvailable()
        val counters = engine.finish()

        assertEquals(0, asr.calls)
        val tags = sink.ofType<PerceptionEvent.AudioTagSegment>()
        assertEquals(1, tags.size)
        val tag = tags.single()
        assertEquals("Music", tag.label)
        assertEquals("audioset", tag.labelSpace)
        assertEquals(700, tag.scorePerMille)
        assertEquals(0L, tag.tStartNanos)
        // Last full 0.975 s frame starts at sample 48000 and ends at 63600.
        assertEquals(3_975_000_000L, tag.tEndNanos)
        assertEquals(0L, counters.ringBufferOverruns)
        assertEquals(64_000L, counters.appendedSamples)
    }

    @Test
    fun speechWindowRunsAsrAndSuppressesSpeechTags() {
        val sink = RecordingSink()
        val asr = FakeAsr { call ->
            if (call == 0) {
                listOf(
                    AsrWindowSegment(
                        text = "hello there",
                        startOffsetNanos = 500_000_000L,
                        endOffsetNanos = 4_500_000_000L,
                        avgLogProbMicro = -150_000,
                    ),
                )
            } else {
                emptyList()
            }
        }
        val engine = engine(sink, asr)

        engine.append(pcm(5.0, speechAmplitude))
        engine.processAvailable()
        engine.append(pcm(4.0, musicAmplitude))
        engine.processAvailable()
        val counters = engine.finish()

        // Second window (4 s..9 s) mixes 1 s speech + 4 s music and falls
        // below the VAD threshold, so ASR ran exactly once.
        assertEquals(1, asr.calls)
        assertEquals(2L, counters.asrWindowsProcessed)
        assertEquals(1L, counters.asrWindowsTranscribed)

        val asrSegments = sink.ofType<PerceptionEvent.AsrSegment>()
        assertEquals(1, asrSegments.size)
        assertEquals("hello there", asrSegments.single().text)
        assertEquals(500_000_000L, asrSegments.single().tStartNanos)
        assertEquals(4_500_000_000L, asrSegments.single().tEndNanos)

        // Speech-labelled frames during the active ASR window are suppressed;
        // only the trailing music run remains.
        val tags = sink.ofType<PerceptionEvent.AudioTagSegment>()
        assertEquals(1, tags.size)
        assertEquals("Music", tags.single().label)
        assertEquals(4_500_000_000L, tags.single().tStartNanos)
        assertEquals(8_975_000_000L, tags.single().tEndNanos)
    }

    @Test
    fun speechTagsEmitWhenVadNeverTriggersAsr() {
        val sink = RecordingSink()
        val asr = FakeAsr { emptyList() }
        // VAD threshold above any test amplitude: ASR never runs.
        val engine = engine(sink, asr, vadThresholdPerMille = 900)

        engine.append(pcm(3.0, speechAmplitude))
        engine.finish()

        assertEquals(0, asr.calls)
        val tags = sink.ofType<PerceptionEvent.AudioTagSegment>()
        assertEquals(1, tags.size)
        assertEquals("Speech", tags.single().label)
    }

    @Test
    fun overlappingWindowsEmitDisjointAsrSegments() {
        val sink = RecordingSink()
        // Every window claims a full-window transcript, so the 1 s seam
        // between windows is re-transcribed and must be de-overlapped.
        val asr = FakeAsr { call ->
            listOf(
                AsrWindowSegment(
                    text = "window $call",
                    startOffsetNanos = 0L,
                    endOffsetNanos = 5_000_000_000L,
                    avgLogProbMicro = -100_000,
                ),
            )
        }
        val engine = engine(sink, asr)

        engine.append(pcm(9.0, speechAmplitude))
        engine.processAvailable()
        engine.finish()

        assertEquals(2, asr.calls)
        val segments = sink.ofType<PerceptionEvent.AsrSegment>()
        assertEquals(2, segments.size)
        assertEquals(0L, segments[0].tStartNanos)
        assertEquals(5_000_000_000L, segments[0].tEndNanos)
        assertEquals(5_000_000_000L, segments[1].tStartNanos)
        assertEquals(9_000_000_000L, segments[1].tEndNanos)
    }

    @Test
    fun ringBufferOverrunIsCounted() {
        val sink = RecordingSink()
        val asr = FakeAsr { emptyList() }
        val engine = AudioPerceptionEngine(
            sink = sink,
            asr = asr,
            tagger = LevelBandTagger(),
            vad = EnergyVad(900),
            asrWindowSeconds = 2,
            asrOverlapSeconds = 0,
            ringCapacitySamples = 16_000,
        )

        // 4 s appended into a 1 s ring without draining: data is lost and the
        // overrun counter must say so.
        engine.append(pcm(4.0, musicAmplitude))
        val counters = engine.finish()

        assertTrue(counters.ringBufferOverruns >= 1)
        assertEquals(64_000L, counters.appendedSamples)
    }

    @Test
    fun laggingAsrSkipsToFreshestWindowAndCountsDeficit() {
        val sink = RecordingSink()
        val asr = FakeAsr { emptyList() }
        val engine = engine(sink, asr, vadThresholdPerMille = 0)

        // 15 s arrives before the (slow) consumer drains: the cursor jumps
        // over the stale strides and only the freshest complete window is
        // transcribed.
        engine.append(pcm(15.0, speechAmplitude))
        engine.processAvailable()
        val counters = engine.finish()

        assertEquals(1L, counters.asrWindowsProcessed)
        assertEquals(1, asr.calls)
        assertEquals(2L, counters.asrWindowsSkipped)
    }

    @Test
    fun finishSkipsPendingAsrWindowsInsteadOfTranscribing() {
        val sink = RecordingSink()
        val asr = FakeAsr { emptyList() }
        val engine = engine(sink, asr, vadThresholdPerMille = 0)

        // Never drained during the session: stop must not run the backlog.
        engine.append(pcm(20.0, speechAmplitude))
        val counters = engine.finish()

        assertEquals(0, asr.calls)
        assertEquals(0L, counters.asrWindowsProcessed)
        assertEquals(4L, counters.asrWindowsSkipped)
    }

    @Test
    fun startTNanosOffsetsAllTimestamps() {
        val sink = RecordingSink()
        val asr = FakeAsr { emptyList() }
        val engine = AudioPerceptionEngine(
            sink = sink,
            asr = asr,
            tagger = LevelBandTagger(),
            vad = EnergyVad(900),
            startTNanos = 10_000_000_000L,
        )

        engine.append(pcm(2.0, musicAmplitude))
        engine.finish()

        val tag = sink.ofType<PerceptionEvent.AudioTagSegment>().single()
        assertEquals(10_000_000_000L, tag.tStartNanos)
        assertTrue(tag.tEndNanos > 10_000_000_000L)
    }
}
