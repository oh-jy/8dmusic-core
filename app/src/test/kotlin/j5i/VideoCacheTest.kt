package j5i.spatial8d

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class VideoCacheTest {
    @Test fun cacheKeyIsStableAndUrlSafe() {
        val url = "https://example.com/video.mp4?token=abc"
        val key = videoCacheKey(url)
        assertEquals(64, key.length)
        assertEquals(key, videoCacheKey(url))
        assertNotEquals(key, videoCacheKey("$url&part=2"))
        check(key.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test fun resumePositionSkipsStartAndEndBoundaries() {
        assertEquals(0, videoResumePosition(5_000, 60_000))
        assertEquals(12_000, videoResumePosition(12_000, 60_000))
        assertEquals(0, videoResumePosition(55_000, 60_000))
        assertEquals(0, videoResumePosition(12_000, 0))
    }
}
