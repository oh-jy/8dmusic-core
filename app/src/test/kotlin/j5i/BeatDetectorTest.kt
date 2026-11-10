package j5i.spatial8d.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BeatDetectorTest {
    @Test fun starBrightnessRespondsToBeatsAndKeepsContrast() {
        val quiet = starAlpha(0.7, 0.5, 0.1, 0.0)
        val beat = starAlpha(0.7, 0.5, 0.1, 1.0)
        assertTrue(beat - quiet >= 100)
        assertTrue(starAlpha(1.0, 1.0, 1.0, 1.0) == 255)
        assertTrue(starAlpha(0.1, 0.0, 0.0, 0.0) < 10)
    }

    @Test fun detectsTransientsAfterWarmupAndHonorsCooldown() {
        val detector = BeatDetector()
        val ms = 1_000_000L
        assertFalse(detector.accept(0.02, 1 * ms))
        assertFalse(detector.accept(0.15, 100 * ms))
        for (time in 200L..500L step 50) assertFalse(detector.accept(0.02, time * ms))
        assertTrue(detector.accept(0.15, 550 * ms))
        assertFalse(detector.accept(0.02, 600 * ms))
        assertFalse(detector.accept(0.15, 650 * ms))
        for (time in 700L..900L step 50) assertFalse(detector.accept(0.02, time * ms))
        assertTrue(detector.accept(0.15, 950 * ms))
    }

    @Test fun silenceSteadyAudioAndGapsDoNotTrigger() {
        val detector = BeatDetector()
        val ms = 1_000_000L
        for (time in 1L..1001L step 50) assertFalse(detector.accept(0.0, time * ms))
        detector.reset()
        for (time in 1L..1001L step 50) assertFalse(detector.accept(0.1, time * ms))
        assertFalse(detector.accept(0.3, 2000 * ms))
    }
}
