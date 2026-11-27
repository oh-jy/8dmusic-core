package j5i.spatial8d.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.Base64

class ProviderDefinitionTest {
    private fun parse(value: String) = ProviderDefinition.parse(value.toByteArray(Charsets.UTF_8))

    @Test fun parsesFmAndSummarizesDomains() {
        val p = parse("""{"version":1,"id":"fm.example","name":"Example","type":"fm","stations":[{"id":"a","name":"A","url":"https://audio.example/live"},{"id":"b","name":"B","url":"http://other.example/live"}]}""")
        assertEquals(ProviderDefinition.Type.FM, p.type)
        assertEquals(setOf("audio.example", "other.example"), p.domains)
        assertTrue(p.usesHttp)
        assertEquals(2, p.stations.size)
    }

    @Test fun importsPlainAndBase64Fm() {
        val json = """{"version":1,"id":"fm.example","name":"电台","type":"fm","stations":[{"id":"a","name":"A","url":"https://audio.example/live"}]}""".toByteArray(Charsets.UTF_8)
        val encoded = Base64.getMimeEncoder(76, "\r\n".toByteArray()).encode(json)
        assertEquals(ProviderDefinition.parse(json), ProviderDefinition.parse(ProviderDefinition.parseImport(encoded).single()))
        assertEquals(ProviderDefinition.parse(json), ProviderDefinition.parse(ProviderDefinition.parseImport(json).single()))
        assertFails { ProviderDefinition.parseImport("not-base64!".toByteArray()) }
        assertFails { ProviderDefinition.parseImport(Base64.getEncoder().encode(ByteArray(ProviderDefinition.MAX_BYTES + 1))) }
    }

    @Test fun acceptsMoreThanOneThousandStations() {
        val stations = (1..4000).joinToString(",") { """{"id":"s$it","name":"Station $it","url":"https://audio.example/$it"}""" }
        val input = """{"version":1,"id":"fm","name":"FM","type":"fm","stations":[$stations]}"""
        assertTrue(input.toByteArray(Charsets.UTF_8).size > 256 * 1024)
        assertEquals(4000, parse(input).stations.size)
        assertEquals(4000, ProviderDefinition.parse(ProviderDefinition.parseImport(input.toByteArray()).single()).stations.size)
    }

    @Test fun parsesDeclarativeMusicSearch() {
        val p = parse("""{"version":1,"id":"demo","name":"Demo","type":"music","search":{"url":"https://api.example/search?q={query}&page={page}","results":"data.items","id":"id","title":"title","artist":"artist","audio":"audio.url"}}""")
        assertEquals("data.items", p.search?.resultsPath)
        assertFalse(p.usesHttp)
        assertFails { parse("""{"version":1,"id":"demo","name":"Demo","type":"music","search":{"url":"https://{query}.example/search","results":"items","id":"id","title":"title","artist":"artist","audio":"audio"}}""") }
    }

    @Test fun searchMapsOnlyValidRows() {
        val p = parse("""{"version":1,"id":"demo","name":"Demo","type":"music","search":{"url":"https://api.example/search?q={query}","results":"data.items","id":"id","title":"name","artist":"artist","audio":"audio"}}""")
        val response = org.json.JSONObject("""{"data":{"items":[{"id":"1","name":"Song","artist":"Artist","audio":"https://audio.example/a.mp3"},{"id":"2","name":"Unavailable","audio":"file:///private"},{"name":"Missing ID"}]}}""")
        val tracks = ProviderSearch.parse(p, response)
        assertEquals(2, tracks.size)
        assertEquals("Song", tracks[0].title)
        assertEquals(null, tracks[1].audioUrl)
    }

    @Test fun searchReadsOptionalDurationInMilliseconds() {
        val p = parse("""{"version":1,"id":"demo","name":"Demo","type":"music","search":{"url":"https://api.example/search?q={query}","results":"items","id":"id","title":"name","artist":"artist","audio":"audio","duration":"durationMs"}}""")
        val tracks = ProviderSearch.parse(p, org.json.JSONObject("""{"items":[{"id":"1","name":"Song","durationMs":123456},{"id":"2","name":"Unknown","durationMs":-1}]}"""))
        assertEquals(123456L, tracks[0].duration)
        assertEquals(0L, tracks[1].duration)
        assertEquals(123456L, ProviderTrackRef.declarative("demo", tracks[0]).duration)
    }

    @Test fun searchReadsOptionalVipAndMvPaths() {
        val p = parse("""{"version":1,"id":"demo","name":"Demo","type":"music","search":{"url":"https://api.example/search?q={query}","results":"items","id":"id","title":"name","artist":"artist","audio":"audio","vip":"payInfo.isVip","mvUrl":"mv.url"}}""")
        val tracks = ProviderSearch.parse(p, org.json.JSONObject("""{"items":[{"id":"1","name":"VIP MV","payInfo":{"isVip":true},"mv":{"url":"https://mv.example/1"}},{"id":"2","name":"Free"}]}"""))
        assertTrue(tracks[0].vip)
        assertEquals("https://mv.example/1", tracks[0].mvUrl)
        assertFalse(tracks[1].vip)
        assertEquals("", tracks[1].mvUrl)
    }

    @Test fun searchReadsOptionalAlbumAndSizePaths() {
        val p = parse("""{"version":1,"id":"demo","name":"Demo","type":"music","search":{"url":"https://api.example/search?q={query}","results":"items","id":"id","title":"name","artist":"artist","audio":"audio","album":"album.name","size":"fileSize"}}""")
        val tracks = ProviderSearch.parse(p, org.json.JSONObject("""{"items":[{"id":"1","name":"Song","album":{"name":"Album"},"fileSize":3145728},{"id":"2","name":"Unknown","fileSize":-1}]}"""))
        assertEquals("Album", tracks[0].album)
        assertEquals(3145728L, tracks[0].size)
        assertEquals("", tracks[1].album)
        assertEquals(0L, tracks[1].size)
    }

    @Test fun rejectsUnsupportedOrDangerousStructure() {
        val base = """{"version":1,"id":"demo","name":"Demo","type":"fm","stations":[{"id":"a","name":"A","url":"https://audio.example/live"}]}"""
        assertFails { parse(base.replace("https://audio.example/live", "file:///tmp/a")) }
        assertFails { parse(base.replace("https://audio.example/live", "https://user:pass@audio.example/live")) }
        assertFails { parse(base.replace("\"version\":1", "\"version\":2")) }
        assertFails { parse(base.replace("\"type\":\"fm\"", "\"type\":\"fm\",\"script\":\"alert(1)\"")) }
        assertFails { ProviderDefinition.parse(ByteArray(ProviderDefinition.MAX_BYTES + 1)) }
    }
}
