package j5i.spatial8d

import j5i.spatial8d.audio.SpatialPlayer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenderCacheTest {
    @Test fun readsRemainStableUntilConsumedAndWrapCorrectly() {
        val cache = SpatialPlayer.RenderCache(0, 0, 0, capacityBytes = 16)
        val first = ByteArray(12) { it.toByte() }
        assertTrue(cache.write(first))
        val read = ByteArray(8)
        assertTrue(cache.read(read, read.size))
        assertContentEquals(first.copyOfRange(0, 8), read)
        assertTrue(cache.read(read, read.size))
        assertContentEquals(first.copyOfRange(0, 8), read)
        cache.consume(2)
        assertTrue(cache.write(byteArrayOf(12, 13, 14, 15, 16, 17, 18, 19)))
        assertTrue(cache.read(read, read.size))
        assertContentEquals(byteArrayOf(8, 9, 10, 11, 12, 13, 14, 15), read)
        assertFalse(cache.canWrite(8))
        cache.discardWhenIdle()
        assertFalse(cache.read(read, read.size))
        cache.writerFinished()
    }
}
