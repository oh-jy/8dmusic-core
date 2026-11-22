package j5i.spatial8d

import kotlin.test.Test
import kotlin.test.assertEquals

class VideoEntryTapTest {
    @Test fun countsOnlyConsecutiveTapsWithinTimeout() {
        assertEquals(1, videoEntryTapCount(0, 0, 100))
        assertEquals(2, videoEntryTapCount(1, 100, 1300))
        assertEquals(3, videoEntryTapCount(2, 1300, 1400))
        assertEquals(1, videoEntryTapCount(2, 100, 1301))
        assertEquals(1, videoEntryTapCount(2, 100, 99))
    }
}
