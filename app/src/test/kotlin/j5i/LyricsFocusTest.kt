package j5i.spatial8d.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LyricsFocusTest {
    @Test fun estimatesOnlyWithinAValidTimedLine() {
        assertEquals(0f, lyricLineProgress(1000, 3000, 500))
        assertEquals(0.5f, lyricLineProgress(1000, 3000, 2000))
        assertEquals(1f, lyricLineProgress(1000, 3000, 4000))
        assertNull(lyricLineProgress(1000, null, 2000))
        assertNull(lyricLineProgress(1000, 1000, 1000))
    }
}
