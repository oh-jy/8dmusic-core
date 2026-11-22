package j5i.spatial8d

import j5i.spatial8d.provider.audioCacheId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackCacheTest {
    @Test fun identifiesOnlyProviderAudioCacheFiles() {
        assertEquals(123L, audioCacheId("123.m4a"))
        assertEquals(-42L, audioCacheId("-42.m4a"))
        assertNull(audioCacheId("123.part"))
        assertNull(audioCacheId("favorite.m4a"))
    }
}
