package org.takopi.percept.perception.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YoloDecodeTest {

    /** Build a flat [1,84,anchors] output with one planted detection. */
    private fun outputWith(
        anchors: Int,
        anchor: Int,
        classId: Int,
        score: Float,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
    ): FloatArray {
        val out = FloatArray(YoloDecode.STRIDE_PUBLIC * anchors)
        out[anchor] = cx
        out[anchors + anchor] = cy
        out[2 * anchors + anchor] = w
        out[3 * anchors + anchor] = h
        out[(4 + classId) * anchors + anchor] = score
        return out
    }

    @Test
    fun decodesPlantedDogAndUndoesLetterbox() {
        // 1280×720 frame → letterbox onto 640: scale 0.5, padY = (640-360)/2 = 140.
        val lb = YoloDecode.Letterbox.forFrame(1280, 720)
        assertEquals(0.5f, lb.scale, 1e-4f)
        assertEquals(0, lb.padX)
        assertEquals(140, lb.padY)

        // A dog (class 16) centered in the letterboxed input at (320, 320), 100×100.
        val anchors = 8400
        val out = outputWith(anchors, anchor = 42, classId = 16, score = 0.9f,
            cx = 320f, cy = 320f, w = 100f, h = 100f)

        val dets = YoloDecode.decode(out, anchors, lb, srcW = 1280, srcH = 720)
        assertEquals(1, dets.size)
        val d = dets[0]
        assertEquals("dog", d.label)
        assertEquals(900, d.scorePerMille)
        // input (270..370, 270..370) → minus padY 140 on y → (270..370, 130..230) /0.5
        assertEquals(540, d.box.x1)
        assertEquals(260, d.box.y1)
        assertEquals(740, d.box.x2)
        assertEquals(460, d.box.y2)
    }

    @Test
    fun dropsBelowThreshold() {
        val anchors = 8400
        val out = outputWith(anchors, 10, classId = 16, score = 0.20f,
            cx = 320f, cy = 320f, w = 80f, h = 80f)
        val dets = YoloDecode.decode(out, anchors, YoloDecode.Letterbox.forFrame(640, 640),
            640, 640, scoreThreshold = 0.35f)
        assertTrue(dets.isEmpty())
    }

    @Test
    fun nmsSuppressesOverlappingSameClass() {
        val a = VideoDetection("car", "coco-80", 900, PixelBox(100, 100, 300, 300))
        val b = VideoDetection("car", "coco-80", 700, PixelBox(110, 110, 310, 310)) // ~IoU 0.85
        val c = VideoDetection("car", "coco-80", 800, PixelBox(500, 500, 700, 700)) // disjoint
        val kept = YoloDecode.nms(listOf(a, b, c), iouThreshold = 0.5f)
        assertEquals(2, kept.size)
        assertEquals(900, kept[0].scorePerMille) // a wins over b
        assertTrue(kept.any { it.box.x1 == 500 })  // c survives
    }

    @Test
    fun differentClassesNotSuppressed() {
        val car = VideoDetection("car", "coco-80", 900, PixelBox(100, 100, 300, 300))
        val dog = VideoDetection("dog", "coco-80", 850, PixelBox(105, 105, 305, 305)) // overlaps but different class
        val kept = YoloDecode.nms(listOf(car, dog), iouThreshold = 0.5f)
        assertEquals(2, kept.size)
    }
}
