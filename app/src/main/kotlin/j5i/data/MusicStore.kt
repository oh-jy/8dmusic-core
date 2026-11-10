package j5i.spatial8d.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import j5i.spatial8d.data.FAVORITES_INDEX
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 本机歌曲索引、手动添加歌曲、队列视图与播放列表的持久化入口。 */
class MusicStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("spatial8d", Context.MODE_PRIVATE)
    init { splitOnlineReferences() }
    var sort = preferences.getString("sort", "name") ?: "name"
        private set
    var ascending = preferences.getBoolean("ascending", true)
        private set
    var query = ""
    var playlistIndex = -1
    var manualOrder = jsonLongs(preferences.getString("manual", "[]")).toMutableList()
        private set
    val playlists = jsonPlaylists(preferences.getString("playlists", "[]")).toMutableList()
    private val favorites = jsonLongs(preferences.getString("favorites", "[]")).toMutableSet()

    fun isFavorite(id: Long) = id in favorites

    fun scan(): List<MusicItem> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            "_id", "_display_name", "_size", "duration", "artist", "album",
            MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.DATE_MODIFIED
        )
        val result = mutableListOf<MusicItem>()
        context.contentResolver.query(
            collection, projection,
            "duration > 30000", null,
            "${MediaStore.Audio.Media.DISPLAY_NAME} COLLATE NOCASE ASC"
        )?.use { cursor ->
            while (cursor.moveToNext() && result.size < 300) {
                val duration = cursor.getLong(3)
                val id = cursor.getLong(0)
                if ((cursor.getString(1) ?: "").startsWith("BV")) continue
                result.add(MusicItem(
                    id, cursor.getString(1) ?: "track_$id",
                    ContentUris.withAppendedId(collection, id).toString(),
                    cursor.getLong(2), duration,
                    cursor.getString(4)?.takeUnless { it.isBlank() || it == "<unknown>" } ?: "未知歌手",
                    cursor.getString(5)?.takeUnless { it.isBlank() || it == "<unknown>" } ?: "未知专辑",
                    cursor.getLong(6) * 1000L,
                    cursor.getLong(7) * 1000L
                ))
            }
        }
        return result
    }

    fun remember(item: MusicItem) {
        val now = System.currentTimeMillis()
        val old = loadAdded().firstOrNull { it.uri == item.uri }
        val enriched = item.copy(
            addedAt = old?.addedAt ?: now,
            modifiedAt = now
        )
        val items = loadAdded().filterNot { it.uri == item.uri } + enriched
        saveAdded(items)
    }

    fun rememberAll(newItems: List<MusicItem>) {
        if (newItems.isEmpty()) return
        val now = System.currentTimeMillis()
        val existing = loadAdded().associateBy { it.id }
        val merged = newItems.map { item ->
            item.copy(addedAt = existing[item.id]?.addedAt ?: now, modifiedAt = now)
        }
        saveAdded((loadAdded().filterNot { old -> newItems.any { it.id == old.id } } + merged).distinctBy { it.id })
    }

    fun library(): List<MusicItem> = (loadAdded() + scan()).filterNot(::isBiliAudio).distinctBy { it.uri }

    fun mergeScan(fresh: List<MusicItem>): List<MusicItem> = (loadAdded() + fresh).filterNot(::isBiliAudio).distinctBy { it.uri }

    private fun isBiliAudio(item: MusicItem) = item.album == "B站音频" || item.name.startsWith("BV")

    /**
     * MediaStore/SAF 的 content Uri 不一定允许随机读取和重复打开。
     * 按歌曲 id 缓存到应用私有目录，解码器后续只读这份稳定副本，绝不改源文件。
     */
    fun readableUri(item: MusicItem): Uri {
        // 应用私有目录里已落盘的文件（如哔哩哔哩保存的 m4a）直接读取，无需再复制。
        if (item.uri.startsWith("file://")) return Uri.parse(item.uri)
        val cached = File(context.filesDir, "picked").listFiles()?.firstOrNull {
            it.name.startsWith("music_${item.id}.") && it.length() > 1024
        }
        if (cached != null) return Uri.fromFile(cached)
        // MediaStore 本地音频可被 MediaExtractor 直接随机读取，切歌不再复制整首文件。
        // 手动 SAF 文件仍使用私有副本，保证授权 Uri 重复打开时的稳定性。
        if (item.uri.startsWith("content://media/")) return Uri.parse(item.uri)
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

    /** 仅修改可写歌曲的标签；保持源 URI 与播放队列不变，并同步应用内索引。 */
    fun updateMetadata(item: MusicItem, title: String, artist: String, album: String): MusicItem {
        require(title.isNotBlank()) { "歌名不能为空" }
        val extension = item.name.substringAfterLast('.', "").lowercase()
        // content Uri 的 path 是媒体库逻辑路径，不能交给 RandomAccessFile。
        // 标签写入应用私有副本，源文件无需 SAF/MediaStore 写权限。
        val target = File(File(context.filesDir, "picked").apply { mkdirs() }, "music_${item.id}.$extension")
        if (!target.exists() || target.length() <= 1024) {
            context.contentResolver.openInputStream(Uri.parse(item.uri)).use { input ->
                requireNotNull(input) { "打不开音频流" }
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val path = target.absolutePath
        when (extension) {
            "mp3" -> writeId3(path, title.trim(), artist.trim(), album.trim())
            "flac" -> writeFlac(path, title.trim(), artist.trim(), album.trim())
            else -> error("暂支持 MP3 / FLAC 标签修正")
        }
        val next = item.copy(name = title.trim(), artist = artist.trim(), album = album.trim())
        val updated = cached().map { if (it.uri == item.uri) next else it }
        saveLibrary(updated)
        remember(next)
        return next
    }

    private fun writeId3(path: String, title: String, artist: String, album: String) {
        val file = java.io.RandomAccessFile(path, "rw")
        file.use { output ->
            val header = ByteArray(10)
            output.readFully(header)
            check(header.copyOfRange(0, 3).contentEquals("ID3".toByteArray())) { "MP3 没有可编辑的 ID3 标签" }
            val oldSize = ((header[6].toInt() and 127) shl 21) or ((header[7].toInt() and 127) shl 14) or
                ((header[8].toInt() and 127) shl 7) or (header[9].toInt() and 127)
            val flags = header.copyOfRange(0, 6)
            fun frame(id: String, text: String): ByteArray {
                val value = text.toByteArray(Charsets.UTF_16LE)
                val payload = byteArrayOf(3, 0) + value + byteArrayOf(0, 0)
                val head = id.toByteArray(Charsets.US_ASCII) + ByteBuffer.allocate(4).putInt(payload.size).array() + byteArrayOf(0, 0, 0, 0)
                return head + payload
            }
            val tag = frame("TIT2", title) + frame("TPE1", artist) + frame("TALB", album)
            val rest = ByteArray((output.length() - 10L - oldSize).toInt())
            output.seek(10L + oldSize)
            output.readFully(rest)
            output.seek(0)
            output.write(flags + byteArrayOf(((tag.size shr 21) and 127).toByte(), ((tag.size shr 14) and 127).toByte(),
                ((tag.size shr 7) and 127).toByte(), (tag.size and 127).toByte()))
            output.write(tag)
            output.write(rest)
            output.setLength(10L + tag.size + rest.size)
        }
    }

    private fun writeFlac(path: String, title: String, artist: String, album: String) {
        val file = java.io.RandomAccessFile(path, "rw")
        file.use { output ->
            check(output.readInt() == 0x664c6143) { "不是 FLAC 文件" }
            var comment: ByteArray? = null
            var commentAt = 0L
            var commentHeader = 0
            var last = false
            while (!last) {
                val head = output.readByte().toInt()
                last = head and 128 != 0
                val type = head and 127
                val length = (output.readUnsignedByte() shl 16) or (output.readUnsignedByte() shl 8) or output.readUnsignedByte()
                if (type == 4) {
                    commentAt = output.filePointer - 4
                    commentHeader = head
                    comment = ByteArray(length)
                    output.readFully(comment)
                } else output.skipBytes(length)
            }
            check(comment != null) { "FLAC 没有可编辑的 Vorbis 标签" }
            val fields = mutableListOf("TITLE=$title", "ARTIST=$artist", "ALBUM=$album")
            val vendor = "8D Spatial Audio".toByteArray()
            val body = ByteBuffer.allocate(8 + vendor.size + fields.sumOf { 4 + it.toByteArray().size }).order(ByteOrder.LITTLE_ENDIAN)
            body.putInt(vendor.size).put(vendor).putInt(fields.size)
            fields.forEach { val bytes = it.toByteArray(); body.putInt(bytes.size).put(bytes) }
            val block = body.array()
            require(block.size <= comment!!.size) { "FLAC 标签空间不足，无法安全写回" }
            output.seek(commentAt)
            output.writeByte(commentHeader)
            output.writeByte(((block.size shr 16) and 255).toByte().toInt())
            output.writeByte(((block.size shr 8) and 255).toByte().toInt())
            output.writeByte((block.size and 255).toByte().toInt())
            output.write(block)
            output.write(ByteArray(comment.size - block.size))
        }
    }

    fun cached(): List<MusicItem> = jsonSongs(preferences.getString("songs", "[]")).filterNot(::isBiliAudio)

    fun saveLibrary(songs: List<MusicItem>) {
        save("songs", JSONArray().apply { songs.forEach { put(songJson(it)) } }.toString())
    }

    /** 纯在线引用（online://）独立存储，不进入本地歌曲库；播放时再按需解析真实地址。 */
    fun onlineCached(): List<MusicItem> = jsonSongs(preferences.getString("onlineSongs", "[]"))

    fun rememberOnline(items: List<MusicItem>) {
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        val existing = onlineCached().associateBy { it.id }
        val merged = items.map { item ->
            item.copy(addedAt = existing[item.id]?.addedAt ?: now, modifiedAt = now)
        }
        val next = (onlineCached().filterNot { old -> items.any { it.id == old.id } } + merged).distinctBy { it.id }
        save("onlineSongs", JSONArray().apply { next.forEach { put(songJson(it)) } }.toString())
    }

    fun removeOnline(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val removed = ids.toSet()
        val next = onlineCached().filterNot { it.id in removed }
        save("onlineSongs", JSONArray().apply { next.forEach { put(songJson(it)) } }.toString())
    }

    data class OnlineQueue(val items: List<MusicItem>, val currentId: Long?, val providerId: String)

    fun saveOnlineQueue(items: List<MusicItem>, currentId: Long?, providerId: String) {
        if (items.isEmpty()) { preferences.edit().remove("onlineQueue").apply(); return }
        preferences.edit().putString("onlineQueue", JSONObject().apply {
            put("items", JSONArray().apply { items.forEach { put(songJson(it)) } })
            put("currentId", currentId ?: JSONObject.NULL)
            put("providerId", providerId)
        }.toString()).apply()
    }

    fun readOnlineQueue(): OnlineQueue? {
        val text = preferences.getString("onlineQueue", null) ?: return null
        return runCatching {
            val root = JSONObject(text)
            OnlineQueue(
                jsonSongs(root.optJSONArray("items")?.toString()),
                if (root.isNull("currentId")) null else root.optLong("currentId"),
                root.optString("providerId")
            )
        }.getOrNull()?.takeIf { it.items.isNotEmpty() }
    }

    fun clearOnlineQueue() { preferences.edit().remove("onlineQueue").apply() }

    fun view(songs: List<MusicItem>): List<MusicItem> {
        val playlist = playlists.getOrNull(playlistIndex)
        val favoritesView = playlistIndex == FAVORITES_INDEX
        // 本地“全部”视图只展示本机歌曲；打开自建歌单时合并在线引用，歌单里的在线歌才能解析出来。
        val pool = if (playlist == null) songs else (songs + onlineCached()).distinctBy { it.id }
        val q = sanitizeQuery(query).trim().lowercase()
        val filtered = pool.filter { song ->
            (if (favoritesView) song.id in favorites
                else playlist == null || if (playlist.rule.isNotEmpty()) matches(song, playlist.rule) else song.id in playlist.ids) &&
            (q.isEmpty() || matches(song, q))
        }
        if (sort != "manual") {
            val sorted = if (sort == "time") {
                // 主按加入媒体库时间，相同时再按文件修改时间；时间缺失的排最后。
                filtered.sortedWith(compareBy({ it.addedAt }, { it.modifiedAt }))
            } else {
                // ICU Collator 让中文按拼音排序，与 UTS 的 Locale.CHINA 行为一致。
                val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
                filtered.sortedWith(compareBy(collator) {
                    when (sort) {
                        "artist" -> "${it.artist} ${it.name}"
                        "album" -> "${it.album} ${it.name}"
                        else -> it.name
                    }
                })
            }
            return if (ascending) sorted else sorted.reversed()
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

    /** 永久移出本机索引、自建列表与收藏；不提供恢复。 */
    fun removePermanently(item: MusicItem) {
        val library = cached().filterNot { it.uri == item.uri }
        saveLibrary(library)
        removeOnline(listOf(item.id))
        val added = loadAdded().filterNot { it.uri == item.uri }
        saveAdded(added)
        playlists.indices.forEach { index ->
            val playlist = playlists[index]
            playlists[index] = playlist.copy(ids = playlist.ids.filterNot { it == item.id }, rule = "")
        }
        savePlaylists()
        favorites.remove(item.id)
        save("favorites", JSONArray(favorites.toList()).toString())
        manualOrder = manualOrder.filterNot { it == item.id }.toMutableList()
        saveOrder()
        dropCache(item)
    }

    /** 仅从当前自定义播放列表移除；搜索型列表固化为快照，避免刷新后再次出现。 */
    fun removeFromCurrentPlaylist(item: MusicItem) {
        val index = playlistIndex
        if (index !in playlists.indices) return
        val playlist = playlists[index]
        playlists[index] = playlist.copy(ids = playlist.ids.filterNot { it == item.id }, rule = "")
        savePlaylists()
    }

    fun addPlaylist(name: String, songs: List<MusicItem>): Boolean {
        if (name.isBlank() || playlists.size >= 10 || songs.isEmpty()) return false
        // 带过滤词的是搜索型列表，后续扫描到匹配歌曲会自动纳入。
        playlists.add(Playlist(name.trim(), songs.map { it.id }, query.trim().lowercase()))
        playlistIndex = playlists.lastIndex
        savePlaylists()
        return true
    }

    fun addToPlaylist(index: Int, songs: List<MusicItem>): Int {
        if (index !in playlists.indices || songs.isEmpty()) return 0
        val playlist = playlists[index]
        val existing = playlist.ids.toSet()
        val added = songs.filter { it.id !in existing }.map { it.id }.distinct()
        if (added.isEmpty()) return 0
        playlists[index] = playlist.copy(ids = playlist.ids + added, rule = "")
        savePlaylists()
        return added.size
    }

    fun deletePlaylist(index: Int) {
        if (index !in playlists.indices) return
        val removedIds = playlists[index].ids
        playlists.removeAt(index)
        playlistIndex = -1
        savePlaylists()
        val liveIds = playlists.flatMap { it.ids }.toSet() + favorites
        removeOnline(removedIds.filter { it !in liveIds && onlineCached().any { online -> online.id == it } })
    }

    fun toggleFavorite(id: Long) {
        val next = if (id in favorites) favorites - id else favorites + id
        favorites.clear()
        favorites.addAll(next)
        save("favorites", JSONArray(next.toList()).toString())
    }


    private fun matches(song: MusicItem, query: String) = "${song.name} ${song.artist} ${song.album}".lowercase().contains(sanitizeQuery(query))
    private fun sanitizeQuery(value: String) = value.filterNot {
        // 清理输入法可能提交的零宽/控制字符，避免不可见过滤词让列表永远为空。
        it in charArrayOf('\u200b', '\u200c', '\u200d', '\u2060', '\ufeff', '\u00ad') || (it.code < 0x20 && it != '\t')
    }
    private fun saveOrder() = save("manual", JSONArray(manualOrder).toString())

    /** 一次性迁移：历史版本把纯在线引用写进了本地库，拆出到 onlineSongs，歌单 id 引用保持不变。 */
    private fun splitOnlineReferences() {
        if (preferences.getBoolean("online_split_v1", false)) return
        val local = cached()
        val moved = local.filter { it.uri.startsWith("online://") }
        if (moved.isNotEmpty()) {
            rememberOnline(moved)
            saveLibrary(local.filterNot { it.uri.startsWith("online://") })
            saveAdded(loadAdded().filterNot { it.uri.startsWith("online://") })
        }
        preferences.edit().putBoolean("online_split_v1", true).apply()
    }
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
                    item.getString("uri"), item.optLong("size"),
                    item.optLong("duration"), item.optString("artist", "未知歌手"),
                    item.optString("album", "未知专辑"), item.optLong("addedAt"),
                    item.optLong("modifiedAt")
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
            put("addedAt", song.addedAt); put("modifiedAt", song.modifiedAt)
        }
        fun jsonSongs(text: String?): List<MusicItem> = try {
            val array = JSONArray(text ?: "[]")
            (0 until array.length()).map { array.getJSONObject(it) }.filter { it.optLong("id") != 0L && it.optString("uri").isNotBlank() }.map {
                MusicItem(it.getLong("id"), it.getString("name"), it.getString("uri"), it.optLong("size"),
                    it.optLong("duration"), it.optString("artist", "未知歌手"), it.optString("album", "未知专辑"),
                    it.optLong("addedAt"), it.optLong("modifiedAt"))
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
