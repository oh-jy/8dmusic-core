package j5i.spatial8d.feature.video
import j5i.spatial8d.feature.ui.*
import j5i.spatial8d.feature.playback.*
import j5i.spatial8d.feature.source.*
import j5i.spatial8d.feature.update.*
import j5i.spatial8d.feature.cache.*
import org.json.JSONArray
import org.json.JSONObject

internal data class VideoHistoryEntry(val uri: String, val title: String, val artist: String = "")

internal fun readVideoHistory(json: String): List<VideoHistoryEntry> =
    runCatching {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { index ->
            val entry = array.optJSONObject(index) ?: return@mapNotNull null
            val uri = entry.optString("uri")
            val title = entry.optString("title")
            if (uri.isBlank() || title.isBlank()) null else VideoHistoryEntry(uri, title, entry.optString("artist"))
        }.distinctBy { it.uri }
    }.getOrDefault(emptyList())

internal fun writeVideoHistory(entries: List<VideoHistoryEntry>): String = JSONArray().apply {
    entries.forEach { entry -> put(JSONObject().put("uri", entry.uri).put("title", entry.title).put("artist", entry.artist)) }
}.toString()

internal fun recordVideoHistory(entries: List<VideoHistoryEntry>, uri: String, title: String, artist: String = ""): List<VideoHistoryEntry> {
    val previous = entries.firstOrNull { it.uri == uri }
    val entry = previous?.copy(artist = artist.ifBlank { previous.artist }) ?: VideoHistoryEntry(uri, title, artist)
    return (listOf(entry) + entries.filterNot { it.uri == uri }).take(200)
}
