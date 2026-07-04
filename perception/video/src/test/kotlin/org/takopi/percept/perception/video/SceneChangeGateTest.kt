package org.takopi.percept.perception.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SceneChangeGateTest {
    @Test
    fun firesOnFirstFrameDetectionSetChangeAndLargeLuminanceDistance() {
        val gate = SceneChangeGate(luminanceThresholdPerMille = 250)

        assertEquals(SceneChangeReason.FIRST_FRAME, gate.process(sceneFrame(0, intArrayOf(10, 0), emptyList()))?.reason)
        assertNull(gate.process(sceneFrame(1, intArrayOf(9, 1), emptyList())))

        val detectionChange = gate.process(sceneFrame(2, intArrayOf(9, 1), listOf(detection())))
        assertEquals(SceneChangeReason.DETECTION_SET_CHANGE, detectionChange?.reason)

        assertNull(gate.process(sceneFrame(3, intArrayOf(8, 2), listOf(detection()))))
        val luminanceChange = gate.process(sceneFrame(4, intArrayOf(0, 10), listOf(detection())))
        assertEquals(SceneChangeReason.LUMINANCE_DISTANCE, luminanceChange?.reason)
        assertEquals(800, luminanceChange?.metricPerMille)
    }

    @Test
    fun l1DistanceIsIntegerPerMille() {
        assertEquals(
            1000,
            l1DistancePerMille(
                LuminanceHistogram(intArrayOf(10, 0)),
                LuminanceHistogram(intArrayOf(0, 10)),
            ),
        )
    }

    private fun sceneFrame(
        second: Long,
        histogram: IntArray,
        detections: List<VideoDetection>,
    ): SceneGateFrame =
        SceneGateFrame(
            tNanos = second * 1_000_000_000L,
            histogram = LuminanceHistogram(histogram),
            detections = detections,
        )

    private fun detection(): VideoDetection =
        VideoDetection(
            label = "person",
            labelSpace = "coco-80",
            scorePerMille = 800,
            box = PixelBox(100, 80, 220, 420),
        )
}
