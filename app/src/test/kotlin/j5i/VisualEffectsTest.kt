package j5i.spatial8d.ui

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class VisualEffectsTest {
    @Test fun rainAndFollowingEffectsComeImmediatelyAfterTravel() {
        assertEquals(listOf(3, 34, 13, 14, 15, 16, 17, 5),
            exclusiveVisualModes.map { it.second }.drop(3).take(8))
    }
    @Test fun dreamScenesChangeSmoothlyOverTime() {
        assertEquals(0f, dreamBlend(0.0))
        assertEquals(0f, dreamBlend(DREAM_SCENE_SECONDS))
        assertTrue(dreamBlend(DREAM_SCENE_SECONDS - .001) > .999f)
        assertTrue(DREAM_SCENE_SECONDS < 10.0)
        assertTrue((0..30).map(::dreamScene).toSet().size >= 3)
        assertTrue((0..100).all { dreamScene(it) != dreamScene(it + 1) })
        assertEquals(dreamScene(1000), dreamScene(1000))
    }
    @Test fun ripplesFollowMobiusAndPhotoEffectsStayTogether() {
        val modes = exclusiveVisualModes.map { it.second }
        assertEquals(22, modes[modes.indexOf(12) + 1])
        assertEquals(listOf(20, 21, 23, 25), modes.drop(modes.indexOf(20)).take(4))
        assertTrue(visualModeGroups.any { (_, ids) -> ids.containsAll(listOf(12, 22)) })
        assertTrue(visualModeGroups.any { (_, ids) -> ids.containsAll(listOf(20, 21, 23, 25)) })
    }
    @Test fun seasonTerrainIsContinuousAcrossWrapAndFrames() {
        for (depth in listOf(0f, .5f, 1f)) {
            assertEquals(seasonHeight(0f, depth, 12.0, .5f), seasonHeight(1f, depth, 12.0, .5f), .00001f)
            for (step in 0..120) {
                val time = step / 30.0
                assertTrue(abs(seasonHeight(.4f, depth, time + 1.0 / 30.0, .5f) -
                    seasonHeight(.4f, depth, time, .5f)) < .01f)
            }
        }
    }
    @Test fun horizontalSwipeUsesDistanceInsteadOfFlingSpeed() {
        assertTrue(isHorizontalVisualSwipe(-60f, 15f, 48f))
        assertTrue(isHorizontalVisualSwipe(60f, -15f, 48f))
        assertTrue(!isHorizontalVisualSwipe(20f, 2f, 48f))
        assertTrue(!isHorizontalVisualSwipe(60f, 70f, 48f))
    }
    @Test fun swipeCyclesAllModesIncludingAnalysisInBothDirections() {
        var state = false to 0
        val visited = mutableSetOf(state)
        repeat(exclusiveVisualModes.size) {
            val next = nextVisualizerState(state.first, state.second, true)
            assertEquals(state, nextVisualizerState(next.first, next.second, false))
            visited += next
            state = next
        }
        assertEquals(exclusiveVisualModes.size + 1, visited.size)
        assertEquals(false to 0, nextVisualizerState(state.first, state.second, true))
        assertEquals(state, nextVisualizerState(false, 0, false))
    }
    @Test fun videoFramesCoverTheWholeAudioWithoutDrift() {
        val duration = 180_123_456L
        val count = videoFrameCount(duration)
        assertTrue(videoFrameTimeUs(count - 1) < duration)
        assertTrue(videoFrameTimeUs(count) >= duration)
        assertEquals(60_000_000L, videoFrameTimeUs(1800))
    }
    @Test fun videoClipUsesOneRangeForAudioAndVideo() {
        assertEquals(48_000L to 96_000L, videoClipFrames(480_000, 48_000, 1.0, 2.0))
        assertEquals(432_000L to 48_000L, videoClipFrames(480_000, 48_000, 9.0, 5.0))
        assertEquals(96_000L to 384_000L, videoClipFrames(480_000, 48_000, 2.0, 0.0))
        assertFailsWith<IllegalArgumentException> { videoClipFrames(480_000, 48_000, 10.0, 1.0) }
    }
    @Test fun creativeRecipesRoundTripAndRejectInvalidValues() {
        val original = VisualCreative(hue = 125, overlay = 19, watermark = true, mode = 3,
            intensity = 145, backgroundMask = true, watermarkText = "听见星河")
        assertEquals(original, VisualCreative.decode(original.encode()))
        assertEquals(100, VisualCreative.decode("{\"version\":1,\"hue\":0,\"overlay\":0}").intensity)
        assertEquals("", VisualCreative.decode("{\"version\":1,\"hue\":0,\"overlay\":0}").outroText)
        assertEquals(0, VisualCreative.decode("{\"version\":1,\"hue\":0,\"overlay\":0}").backgroundBlur)
        assertFailsWith<IllegalArgumentException> { VisualCreative.decode("{\"version\":1,\"hue\":999,\"overlay\":0}") }
        assertFailsWith<IllegalArgumentException> { VisualCreative.decode("{\"version\":1,\"hue\":0,\"overlay\":0,\"mode\":999}") }
    }

    @Test fun historyContainsOnlyRealSamplesAndKeepsNewestRows() {
        val history = SpectrumTerrainHistory(rows = 3, columns = 4)
        assertEquals(0, history.size)
        val spectrum = doubleArrayOf(.1, .2, .3, .4)
        history.accept(spectrum, 1_000_000_000L)
        spectrum.fill(.5)
        history.accept(spectrum, 1_050_000_000L)
        assertEquals(1, history.size)
        assertEquals(.1f, history[0, 0])
        assertEquals(.4f, history[0, 3])
        for (sample in 1..4) {
            spectrum.fill(sample / 10.0)
            history.accept(spectrum, 1_000_000_000L + sample * 100_000_000L)
        }
        assertEquals(3, history.size)
        assertEquals(.4f, history[0, 0])
        assertEquals(.2f, history[2, 0])
        history.clear()
        assertEquals(0, history.size)
        history.accept(spectrum, 10L)
        assertEquals(1, history.size)
    }

    @Test fun stringsKeepEndpointsFixedAndVibrateBetweenFrames() {
        val seconds = 8_000_000.0
        for (harmonic in 1..4) {
            assertEquals(0f, stringDisplacement(0f, seconds, 1f, harmonic))
            assertEquals(0f, stringDisplacement(1f, seconds, 1f, harmonic))
            assertEquals(0f, stringDisplacement(.4f, seconds, 0f, harmonic), .000001f)
            val first = stringDisplacement(.4f, seconds, 1f, harmonic)
            val next = stringDisplacement(.4f, seconds + 1.0 / 30.0, 1f, harmonic)
            assertTrue(abs(first - next) > .001f)
            assertTrue(abs(first) <= 1f)
        }
    }

    @Test fun drivingCyclesThroughEveryEffectInBothDirections() {
        val modes = exclusiveVisualModes.map { it.second }
        assertEquals((1..34).filter { it != 9 && it != 24 }.toSet(), modes.toSet())
        assertEquals(modes.toSet(), visualModeGroups.flatMap { it.second }.toSet())
        assertEquals(modes.size, visualModeGroups.sumOf { it.second.size })
        var current = 3
        val visited = mutableSetOf<Int>()
        repeat(modes.size) {
            visited += current
            val next = nextVisualMode(current, true)
            assertEquals(current, nextVisualMode(next, false))
            current = next
        }
        assertEquals(modes.toSet(), visited)
        assertEquals(3, current)
    }
}
