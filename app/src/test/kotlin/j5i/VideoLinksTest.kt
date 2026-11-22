package j5i.spatial8d

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import java.net.URI

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

    @Test fun findsM3u8FromSlapibfApiShape() {
        val text = "\"\"\"{\"vod_play_url\":\"在线播放${'$'}https:\\\\/\\\\/2605.senlin2026.com\\\\/20260513\\\\/BAIcDlP0\\\\/index.m3u8\"}\"\"\""
        assertEquals(
            listOf("https://2605.senlin2026.com/20260513/BAIcDlP0/index.m3u8"),
            findM3u8(text)
        )
    }

    @Test fun extractsBilibiliBvidFromShortLinkRedirect() {
        assertEquals("BV1abc123456", extractBilibiliBvid("https://www.bilibili.com/video/BV1abc123456"))
        assertEquals("BV1abc123456", extractBilibiliBvid("redirect=bilibili://video/BV1abc123456"))
    }

    @Test fun extractsM3u8FromMacPlayerHtml() {
        val html = """
            <script type="text/javascript">var player_aaaa={"flag":"play","url":"https:\\/\\/lsbbf11.com\\/20261120\\/QW8pJfoc\\/index.m3u8","from":"ytm3u8"}</script>
        """.trimIndent()
        assertEquals(
            listOf("https://lsbbf11.com/20261120/QW8pJfoc/index.m3u8"),
            extractHtmlVideoTargets(html, URI("https://wap.jiejiesp27.xyz/jiejie/index.php/vod/play/id/1543243/sid/1/nid/1.html"))
        )
    }

    @Test fun extractsVideoUrlRegardlessOfPlayerVariableName() {
        val html = "<script>var player_BBB = {\"file\":\"https://cdn.example.com/live/index.m3u8?token=abc\"}</script>"
        assertEquals(
            listOf("https://cdn.example.com/live/index.m3u8?token=abc"),
            extractHtmlVideoTargets(html, URI("https://example.com/play/page.html"))
        )
    }
}
