package j5i.spatial8d

import kotlin.test.Test
import kotlin.test.assertEquals

class RadioStationOrderTest {
    @Test fun pinnedStationsLeadOnlyWithoutFilters() {
        val stations = listOf("a", "b", "c", "d")
        val pinned = listOf("c", "a", "missing", "c")
        assertEquals(listOf("c", "a", "b", "d"), orderedRadioStationIds(stations, pinned, true))
        assertEquals(stations, orderedRadioStationIds(stations, pinned, false))
    }
}
