package j5i.spatial8d.provider

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails

class DocumentedProvidersTest {
    @Test fun documentedConfigsParseWithAppParser() {
        val directory = listOf(File("docs/provider"), File("../docs/provider"))
            .first { it.isDirectory }
        val music = ProviderDefinition.parse(File(directory, "apple-itunes-preview.json").readBytes())
        val fm = ProviderDefinition.parse(File(directory, "cnr-radio.json").readBytes())
        assertEquals(ProviderDefinition.Type.MUSIC, music.type)
        assertEquals("previewUrl", music.search?.audioPath)
        assertEquals(ProviderDefinition.Type.FM, fm.type)
        assertEquals(4, fm.stations.size)
        assertFalse(music.usesHttp || fm.usesHttp)
        val allFm = ProviderDefinition.parse(File(directory, "all-fm.json").readBytes())
        assertEquals(173, allFm.stations.size)
        assertEquals(581, allFm.stations.sumOf { 1 + it.alternateUrls.size })
        val station = allFm.stations.first { it.id == "cnr-1" }
        assertEquals("全国", station.region)
        assertEquals("新闻综合", station.category)
        assertEquals(1, station.alternateUrls.size)
        val bundle = ProviderDefinition.parseImport(File(directory, "combined-starter.json").readBytes())
        assertEquals(listOf(music.id, fm.id), bundle.map { ProviderDefinition.parse(it).id })
        assertFails { ProviderDefinition.parseImport("""{"version":1,"providers":[${String(bundle[0])},${String(bundle[0])}]}""".toByteArray()) }
    }
}
