package j5i.spatial8d.feature.playback
import j5i.spatial8d.feature.ui.*
import j5i.spatial8d.feature.source.*
import j5i.spatial8d.feature.video.*
import j5i.spatial8d.feature.update.*
import j5i.spatial8d.feature.cache.*
import j5i.spatial8d.MainActivity
import android.content.ContentValues
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer

internal fun MainActivity.exportMvAudioPublic(source: Uri, title: String) {
    if (exporting) { toast(t("mv.export.busy")); return }
    val name = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).ifBlank { "MV" }
    if (Build.VERSION.SDK_INT < 29) {
        pendingMvAudio = source
        saveMvAudio.launch("$name.m4a")
        return
    }
    val values = ContentValues().apply {
        put(MediaStore.Audio.Media.DISPLAY_NAME, "$name.m4a")
        put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
        put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/8D")
        put(MediaStore.Audio.Media.IS_PENDING, 1)
    }
    val destination = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
    if (destination == null) { toast(t("mv.export.create_failed")); return }
    exportMvAudio(source, destination, true)
}

internal fun MainActivity.exportMvAudio(source: Uri, destination: Uri, pending: Boolean) {
    if (exporting) {
        if (pending) contentResolver.delete(destination, null, null)
        toast(t("mv.export.busy"))
        return
    }
    exporting = true
    toast(t("mv.export.progress"))
    Thread {
        var temporary: File? = null
        val result = runCatching {
            val output = File.createTempFile("mv-audio-", ".m4a", cacheDir).also { temporary = it }
            val extractor = MediaExtractor()
            var muxer: MediaMuxer? = null
            var started = false
            try {
                extractor.setDataSource(this, source, null)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm"
                } ?: error(t("mv.export.no_aac"))
                extractor.selectTrack(track)
                val writer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxer = writer
                val outputTrack = writer.addTrack(extractor.getTrackFormat(track))
                writer.start(); started = true
                val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
                val info = MediaCodec.BufferInfo()
                var samples = 0
                while (true) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    check(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { t("mv.export.encrypted") }
                    check(size <= buffer.capacity()) { t("mv.export.frame_large") }
                    info.set(0, size, extractor.sampleTime.coerceAtLeast(0), 0)
                    writer.writeSampleData(outputTrack, buffer, info)
                    samples++
                    extractor.advance()
                }
                check(samples > 0) { t("mv.export.empty") }
                writer.stop(); started = false
            } finally {
                if (started) runCatching { muxer?.stop() }
                muxer?.release()
                extractor.release()
            }
            val stream = contentResolver.openOutputStream(destination) ?: error(t("mv.export.write_failed"))
            stream.use { target -> output.inputStream().use { it.copyTo(target) } }
            if (pending) contentResolver.update(destination, ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }, null, null)
        }
        temporary?.delete()
        if (result.isFailure && pending) runCatching { contentResolver.delete(destination, null, null) }
        main.post {
            exporting = false
            result.onSuccess { toast(t("mv.export.done")) }
                .onFailure { toast("${t("mv.export.failed")}: ${errorDetail(it)}") }
        }
    }.start()
}
