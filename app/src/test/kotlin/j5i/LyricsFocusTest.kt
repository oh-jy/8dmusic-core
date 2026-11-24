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

    @Test fun rippleRestartsForEachCharacterAndStaysBounded() {
        assertEquals(0f, lyricRipplePhase(0f, 4))
        assertEquals(0.5f, lyricRipplePhase(0.125f, 4))
        assertEquals(0f, lyricRipplePhase(0.25f, 4))
        kotlin.test.assertTrue(lyricRipplePhase(1f, 4) in 0f..1f)
        assertEquals(0f, lyricRipplePhase(0.5f, 0))
    }
}
