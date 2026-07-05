package org.takopi.percept.perception.audio

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * §3.4 audio tagger: YAMNet via the plain TFLite interpreter over the
 * engine's 0.975 s / 15600-sample frames. (MediaPipe's audio task cannot
 * coexist in one process with tasks-vision — each JNI lib registers its own
 * framework singleton — so audio runs on LiteRT directly, as the plan
 * originally specified.)
 */
class TfLiteYamnetTagger private constructor(
    private val interpreter: Interpreter,
    private val labels: List<String>,
    private val inputIsBatched: Boolean,
    private val inputSamples: Int,
    private val outputIsBatched: Boolean,
    private val minScorePerMille: Int,
    val extractionRunId: String,
) : AudioTagger, AutoCloseable {

    override fun classifyTop(samples: ShortArray, sampleRate: Int): AudioTagScore? {
        val floats = FloatArray(inputSamples)
        val count = minOf(samples.size, inputSamples)
        for (index in 0 until count) {
            floats[index] = samples[index] / 32768f
        }
        val scores: FloatArray
        synchronized(interpreter) {
            if (outputIsBatched) {
                val output = Array(1) { FloatArray(labels.size) }
                interpreter.run(if (inputIsBatched) arrayOf(floats) else floats, output)
                scores = output[0]
            } else {
                val output = FloatArray(labels.size)
                interpreter.run(if (inputIsBatched) arrayOf(floats) else floats, output)
                scores = output
            }
        }
        var best = 0
        for (index in 1 until scores.size) {
            if (scores[index] > scores[best]) best = index
        }
        val scorePerMille = (scores[best] * 1000f).roundToInt().coerceIn(0, 1000)
        if (scorePerMille < minScorePerMille) return null
        return AudioTagScore(
            label = labels[best],
            labelSpace = LABEL_SPACE,
            scorePerMille = scorePerMille,
        )
    }

    override fun close() {
        interpreter.close()
    }

    companion object {
        const val LABEL_SPACE: String = "audioset"
        const val MODEL_ASSET_PATH: String = "models/yamnet.tflite"
        const val LABELS_ASSET_PATH: String = "models/yamnet_class_map.csv"
        const val EXTRACTION_RUN_ID: String = "yamnet@tflite-2.14.0-cpu2"

        fun create(
            context: Context,
            minScorePerMille: Int = 50,
            threads: Int = 2,
        ): TfLiteYamnetTagger {
            val labels = context.assets.open(LABELS_ASSET_PATH).bufferedReader().useLines {
                YamnetClassMap.parse(it)
            }
            val modelBytes = context.assets.open(MODEL_ASSET_PATH).readBytes()
            val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size)
                .order(ByteOrder.nativeOrder())
            modelBuffer.put(modelBytes)
            modelBuffer.rewind()
            val interpreter = Interpreter(modelBuffer, Interpreter.Options().setNumThreads(threads))

            var inputShape = interpreter.getInputTensor(0).shape()
            val wantedSamples = AudioPerceptionEngine.DEFAULT_TAG_FRAME_SAMPLES
            // Dynamic-length variants ship with a placeholder shape; pin it.
            if (inputShape.size == 1 && inputShape[0] != wantedSamples) {
                interpreter.resizeInput(0, intArrayOf(wantedSamples))
                interpreter.allocateTensors()
                inputShape = interpreter.getInputTensor(0).shape()
            }
            val inputIsBatched = inputShape.size == 2
            val inputSamples = inputShape.last()
            val outputShape = interpreter.getOutputTensor(0).shape()
            val classCount = outputShape.last()
            check(classCount == labels.size) {
                "yamnet output has $classCount classes but class map has ${labels.size}"
            }
            return TfLiteYamnetTagger(
                interpreter = interpreter,
                labels = labels,
                inputIsBatched = inputIsBatched,
                inputSamples = inputSamples,
                outputIsBatched = outputShape.size == 2,
                minScorePerMille = minScorePerMille,
                extractionRunId = EXTRACTION_RUN_ID,
            )
        }
    }
}
