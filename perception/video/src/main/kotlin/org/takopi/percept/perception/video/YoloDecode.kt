package org.takopi.percept.perception.video

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure YOLO11 output decoder — no Android or TFLite types, so it is unit-tested
 * on the host. The exported model emits one tensor shaped [1, 84, 8400]:
 * 8400 anchors, each with 4 box values (cx, cy, w, h, in letterboxed input
 * pixels) followed by 80 COCO class scores (already sigmoid-activated by the
 * export). Decoding = threshold on max class score, convert to corner boxes in
 * ORIGINAL image pixels (undo the letterbox), then per-class NMS.
 */
object YoloDecode {
    const val LABEL_SPACE: String = "coco-80"
    const val INPUT_SIZE: Int = 640
    const val NUM_CLASSES: Int = 80
    private const val STRIDE: Int = 4 + NUM_CLASSES // 84 values per anchor

    /** Public alias of [STRIDE] for the Interpreter output buffer shape. */
    const val STRIDE_PUBLIC: Int = 4 + NUM_CLASSES

    val COCO_LABELS: List<String> = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
        "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
        "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
        "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
        "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
        "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
        "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator",
        "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush",
    )

    /**
     * Letterbox geometry mapping the original [srcW]×[srcH] frame onto the
     * square [INPUT_SIZE] model input: uniform [scale] then centered pad
     * ([padX], [padY]). Mirror of the server-side letterbox so on-device and
     * server detections share a coordinate convention.
     */
    data class Letterbox(val scale: Float, val padX: Int, val padY: Int) {
        companion object {
            fun forFrame(srcW: Int, srcH: Int, size: Int = INPUT_SIZE): Letterbox {
                val scale = min(size.toFloat() / srcW, size.toFloat() / srcH)
                val newW = (srcW * scale).roundToInt()
                val newH = (srcH * scale).roundToInt()
                return Letterbox(scale, (size - newW) / 2, (size - newH) / 2)
            }
        }
    }

    /**
     * Decode a flat [1,84,8400] output (row-major: value v at anchor a is
     * `output[v * numAnchors + a]`) into detections in original-frame pixels.
     */
    fun decode(
        output: FloatArray,
        numAnchors: Int,
        letterbox: Letterbox,
        srcW: Int,
        srcH: Int,
        scoreThreshold: Float = 0.35f,
        iouThreshold: Float = 0.5f,
        maxResults: Int = 16,
    ): List<VideoDetection> {
        require(output.size >= STRIDE * numAnchors) { "output too small for $numAnchors anchors" }
        val candidates = ArrayList<VideoDetection>()
        for (a in 0 until numAnchors) {
            var bestClass = 0
            var bestScore = 0f
            for (c in 0 until NUM_CLASSES) {
                val s = output[(4 + c) * numAnchors + a]
                if (s > bestScore) {
                    bestScore = s
                    bestClass = c
                }
            }
            if (bestScore < scoreThreshold) continue
            val cx = output[a]
            val cy = output[numAnchors + a]
            val bw = output[2 * numAnchors + a]
            val bh = output[3 * numAnchors + a]
            // letterboxed input px → original px
            val x1 = ((cx - bw / 2f) - letterbox.padX) / letterbox.scale
            val y1 = ((cy - bh / 2f) - letterbox.padY) / letterbox.scale
            val x2 = ((cx + bw / 2f) - letterbox.padX) / letterbox.scale
            val y2 = ((cy + bh / 2f) - letterbox.padY) / letterbox.scale
            val ix1 = x1.roundToInt().coerceIn(0, srcW)
            val iy1 = y1.roundToInt().coerceIn(0, srcH)
            val ix2 = x2.roundToInt().coerceIn(0, srcW)
            val iy2 = y2.roundToInt().coerceIn(0, srcH)
            if (ix2 <= ix1 || iy2 <= iy1) continue
            candidates.add(
                VideoDetection(
                    label = COCO_LABELS[bestClass],
                    labelSpace = LABEL_SPACE,
                    scorePerMille = (bestScore * 1000f).roundToInt().coerceIn(0, 1000),
                    box = PixelBox(ix1, iy1, ix2, iy2),
                ),
            )
        }
        return nms(candidates, iouThreshold).take(maxResults)
    }

    /** Per-class greedy NMS, highest score first. */
    fun nms(detections: List<VideoDetection>, iouThreshold: Float): List<VideoDetection> {
        val kept = ArrayList<VideoDetection>()
        val byScore = detections.sortedByDescending { it.scorePerMille }
        val removed = BooleanArray(byScore.size)
        for (i in byScore.indices) {
            if (removed[i]) continue
            val a = byScore[i]
            kept.add(a)
            for (j in i + 1 until byScore.size) {
                if (removed[j]) continue
                val b = byScore[j]
                if (a.label == b.label && a.box.iouPerMille(b.box) >= (iouThreshold * 1000).toInt()) {
                    removed[j] = true
                }
            }
        }
        return kept
    }

    /** ARGB pixels → NCHW float32 [1,3,H,W], RGB, 0..1, letterboxed with pad 114. */
    fun letterboxToInput(argb: IntArray, srcW: Int, srcH: Int, size: Int = INPUT_SIZE): FloatArray {
        val lb = Letterbox.forFrame(srcW, srcH, size)
        val plane = size * size
        val input = FloatArray(3 * plane) { if (it < 3 * plane) 114f / 255f else 0f }
        val newW = (srcW * lb.scale).roundToInt()
        val newH = (srcH * lb.scale).roundToInt()
        for (dy in 0 until newH) {
            val sy = min(srcH - 1, (dy / lb.scale).toInt())
            val outY = dy + lb.padY
            for (dx in 0 until newW) {
                val sx = min(srcW - 1, (dx / lb.scale).toInt())
                val px = argb[sy * srcW + sx]
                val outX = dx + lb.padX
                val idx = outY * size + outX
                input[idx] = ((px shr 16) and 0xFF) / 255f          // R
                input[plane + idx] = ((px shr 8) and 0xFF) / 255f   // G
                input[2 * plane + idx] = (px and 0xFF) / 255f       // B
            }
        }
        return input
    }
}
