package j5i.spatial8d.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrajectoryTest {
    @Test fun pathsAreContinuousAndDeterministic() {
        for (mode in 0..4) for (direction in listOf(-1.0, 0.0, 1.0)) {
            for (i in 0..200) {
                val t = i / 10.0
                val a = trajectoryAngles(t, 8.0, 45.0, direction, 50.0, mode)
                val b = trajectoryAngles(t + 0.00001, 8.0, 45.0, direction, 50.0, mode)
                assertTrue(abs(a.first - b.first) < .001 && abs(a.second - b.second) < .001)
                assertEquals(a, trajectoryAngles(t, 8.0, 45.0, direction, 50.0, mode))
            }
        }
    }

    @Test fun pathsHaveExpectedShapeAndLegacyOrbit() {
        val legacy = trajectoryAngles(2.0, 8.0, 0.0, 1.0, 40.0, 0)
        assertEquals(PI / 2, legacy.first, 1e-10)
        assertEquals(0.0, legacy.second, 1e-10)
        val eight = trajectoryAngles(1.0, 8.0, 0.0, 1.0, 40.0, 1)
        assertTrue(eight.first > 0 && eight.second > 0)
        val vertical = trajectoryAngles(2.0, 8.0, 45.0, 1.0, 40.0, 2)
        assertEquals(PI / 4, vertical.first, 1e-10)
        assertEquals(40 * PI / 180, vertical.second, 1e-10)
        val spiral = trajectoryAngles(8.0, 8.0, 0.0, 1.0, 40.0, 3)
        assertEquals(2 * PI, spiral.first, 1e-10)
        assertEquals(0.0, spiral.second, 1e-10)
    }

    @Test fun matchingDistinguishesTrajectory() {
        val p = DEFAULT_SPACE_PRESET
        assertEquals(CUSTOM_SPACE, matchPreset(p.period, p.elev, p.depth, p.room, p.wet,
            p.rt60, p.phase, p.direction, p.width, p.raw, 1))
    }

    @Test fun builtInPresetsCoverTrajectoriesAndMatchTheirSettings() {
        assertEquals((0..4).toSet(), SPACE_PRESETS.filterNot { it.raw }.map { it.trajectory }.toSet())
        assertEquals(0, SPACE_PRESETS.first { it.raw }.trajectory)
        assertEquals(0, DEFAULT_SPACE_PRESET.trajectory)
        for (p in SPACE_PRESETS) {
            assertEquals(p.name, matchPresetIn(SPACE_PRESETS, p.period, p.elev, p.depth, p.room,
                p.wet, p.rt60, p.phase, p.direction, p.width, p.raw, p.trajectory, p.sourceDistance))
        }
    }
}
