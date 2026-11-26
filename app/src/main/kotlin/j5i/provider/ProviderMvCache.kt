package j5i.spatial8d.provider

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import j5i.spatial8d.data.MusicItem
import j5i.spatial8d.feature.cache.*
import j5i.spatial8d.feature.cache.isVideoCacheName
import j5i.spatial8d.ui.AppLanguage
import j5i.spatial8d.feature.video.videoCacheKey
import java.io.File
import java.io.InputStream
import java.io.OutputStream

internal fun copyMvMedia(input: InputStream, output: OutputStream, total: Long,
    active: () -> Boolean, progress: (Long, Long) -> Unit): Long {
    val buffer = ByteArray(64 * 1024)
    var copied = 0L
    var reported = -1L
    while (true) {
        check(active()) { "MV 加载已取消" }
        val count = input.read(buffer)
        if (count < 0) break
        output.write(buffer, 0, count)
        copied += count
        if (copied / (256 * 1024) != reported) {
            reported = copied / (256 * 1024)
            progress(copied, total)
        }
    }
    check(total <= 0L || copied == total) { "MV 下载不完整，请重试" }
    return copied
}

internal object ProviderMvCache {
    fun delete(context: Context, file: File): Boolean {
        val directory = File(context.filesDir, "video-cache")
        if (!PlaybackCacheMetadata.tryBegin(file)) return false
        try {
            return if (!file.isFile || !isVideoCacheName(file.name) ||
                file.canonicalFile.parentFile != directory.canonicalFile) false
            else file.delete().also { if (it) PlaybackCacheMetadata.remove(context, file) }
        } finally { PlaybackCacheMetadata.end(file) }
    }

    fun file(context: Context, item: MusicItem, option: ProviderTrackRef.MvOption): File {
        val (source, mvId) = ProviderTrackRef.mvIdentity(item.uri)
        return File(context.filesDir, "video-cache/${videoCacheKey("provider-mv:$source:$mvId:${option.id}")}.mp4")
    }

    @Synchronized
    fun cache(context: Context, item: MusicItem, option: ProviderTrackRef.MvOption, active: () -> Boolean,
        progress: (Long, Long) -> Unit): MusicItem {
        val (source, mvId) = ProviderTrackRef.mvIdentity(item.uri)
        val entry = ProviderPluginStore(context).list().firstOrNull { it.enabled && it.descriptor.id == source }
            ?: error(AppLanguage.text(context, "provider.error.plugin_unavailable"))
        val directory = File(context.filesDir, "video-cache").apply { check(isDirectory || mkdirs()) }
        val target = file(context, item, option)
        check(PlaybackCacheMetadata.tryBegin(target)) { AppLanguage.text(context, "cache.protected") }
        try {
        check(active()) { AppLanguage.text(context, "provider.error.mv_cancelled") }
        if (!target.isFile || target.length() <= 1024L) {
            ProviderBrokerPolicy.validateUrl(option.url, entry.descriptor.domains, "http-audio" in entry.descriptor.capabilities)
            val part = File(target.path + ".part")
            try {
                val headers = mapOf("Referer" to when (source) {
                    "platform.qq" -> "https://y.qq.com/"
                    "platform.netease" -> "https://music.163.com/"
                    else -> "https://www.kugou.com/"
                })
                ProviderNetwork.open(option.url, "http-audio" in entry.descriptor.capabilities, headers).use { response ->
                    check(response.isSuccessful) { AppLanguage.text(context, "provider.error.mv_http").format(response.code) }
                    val body = requireNotNull(response.body) { AppLanguage.text(context, "provider.error.mv_empty") }
                    val total = body.contentLength()
                    progress(0L, total)
                    body.byteStream().use { input -> part.outputStream().use { output ->
                        copyMvMedia(input, output, total, active, progress)
                    } }
                }
                check(active()) { AppLanguage.text(context, "provider.error.mv_cancelled") }
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(part.absolutePath)
                    val types = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
                    check(types.any { it.startsWith("audio/") } && types.any { it.startsWith("video/") }) { AppLanguage.text(context, "provider.error.mv_tracks") }
                } finally { extractor.release() }
                check(part.renameTo(target)) { AppLanguage.text(context, "provider.error.mv_save") }
                if (Log.isLoggable("8DProvider", Log.INFO))
                    Log.i("8DProvider", "MV 单次缓存完成 method=cache source=$source mvId=$mvId quality=${option.id} bytes=${target.length()}")
            } finally { part.delete() }
        }
        PlaybackCacheMetadata.put(context, target, item.name, source, option.label)
        return item.copy(uri = Uri.fromFile(target).toString(), size = target.length(), album = "在线缓存:$source")
        } finally { PlaybackCacheMetadata.end(target) }
    }
}
