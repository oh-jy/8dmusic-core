package j5i.spatial8d.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceDistanceTest {
    @Test fun fastOrbitUsesThreeSeconds() {
        assertEquals(3.0, SPACE_PRESETS.first { it.name.startsWith("极速环绕") }.period)
    }

    @Test fun standardPresetUsesThreeMeters() {
        assertEquals(3.0, DEFAULT_SPACE_PRESET.sourceDistance)
    }

    @Test fun distanceChangesDirectSoundAndPresetMatching() {
        fun render(distance: Double): Double {
            val engine = SpaceEngine(44100.0)
            engine.setSpace(8.0, 35.0, 45.0, 0.0, 1.0, 100.0, false, 0, distance)
            engine.setRoom(10.0, 1.2, 0.0)
            val input = DoubleArray(44100) { .1 }
            val left = DoubleArray(input.size)
            val right = DoubleArray(input.size)
            engine.process(input, left, right, input.size, 0.0)
            return left.drop(1000).sumOf { abs(it) } + right.drop(1000).sumOf { abs(it) }
        }
        assertTrue(render(5.0) < render(1.0))
        val p = DEFAULT_SPACE_PRESET
        assertEquals(CUSTOM_SPACE, matchPreset(p.period, p.elev, p.depth, p.room, p.wet,
            p.rt60, p.phase, p.direction, p.width, p.raw, p.trajectory, 2.0))
    }
}
