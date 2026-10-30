package jiangyi.spatial8d.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import android.os.Process
import jiangyi.spatial8d.core.PretreatChain
import jiangyi.spatial8d.core.SpacePreset
import jiangyi.spatial8d.core.SpatialRenderer
import jiangyi.spatial8d.core.TransformParams
import jiangyi.spatial8d.core.renderSpatialWav
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.concurrent.Volatile
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

class SpatialPlayer {
    var preset: SpacePreset = SpacePreset(
        "标准绕头 · 默认", "", 8.0, 35.0, 45.0, 10.0, 18.0, 1.2, 0.0, 1.0
    )
        set(value) {
            synchronized(lock) {
                field = value
                val renderer = this.renderer ?: return
                renderer.configure(value)
                renderer.reset()
                restartAt(position)
            }
        }
    var transform = TransformParams()
        set(value) {
            synchronized(lock) {
                if (field == value) return
                val oldActive = field.active
                field = value.copy(
                    speed = value.speed.coerceIn(0.5, 2.0),
                    semi = value.semi.coerceIn(-12.0, 12.0).roundToInt().toDouble()
                )
                renderer?.setDenoise(field.active)
                if (!paused) applyPlaybackParams()
                if (oldActive != field.active) restartAt(position)
            }
        }
    var reverse = false
        set(value) {
            synchronized(lock) {
                if (field == value) return
                field = value
                renderer?.reset()
                restartAt(position)
            }
        }
    var onEnd: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var mono: FloatArray? = null
    private var decoded: jiangyi.spatial8d.core.DecodedPcm? = null
    private var sampleRate = 44100
    private var renderer: SpatialRenderer? = null
    private var track: AudioTrack? = null
    private var worker: Thread? = null
    private var playPos = 0
    private var headBase = 0
    private var paused = true
    private var prepared = false
    @Volatile private var generation = 0
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    val duration: Double get() = mono?.let { it.size.toDouble() / sampleRate } ?: 0.0
    val isPlaying: Boolean get() = prepared && !paused
    val position: Double
        get() {
            val tr = track ?: return playPos.toDouble() / sampleRate
            val current = if (paused) playPos else headBase + tr.playbackHeadPosition
            return (current.toDouble() / sampleRate).coerceIn(0.0, duration)
        }

    fun prepare(mono: FloatArray, sampleRate: Int, decoded: jiangyi.spatial8d.core.DecodedPcm? = null) {
        stop()
        this.mono = mono
        this.decoded = decoded
        this.sampleRate = sampleRate
        this.renderer = SpatialRenderer(sampleRate.toDouble()).also {
            it.configure(preset)
            it.setDenoise(transform.active)
        }
        prepared = true
        playPos = 0
        headBase = 0
    }

    fun play(): Boolean {
        synchronized(lock) {
            if (!prepared) return false
            if (!paused) return true
            if (playPos >= (mono?.size ?: 0)) restartAt(0.0)
            return try {
                paused = false
                if (track == null) buildTrack()
                applyPlaybackParams()
                track?.play()
                (lock as java.lang.Object).notifyAll()
                true
            } catch (e: Exception) {
                report("播放失败: ${e.message}")
                false
            }
        }
    }

    fun pause() {
        synchronized(lock) {
            if (paused) return
            playPos = (headBase + (track?.playbackHeadPosition ?: 0)).coerceAtMost(mono?.size ?: 0)
            paused = true
            try { track?.pause() } catch (_: Exception) {}
        }
    }

    fun toggle(): Boolean {
        if (paused) return play()
        pause()
        return true
    }

    fun seek(seconds: Double) {
        restartAt(seconds)
    }

    fun stop() {
        synchronized(lock) {
            paused = true
            prepared = false
            generation++
            try { track?.stop() } catch (_: Exception) {}
            track?.release()
            track = null
            worker = null
            mono = null
            decoded = null
            renderer = null
            playPos = 0
            headBase = 0
        }
    }

    fun export(
        stream: OutputStream,
        mp3: Boolean = false,
        wavPath: String? = null,
        mp3Path: String? = null,
        onProgress: (Int) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        val source = mono ?: run {
            onDone(false, "没有已加载的音频")
            return
        }
        val sr = sampleRate
        val currentPreset = preset
        val currentTransform = transform
        val currentReverse = reverse
        val rawSource = if (currentPreset.raw && !currentTransform.active) decoded else null
        Thread {
            try {
                val transformed = PretreatChain().run(source, currentTransform) {
                    onProgress(it / 2)
                }
                val renderer = jiangyi.spatial8d.core.SpatialRenderer(sr.toDouble())
                renderer.configure(currentPreset)
                renderer.setDenoise(currentTransform.active)
                val header = java.nio.ByteBuffer.allocate(44)
                jiangyi.spatial8d.core.writeWavHeader(header, transformed.size, sr)
                stream.write(header.array())
                var position = 0
                while (position < transformed.size) {
                    val count = min(4096, transformed.size - position)
                    stream.write(renderer.render(transformed, position, count, currentReverse, rawSource))
                    position += count
                    onProgress(50 + position * 50 / transformed.size)
                }
                stream.flush()
                stream.close()
                if (mp3) {
                    if (wavPath == null || mp3Path == null) error("MP3 缺少临时文件路径")
                    com.jiangyi.spatial8d.Mp3Export.nativeEncode(wavPath, mp3Path)
                }
                main.post { onDone(true, if (mp3) "MP3 导出完成" else "WAV 导出完成") }
            } catch (e: Exception) {
                val message = "导出失败: ${e.message}"
                main.post { onDone(false, message) }
            }
        }.start()
    }

    private fun buildTrack() {
        val source = mono ?: return
        // 旧 worker 使用 generation 退出；先停轨，确保不会和新的输出并存。
        try { track?.stop() } catch (_: Exception) {}
        track?.release()
        track = null
        val minimum = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val buffer = maxOf(minimum * 6, 131072)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(buffer)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        headBase = playPos
        val ownGeneration = generation
        worker = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                val renderer = this.renderer ?: return@Thread
                val fade = ByteArray(OUTPUT_CHUNK * 4)
                var first = true
                while (ownGeneration == generation && playPos < source.size) {
                    synchronized(lock) {
                        while (paused && ownGeneration == generation) (lock as java.lang.Object).wait(200)
                    }
                    if (paused || ownGeneration != generation) continue
                    val n = min(OUTPUT_CHUNK, source.size - playPos)
                    val block = renderer.render(source, playPos, n, reverse, decoded)
                    val bytes = if (first && playPos > 0) {
                        // 短渐入消除 seek/参数切换时旧尾音与新状态的边界爆音。
                        for (i in 0 until n) {
                            val gain = i.toDouble() / min(512, n)
                            val index = i * 4
                            fade[index] = (block[index].toInt() * gain).toInt().toByte()
                            fade[index + 1] = (block[index + 1].toInt() * gain).toInt().toByte()
                            fade[index + 2] = (block[index + 2].toInt() * gain).toInt().toByte()
                            fade[index + 3] = (block[index + 3].toInt() * gain).toInt().toByte()
                        }
                        fade
                    } else block
                    first = false
                    val tr = track ?: break
                    var written = 0
                    while (written < bytes.size && ownGeneration == generation && !paused) {
                        val count = tr.write(bytes, written, bytes.size - written, AudioTrack.WRITE_NON_BLOCKING)
                        if (count > 0) written += count else Thread.sleep(6)
                    }
                    if (ownGeneration != generation || paused) break
                    playPos += n
                }
                if (ownGeneration == generation && playPos >= source.size) {
                    synchronized(lock) { if (ownGeneration == generation) paused = true }
                    main.post { onEnd?.invoke() }
                }
            } catch (e: Exception) {
                report("渲染播放失败: ${e.message}")
            }
        }.also { it.start() }
    }

    private fun applyPlaybackParams() {
        val tr = track ?: return
        try {
            val pitch = (if (transform.tape) transform.speed else 1.0) *
                2.0.pow(transform.semi / 12.0)
            // Android 的 pitch 补偿同样改变时长，speed 再乘一次以抵消它。
            tr.playbackParams = PlaybackParams()
                .setSpeed((transform.speed * pitch).coerceIn(0.25, 4.0).toFloat())
                .setPitch(pitch.coerceIn(0.5, 2.0).toFloat())
        } catch (e: Exception) {
            report("播放参数设置失败: ${e.message}")
        }
    }

    private fun restartAt(seconds: Double) {
        synchronized(lock) {
            if (!prepared) return
            val wasPlaying = !paused
            generation++
            try { track?.stop() } catch (_: Exception) {}
            track?.release()
            track = null
            worker = null
            playPos = (seconds.coerceIn(0.0, duration) * sampleRate).toInt()
                .coerceIn(0, (mono?.size ?: 0))
            headBase = playPos
            paused = !wasPlaying
            if (wasPlaying) {
                buildTrack()
                applyPlaybackParams()
                track?.play()
            }
        }
    }

    private fun report(message: String) {
        main.post { onError?.invoke(message) }
    }

    private companion object {
        const val OUTPUT_CHUNK = 1024
    }
}
