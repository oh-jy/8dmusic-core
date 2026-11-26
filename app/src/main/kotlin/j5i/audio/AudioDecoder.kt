package j5i.spatial8d.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaDataSource
import android.net.Uri
import com.j5i.spatial8d.HttpMediaDataSource

import j5i.spatial8d.core.PcmBuffer
import j5i.spatial8d.ui.AppLanguage
import java.io.File
import java.nio.ByteOrder

internal class DecodeCancelledException : IllegalStateException()
private class AudioRequestException(message: String, cause: Throwable) : IllegalStateException(message, cause)

class AudioDecoder(private val context: Context) {
    private fun t(key: String) = AppLanguage.text(context, key)
    /**
     * MediaExtractor + MediaCodec 顺序解码，调用方必须在后台线程执行。
     * 首个有效 PCM 输出就回调 onHead，后续继续抬高同一 PCM 缓冲的水位；
     * isActive 对应页面加载代号，阻止旧歌的迟到回调污染新曲。
     */
    fun decode(uri: Uri, onProgress: (Int) -> Unit, isActive: () -> Boolean = { true },
        onHead: (PcmBuffer, Long) -> Unit = { _, _ -> },
        onFormat: (PcmBuffer) -> Unit = {}, startPositionSec: Double = 0.0): PcmBuffer = decode(
        { extractor -> extractor.setDataSource(context, uri, null) }, onProgress, isActive, onHead, onFormat,
        (startPositionSec * 1_000_000L).toLong().coerceAtLeast(0L))

    /** 直播使用滑动 HTTP 数据源；其余解码流程与本地文件完全一致。 */
    fun decode(source: MediaDataSource, onProgress: (Int) -> Unit, isActive: () -> Boolean = { true },
        onHead: (PcmBuffer, Long) -> Unit = { _, _ -> },
        onFormat: (PcmBuffer) -> Unit = {}): PcmBuffer = decode(
        { extractor ->
            if (source is HttpMediaDataSource) {
                try {
                    source.readAt(0, ByteArray(1), 0, 1)
                } catch (e: java.io.IOException) {
                    throw AudioRequestException("${t("decoder.request.failed")}: ${e.message ?: t("decoder.connection.failed")}", e)
                }
            }
            extractor.setDataSource(source)
        }, onProgress, isActive, onHead, onFormat)

    private fun decode(setSource: (MediaExtractor) -> Unit, onProgress: (Int) -> Unit,
        isActive: () -> Boolean, onHead: (PcmBuffer, Long) -> Unit,
        onFormat: (PcmBuffer) -> Unit, startUs: Long = 0L): PcmBuffer {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var lastPercent = -1
        fun reportProgress(percent: Int) {
            if (percent == lastPercent) return
            lastPercent = percent
            onProgress(percent)
        }
        var output: PcmBuffer? = null
        try {
            setSource(extractor)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: error(t("decoder.no_track"))
            extractor.selectTrack(track)
            if (startUs > 0L) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error(t("radio.invalid_format"))
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (mime in setOf("audio/mpeg-L1", "audio/mpeg-L2")) {
                return decodeMpeg(extractor, format, durationUs, startUs, ::reportProgress, isActive, onHead, onFormat)
            }
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var sampleRate = 0
            var channels = 0
            var floating = false
            var headPublished = false
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!isActive()) throw DecodeCancelledException()
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index) ?: continue
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                            if (durationUs > 0 && extractor.sampleTime > 0) {
                                reportProgress((extractor.sampleTime * 100 / durationUs).toInt().coerceIn(0, 95))
                            }
                        }
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val changed = codec.outputFormat
                        sampleRate = changed.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floating = changed.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            changed.getInteger(MediaFormat.KEY_PCM_ENCODING) == 4
                        val baseFrame = if (startUs > 0L) (startUs * sampleRate / 1_000_000L).toInt() else 0
                        output = PcmBuffer(sampleRate, channels, floating, durationUs,
                            File(context.cacheDir, "decoded-pcm"), baseFrame = baseFrame)
                        onFormat(output!!)
                    }
                    else -> if (index >= 0) {
                        val buffer = codec.getOutputBuffer(index)
                        val beforeStart = startUs > 0L && info.presentationTimeUs < startUs
                        if (buffer != null && info.size > 0 && !beforeStart) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            // MediaCodec 的 PCM 是小端；按原字节保留 16bit/float 和声道布局。
                            buffer.order(ByteOrder.LITTLE_ENDIAN)
                            val bytes = ByteArray(info.size)
                            buffer.get(bytes)
                            // 窗口写满时保留 codec output buffer，等播放线程释放旧 PCM 后重试同一块。
                            while (output?.append(bytes) == false) {
                                if (!isActive()) throw DecodeCancelledException()
                                Thread.sleep(16)
                            }
                            if (!headPublished && output != null && output!!.frames > 0) {
                                headPublished = true
                                onHead(output!!, durationUs)
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            if (sampleRate == 0) {
                sampleRate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
            check(channels in 1..8 && sampleRate > 0) { t("decoder.unsupported") }
            reportProgress(100)
            // 水位置完成前不能把播完或导出当成安全操作。
            return (output ?: error(t("decoder.no_pcm"))).also { it.decodeDone = true }
        } catch (e: Exception) {
            output?.close()
            if (e is DecodeCancelledException || e is AudioRequestException) throw e
            throw IllegalStateException("${t("decoder.failed")}: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }

    private fun decodeMpeg(extractor: MediaExtractor, format: MediaFormat, durationUs: Long, startUs: Long,
        onProgress: (Int) -> Unit, isActive: () -> Boolean, onHead: (PcmBuffer, Long) -> Unit,
        onFormat: (PcmBuffer) -> Unit): PcmBuffer {
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        check(sampleRate > 0 && channels in 1..2) { t("decoder.unsupported") }
        val handle = com.j5i.spatial8d.MpegAudioDecoder.create()
        check(handle != 0L) { t("decoder.unsupported") }
        var pcm: PcmBuffer? = null
        try {
            val output = PcmBuffer(sampleRate, channels, false, durationUs, File(context.cacheDir, "decoded-pcm"),
                baseFrame = (startUs * sampleRate / 1_000_000L).toInt())
            pcm = output
            onFormat(output)
            val packet = java.nio.ByteBuffer.allocate(65_536)
            var headPublished = false
            while (true) {
                if (!isActive()) throw DecodeCancelledException()
                packet.clear()
                val size = extractor.readSampleData(packet, 0)
                val packetTime = extractor.sampleTime
                val input = if (size < 0) ByteArray(0) else ByteArray(size).also { packet.get(it) }
                val bytes = com.j5i.spatial8d.MpegAudioDecoder.decode(handle, input, channels)
                    ?: error(t("decoder.no_pcm"))
                if (size < 0 || startUs == 0L || packetTime >= startUs) {
                    while (!output.append(bytes)) {
                        if (!isActive()) throw DecodeCancelledException()
                        Thread.sleep(16)
                    }
                    if (!headPublished && output.frames > 0) {
                        headPublished = true
                        onHead(output, durationUs)
                    }
                }
                if (size < 0) break
                if (durationUs > 0) onProgress((packetTime * 100 / durationUs).toInt().coerceIn(0, 95))
                extractor.advance()
            }
            check(headPublished) { t("decoder.no_pcm") }
            output.decodeDone = true
            onProgress(100)
            return output
        } catch (exception: Exception) {
            pcm?.close()
            throw exception
        } finally { com.j5i.spatial8d.MpegAudioDecoder.release(handle) }
    }
}
