package jiangyi.spatial8d.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject

class MusicStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("spatial8d", Context.MODE_PRIVATE)
    var sort = preferences.getString("sort", "name") ?: "name"
        private set
    var ascending = preferences.getBoolean("ascending", true)
        private set
    var query = ""
    var playlistIndex = -1
    val hidden = preferences.getStringSet("hidden", emptySet())!!.mapNotNull { it.toLongOrNull() }.toMutableSet()
    var manualOrder = jsonLongs(preferences.getString("manual", "[]")).toMutableList()
        private set
    val playlists = jsonPlaylists(preferences.getString("playlists", "[]")).toMutableList()

    fun scan(): List<MusicItem> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf("_id", "_display_name", "_size", "duration", "artist", "album")
        val result = mutableListOf<MusicItem>()
        context.contentResolver.query(
            collection, projection,
            "duration > 30000", null,
            "${MediaStore.Audio.Media.DISPLAY_NAME} COLLATE NOCASE ASC"
        )?.use { cursor ->
            while (cursor.moveToNext() && result.size < 300) {
                val duration = cursor.getLong(3)
                if (duration > 30 * 60 * 1000) continue
                val id = cursor.getLong(0)
                result.add(MusicItem(
                    id, cursor.getString(1) ?: "track_$id",
                    ContentUris.withAppendedId(collection, id).toString(),
                    cursor.getLong(2), duration,
                    cursor.getString(4)?.takeUnless { it.isBlank() || it == "<unknown>" } ?: "未知歌手",
                    cursor.getString(5)?.takeUnless { it.isBlank() || it == "<unknown>" } ?: "未知专辑"
                ))
            }
        }
        return result
    }

    fun remember(item: MusicItem) {
        val items = loadAdded().filterNot { it.uri == item.uri } + item
        saveAdded(items)
    }

    fun library(): List<MusicItem> = (loadAdded() + scan()).distinctBy { it.uri }

    fun cached(): List<MusicItem> = jsonSongs(preferences.getString("songs", "[]"))

    fun saveLibrary(songs: List<MusicItem>) {
        save("songs", JSONArray().apply { songs.forEach { put(songJson(it)) } }.toString())
    }

    fun view(songs: List<MusicItem>): List<MusicItem> {
        val playlist = playlists.getOrNull(playlistIndex)
        val q = query.trim().lowercase()
        val filtered = songs.filter { song ->
            song.duration <= 30 * 60 * 1000 && song.id !in hidden &&
                (playlist == null || if (playlist.rule.isNotEmpty()) matches(song, playlist.rule) else song.id in playlist.ids) &&
                (q.isEmpty() || matches(song, q))
        }
        if (sort != "manual") {
            return filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) {
                when (sort) {
                    "artist" -> "${it.artist} ${it.name}"
                    "album" -> "${it.album} ${it.name}"
                    else -> it.name
                }
            }).let { if (ascending) it else it.reversed() }
        }
        val order = manualOrder.withIndex().associate { it.value to it.index }
        return filtered.sortedBy { order[it.id] ?: Int.MAX_VALUE }
    }

    fun setSort(value: String) {
        if (value == "manual") manualOrder = (manualOrder + cached().map { it.id }).distinct().toMutableList()
        sort = value
        saveOrder()
        preferences.edit().putString("sort", value).apply()
    }

    fun toggleAscending() {
        ascending = !ascending
        preferences.edit().putBoolean("ascending", ascending).apply()
    }

    fun move(from: Int, to: Int, songs: List<MusicItem>) {
        if (from !in songs.indices || to !in songs.indices || from == to) return
        val ids = songs.map { it.id }.toMutableList()
        ids.add(to, ids.removeAt(from))
        sort = "manual"
        manualOrder = (ids + manualOrder).distinct().toMutableList()
        saveOrder()
        preferences.edit().putString("sort", sort).apply()
    }

    fun hide(item: MusicItem) {
        hidden.add(item.id)
        saveHidden()
    }

    fun restoreHidden() {
        hidden.clear()
        saveHidden()
    }

    fun addPlaylist(name: String, songs: List<MusicItem>): Boolean {
        if (name.isBlank() || playlists.size >= 10 || songs.isEmpty()) return false
        playlists.add(Playlist(name.trim(), songs.map { it.id }, query.trim().lowercase()))
        playlistIndex = playlists.lastIndex
        savePlaylists()
        return true
    }

    fun deletePlaylist(index: Int) {
        if (index !in playlists.indices) return
        playlists.removeAt(index)
        playlistIndex = -1
        savePlaylists()
    }

    private fun matches(song: MusicItem, query: String) = "${song.name} ${song.artist} ${song.album}".lowercase().contains(query)
    private fun saveOrder() = save("manual", JSONArray(manualOrder).toString())
    private fun saveHidden() { preferences.edit().putStringSet("hidden", hidden.map { it.toString() }.toSet()).apply() }
    private fun savePlaylists() {
        save("playlists", JSONArray().apply {
            playlists.forEach { put(JSONObject().apply {
                put("name", it.name); put("ids", JSONArray(it.ids)); put("rule", it.rule)
            }) }
        }.toString())
    }
    private fun save(key: String, value: String) { preferences.edit().putString(key, value).apply() }

    private fun loadAdded(): List<MusicItem> {
        val text = preferences.getString("added", "[]") ?: "[]"
        return try {
            val array = JSONArray(text)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                MusicItem(
                    item.optLong("id"), item.getString("name"),
                    item.getString("uri"), item.optLong("size")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveAdded(items: List<MusicItem>) {
        val array = JSONArray()
        items.forEach { array.put(songJson(it)) }
        preferences.edit().putString("added", array.toString()).apply()
    }

    companion object {
        fun songJson(song: MusicItem) = JSONObject().apply {
            put("id", song.id); put("name", song.name); put("uri", song.uri); put("size", song.size)
            put("duration", song.duration); put("artist", song.artist); put("album", song.album)
        }
        fun jsonSongs(text: String?): List<MusicItem> = try {
            val array = JSONArray(text ?: "[]")
            (0 until array.length()).map { array.getJSONObject(it) }.filter { it.optLong("id") != 0L && it.optString("uri").isNotBlank() }.map {
                MusicItem(it.getLong("id"), it.getString("name"), it.getString("uri"), it.optLong("size"),
                    it.optLong("duration"), it.optString("artist", "未知歌手"), it.optString("album", "未知专辑"))
            }
        } catch (_: Exception) { emptyList() }
        private fun jsonPlaylists(text: String?): List<Playlist> = try {
            val array = JSONArray(text ?: "[]")
            (0 until minOf(10, array.length())).map { array.getJSONObject(it) }.map {
                Playlist(it.getString("name"), jsonLongs(it.optJSONArray("ids")?.toString()), it.optString("rule"))
            }
        } catch (_: Exception) { emptyList() }
        private fun jsonLongs(text: String?): List<Long> = try {
            val array = JSONArray(text ?: "[]")
            (0 until array.length()).map { array.getLong(it) }
        } catch (_: Exception) { emptyList() }
    }
}
