package j5i.spatial8d.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaDataSource
import android.net.Uri

import j5i.spatial8d.core.PcmBuffer
import java.nio.ByteOrder

class AudioDecoder(private val context: Context) {
    /**
     * MediaExtractor + MediaCodec 顺序解码，调用方必须在后台线程执行。
     * 首个有效 PCM 输出就回调 onHead，后续继续抬高同一 PCM 缓冲的水位；
     * isActive 对应页面加载代号，阻止旧歌的迟到回调污染新曲。
     */
    fun decode(uri: Uri, onProgress: (Int) -> Unit, isActive: () -> Boolean = { true },
        onHead: (PcmBuffer, Long) -> Unit = { _, _ -> },
        onFormat: (PcmBuffer) -> Unit = {}): PcmBuffer = decode(
        { extractor -> extractor.setDataSource(context, uri, null) }, onProgress, isActive, onHead, onFormat)

    /** 直播使用滑动 HTTP 数据源；其余解码流程与本地文件完全一致。 */
    fun decode(source: MediaDataSource, onProgress: (Int) -> Unit, isActive: () -> Boolean = { true },
        onHead: (PcmBuffer, Long) -> Unit = { _, _ -> },
        onFormat: (PcmBuffer) -> Unit = {}): PcmBuffer = decode(
        { extractor -> extractor.setDataSource(source) }, onProgress, isActive, onHead, onFormat)

    private fun decode(setSource: (MediaExtractor) -> Unit, onProgress: (Int) -> Unit,
        isActive: () -> Boolean, onHead: (PcmBuffer, Long) -> Unit,
        onFormat: (PcmBuffer) -> Unit): PcmBuffer {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var lastPercent = -1
        fun reportProgress(percent: Int) {
            if (percent == lastPercent) return
            lastPercent = percent
            onProgress(percent)
        }
        try {
            setSource(extractor)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: error("没有音频轨")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("音频格式不可识别")
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs > 30L * 60L * 1_000_000L) error("音频超过 30 分钟")
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var sampleRate = 0
            var channels = 0
            var floating = false
            var output: PcmBuffer? = null
            var headPublished = false
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!isActive()) error("已取消旧音频解码")
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
                        output = PcmBuffer(sampleRate, channels, floating, durationUs)
                        onFormat(output!!)
                    }
                    else -> if (index >= 0) {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            // MediaCodec 的 PCM 是小端；按原字节保留 16bit/float 和声道布局。
                            buffer.order(ByteOrder.LITTLE_ENDIAN)
                            val bytes = ByteArray(info.size)
                            buffer.get(bytes)
                            output?.append(bytes)
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
            check(channels in 1..8 && sampleRate > 0) { "不支持的音频声道或采样率" }
            reportProgress(100)
            // 水位置完成前不能把播完或导出当成安全操作。
            return (output ?: error("音频没有 PCM 输出")).also { it.decodeDone = true }
        } catch (e: Exception) {
            throw IllegalStateException("解码失败: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }
}
