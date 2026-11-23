package j5i.spatial8d.provider

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ProviderTrackRefTest {
    @Test fun mvResolutionOnlyAcceptsMatchingProviderReferences() {
        fun reference(source: String, mv: String) = ProviderTrackRef.plugin(source, JSONObject()
            .put("id", "song").put("title", "歌曲").put("mvUrl", mv)).uri
        assertEquals("platform.qq" to "d0024ro2pbh", ProviderTrackRef.mvIdentity(
            reference("platform.qq", "https://y.qq.com/n/ryqq/mv/d0024ro2pbh")))
        assertEquals("platform.kugou" to "352E69DF7161AC55299AFA0E0365E695", ProviderTrackRef.mvIdentity(
            reference("platform.kugou", "https://www.kugou.com/mvweb/html/mv_352E69DF7161AC55299AFA0E0365E695.html")))
        assertEquals("platform.netease" to "5293234", ProviderTrackRef.mvIdentity(
            reference("platform.netease", "https://music.163.com/mv/5293234")))
        listOf("https://y.qq.com/n/ryqq/mv/d0024ro2pbh",
            reference("platform.qq", "https://other.qq.com/n/ryqq/mv/d0024ro2pbh"),
            reference("platform.kugou", "https://y.qq.com/n/ryqq/mv/d0024ro2pbh"),
            reference("platform.netease", "https://other.163.com/mv/5293234"),
            reference("platform.qq", "https://y.qq.com/n/ryqq/mv/d0024ro2pbh?url=other"))
            .forEach { assertFailsWith<IllegalArgumentException> { ProviderTrackRef.mvIdentity(it) } }
    }
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
