package org.takopi.percept.perception.audio

/**
 * Boundary to the whisper.cpp JNI build. The native library is compiled from
 * the whisper.cpp submodule for arm64-v8a (M5); on hosts or builds without it
 * [NativeWhisper.isAvailable] is false and callers fall back to [NoopAsrEngine].
 */
interface WhisperBridge : AutoCloseable {
    fun transcribe(pcm: FloatArray): List<WhisperBridgeSegment>
}

data class WhisperBridgeSegment(
    val text: String,
    val startMillis: Long,
    val endMillis: Long,
    val avgLogProbMicro: Long,
)

class WhisperAsrEngine(
    private val bridge: WhisperBridge,
    private val langHint: String = "en",
) : AsrEngine {
    override fun transcribe(samples: ShortArray, sampleRate: Int): List<AsrWindowSegment> {
        val floats = FloatArray(samples.size) { index -> samples[index] / 32768f }
        return bridge.transcribe(floats).mapNotNull { segment ->
            val text = segment.text.trim()
            if (text.isEmpty()) return@mapNotNull null
            AsrWindowSegment(
                text = text,
                startOffsetNanos = segment.startMillis * 1_000_000L,
                endOffsetNanos = segment.endMillis * 1_000_000L,
                avgLogProbMicro = segment.avgLogProbMicro,
                langHint = langHint,
            )
        }
    }
}

/** ASR disabled: emits nothing, keeping the rest of the pipeline intact. */
class NoopAsrEngine : AsrEngine {
    override fun transcribe(samples: ShortArray, sampleRate: Int): List<AsrWindowSegment> =
        emptyList()
}

object NativeWhisper {
    const val LIBRARY_NAME: String = "whisper_percept"
    const val MODEL_ASSET_PATH: String = "models/ggml-tiny-q8_0.bin"
    const val EXTRACTION_RUN_ID: String = "whisper-tiny-q8_0@whispercpp-cpu4"
    const val DISABLED_RUN_ID: String = "asr-disabled"

    private val loaded: Boolean by lazy {
        try {
            System.loadLibrary(LIBRARY_NAME)
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    fun isAvailable(): Boolean = loaded

    /** @param modelPath filesystem path of the ggml model (copied out of assets). */
    fun createBridge(modelPath: String, threads: Int = 4, language: String = "en"): WhisperBridge {
        check(isAvailable()) { "native whisper library not available" }
        val context = initContext(modelPath, threads)
        check(context != 0L) { "whisper context init failed for $modelPath" }
        return NativeBridge(context, language)
    }

    /** Returns the engine plus the extractionRunId to record in provenance. */
    fun createEngineOrNoop(modelPath: String?, language: String = "en"): Pair<AsrEngine, String> =
        if (modelPath != null && isAvailable()) {
            WhisperAsrEngine(createBridge(modelPath, language = language), langHint = language) to
                EXTRACTION_RUN_ID
        } else {
            NoopAsrEngine() to DISABLED_RUN_ID
        }

    private class NativeBridge(
        private var context: Long,
        private val language: String,
    ) : WhisperBridge {
        override fun transcribe(pcm: FloatArray): List<WhisperBridgeSegment> {
            check(context != 0L) { "bridge closed" }
            val flat = transcribeNative(context, pcm, language) ?: return emptyList()
            // Flat layout per segment: [startMillis, endMillis, avgLogProbMicro] with
            // texts returned in parallel; see jni/whisper_percept.cpp.
            val texts = lastSegmentTexts(context) ?: return emptyList()
            return texts.mapIndexed { index, text ->
                WhisperBridgeSegment(
                    text = text,
                    startMillis = flat[index * 3],
                    endMillis = flat[index * 3 + 1],
                    avgLogProbMicro = flat[index * 3 + 2],
                )
            }
        }

        override fun close() {
            if (context != 0L) {
                freeContext(context)
                context = 0L
            }
        }
    }

    private external fun initContext(modelPath: String, threads: Int): Long

    private external fun transcribeNative(context: Long, pcm: FloatArray, language: String): LongArray?

    private external fun lastSegmentTexts(context: Long): Array<String>?

    private external fun freeContext(context: Long)
}
