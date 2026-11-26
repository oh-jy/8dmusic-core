package j5i.spatial8d
import j5i.spatial8d.feature.ui.*
import j5i.spatial8d.feature.playback.*
import j5i.spatial8d.feature.source.*
import j5i.spatial8d.feature.video.*
import j5i.spatial8d.feature.update.*
import j5i.spatial8d.feature.cache.*

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
