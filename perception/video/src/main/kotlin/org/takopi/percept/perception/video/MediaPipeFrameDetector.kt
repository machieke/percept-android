package org.takopi.percept.perception.video

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import kotlin.math.roundToInt

/**
 * §3.3 v1 default detector: EfficientDet-Lite0 int8 via MediaPipe Tasks.
 * GPU delegate is requested first; on Adreno driver init failure the caller
 * should retry with [useGpu] = false (risk 2 fallback).
 */
class MediaPipeFrameDetector private constructor(
    private val detector: ObjectDetector,
    override val extractionRunId: String,
) : FrameDetector {

    override fun detect(nv21: ByteArray, width: Int, height: Int): List<VideoDetection> {
        // Direct integer YUV→RGB at half resolution: the model letterboxes to
        // 320×320 anyway, and the former JPEG round trip dominated the frame
        // budget. Boxes are reported in this (width/2 × height/2) space.
        val argb = YuvToRgb.nv21ToArgb(nv21, width, height, DETECT_SUBSAMPLE)
        val bitmap = Bitmap.createBitmap(
            argb,
            width / DETECT_SUBSAMPLE,
            height / DETECT_SUBSAMPLE,
            Bitmap.Config.ARGB_8888,
        )
        return detectBitmap(bitmap)
    }

    private fun detectBitmap(bitmap: Bitmap): List<VideoDetection> {
        val result = detector.detect(BitmapImageBuilder(bitmap).build())
        return result.detections().mapNotNull { detection ->
            val category = detection.categories().firstOrNull() ?: return@mapNotNull null
            val box = detection.boundingBox()
            VideoDetection(
                label = category.categoryName(),
                labelSpace = LABEL_SPACE,
                scorePerMille = (category.score() * 1000f).roundToInt().coerceIn(0, 1000),
                box = PixelBox(
                    x1 = box.left.roundToInt().coerceAtLeast(0),
                    y1 = box.top.roundToInt().coerceAtLeast(0),
                    x2 = box.right.roundToInt().coerceAtLeast(box.left.roundToInt().coerceAtLeast(0)),
                    y2 = box.bottom.roundToInt().coerceAtLeast(box.top.roundToInt().coerceAtLeast(0)),
                ),
            )
        }
    }

    /**
     * Runs one dummy inference. Delegate problems can pass construction and
     * only fail at Process() time (e.g. the GPU delegate cannot execute this
     * int8 model: "ToTensorConverter: input data size does not match"), so
     * fallback decisions must be based on an actual run.
     */
    fun probe() {
        detectBitmap(Bitmap.createBitmap(PROBE_SIZE, PROBE_SIZE, Bitmap.Config.ARGB_8888))
    }

    override fun close() {
        detector.close()
    }

    companion object {
        const val LABEL_SPACE: String = "coco-80"
        const val MODEL_ASSET_PATH: String = "models/efficientdet_lite0_int8.tflite"
        const val DETECT_SUBSAMPLE: Int = 2
        private const val PROBE_SIZE: Int = 64

        fun create(
            context: Context,
            useGpu: Boolean,
            scoreThreshold: Float = 0.3f,
            maxResults: Int = 8,
        ): MediaPipeFrameDetector {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET_PATH)
                .setDelegate(if (useGpu) Delegate.GPU else Delegate.CPU)
                .build()
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setScoreThreshold(scoreThreshold)
                .setMaxResults(maxResults)
                .build()
            val delegateTag = if (useGpu) "gpu" else "cpu"
            return MediaPipeFrameDetector(
                detector = ObjectDetector.createFromOptions(context, options),
                extractionRunId = "efficientdet-lite0-int8-320@mediapipe-0.10.14-$delegateTag",
            )
        }

        /**
         * Risk 2: GPU delegate first, CPU/XNNPACK fallback — verified by a
         * probe inference, since delegate incompatibilities with the int8
         * model surface at run time rather than at construction.
         */
        fun createWithFallback(context: Context): MediaPipeFrameDetector {
            val gpu = try {
                create(context, useGpu = true)
            } catch (_: RuntimeException) {
                null
            }
            if (gpu != null) {
                try {
                    gpu.probe()
                    return gpu
                } catch (_: RuntimeException) {
                    try {
                        gpu.close()
                    } catch (_: RuntimeException) {
                        // A poisoned graph may refuse to close; fall through.
                    }
                }
            }
            return create(context, useGpu = false).also(MediaPipeFrameDetector::probe)
        }
    }
}
