package j5i.spatial8d
import j5i.spatial8d.feature.ui.*
import j5i.spatial8d.feature.playback.*
import j5i.spatial8d.feature.source.*
import j5i.spatial8d.feature.video.*
import j5i.spatial8d.feature.update.*
import j5i.spatial8d.feature.cache.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class VideoCacheTest {
    @Test fun formatsVideoTimeWithHours() {
        assertEquals("01:02:03", videoTime(3_723_000))
        assertEquals("05:01", videoTime(301_000))
        assertEquals("59:59", videoTime(3_599_000))
        assertEquals("01:00:00", videoTime(3_600_000))
    }
    @Test fun cacheKeyIsStableAndUrlSafe() {
        val url = "https://example.com/video.mp4?token=abc"
        val key = videoCacheKey(url)
        assertEquals(64, key.length)
        assertEquals(key, videoCacheKey(url))
        assertNotEquals(key, videoCacheKey("$url&part=2"))
        check(key.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test fun concurrentCacheWritersRemainProtectedUntilBothFinish() {
        val file = java.io.File("video-cache-test.mp4")
        PlaybackCacheMetadata.begin(file)
        PlaybackCacheMetadata.begin(file)
        assertTrue(PlaybackCacheMetadata.isActive(file))
        PlaybackCacheMetadata.end(file)
        assertTrue(PlaybackCacheMetadata.isActive(file))
        PlaybackCacheMetadata.end(file)
        assertFalse(PlaybackCacheMetadata.isActive(file))
        assertTrue(PlaybackCacheMetadata.tryBegin(file))
        assertFalse(PlaybackCacheMetadata.tryBegin(file))
        PlaybackCacheMetadata.end(file)
    }

    @Test fun deletingAndWritingCannotReserveSameFile() {
        val file = java.io.File("video-cache-race.mp4")
        val acquired = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val writer = Thread {
            check(PlaybackCacheMetadata.tryBegin(file))
            acquired.countDown()
            release.await()
            PlaybackCacheMetadata.end(file)
        }
        writer.start()
        assertTrue(acquired.await(5, java.util.concurrent.TimeUnit.SECONDS))
        try { assertFalse(PlaybackCacheMetadata.tryBegin(file)) }
        finally { release.countDown(); writer.join(5000) }
        assertTrue(PlaybackCacheMetadata.tryBegin(file))
        PlaybackCacheMetadata.end(file)
    }

    @Test fun cacheNamesAndMetadataRejectUnsafeValues() {
        assertTrue(isVideoCacheName("a".repeat(64) + ".mp4"))
        assertFalse(isVideoCacheName("../" + "a".repeat(64) + ".mp4"))
        assertFalse(isVideoCacheName("a".repeat(64) + ".mp4.part"))
        assertEquals("", cacheMetadataValue("https://example.test/video?token=secret"))
        assertEquals("", cacheMetadataValue("x".repeat(201) + "https://example.test"))
        assertEquals(200, cacheMetadataValue("x".repeat(201)).length)
    }

    @Test fun resumePositionSkipsStartAndEndBoundaries() {
        assertEquals(5_000, videoResumePosition(5_000, 60_000))
        assertEquals(12_000, videoResumePosition(12_000, 60_000))
        assertEquals(55_000, videoResumePosition(55_000, 60_000))
        assertEquals(12_000, videoResumePosition(12_000, 0))
    }
}
