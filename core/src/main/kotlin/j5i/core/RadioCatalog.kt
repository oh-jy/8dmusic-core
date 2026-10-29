package j5i.spatial8d.core

/** 电台的一条可直连线路；目录按声明顺序回退，不合并不同电台的地址。 */
data class RadioSource(val label: String, val url: String, val quality: String)

data class RadioStation(
    val id: String,
    val name: String,
    val region: String,
    val city: String,
    val type: String,
    val level: String,
    val note: String,
    val sources: List<RadioSource>
)

/**
 * 少量 SomaFM 官方公开播放入口。公开播放不代表无版权或允许再分发，
 * 本项目与 SomaFM 无官方合作；来源与使用边界见 THIRD-PARTY-SOURCES.md。
 */
object RadioCatalog {
    val regions = listOf("全部", "国际")
    val types = listOf("全部", "音乐", "网络台")

    private fun soma(id: String, name: String, note: String) = RadioStation(
        id, name, "国际", "美国", "网络台", "公开网络电台", note,
        listOf(
            RadioSource("SomaFM 官方 MP3 · 主线路", "https://ice1.somafm.com/$id-128-mp3", "MP3 128k"),
            RadioSource("SomaFM 官方 MP3 · 备用线路", "https://ice2.somafm.com/$id-128-mp3", "MP3 128k")
        )
    )

    val fmStations = listOf(
        soma("groovesalad", "Groove Salad", "Ambient / Downtempo · SomaFM 官方公开播放源"),
        soma("dronezone", "Drone Zone", "Atmospheric Ambient · SomaFM 官方公开播放源"),
        soma("spacestation", "Space Station Soma", "Electronic / Ambient · SomaFM 官方公开播放源"),
        soma("deepspaceone", "Deep Space One", "Experimental Ambient · SomaFM 官方公开播放源")
    )
    val internationalStations = emptyList<RadioStation>()

    fun filter(
        international: Boolean,
        region: String = "全部",
        city: String = "全部",
        type: String = "全部",
        query: String = ""
    ): List<RadioStation> {
        val keyword = query.trim().lowercase()
        return fmStations.filter { station ->
            (region == "全部" || station.region == region) &&
                (type == "全部" || station.type == type || type == "音乐") &&
                (keyword.isEmpty() || listOf(station.name, station.region, station.city, station.type, station.note)
                    .any { it.lowercase().contains(keyword) })
        }
    }
}
