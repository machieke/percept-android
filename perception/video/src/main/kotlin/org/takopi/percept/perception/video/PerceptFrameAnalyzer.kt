package org.takopi.percept.perception.video

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.takopi.percept.core.trace.SessionTimeBase
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * CameraX analysis entry point: repacks YUV_420_888, resolves the timestamp
 * clock base, applies the thermal frame governor, runs the detector, and
 * feeds the engine. Use with STRATEGY_KEEP_ONLY_LATEST (§3.3).
 */
class PerceptFrameAnalyzer(
    private val engine: VideoPerceptionEngine,
    private val detector: FrameDetector,
    private val timeBase: SessionTimeBase,
    private val governor: FrameRateGovernor = FrameRateGovernor(),
    private val thermalLevelProvider: () -> ThermalLevel = { ThermalLevel.NOMINAL },
    private val jpegQuality: Int = KEYFRAME_JPEG_QUALITY,
    private val onFrameAnalyzed: ((List<VideoDetection>) -> Unit)? = null,
) : ImageAnalysis.Analyzer {
    private val fallbackTimestamps = AtomicLong(0)

    /** Frames whose HAL timestamp was not in the elapsedRealtime domain (risk 3). */
    val clockBaseFallbacks: Long
        get() = fallbackTimestamps.get()

    override fun analyze(image: ImageProxy) {
        // CameraX propagates analyzer exceptions into a process crash; a bad
        // frame or detector hiccup must only cost us that frame.
        try {
            analyzeOrThrow(image)
        } catch (_: Exception) {
            engine.onFrameDropped()
        }
    }

    private fun analyzeOrThrow(image: ImageProxy) {
        image.use { proxy ->
            val resolution = FrameTimestamps.resolveFrameElapsedNanos(
                imageTimestampNanos = proxy.imageInfo.timestamp,
                receiptElapsedNanos = SystemClock.elapsedRealtimeNanos(),
            )
            if (resolution.usedFallback) {
                fallbackTimestamps.incrementAndGet()
            }
            if (resolution.elapsedNanos < timeBase.monotonicAnchorNanos) {
                engine.onFrameDropped()
                return
            }
            val tNanos = timeBase.elapsedNanos(resolution.elapsedNanos)
            if (!governor.shouldProcess(tNanos, thermalLevelProvider())) {
                engine.onFrameDropped()
                return
            }

            val width = proxy.width
            val height = proxy.height
            val yPlane = proxy.planes[0]
            val nv21 = Nv21.fromPlanes(
                width = width,
                height = height,
                yPlane = yPlane.buffer.toByteArray(),
                yRowStride = yPlane.rowStride,
                yPixelStride = yPlane.pixelStride,
                uPlane = proxy.planes[1].buffer.toByteArray(),
                vPlane = proxy.planes[2].buffer.toByteArray(),
                chromaRowStride = proxy.planes[1].rowStride,
                chromaPixelStride = proxy.planes[1].pixelStride,
            )
            val histogram = LuminanceHistograms.fromLuminancePlane(
                data = nv21,
                width = width,
                height = height,
                rowStride = width,
            )
            val detections = detector.detect(nv21, width, height)
            onFrameAnalyzed?.invoke(detections)
            engine.onFrame(
                FrameObservation(
                    tNanos = tNanos,
                    detections = detections,
                    histogram = histogram,
                    keyframeJpegProvider = { encodeJpeg(nv21, width, height) },
                ),
            )
        }
    }

    private fun encodeJpeg(nv21: ByteArray, width: Int, height: Int): ByteArray {
        val output = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, width, height, null)
            .compressToJpeg(Rect(0, 0, width, height), jpegQuality, output)
        return output.toByteArray()
    }

    companion object {
        const val KEYFRAME_JPEG_QUALITY: Int = 70
    }
}

private fun java.nio.ByteBuffer.toByteArray(): ByteArray {
    val duplicate = duplicate()
    duplicate.rewind()
    val bytes = ByteArray(duplicate.remaining())
    duplicate.get(bytes)
    return bytes
}
