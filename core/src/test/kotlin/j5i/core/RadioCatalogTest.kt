package j5i.spatial8d.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RadioCatalogTest {
    @Test
    fun catalogKeepsFallbackSourcesAndFiltersStations() {
        assertEquals(4, RadioCatalog.fmStations.size)
        assertTrue(RadioCatalog.internationalStations.isEmpty())
        assertEquals(RadioCatalog.fmStations.size + RadioCatalog.internationalStations.size,
            (RadioCatalog.fmStations + RadioCatalog.internationalStations).map { it.id }.distinct().size)
        assertTrue(RadioCatalog.fmStations.flatMap { it.sources }.all {
            it.url.startsWith("https://") || it.url.startsWith("http://")
        })

        val international = RadioCatalog.filter(international = false, region = "国际", query = "ambient")
        assertTrue(international.isNotEmpty())
        assertTrue(international.all { it.region == "国际" })
        val music = RadioCatalog.filter(false, type = "音乐")
        assertTrue(music.isNotEmpty() && music.all { it.type == "网络台" })
        assertTrue(RadioCatalog.fmStations.flatMap { it.sources }.all { it.url.startsWith("https://") })
    }
}
