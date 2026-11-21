package j5i.spatial8d

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals

class VideoLinksTest {
    @Test fun findsNestedM3u8Addresses() {
        val json = JSONObject().put("items", JSONArray()
            .put(JSONObject().put("url", "https://example.com/one.m3u8?token=test"))
            .put(JSONObject().put("url", "../two.M3U8"))
            .put(JSONObject().put("url", "https://example.com/video.mp4")))
        assertEquals(listOf("https://example.com/one.m3u8?token=test", "../two.M3U8"), findM3u8(json))
        assertEquals(listOf("one.m3u8"), findM3u8(JSONArray().put("one.m3u8")))
        assertEquals(emptyList(), findM3u8(JSONObject().put("url", "page.html")))
    }

    @Test fun findsM3u8EmbeddedInTextValue() {
        val json = JSONObject().put(
            "vod_play_url",
            "在线播放$[https://example.com/path/video.m3u8?token=test](https://example.com/path/video.m3u8?token=test)"
        )
        assertEquals(
            listOf("https://example.com/path/video.m3u8?token=test", "https://example.com/path/video.m3u8?token=test"),
            findM3u8(json)
        )
    }

    @Test fun findsEscapedM3u8InUnformattedJsonText() {
        val text = """,\"vod_play_url\":\"在线播放${'$'}https:\\/\\/2605.example.com\\/20260513\\/BAIcDlP0\\/index.m3u8\""""
        assertEquals(
            listOf("https://2605.example.com/20260513/BAIcDlP0/index.m3u8"),
            findM3u8(text)
        )
    }

    @Test fun extractsBilibiliBvidFromShortLinkRedirect() {
        assertEquals("BV1abc123456", extractBilibiliBvid("https://www.bilibili.com/video/BV1abc123456"))
        assertEquals("BV1abc123456", extractBilibiliBvid("redirect=bilibili://video/BV1abc123456"))
    }
}
