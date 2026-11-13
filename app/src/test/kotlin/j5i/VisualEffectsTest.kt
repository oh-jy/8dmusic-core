package j5i.spatial8d.ui

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class VisualEffectsTest {
    @Test fun sandExtractsDarkDetailsAndRevealsInContinuousStrokes() {
        val pixels = intArrayOf(-1, -1, -1, -1, 0xff000000.toInt(), -1, -1, -1, -1)
        val density = sandDensity(pixels, 3, 3)
        assertTrue(density[4] > density[0])
        assertTrue(density[1] > density[0])
        assertEquals(0f, sandRowProgress(0f, 0))
        assertEquals(1f, sandRowProgress(1f, 7))
        assertEquals(.5f, sandRowProgress(.0625f, 0))
        assertEquals(0f, sandRowProgress(.0625f, 1))
    }
    @Test fun sandTimelineFollowsPlaybackAndHandlesBoundaries() {
        assertEquals(null, sandProgress(0, 0, 1))
        assertEquals(null, sandProgress(0, 120000, 0))
        assertEquals(0 to 0f, sandProgress(0, 120000, 2))
        assertEquals(0 to .5f, sandProgress(30000, 120000, 2))
        assertEquals(1 to 0f, sandProgress(60000, 120000, 2))
        assertEquals(1 to 1f, sandProgress(120000, 120000, 2))
        assertEquals(0 to .5f, sandProgress(60000, 120000, 1))
        assertEquals(39, exclusiveVisualModes.last().second)
    }
    @Test fun particleFieldTrackCountVariesWithinBounds() {
        val counts = (0L..1000L).map { fieldTrackCount(it, 1234L) }
        assertEquals((4..12).toSet(), counts.toSet())
        assertTrue(counts.zipWithNext().any { (first, second) -> first != second })
        assertEquals(counts, (0L..1000L).map { fieldTrackCount(it, 1234L) })
    }
    @Test fun ringShapesAreClosedDistinctAndRandomized() {
        assertTrue(RING_SHAPE_COUNT > 16)
        val outlines = (0 until RING_SHAPE_COUNT).map { shape ->
            val first = ringPoint(shape, 0.0)
            val last = ringPoint(shape, 2 * kotlin.math.PI)
            assertEquals(first.first, last.first, .000001)
            assertEquals(first.second, last.second, .000001)
            (0 until 96).map { step -> ringPoint(shape, step * 2 * kotlin.math.PI / 96) }
        }
        assertEquals(RING_SHAPE_COUNT, outlines.toSet().size)
        val sequence = (0L..999L).map { ringShape(it, 1234L) }
        assertEquals(RING_SHAPE_COUNT, sequence.toSet().size)
        assertTrue(sequence != (0L..999L).map { ringShape(it, 5678L) })
        assertEquals(sequence, (0L..999L).map { ringShape(it, 1234L) })
    }
    @Test fun ringScenesHoldThenMorphWithoutRepeating() {
        assertEquals(10.0, RING_SCENE_SECONDS)
        assertEquals(0.0, ringSceneBlend(0.0))
        assertEquals(0.0, ringSceneBlend(RING_SCENE_SECONDS * .8))
        assertTrue(ringSceneBlend(RING_SCENE_SECONDS - .001) > .999)
        assertEquals(0.0, ringSceneBlend(RING_SCENE_SECONDS))
        assertTrue((0L..1000L).all { ringScene(it, 1234L) != ringScene(it + 1, 1234L) })
        assertEquals(RING_SHAPE_COUNT, (0L until RING_SHAPE_COUNT.toLong()).map { ringScene(it, 1234L) }.toSet().size)
    }
    @Test fun graphStylesAreDistinctAndKeepOriginalFirst() {
        assertEquals(13, graphStyleKeys.size)
        assertEquals(graphStyleKeys.size, graphStyleKeys.toSet().size)
        assertEquals("graph.style.original", graphStyleKeys.first())
        assertTrue(isVerticalGraphSwipe(10f, -60f, 48f))
        assertTrue(!isVerticalGraphSwipe(60f, 10f, 48f))
        assertTrue(!isVerticalGraphSwipe(10f, 20f, 48f))
        assertEquals(0, nextGraphStyle(graphStyleKeys.lastIndex, true))
        assertEquals(graphStyleKeys.lastIndex, nextGraphStyle(0, false))
    }
    @Test fun flowerProjectionHasDepthAndPerspective() {
        val near = projectFlowerPoint(.4, .2, .5, .8, 100f)
        val far = projectFlowerPoint(.4, .2, -.5, .8, 100f)
        assertTrue(near.depth > far.depth)
        assertTrue(near.x > far.x)
        assertTrue(near.y != far.y)
    }
    @Test fun flowersMoveAndBloomIndependentlyWithoutJumps() {
        val flowers = (0..4).map { flowerMotion(it, 10.0) }
        assertTrue(flowers.map { it.opening }.toSet().size > 3)
        assertTrue(flowers.map { it.x to it.y }.toSet().size == 5)
        assertTrue((0..4).all { flowerMotion(it, 10.0) != flowerMotion(it, 40.0) })
        for (index in 0..4) for (step in 0..1000) {
            val before = flowerMotion(index, step * .1)
            val after = flowerMotion(index, step * .1 + .001)
            assertTrue(abs(before.x - after.x) < .002)
            assertTrue(abs(before.y - after.y) < .002)
            assertTrue(abs(before.opening - after.opening) < .002)
            assertTrue(abs(before.rotation - after.rotation) < .002)
        }
    }
    @Test fun flowersTravelAndTurnIndependently() {
        for (index in 0..4) {
            val positions = (0..600).map { flowerPositions(it.toDouble(), 1000.0, 1000.0)[index] }
            val rotations = (0..120).map { flowerMotion(index, it.toDouble()).rotation }
            assertTrue(positions.map { it.first }.toSet().size > 100)
            assertTrue(positions.map { it.second }.toSet().size > 100)
            assertTrue(positions.maxOf { it.first } - positions.minOf { it.first } > 400)
            assertTrue(positions.maxOf { it.second } - positions.minOf { it.second } > 400)
            assertTrue(rotations.toSet().size > 100)
            assertTrue(rotations.zipWithNext().any { (first, second) -> second > first })
            assertTrue(rotations.zipWithNext().any { (first, second) -> second < first })
        }
    }
    @Test fun flowersAvoidCrowdingAndMoveContinuously() {
        for ((width, height) in listOf(1000.0 to 1000.0, 400.0 to 900.0, 900.0 to 400.0)) {
            for (step in 0..300) {
                val positions = flowerPositions(step.toDouble(), width, height)
                val next = flowerPositions(step + .001, width, height)
                for (index in positions.indices) {
                    assertTrue(kotlin.math.hypot(positions[index].first - next[index].first,
                        positions[index].second - next[index].second) < minOf(width, height) * .01)
                }
                for (first in 0..4) for (second in first + 1..4) {
                    val dx = positions[first].first - positions[second].first
                    val dy = positions[first].second - positions[second].second
                    assertTrue(dx * dx + dy * dy > .12 * .12 * minOf(width, height) * minOf(width, height))
                }
            }
        }
    }
    @Test fun stardustShapesChangeWithoutImmediateRepeats() {
        val count = stardustKinds.size
        assertTrue(count >= 30)
        assertTrue((0..10).all { block -> (0 until count).map { stardustShape(block * count + it) }.toSet().size == count })
        assertTrue((0..1000).all { stardustShape(it) != stardustShape(it + 1) })
        assertTrue(stardustKinds.all { it.simplified.isNotBlank() && it.traditional.isNotBlank() && it.english.isNotBlank() })
    }
    @Test fun stardustTimingUsesComplexityAndSupportsSeeking() {
        val simple = StardustTimeline(DoubleArray(stardustKinds.size))
        val complex = StardustTimeline(DoubleArray(stardustKinds.size) { 1.0 })
        val durations = (0..100).map(simple::duration)
        assertTrue(durations.all { it in 6.0..12.0 })
        assertTrue((0..100).all { complex.duration(it) in 6.0..12.0 && complex.duration(it) > simple.duration(it) })
        assertTrue(durations.toSet().size > 90)
        val firstEnd = simple.duration(0)
        assertEquals(0, simple.position(firstEnd - .001).first)
        assertEquals(1, simple.position(firstEnd).first)
        val later = simple.position(500.0)
        simple.position(0.0)
        assertEquals(later, simple.position(500.0))
        assertTrue(simple.position(firstEnd - .001).second > .99)
        assertEquals(0.0, simple.position(firstEnd).second)
    }
    @Test fun rainAndFollowingEffectsComeImmediatelyAfterTravel() {
        assertEquals(listOf(3, 34, 38, 35, 36, 37, 13, 14, 15, 16, 17, 5),
            exclusiveVisualModes.map { it.second }.drop(3).take(12))
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
    @Test fun previewTailStaysWithinSelectedClip() {
        assertEquals(336_000L to 96_000L, videoPreviewTail(96_000, 336_000, 48_000, 2))
        assertEquals(96_000L to 48_000L, videoPreviewTail(96_000, 48_000, 48_000, 5))
        assertFailsWith<IllegalArgumentException> { videoPreviewTail(0, 48_000, 48_000, 0) }
    }
    @Test fun outroFormationUsesConfiguredDuration() {
        assertEquals(0f, outroFormationProgress(0f, 5f))
        assertTrue(outroFormationProgress(1f, 5f) < .25f)
        assertTrue(outroFormationProgress(2.5f, 5f) < 1f)
        assertEquals(1f, outroFormationProgress(5f, 5f))
    }
    @Test fun creativeRecipesRoundTripAndRejectInvalidValues() {
        val original = VisualCreative(hue = 125, overlay = 19, watermark = true, mode = 3,
            intensity = 145, backgroundMask = true, watermarkText = "听见星河")
        assertEquals(original, VisualCreative.decode(original.encode()))
        assertEquals(100, VisualCreative.decode("{\"version\":1,\"hue\":0,\"overlay\":0}").intensity)
        assertEquals("", VisualCreative.decode("{\"version\":1,\"hue\":0,\"overlay\":0}").outroText)
        assertEquals(0, VisualCreative.decode("{\"version\":1,\"hue\":0,\"overlay\":0}").backgroundBlur)
        assertEquals(1, VisualCreative.decode("{\"version\":1,\"hue\":0,\"overlay\":0,\"outroMotif\":1}").outroMotifs)
        assertEquals(2, VisualCreative.decode("{\"version\":1,\"hue\":0,\"overlay\":0,\"outroMotif\":2}").outroMotifs)
        assertEquals(7, VisualCreative.decode("{\"version\":1,\"hue\":0,\"overlay\":0,\"outroMotif\":3}").outroMotifs)
        assertEquals(28, VisualCreative.decode(VisualCreative(outroMotifs = 28).encode()).outroMotifs)
        assertFailsWith<IllegalArgumentException> { VisualCreative.decode("{\"version\":1,\"hue\":0,\"overlay\":0,\"outroMotifs\":0}") }
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
        assertEquals((1..39).filter { it != 9 && it != 24 }.toSet(), modes.toSet())
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
