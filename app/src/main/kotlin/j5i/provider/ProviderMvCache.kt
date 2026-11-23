package j5i.spatial8d.provider

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import j5i.spatial8d.data.MusicItem
import j5i.spatial8d.videoCacheKey
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
    fun file(context: Context, item: MusicItem, option: ProviderTrackRef.MvOption): File {
        val (source, mvId) = ProviderTrackRef.mvIdentity(item.uri)
        return File(context.filesDir, "video-cache/${videoCacheKey("provider-mv:$source:$mvId:${option.id}")}.mp4")
    }

    @Synchronized
    fun cache(context: Context, item: MusicItem, option: ProviderTrackRef.MvOption, active: () -> Boolean,
        progress: (Long, Long) -> Unit): MusicItem {
        val (source, mvId) = ProviderTrackRef.mvIdentity(item.uri)
        val entry = ProviderPluginStore(context).list().firstOrNull { it.enabled && it.descriptor.id == source }
            ?: error("插件来源不存在或已停用")
        val directory = File(context.filesDir, "video-cache").apply { check(isDirectory || mkdirs()) }
        val target = file(context, item, option)
        check(active()) { "MV 加载已取消" }
        if (!target.isFile || target.length() <= 1024L) {
            ProviderBrokerPolicy.validateUrl(option.url, entry.descriptor.domains, "http-audio" in entry.descriptor.capabilities)
            val part = File(target.path + ".part")
            try {
                val headers = mapOf("Referer" to if (source == "platform.qq") "https://y.qq.com/" else "https://www.kugou.com/")
                ProviderNetwork.open(option.url, "http-audio" in entry.descriptor.capabilities, headers).use { response ->
                    check(response.isSuccessful) { "MV 下载失败（HTTP ${response.code}）" }
                    val body = requireNotNull(response.body) { "MV 响应为空" }
                    val total = body.contentLength()
                    progress(0L, total)
                    body.byteStream().use { input -> part.outputStream().use { output ->
                        copyMvMedia(input, output, total, active, progress)
                    } }
                }
                check(active()) { "MV 加载已取消" }
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(part.absolutePath)
                    val types = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
                    check(types.any { it.startsWith("audio/") } && types.any { it.startsWith("video/") }) { "MV 没有可识别的音视频轨" }
                } finally { extractor.release() }
                check(part.renameTo(target)) { "无法保存 MV 缓存" }
                if (Log.isLoggable("8DProvider", Log.INFO))
                    Log.i("8DProvider", "MV 单次缓存完成 method=cache source=$source mvId=$mvId quality=${option.id} bytes=${target.length()}")
            } finally { part.delete() }
        }
        return item.copy(uri = Uri.fromFile(target).toString(), size = target.length(), album = "在线缓存:$source")
    }
}
