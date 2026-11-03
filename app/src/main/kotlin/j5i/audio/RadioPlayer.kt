package j5i.spatial8d.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.j5i.spatial8d.HttpMediaDataSource
import com.j5i.spatial8d.TsAdtsMediaDataSource
import com.j5i.spatial8d.NativeSpatialPlayer
import com.j5i.spatial8d.PcmWavRecorder
import j5i.spatial8d.core.DEFAULT_SPACE_PRESET
import j5i.spatial8d.core.SpaceEngine
import j5i.spatial8d.core.SpacePreset
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 电台复用本地播放器的 JNI 空间渲染器，但不保留整轨 PCM。
 * HTTP/ICY -> MediaExtractor/MediaCodec -> 8D -> AudioTrack；直播没有 seek 和导出。
 */
class RadioPlayer {
    var preset: SpacePreset = DEFAULT_SPACE_PRESET
        set(value) {
            if (field == value) return
            field = value
            resetRenderer()
        }
    @Volatile var semi: Int = 0
        set(value) {
            field = value.coerceIn(-12, 12)
            if (running && !paused) synchronized(trackLock) { track?.let(::applyPitch) }
        }
    var onReady: (() -> Unit)? = null
    var onEnd: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onReconnecting: ((String) -> Unit)? = null
    val isPlaying get() = running && !paused
    val position get() = playedFrames.toDouble() / sampleRate
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var sampleRate = 44100
    @Volatile private var playedFrames = 0L
    private var source: android.media.MediaDataSource? = null
    private var track: AudioTrack? = null
    // 解码线程写轨道时，UI 线程不能同时 pause/release；代际标记只负责废弃旧任务。
    private val trackLock = Any()
    private var renderer = 0L
    private val rendererLock = Any()
    private var recorder: PcmWavRecorder? = null
    // FM 与 UTS 一样使用持续 SpaceEngine，而不是本地整轨渲染的淡出/输出限幅链。
    private var engine: SpaceEngine? = null
    private val mono = DoubleArray(CHUNK)
    private val right = DoubleArray(CHUNK)
    private val outL = DoubleArray(CHUNK)
    private val outR = DoubleArray(CHUNK)
    private val pcm = java.nio.ByteBuffer.allocate(CHUNK * 8).order(ByteOrder.LITTLE_ENDIAN)
    private var hpCoef = 0.0
    private var envAttack = 0.0
    private var envRelease = 0.0
    private var gainAttack = 0.0
    private var gainRelease = 0.0
    private var hpXL = 0.0
    private var hpXR = 0.0
    private var hpYL = 0.0
    private var hpYR = 0.0
    private var envelope = 0.0
    private var noiseGain = 1.0

    fun load(url: String) {
        stop()
        val own = ++generation
        running = true
        paused = false
        playedFrames = 0
        Thread({ run(url, own) }, "spatial-radio").start()
    }
    @Volatile var denoise = 65
        set(value) { field = value.coerceIn(0, 100) }

    fun play(): Boolean {
        if (!running) return false
        try {
            synchronized(trackLock) {
                track?.play()
                track?.let(::applyPitch)
            }
        } catch (_: Exception) { return false }
        paused = false
        return true
    }

    fun pause() {
        paused = true
        try { synchronized(trackLock) { track?.pause() } } catch (_: Exception) {}
    }

    fun stop() {
        running = false
        generation++
        paused = false
        try { source?.close() } catch (_: Exception) {}
        source = null
        releaseTrack()
        resetRenderer()
        resetNoiseReducer()
    }

    fun startRecording(path: String): Boolean {
        if (recorder?.isRecording == true || !running) return false
        val writer = PcmWavRecorder()
        if (!writer.start(path, sampleRate, 2, 16)) return false
        recorder = writer
        return true
    }

    fun stopRecording() {
        val writer = recorder
        recorder = null
        writer?.stop()
    }

    private fun run(url: String, own: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        var failures = 0
        while (own == generation && running) {
            var producedAudio = false
            try {
                producedAudio = session(url, own)
                if (own == generation && running) {
                    running = false
                    releaseTrack()
                    main.post { if (own == generation) onEnd?.invoke() }
                }
                return
            } catch (e: Exception) {
                if (own != generation || !running) return
                // 尚未出声时不重复消耗同一坏地址，交给页面立即尝试下一路源。
                if (!producedAudio) {
                    running = false
                    releaseTrack()
                    main.post { if (own == generation) onError?.invoke("连接失败：${e.message}") }
                    return
                }
                if (++failures >= 3) {
                    running = false
                    releaseTrack()
                    main.post { if (own == generation) onError?.invoke("电台连接中断：${e.message}") }
                    return
                }
                main.post { if (own == generation) onReconnecting?.invoke("电台连接中断，正在重连 $failures/2") }
                Thread.sleep(500)
            }
        }
    }

    private fun session(url: String, own: Int): Boolean {
        val stream = if (url.substringBefore('?').endsWith(".m3u8", ignoreCase = true))
            TsAdtsMediaDataSource(url) else HttpMediaDataSource(url)
        source = stream
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var ready = false
        try {
            extractor.setDataSource(stream)
            // MediaExtractor 探测直播时没有可靠的系统超时，到期关闭数据源打断建链。
            main.postDelayed({
                if (!ready && own == generation && running && source === stream) stream.close()
            }, if (url.contains(".m3u8")) 20_000 else 8_000)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("流里没有音频轨")
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("音频格式不可识别")
            sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            extractor.selectTrack(index)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            buildTrack(own)
            ready = true
            main.post { if (own == generation) onReady?.invoke() }
            val info = MediaCodec.BufferInfo()
            var floating = false
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var eos = false
            while (own == generation && running && !eos) {
                if (paused) { Thread.sleep(15); continue }
                val input = codec.dequeueInputBuffer(10_000)
                if (input >= 0) {
                    val buffer = codec.getInputBuffer(input)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    else {
                        codec.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                val output = codec.dequeueOutputBuffer(info, 10_000)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val changed = codec.outputFormat
                    channels = changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floating = changed.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        changed.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (output >= 0) {
                    val buffer = codec.getOutputBuffer(output)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        buffer.order(ByteOrder.LITTLE_ENDIAN)
                        val bytes = ByteArray(info.size)
                        buffer.get(bytes)
                        renderAndWrite(bytes, channels, floating, own)
                        ready = true
                    }
                    codec.releaseOutputBuffer(output, false)
                    eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                }
            }
            return ready
        } finally {
            if (!ready) stream.close()
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
            if (source === stream) source = null
            stream.close()
        }
    }

    private fun renderAndWrite(pcm: ByteArray, channels: Int, floating: Boolean, own: Int) {
        val bpf = channels * if (floating) 4 else 2
        var offset = 0
        while (offset + bpf <= pcm.size && own == generation && running) {
            // 暂停时保留当前块和 offset；恢复后接着写，不能丢 PCM 又推进时间轴。
            if (paused) { Thread.sleep(15); continue }
            val frames = minOf(CHUNK, (pcm.size - offset) / bpf)
            val chunk = if (offset == 0 && frames * bpf == pcm.size) pcm else pcm.copyOfRange(offset, offset + frames * bpf)
            val output = synchronized(rendererLock) { renderChunk(chunk, bpf, channels, floating, frames) }
            val audio = track ?: return
            var written = 0
            while (written < output.size && own == generation && running && !paused) {
                val n = synchronized(trackLock) {
                    if (own != generation || track !== audio || !running || paused) -1
                    else audio.write(output, written, output.size - written)
                }
                if (n < 0 && (own != generation || track !== audio || !running || paused)) return
                if (n < 0) error("AudioTrack 写入失败：$n")
                written += n
            }
            if (own != generation || !running || paused) return
            recorder?.write(output, 0, output.size)
            playedFrames += frames
            offset += frames * bpf
        }
    }

    private fun createRenderer(): Long {
        val p = preset
        return NativeSpatialPlayer.create(sampleRate.toDouble(), p.raw, p.period, p.elev, p.depth,
            p.phase, p.direction, p.width, p.room, p.rt60, p.wet, denoise)
    }

    private fun renderChunk(chunk: ByteArray, bpf: Int, channels: Int, floating: Boolean, frames: Int): ByteArray {
        val p = preset
        pcm.clear()
        pcm.put(chunk, 0, frames * bpf)
        for (i in 0 until frames) {
            val offset = i * bpf
            val left: Double
            val r: Double
            pcm.position(offset)
            if (floating) {
                left = pcm.float.toDouble()
                r = if (channels > 1) pcm.float.toDouble() else left
            } else {
                left = pcm.short / 32768.0
                r = if (channels > 1) pcm.short / 32768.0 else left
            }
            mono[i] = (left + r) * 0.5
            right[i] = r
        }
        if (p.raw) {
            if (renderer == 0L) renderer = createRenderer()
            check(renderer != 0L) { "空间渲染器初始化失败" }
            return NativeSpatialPlayer.renderStream(renderer, chunk, bpf, channels, floating, frames, playedFrames)
        }
        val current = engine ?: SpaceEngine(sampleRate.toDouble()).also {
            it.setSpace(p.period, p.elev, p.depth, p.phase, p.direction, p.width, false)
            it.setRoom(p.room, p.rt60, p.wet)
            engine = it
        }
        current.process(mono, outL, outR, frames, playedFrames.toDouble() / sampleRate)
        var peak = 0.0
        for (i in 0 until frames) peak = max(peak, max(abs(outL[i]), abs(outR[i])))
        val gain = if (peak > 0.98) 0.98 / peak else 1.0
        val output = ByteArray(frames * 4)
        for (i in 0 until frames) {
            var l = outL[i] * gain
            var r = outR[i] * gain
            if (denoise > 0) {
                val xl = l
                val xr = r
                val yl = hpCoef * (hpYL + xl - hpXL)
                val yr = hpCoef * (hpYR + xr - hpXR)
                hpXL = xl; hpXR = xr; hpYL = yl; hpYR = yr
                val level = max(abs(yl), abs(yr))
                val rate = if (level > envelope) envAttack else envRelease
                envelope = envelope * rate + level * (1.0 - rate)
                val floor = 1.0 - denoise / 100.0 * 0.10
                val wanted = if (envelope < 0.004) floor + (1.0 - floor) * (envelope / 0.004).pow(2.0) else 1.0
                val smoothing = if (wanted < noiseGain) gainAttack else gainRelease
                noiseGain = noiseGain * smoothing + wanted * (1.0 - smoothing)
                l = yl * noiseGain; r = yr * noiseGain
            }
            val lo = (l.coerceIn(-1.0, 1.0) * 32767.0).roundToInt()
            val ro = (r.coerceIn(-1.0, 1.0) * 32767.0).roundToInt()
            output[i * 4] = lo.toByte(); output[i * 4 + 1] = (lo shr 8).toByte()
            output[i * 4 + 2] = ro.toByte(); output[i * 4 + 3] = (ro shr 8).toByte()
        }
        return output
    }

    private fun resetRenderer() = synchronized(rendererLock) {
        releaseRenderer()
        engine = null
    }
    private fun releaseRenderer() {
        if (renderer != 0L) NativeSpatialPlayer.release(renderer)
        renderer = 0L
    }

    private fun resetNoiseReducer() {
        hpCoef = exp(-2.0 * Math.PI * 30.0 / sampleRate)
        envAttack = exp(-1.0 / (0.005 * sampleRate))
        envRelease = exp(-1.0 / (0.080 * sampleRate))
        gainAttack = exp(-1.0 / (0.012 * sampleRate))
        gainRelease = exp(-1.0 / (0.250 * sampleRate))
        hpXL = 0.0; hpXR = 0.0; hpYL = 0.0; hpYR = 0.0
        envelope = 0.0; noiseGain = 1.0
    }

    private fun buildTrack(own: Int) {
        synchronized(trackLock) {
            releaseTrack()
            val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(maxOf(min * 2, CHUNK * 16)).setTransferMode(AudioTrack.MODE_STREAM).build().also {
                    check(it.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack 初始化失败" }
                    if (running && !paused && own == generation) {
                        it.play()
                        applyPitch(it)
                    }
                }
        }
    }

    private fun applyPitch(track: AudioTrack) {
        try {
            track.playbackParams = PlaybackParams().setSpeed(1.0f)
                .setPitch(2.0.pow(semi / 12.0).toFloat())
        } catch (e: Exception) {
            main.post { onError?.invoke("电台变声设置失败：${e.message}") }
        }
    }

    private fun releaseTrack() = synchronized(trackLock) {
        val old = track ?: return@synchronized
        track = null
        try { old.pause(); old.flush(); old.stop() } catch (_: Exception) {}
        old.release()
    }

    private companion object { const val CHUNK = 4096 }
}
