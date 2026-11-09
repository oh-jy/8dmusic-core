package j5i.spatial8d.provider

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import j5i.spatial8d.data.MusicItem
import org.json.JSONObject
import java.io.File

/** Provider audio is cached locally only after an explicit play/save/favorite action. */
internal object ProviderAudioCache {
    fun cached(context: Context, item: MusicItem): MusicItem? {
        val file = privateFile(context, item).takeIf { it.isFile && it.length() > 1024 }
        if (file != null) return item.copy(uri = android.net.Uri.fromFile(file).toString(), size = file.length(), album = cacheAlbum(item))
        if (Build.VERSION.SDK_INT >= 29) {
            val name = fileName(item)
            context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.SIZE),
                "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.IS_PENDING}=0",
                arrayOf(name, "Download/bili/"), null)?.use { cursor ->
                if (cursor.moveToFirst() && cursor.getLong(1) > 1024) {
                    return item.copy(uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(0)).toString(), size = cursor.getLong(1), album = cacheAlbum(item))
                }
            }
        }
        return null
    }

    fun cache(context: Context, item: MusicItem, public: Boolean): MusicItem {
        cached(context, item)?.let { cached ->
            // A private favorite may later be explicitly published to
            // Download; do not silently skip that request.
            if (!public || Build.VERSION.SDK_INT < 29 || cached.uri.startsWith("content://")) return cached
        }
        val source = if (ProviderTrackRef.isOnline(item)) ProviderTrackRef.resolve(context, item) else item
        require(source.uri.startsWith("https://") || source.uri.startsWith("http://")) { "缓存只接受 HTTP(S) 音频" }
        val part = File(context.cacheDir, "provider-${item.id}.part")
        val target = privateFile(context, item)
        try {
            val pluginId = source.album.substringAfter("plugin:https:", "")
            val entry = if (pluginId.isNotEmpty()) ProviderPluginStore(context).list().first { it.enabled && it.descriptor.id == pluginId } else null
            val allowHttp = entry != null || source.album.startsWith("provider:http:")
            val headers = if (entry != null) {
                buildMap {
                    if ("referer" in entry.descriptor.capabilities) {
                        put("Referer", "https://www.bilibili.com/")
                        put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
                    }
                    val cookie = ProviderCredentials(context).get(pluginId)
                    if (cookie.isNotEmpty()) put("Cookie", cookie)
                }
            } else emptyMap()
            ProviderNetwork.open(source.uri, allowHttp, headers).use { response ->
                check(response.isSuccessful) { "音频缓存失败（HTTP ${response.code}）" }
                requireNotNull(response.body).byteStream().use { input -> part.outputStream().use { input.copyTo(it) } }
            }
            check(part.length() > 1024) { "音频缓存内容为空" }
            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(part.absolutePath)
                check((0 until extractor.trackCount).any {
                    extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }) { "缓存内容不是可识别的音频" }
            } finally { extractor.release() }
            if (public && Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName(item))
                    put(MediaStore.Downloads.MIME_TYPE, "audio/mp4")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/bili/")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
                try {
                    requireNotNull(resolver.openOutputStream(uri, "w")).use { output -> part.inputStream().use { it.copyTo(output) } }
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                    return item.copy(uri = uri.toString(), size = part.length(), album = cacheAlbum(item))
                } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
            }
            target.parentFile?.mkdirs()
            check(part.renameTo(target)) { "无法保存音频缓存" }
            return item.copy(uri = android.net.Uri.fromFile(target).toString(), size = target.length(), album = cacheAlbum(item))
        } finally { part.delete() }
    }

    fun delete(context: Context, item: MusicItem): Boolean {
        var deleted = privateFile(context, item).delete()
        if (Build.VERSION.SDK_INT >= 29) {
            val uri = cached(context, item)?.uri ?: return deleted
            if (uri.startsWith("content://")) deleted = context.contentResolver.delete(android.net.Uri.parse(uri), null, null) > 0 || deleted
        }
        return deleted
    }

    private fun privateFile(context: Context, item: MusicItem) = File(context.filesDir, "provider-audio/${fileName(item)}")
    private fun cacheAlbum(item: MusicItem): String = if (item.uri.contains("source=platform.bilibili"))
        "在线缓存:platform.bilibili" else "在线缓存"
    private fun fileName(item: MusicItem): String {
        return "${item.id}.m4a"
    }
}
