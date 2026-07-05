package org.takopi.percept.perception.audio

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * Streaming Zipformer transducer via sherpa-onnx — the primary ASR backend.
 * whisper tiny measured RTF ~2.6 on the Moto G84 even with a capped encoder
 * context; the 20M int8 Zipformer decodes well under realtime on one big
 * core. Each 5 s engine window feeds one short-lived stream, so the windowed
 * [AsrEngine] contract is unchanged.
 */
class SherpaZipformerAsrEngine private constructor(
    private val recognizer: OnlineRecognizer,
    val extractionRunId: String,
) : CancellableAsrEngine, AutoCloseable {

    /** Windows decode in well under a second; nothing worth aborting. */
    override fun cancelInFlight() {}

    override fun transcribe(samples: ShortArray, sampleRate: Int): List<AsrWindowSegment> {
        val floats = FloatArray(samples.size) { index -> samples[index] / 32768f }
        val stream = recognizer.createStream("")
        try {
            stream.acceptWaveform(floats, sampleRate)
            // Tail padding flushes the final frames through the encoder.
            stream.acceptWaveform(FloatArray(TAIL_PADDING_SAMPLES), sampleRate)
            stream.inputFinished()
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream)
            }
            val result = recognizer.getResult(stream)
            val text = result.text.trim()
            if (text.isEmpty()) return emptyList()

            val windowEndNanos = samples.size * 1_000_000_000L / sampleRate
            val startNanos = result.timestamps.firstOrNull()
                ?.let { (it.toDouble() * 1_000_000_000L).toLong() }
                ?.coerceIn(0L, windowEndNanos)
                ?: 0L
            val avgLogProbMicro = result.ysProbs.takeIf { it.isNotEmpty() }
                ?.let { probs -> (probs.sumOf { p -> p.toDouble() } / probs.size * 1_000_000).toLong() }
                ?: 0L
            return listOf(
                AsrWindowSegment(
                    text = text,
                    startOffsetNanos = startNanos,
                    endOffsetNanos = windowEndNanos,
                    avgLogProbMicro = avgLogProbMicro,
                    langHint = "en",
                ),
            )
        } finally {
            stream.release()
        }
    }

    override fun close() {
        recognizer.release()
    }

    companion object {
        const val EXTRACTION_RUN_ID: String = "zipformer-en-20m-int8@sherpa-onnx-1.13.3-cpu2"
        private const val ASSET_DIR = "models/zipformer20m"

        /** 0.6 s of silence to flush the streaming encoder's lookahead. */
        private const val TAIL_PADDING_SAMPLES: Int = 9_600

        fun create(context: Context, threads: Int = 2): SherpaZipformerAsrEngine {
            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$ASSET_DIR/encoder-epoch-99-avg-1.int8.onnx",
                        decoder = "$ASSET_DIR/decoder-epoch-99-avg-1.onnx",
                        joiner = "$ASSET_DIR/joiner-epoch-99-avg-1.int8.onnx",
                    ),
                    tokens = "$ASSET_DIR/tokens.txt",
                    numThreads = threads,
                    modelType = "zipformer",
                ),
            )
            return SherpaZipformerAsrEngine(
                recognizer = OnlineRecognizer(context.assets, config),
                extractionRunId = EXTRACTION_RUN_ID,
            )
        }
    }
}
