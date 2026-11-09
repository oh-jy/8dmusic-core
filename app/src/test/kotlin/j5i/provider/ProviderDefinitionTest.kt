package j5i.spatial8d.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderDefinitionTest {
    private fun parse(value: String) = ProviderDefinition.parse(value.toByteArray(Charsets.UTF_8))

    @Test fun parsesFmAndSummarizesDomains() {
        val p = parse("""{"version":1,"id":"fm.example","name":"Example","type":"fm","stations":[{"id":"a","name":"A","url":"https://audio.example/live"},{"id":"b","name":"B","url":"http://other.example/live"}]}""")
        assertEquals(ProviderDefinition.Type.FM, p.type)
        assertEquals(setOf("audio.example", "other.example"), p.domains)
        assertTrue(p.usesHttp)
        assertEquals(2, p.stations.size)
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

    @Test fun rejectsUnsupportedOrDangerousStructure() {
        val base = """{"version":1,"id":"demo","name":"Demo","type":"fm","stations":[{"id":"a","name":"A","url":"https://audio.example/live"}]}"""
        assertFails { parse(base.replace("https://audio.example/live", "file:///tmp/a")) }
        assertFails { parse(base.replace("https://audio.example/live", "https://user:pass@audio.example/live")) }
        assertFails { parse(base.replace("\"version\":1", "\"version\":2")) }
        assertFails { parse(base.replace("\"type\":\"fm\"", "\"type\":\"fm\",\"script\":\"alert(1)\"")) }
        assertFails { ProviderDefinition.parse(ByteArray(ProviderDefinition.MAX_BYTES + 1)) }
    }
}
