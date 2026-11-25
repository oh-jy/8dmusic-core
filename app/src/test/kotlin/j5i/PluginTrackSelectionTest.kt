package j5i.spatial8d

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginTrackSelectionTest {
    @Test fun bilibiliPartsPlayDirectlyWhileVideosExpand() {
        assertFalse(shouldExpandPluginTrack("platform.bilibili", "BV1abc123456:123", true))
        assertTrue(shouldExpandPluginTrack("platform.bilibili", "BV1abc123456", false))
        assertTrue(shouldExpandPluginTrack("other", "album", true))
        assertFalse(shouldExpandPluginTrack("other", "track", false))
    }

}
