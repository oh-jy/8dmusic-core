package jiangyi.spatial8d.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 本机歌曲索引、手动添加歌曲、队列视图与播放列表的持久化入口。 */
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

    fun mergeScan(fresh: List<MusicItem>): List<MusicItem> = (loadAdded() + fresh).distinctBy { it.uri }

    /**
     * MediaStore/SAF 的 content Uri 不一定允许随机读取和重复打开。
     * 按歌曲 id 缓存到应用私有目录，解码器后续只读这份稳定副本，绝不改源文件。
     */
    fun readableUri(item: MusicItem): Uri {
        val dir = File(context.filesDir, "picked").apply { mkdirs() }
        val extension = item.name.substringAfterLast('.', "mp3").lowercase()
        val target = File(dir, "music_${item.id}.$extension")
        if (target.exists() && target.length() > 1024) return Uri.fromFile(target)
        context.contentResolver.openInputStream(Uri.parse(item.uri)).use { input ->
            requireNotNull(input) { "打不开音频流" }
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return Uri.fromFile(target)
    }

    fun dropCache(item: MusicItem) {
        File(context.filesDir, "picked").listFiles()?.forEach {
            if (it.name.startsWith("music_${item.id}.")) it.delete()
        }
    }

    fun cached(): List<MusicItem> = jsonSongs(preferences.getString("songs", "[]"))

    fun saveLibrary(songs: List<MusicItem>) {
        save("songs", JSONArray().apply { songs.forEach { put(songJson(it)) } }.toString())
    }

    fun view(songs: List<MusicItem>): List<MusicItem> {
        val playlist = playlists.getOrNull(playlistIndex)
        val q = sanitizeQuery(query).trim().lowercase()
        val filtered = songs.filter { song ->
            song.duration <= 30 * 60 * 1000 && song.id !in hidden &&
                (playlist == null || if (playlist.rule.isNotEmpty()) matches(song, playlist.rule) else song.id in playlist.ids) &&
                (q.isEmpty() || matches(song, q))
        }
        if (sort != "manual") {
            // ICU Collator 让中文按拼音排序，与 UTS 的 Locale.CHINA 行为一致。
            val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
            return filtered.sortedWith(compareBy(collator) {
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
        adoptManualOrder(ids)
        commitManualOrder()
    }

    /** 首次拖动采用当前可见顺序，隐藏项原位保留。 */
    fun adoptManualOrder(visibleIds: List<Long>) {
        val visible = visibleIds.toSet()
        var next = 0
        manualOrder = buildList {
            manualOrder.forEach { id ->
                if (id in visible) add(visibleIds[next++]) else add(id)
            }
            while (next < visibleIds.size) add(visibleIds[next++])
        }.toMutableList()
        sort = "manual"
    }

    fun commitManualOrder() {
        saveOrder()
        preferences.edit().putString("sort", "manual").apply()
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
        // 带过滤词的是搜索型列表，后续扫描到匹配歌曲会自动纳入。
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

    fun removeFromPlaylist(index: Int, item: MusicItem) {
        val playlist = playlists.getOrNull(index) ?: return
        // 搜索型列表移除一首后固化为快照，否则刷新时该歌曲会再次出现。
        playlists[index] = playlist.copy(ids = playlist.ids.filterNot { it == item.id }, rule = "")
        savePlaylists()
    }

    private fun matches(song: MusicItem, query: String) = "${song.name} ${song.artist} ${song.album}".lowercase().contains(sanitizeQuery(query))
    private fun sanitizeQuery(value: String) = value.filterNot {
        // 清理输入法可能提交的零宽/控制字符，避免不可见过滤词让列表永远为空。
        it in charArrayOf('\u200b', '\u200c', '\u200d', '\u2060', '\ufeff', '\u00ad') || (it.code < 0x20 && it != '\t')
    }
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
