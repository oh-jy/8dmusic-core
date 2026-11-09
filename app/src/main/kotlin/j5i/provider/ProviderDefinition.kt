package j5i.spatial8d.provider

import org.json.JSONObject
import java.net.URI

/** Declarative data only. Parsing this definition never executes a request. */
internal data class ProviderDefinition(
    val id: String,
    val name: String,
    val type: Type,
    val search: Search?,
    val stations: List<Station>,
    val domains: Set<String>,
    val usesHttp: Boolean,
) {
    enum class Type { MUSIC, FM }
    data class Search(val url: String, val resultsPath: String, val idPath: String,
                      val titlePath: String, val artistPath: String, val audioPath: String)
    data class Station(val id: String, val name: String, val url: String, val alternateUrls: List<String> = emptyList(),
                       val region: String = "", val city: String = "", val category: String = "",
                       val level: String = "", val note: String = "")

    companion object {
        const val MAX_BYTES = 256 * 1024
        private val identifier = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        private val path = Regex("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*")

        fun parse(bytes: ByteArray): ProviderDefinition {
            require(bytes.size in 1..MAX_BYTES) { "配置文件为空或超过 256 KB" }
            val text = bytes.toString(Charsets.UTF_8)
            val root = JSONObject(text)
            requireKeys(root, setOf("version", "id", "name", "type", "search", "stations"))
            require(root.optInt("version", -1) == 1) { "不支持的 Provider 版本" }
            val id = root.getString("id").also { require(identifier.matches(it)) { "来源 ID 无效" } }
            val name = root.getString("name").trim().also { require(it.length in 1..80) { "来源名称无效" } }
            val type = when (root.getString("type")) {
                "music" -> Type.MUSIC
                "fm" -> Type.FM
                else -> throw IllegalArgumentException("不支持的来源类型")
            }
            val urls = mutableListOf<URI>()
            fun url(raw: String, template: Boolean): String {
                require(raw.length in 1..2048) { "地址长度无效" }
                require(!raw.substringBefore('?').contains('{')) { "模板变量只能用于查询参数" }
                val replaced = if (template) raw.replace("{query}", "test").replace("{page}", "1") else raw
                require(!replaced.contains('{') && !replaced.contains('}')) { "不支持的模板变量" }
                val uri = URI(replaced)
                require(uri.scheme?.lowercase() in setOf("http", "https") && uri.host != null &&
                    uri.userInfo == null && uri.fragment == null) { "地址必须为不含凭据的 HTTP(S) 地址" }
                val sensitive = Regex("(?i)(?:^|[?&])(?:cookie|token|key|secret|password|authorization|session|auth|sign)=[^&]+")
                require(!sensitive.containsMatchIn(replaced)) { "配置地址不能包含凭据" }
                require(uri.port == -1 || uri.port in 1..65535) { "端口无效" }
                urls += uri
                return raw
            }
            fun field(obj: JSONObject, key: String): String = obj.getString(key).also {
                require(path.matches(it) && it.length <= 160) { "字段路径无效: $key" }
            }
            val search = if (type == Type.MUSIC) {
                require(!root.has("stations")) { "歌曲来源不能包含电台" }
                val obj = root.getJSONObject("search")
                requireKeys(obj, setOf("url", "results", "id", "title", "artist", "audio"))
                Search(url(obj.getString("url"), true), field(obj, "results"), field(obj, "id"),
                    field(obj, "title"), field(obj, "artist"), field(obj, "audio"))
            } else null
            val stations = if (type == Type.FM) {
                require(!root.has("search")) { "FM 来源不能包含歌曲搜索" }
                val list = root.getJSONArray("stations")
                require(list.length() in 1..1000) { "电台数量无效" }
                (0 until list.length()).map { index ->
                    val obj = list.getJSONObject(index)
                    requireKeys(obj, setOf("id", "name", "url", "alternateUrls", "region", "city", "category", "level", "note"))
                    fun metadata(key: String): String = obj.optString(key, "").also {
                        require(it.length <= 160) { "电台信息过长: $key" }
                    }
                    val alternates = (if (obj.has("alternateUrls")) obj.getJSONArray("alternateUrls") else null)?.let { array ->
                        require(array.length() <= 20) { "备用线路过多" }
                        (0 until array.length()).map { url(array.getString(it), false) }
                    } ?: emptyList()
                    Station(obj.getString("id").also { require(identifier.matches(it)) { "电台 ID 无效" } },
                        obj.getString("name").trim().also { require(it.length in 1..80) { "电台名称无效" } },
                        url(obj.getString("url"), false), alternates, metadata("region"), metadata("city"),
                        metadata("category"), metadata("level"), metadata("note"))
                }.also { values -> require(values.map { it.id }.distinct().size == values.size) { "电台 ID 重复" } }
            } else emptyList()
            return ProviderDefinition(id, name, type, search, stations,
                urls.map { it.host.lowercase() }.toSet(), urls.any { it.scheme.equals("http", true) })
        }

        fun parseImport(bytes: ByteArray): List<ByteArray> {
            require(bytes.size in 1..MAX_BYTES) { "配置文件为空或超过 256 KB" }
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            val items = if (root.has("providers")) {
                requireKeys(root, setOf("version", "providers"))
                require(root.optInt("version", -1) == 1) { "不支持的来源包版本" }
                val providers = root.getJSONArray("providers")
                require(providers.length() in 1..100) { "来源数量无效" }
                (0 until providers.length()).map { providers.getJSONObject(it).toString().toByteArray(Charsets.UTF_8) }
            } else listOf(bytes)
            val definitions = items.map(::parse)
            require(definitions.map { it.id }.distinct().size == definitions.size) { "来源 ID 重复" }
            return items
        }

        private fun requireKeys(obj: JSONObject, allowed: Set<String>) {
            val keys = obj.keys()
            while (keys.hasNext()) require(keys.next() in allowed) { "配置包含不支持的字段" }
        }
    }
}
