package j5i.spatial8d.provider

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

internal object ProviderSearch {
    data class Track(val id: String, val title: String, val artist: String, val audioUrl: String?)

    fun search(provider: ProviderDefinition, query: String, page: Int): List<Track> {
        require(provider.type == ProviderDefinition.Type.MUSIC)
        val definition = requireNotNull(provider.search)
        val url = definition.url.replace("{query}", URLEncoder.encode(query, "UTF-8"))
            .replace("{page}", page.coerceAtLeast(1).toString())
        val bytes = ProviderNetwork.get(url, provider.usesHttp)
        return parse(provider, JSONObject(bytes.toString(Charsets.UTF_8)))
    }

    internal fun parse(provider: ProviderDefinition, response: JSONObject): List<Track> {
        val search = requireNotNull(provider.search)
        fun value(root: JSONObject, path: String): Any? {
            var node: Any? = root
            for (key in path.split('.')) node = (node as? JSONObject)?.opt(key) ?: return null
            return node
        }
        val rows = value(response, search.resultsPath) as? JSONArray ?: error("歌曲列表字段不存在或格式不正确")
        require(rows.length() <= 1000) { "搜索结果过多" }
        return (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            fun text(path: String): String = value(row, path)?.takeUnless { it == JSONObject.NULL }?.toString()?.trim().orEmpty()
            val id = text(search.idPath)
            val title = text(search.titlePath)
            if (id.isEmpty() || title.isEmpty()) return@mapNotNull null
            val audio = text(search.audioPath).takeIf { raw ->
                runCatching {
                    val uri = URI(raw)
                    uri.scheme?.lowercase() in setOf("http", "https") && uri.host != null &&
                        uri.userInfo == null && uri.fragment == null
                }.getOrDefault(false)
            }
            Track(id, title, text(search.artistPath), audio)
        }
    }
}
