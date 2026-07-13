package org.takopi.percept.perception.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SceneChangeGateTest {
    @Test
    fun firesOnFirstFrameDetectionSetChangeAndLargeLuminanceDistance() {
        val gate = SceneChangeGate(
            luminanceThresholdPerMille = 250,
            signatureHoldFrames = 1,
            minIntervalNanos = 0,
            subjectLabels = emptySet(),
        )

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
    fun movingSameDetectionDoesNotTriggerDetectionSetChange() {
        val gate = SceneChangeGate(luminanceThresholdPerMille = 900, subjectLabels = emptySet())

        gate.process(sceneFrame(0, intArrayOf(10, 0), listOf(detection(x1 = 100))))

        assertNull(gate.process(sceneFrame(1, intArrayOf(10, 0), listOf(detection(x1 = 160)))))
    }

    @Test
    fun flickeringDetectionNeverFiresSetChange() {
        val gate = SceneChangeGate(luminanceThresholdPerMille = 900, signatureHoldFrames = 3, subjectLabels = emptySet())
        gate.process(sceneFrame(0, intArrayOf(10, 0), emptyList()))

        // A marginal detection blinking on/off never holds for 3 frames.
        for (second in 1..12) {
            val detections = if (second % 2 == 0) listOf(detection()) else emptyList()
            assertNull(gate.process(sceneFrame(second.toLong(), intArrayOf(10, 0), detections)))
        }
    }

    @Test
    fun heldSignatureChangeFiresAfterHoldFrames() {
        val gate = SceneChangeGate(luminanceThresholdPerMille = 900, signatureHoldFrames = 3, subjectLabels = emptySet())
        gate.process(sceneFrame(0, intArrayOf(10, 0), emptyList()))

        assertNull(gate.process(sceneFrame(3, intArrayOf(10, 0), listOf(detection()))))
        assertNull(gate.process(sceneFrame(4, intArrayOf(10, 0), listOf(detection()))))
        val change = gate.process(sceneFrame(5, intArrayOf(10, 0), listOf(detection())))
        assertEquals(SceneChangeReason.DETECTION_SET_CHANGE, change?.reason)
        // Reverting and holding again fires the next change too.
        assertNull(gate.process(sceneFrame(8, intArrayOf(10, 0), emptyList())))
        assertNull(gate.process(sceneFrame(9, intArrayOf(10, 0), emptyList())))
        assertEquals(
            SceneChangeReason.DETECTION_SET_CHANGE,
            gate.process(sceneFrame(10, intArrayOf(10, 0), emptyList()))?.reason,
        )
    }

    @Test
    fun cooldownDefersFiresUntilIntervalPasses() {
        val gate = SceneChangeGate(
            luminanceThresholdPerMille = 250,
            signatureHoldFrames = 1,
            minIntervalNanos = 2_000_000_000L,
            subjectLabels = emptySet(),
        )
        gate.process(sceneFrame(0, intArrayOf(10, 0), emptyList()))

        // Luminance jump 1 s after the first-frame scene: inside cooldown.
        assertNull(gate.process(sceneFrame(1, intArrayOf(0, 10), emptyList())))
        // Detection change still inside cooldown keeps waiting…
        assertNull(gate.process(sceneFrame(1, intArrayOf(0, 10), listOf(detection()))))
        // …and fires once the interval has passed and the set still differs.
        assertEquals(
            SceneChangeReason.DETECTION_SET_CHANGE,
            gate.process(sceneFrame(2, intArrayOf(0, 10), listOf(detection())))?.reason,
        )
    }

    @Test
    fun salientSubjectFiresPeriodicallyWithUnchangedBackground() {
        // A dog fills the frame in a static room: same detection set, same
        // luminance — the set/luminance gate stays silent, but the subject
        // gate must keep producing keyframes that contain the dog once it has
        // persisted subjectHoldFrames frames.
        val gate = SceneChangeGate(
            luminanceThresholdPerMille = 900,
            minIntervalNanos = 500_000_000L,
            subjectIntervalNanos = 2_000_000_000L,
            subjectHoldFrames = 4,
        )
        val dog = VideoDetection("dog", "coco-80", 800, PixelBox(200, 150, 380, 350)) // 36000 px
        fun frame(second: Long) = gate.process(sceneFrame(second, intArrayOf(10, 0), listOf(dog)))

        frame(0) // FIRST_FRAME (persist=1)
        assertNull(frame(1)) // persist=2
        assertNull(frame(2)) // persist=3
        // persist=4 and interval elapsed: subject capture fires.
        assertEquals(SceneChangeReason.SUBJECT_PRESENT, frame(3)?.reason)
        // Within the subject interval: no re-fire.
        assertNull(frame(4))
        // Interval elapsed again: fires.
        assertEquals(SceneChangeReason.SUBJECT_PRESENT, frame(5)?.reason)
    }

    @Test
    fun flickeringSubjectNeverFires() {
        // A COCO false positive (tree scored 'elephant') blinks on and off:
        // it never persists subjectHoldFrames consecutive frames, so it never
        // triggers a capture.
        val gate = SceneChangeGate(
            luminanceThresholdPerMille = 900,
            minIntervalNanos = 0,
            subjectIntervalNanos = 0,
            subjectHoldFrames = 4,
        )
        val elephant = VideoDetection("elephant", "coco-80", 700, PixelBox(200, 100, 440, 400))
        gate.process(sceneFrame(0, intArrayOf(10, 0), emptyList()))
        for (second in 1..12) {
            val dets = if (second % 2 == 0) listOf(elephant) else emptyList()
            assertNull(gate.process(sceneFrame(second.toLong(), intArrayOf(10, 0), dets)))
        }
    }

    @Test
    fun smallOrLowConfidenceSubjectDoesNotFire() {
        val gate = SceneChangeGate(luminanceThresholdPerMille = 900, minIntervalNanos = 0)
        gate.process(sceneFrame(0, intArrayOf(10, 0), emptyList()))
        // A distant, tiny dog (below area threshold) is not a capture subject.
        val tinyDog = VideoDetection("dog", "coco-80", 800, PixelBox(200, 150, 260, 210)) // 3600 px
        assertNull(gate.process(sceneFrame(2, intArrayOf(10, 0), listOf(tinyDog))))
        // A large but low-confidence detection is not either.
        val faintDog = VideoDetection("dog", "coco-80", 300, PixelBox(200, 150, 400, 380)) // 46000 px, score 300
        assertNull(gate.process(sceneFrame(4, intArrayOf(10, 0), listOf(faintDog))))
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

    private fun detection(x1: Int = 100): VideoDetection =
        VideoDetection(
            label = "person",
            labelSpace = "coco-80",
            scorePerMille = 800,
            box = PixelBox(x1, 80, x1 + 120, 420),
        )
}
