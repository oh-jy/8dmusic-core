package j5i.spatial8d.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RadioCatalogTest {
    @Test
    fun catalogKeepsFallbackSourcesAndFiltersStations() {
        assertEquals(161, RadioCatalog.fmStations.size)
        assertEquals(12, RadioCatalog.internationalStations.size)
        assertEquals(RadioCatalog.fmStations.size + RadioCatalog.internationalStations.size,
            (RadioCatalog.fmStations + RadioCatalog.internationalStations).map { it.id }.distinct().size)
        assertTrue(RadioCatalog.fmStations.flatMap { it.sources }.all {
            it.url.startsWith("https://") || it.url.startsWith("http://")
        })

        val china = RadioCatalog.filter(international = false, region = "北京", query = "新闻")
        assertTrue(china.isNotEmpty())
        assertTrue(china.all { it.region == "北京" })
        assertTrue(RadioCatalog.filter(true, query = "香港").all { "香港" in it.name + it.region + it.city })
        val music = RadioCatalog.filter(false, type = "音乐")
        assertTrue(music.isNotEmpty() && music.all { it.type == "音乐" })
    }
}
