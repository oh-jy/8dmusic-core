package j5i.spatial8d
import j5i.spatial8d.feature.ui.*
import j5i.spatial8d.feature.playback.*
import j5i.spatial8d.feature.source.*
import j5i.spatial8d.feature.video.*
import j5i.spatial8d.feature.update.*
import j5i.spatial8d.feature.cache.*

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import java.net.URI

class VideoLinksTest {
    @Test fun findsNestedM3u8Addresses() {
        val json = JSONObject().put("items", JSONArray()
            .put(JSONObject().put("url", "https://media.example.test/one.m3u8?token=test"))
            .put(JSONObject().put("url", "../two.M3U8"))
            .put(JSONObject().put("url", "https://media.example.test/video.mp4")))
        assertEquals(listOf("https://media.example.test/one.m3u8?token=test", "../two.M3U8"), findM3u8(json))
        assertEquals(listOf("one.m3u8"), findM3u8(JSONArray().put("one.m3u8")))
        assertEquals(emptyList(), findM3u8(JSONObject().put("url", "page.html")))
    }

    @Test fun findsM3u8EmbeddedInTextValue() {
        val json = JSONObject().put(
            "vod_play_url",
            "在线播放$[https://media.example.test/path/video.m3u8?token=test](https://media.example.test/path/video.m3u8?token=test)"
        )
        assertEquals(
            listOf("https://media.example.test/path/video.m3u8?token=test", "https://media.example.test/path/video.m3u8?token=test"),
            findM3u8(json)
        )
    }

    @Test fun findsEscapedM3u8InUnformattedJsonText() {
        val text = """,\"vod_play_url\":\"在线播放${'$'}https://media.example.test/path/index.m3u8\""".replace("/", "\\/")
        assertEquals(listOf("https://media.example.test/path/index.m3u8"), findM3u8(text))
    }

    @Test fun findsM3u8FromApiJsonShape() {
        val text = "\"\"\"{\"vod_play_url\":\"在线播放${'$'}https:\\\\/\\\\/media.example.test\\\\/path\\\\/index.m3u8\"}\"\"\""
        assertEquals(listOf("https://media.example.test/path/index.m3u8"), findM3u8(text))
    }

    @Test fun extractsBilibiliBvidFromShortLinkRedirect() {
        assertEquals("BV1abc123456", extractBilibiliBvid("https://video.example.test/video/BV1abc123456"))
        assertEquals("BV1abc123456", extractBilibiliBvid("redirect=bilibili://video/BV1abc123456"))
    }

    @Test fun extractsM3u8FromMacPlayerHtml() {
        val html = """
            <script type="text/javascript">var player_aaaa={"flag":"play","url":"https:\\/\\/media.example.test\\/path\\/index.m3u8","from":"hls"}</script>
        """.trimIndent()
        assertEquals(
            listOf("https://media.example.test/path/index.m3u8"),
            extractHtmlVideoTargets(html, URI("https://page.example.test/video/play.html"))
        )
    }

    @Test fun extractsVideoUrlRegardlessOfPlayerVariableName() {
        val html = "<script>var player_BBB = {\"file\":\"https://media.example.test/live/index.m3u8?token=abc\"}</script>"
        assertEquals(
            listOf("https://media.example.test/live/index.m3u8?token=abc"),
            extractHtmlVideoTargets(html, URI("https://page.example.test/play/page.html"))
        )
    }
}
