package org.takopi.percept.perception.video

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Opt-in on-device detector: YOLO11n via the raw TFLite Interpreter.
 *
 * YOLO won a server-side A/B over the default EfficientDet-Lite0 decisively
 * (animal precision 0.33→1.0, zero false positives), but MediaPipe's
 * ObjectDetector cannot run a raw YOLO model — its output is a bare
 * [1,84,8400] tensor with no post-process op — so this adapter runs the
 * Interpreter directly and decodes with [YoloDecode] (which is unit-tested on
 * the host). On-device inference is NOT validated on this hardware, so the app
 * keeps EfficientDet as the default and exposes this behind a setting; enable
 * it, record a session, and compare against the server that runs the same
 * model as ground truth.
 */
class YoloTfliteFrameDetector private constructor(
    private val interpreter: Interpreter,
    private val numAnchors: Int,
    override val extractionRunId: String,
) : FrameDetector {

    private val outputBuffer =
        Array(1) { Array(YoloDecode.STRIDE_PUBLIC) { FloatArray(numAnchors) } }

    override fun detect(nv21: ByteArray, width: Int, height: Int): List<VideoDetection> {
        val argb = YuvToRgb.nv21ToArgb(nv21, width, height, subsample = 1)
        val input = YoloDecode.letterboxToInput(argb, width, height)
        val inBuf = ByteBuffer.allocateDirect(4 * input.size).order(ByteOrder.nativeOrder())
        for (v in input) inBuf.putFloat(v)
        inBuf.rewind()

        interpreter.run(inBuf, outputBuffer)

        // Flatten [1,84,anchors] → row-major FloatArray the decoder expects.
        val flat = FloatArray(YoloDecode.STRIDE_PUBLIC * numAnchors)
        for (v in 0 until YoloDecode.STRIDE_PUBLIC) {
            System.arraycopy(outputBuffer[0][v], 0, flat, v * numAnchors, numAnchors)
        }
        return YoloDecode.decode(
            output = flat,
            numAnchors = numAnchors,
            letterbox = YoloDecode.Letterbox.forFrame(width, height),
            srcW = width,
            srcH = height,
        )
    }

    override fun close() = interpreter.close()

    companion object {
        const val MODEL_ASSET_PATH: String = "models/yolo11n_float32.tflite"

        fun create(context: Context, useGpu: Boolean = false): YoloTfliteFrameDetector {
            val model = loadModel(context, MODEL_ASSET_PATH)
            val options = Interpreter.Options().apply {
                numThreads = 4
                if (useGpu) setUseNNAPI(true)
            }
            val interpreter = Interpreter(model, options)
            // Output tensor is [1, 84, anchors]; read the anchor count from the
            // model so a re-export at a different input size still works.
            val shape = interpreter.getOutputTensor(0).shape()
            val anchors = shape.last()
            return YoloTfliteFrameDetector(
                interpreter = interpreter,
                numAnchors = anchors,
                extractionRunId = "yolo11n-float32-640@tflite-${if (useGpu) "nnapi" else "cpu"}",
            )
        }

        private fun loadModel(context: Context, assetPath: String): MappedByteBuffer {
            val afd = context.assets.openFd(assetPath)
            java.io.FileInputStream(afd.fileDescriptor).channel.use { channel ->
                return channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
        }
    }
}
