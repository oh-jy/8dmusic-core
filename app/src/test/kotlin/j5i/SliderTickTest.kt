package j5i.spatial8d

import kotlin.test.Test
import kotlin.test.assertEquals

class SliderTickTest {
    @Test fun tickCentersFollowActualTrackPositions() {
        val centers = speedPresets.indices.map { index ->
            sliderTickCenter(340, 10, 10, 16, 0, index.toFloat() / speedPresets.lastIndex)
        }
        listOf(18f, 78.8f, 139.6f, 200.4f, 261.2f, 322f).forEachIndexed { index, expected ->
            assertEquals(expected, centers[index], 0.001f)
        }
        assertEquals(170f, sliderTickCenter(340, 10, 10, 16, 0, 0.5f), 0.001f)
        assertEquals(18f, sliderTickCenter(340, 18, 18, 16, 8, 0f), 0.001f)
        assertEquals(170f, sliderTickCenter(340, 18, 18, 16, 8, 0.5f), 0.001f)
        assertEquals(322f, sliderTickCenter(340, 18, 18, 16, 8, 1f), 0.001f)
    }

    @Test fun speedPresetsAndIntermediateValuesRoundTrip() {
        speedPresets.forEachIndexed { index, speed ->
            assertEquals(index * 20, progressForSpeed(speed))
            assertEquals(speed, speedForProgress(index * 20), 0.0001)
        }
        (10..40).forEach { step ->
            val speed = step / 20.0
            assertEquals(speed, speedForProgress(progressForSpeed(speed)), 0.0001)
        }
    }
}
