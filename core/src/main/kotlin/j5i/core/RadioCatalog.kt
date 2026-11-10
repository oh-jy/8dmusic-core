package j5i.spatial8d.core

/** 电台的一条可直连线路；目录按声明顺序回退，不合并不同电台的地址。 */
data class RadioSource(val label: String, val url: String, val quality: String)

/** 与 UTS core/radio.uts 同源的展示与筛选元数据。 */
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
