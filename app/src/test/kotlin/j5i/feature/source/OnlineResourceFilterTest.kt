package j5i.spatial8d.feature.source

import j5i.spatial8d.provider.ProviderSearch
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnlineResourceFilterTest {
    @Test fun pluginVipWithMvRemainsUsableAsNonVResource() {
        val free = JSONObject().put("vip", false)
        val vip = JSONObject().put("vip", true)
        val vipWithMv = JSONObject().put("vip", true).put("mvUrl", "https://example.test/mv")
        val mv = JSONObject().put("mvUrl", "https://example.test/mv")

        assertTrue(onlinePluginTrackMatchesFilter("platform.qq", free, OnlineResourceFilter.NON_V))
        assertFalse(onlinePluginTrackMatchesFilter("platform.qq", vip, OnlineResourceFilter.NON_V))
        assertTrue(onlinePluginTrackMatchesFilter("platform.qq", vipWithMv, OnlineResourceFilter.NON_V))
        assertTrue(onlinePluginTrackMatchesFilter("platform.qq", mv, OnlineResourceFilter.MV))
        assertFalse(onlinePluginTrackMatchesFilter("platform.qq", free, OnlineResourceFilter.MV))
        assertTrue(onlinePluginTrackMatchesFilter("platform.bilibili", free, OnlineResourceFilter.MV))
    }

    @Test fun declarativeProviderUsesSameVipAndMvRules() {
        val free = ProviderSearch.Track("1", "Song", "Artist", "https://example.test/audio")
        val vip = free.copy(vip = true)
        val vipWithMv = vip.copy(mvUrl = "https://example.test/mv")

        assertTrue(onlineDeclarativeTrackMatchesFilter(free, OnlineResourceFilter.NON_V))
        assertFalse(onlineDeclarativeTrackMatchesFilter(vip, OnlineResourceFilter.NON_V))
        assertTrue(onlineDeclarativeTrackMatchesFilter(vipWithMv, OnlineResourceFilter.NON_V))
        assertTrue(onlineDeclarativeTrackMatchesFilter(vipWithMv, OnlineResourceFilter.MV))
        assertFalse(onlineDeclarativeTrackMatchesFilter(free, OnlineResourceFilter.MV))
    }

    @Test fun extraDetailsOnlyAppearWhenProviderSuppliesThem() {
        assertEquals("", onlinePluginTrackDetail(JSONObject().put("album", "").put("size", 0)))
        assertEquals("Album · 3.0 MB", onlinePluginTrackDetail(JSONObject().put("album", "Album").put("size", 3145728)))
        val base = ProviderSearch.Track("1", "Song", "Artist", "https://example.test/audio")
        assertEquals("", onlineDeclarativeTrackDetail(base))
        assertEquals("Album · 3.0 MB", onlineDeclarativeTrackDetail(base.copy(album = "Album", size = 3145728)))
    }
}
