package j5i.spatial8d.provider

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderTrackRefTest {
    @Test fun pluginTrackKeepsStableProviderAndTrackIds() {
        val item = ProviderTrackRef.plugin("platform.bilibili", JSONObject()
            .put("id", "BV1abc:123").put("title", "测试歌曲").put("artist", "歌手").put("duration", 120))
        assertTrue(ProviderTrackRef.isOnline(item))
        val uri = java.net.URI(item.uri)
        val fields = uri.rawQuery.split('&').associate { it.substringBefore('=') to
            java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
        assertEquals("platform.bilibili", fields["source"])
        assertEquals("BV1abc:123", fields["id"])
        assertEquals(120000, item.duration)
    }
}
