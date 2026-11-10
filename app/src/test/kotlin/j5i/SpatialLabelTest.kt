package j5i.spatial8d.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpatialLabelTest {
    @Test fun orbitReservesSpaceForBothLabels() {
        assertEquals(170f, spatialRingMaxRadius(400f, 22f))
        assertTrue(spatialRingMaxRadius(400f, 40f) < spatialRingMaxRadius(400f, 22f))
        assertEquals(0f, spatialRingMaxRadius(10f, 22f))
    }
}
