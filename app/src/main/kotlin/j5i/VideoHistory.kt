package j5i.spatial8d

import org.json.JSONArray
import org.json.JSONObject

internal data class VideoHistoryEntry(val uri: String, val title: String)

internal fun readVideoHistory(json: String): List<VideoHistoryEntry> =
    runCatching {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { index ->
            val entry = array.optJSONObject(index) ?: return@mapNotNull null
            val uri = entry.optString("uri")
            val title = entry.optString("title")
            if (uri.isBlank() || title.isBlank()) null else VideoHistoryEntry(uri, title)
        }.distinctBy { it.uri }
    }.getOrDefault(emptyList())

internal fun writeVideoHistory(entries: List<VideoHistoryEntry>): String = JSONArray().apply {
    entries.forEach { entry -> put(JSONObject().put("uri", entry.uri).put("title", entry.title)) }
}.toString()

internal fun recordVideoHistory(entries: List<VideoHistoryEntry>, uri: String, title: String): List<VideoHistoryEntry> {
    val previous = entries.firstOrNull { it.uri == uri }
    return (listOf(previous ?: VideoHistoryEntry(uri, title)) + entries.filterNot { it.uri == uri }).take(200)
}
