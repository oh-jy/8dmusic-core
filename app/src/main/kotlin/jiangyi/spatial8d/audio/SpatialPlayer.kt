package jiangyi.spatial8d.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.PowerManager
import android.util.Log
import com.jiangyi.spatial8d.NativePreprocess
import com.jiangyi.spatial8d.NativeSpatialExport
import com.jiangyi.spatial8d.NativeSpatialPlayer
import com.jiangyi.spatial8d.PcmWavRecorder
import jiangyi.spatial8d.core.PcmBuffer
import jiangyi.spatial8d.core.SpacePreset
import jiangyi.spatial8d.core.TransformParams
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.Volatile
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 原生 Android 播放链路，对应 UTS 的 platform/android-player.uts。
 * 后台线程做 HRTF/混响预渲染，音频线程只读磁盘 PCM 并写 AudioTrack，
 * 避免音频回调同时承担重 DSP 导致欠载、咔哒声和播放拖慢。
 */
class SpatialPlayer(context: Context) {
    var preset = SpacePreset("标准绕头 · 默认", "", 8.0, 35.0, 45.0, 10.0, 18.0, 1.2, 0.0, 1.0)
        set(value) {
            if (field == value) return
            field = value
            scheduleParams()
        }
    var transform = TransformParams()
        set(value) {
            val next = value.copy(speed = value.speed.coerceIn(0.5, 2.0),
                semi = value.semi.coerceIn(-12.0, 12.0).roundToInt().toDouble(), denoise = value.denoiseLevel)
            if (field == next) return
            val changed = field.active != next.active || field.denoise != next.denoise
            field = next
            applyPlaybackParams()
            if (changed) invalidate(position)
        }
    var reverse = false
        set(value) { if (field != value) { field = value; invalidate(position) } }
    var onEnd: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onPcm: ((ByteArray, Int) -> Unit)? = null

    private val lock = Any()
    // AudioTrack 的写、flush、play 和 release 必须互斥，代际标记本身不能保护 native 调用。
    private val trackLock = Any()
    private val main = Handler(Looper.getMainLooper())
    // Service 构造期间尚未 attach，直接保存传入 Context，不能提前读 applicationContext。
    private val appContext = context
    private val wakeLock by lazy {
        appContext.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "spatial8d:AudioTrack").apply {
                setReferenceCounted(false)
            }
    }
    private var decoded: PcmBuffer? = null
    private var remoteDurationSec = 0.0
    private var sampleRate = 44100
    private var track: AudioTrack? = null
    @Volatile private var cache: RenderCache? = null
    private var prepared = false
    private var paused = true
    private var playPos = 0
    // 已写入不等于已听到；进度、暂停和播完判定以 AudioTrack 播放头为准。
    private var headBase = 0
    private var pausedHead = 0
    private var trackStarted = false
    @Volatile private var outputThread: Thread? = null
    private var playedOnce = false
    private var recorder: PcmWavRecorder? = null
    // 每次 seek/切歌/缓存失效递增代号，旧线程和旧回调立即作废。
    @Volatile private var generation = 0
    private val cacheEpoch = AtomicLong()
    private val cacheStart = AtomicLong()
    private val params = Runnable { invalidate(position) }

    @SuppressLint("WakelockTimeout")
    private fun syncWakeLock() {
        // 播放期间持续持有；暂停、自然播完和停止均立即释放。
        if (prepared && !paused) {
            if (!wakeLock.isHeld) wakeLock.acquire()
        } else if (wakeLock.isHeld) wakeLock.release()
    }

    val duration get() = decoded?.let {
        if (it.decodeDone) it.durationSec else maxOf(it.estimatedDurationSec, remoteDurationSec)
    } ?: 0.0
    val isPrepared get() = prepared
    val isPlaying get() = prepared && !paused
    val isDecodeDone get() = decoded?.decodeDone == true
    val position: Double
        get() {
            if (!paused && trackStarted) {
                val heard = (headBase + (track?.playbackHeadPosition ?: 0)).toDouble() / sampleRate
                // 切歌/换轨的瞬时旧播放头不能把时间钳到新曲末尾。
                if (heard >= 0.0 && heard <= duration + 0.2) return heard
            }
            return ((if (paused) pausedHead else playPos).toDouble() / sampleRate).coerceIn(0.0, duration)
        }

    fun prepare(source: PcmBuffer, knownDurationSec: Double = 0.0) {
        stop()
        // 切歌保持同一个 AudioTrack，避免 PCM 尚未到达时 mixer 停轨/起轨爆音。
        if (track != null && sampleRate != source.sampleRate) releaseTrack()
        decoded = source
        remoteDurationSec = knownDurationSec.coerceAtLeast(0.0)
        sampleRate = source.sampleRate
        cacheStart.set(0)
        trackStarted = false
        prepared = true
        playedOnce = false
        syncWakeLock()
    }

    fun play(): Boolean = synchronized(lock) {
        if (!prepared || !paused) return@synchronized prepared
        val source = decoded ?: return@synchronized false
        if (playPos >= source.frames) { playPos = 0; headBase = 0 }
        try {
            paused = false
            playedOnce = true
            if (track == null) synchronized(trackLock) { buildTrack() }
            synchronized(trackLock) { applyPlaybackParams() }
            if (trackStarted) synchronized(trackLock) { track?.play() }
            startWorkers()
            syncWakeLock()
            true
        } catch (e: Exception) {
            paused = true
            syncWakeLock()
            report("播放失败: ${e.message}")
            false
        }
    }

    fun pause() = synchronized(lock) {
        if (paused) return@synchronized
        val heard = if (trackStarted) synchronized(trackLock) {
            // 先取硬件播放头再 pause；pause 后部分 ROM 会立即把 head 清零。
            headBase + (track?.playbackHeadPosition ?: 0)
        } else playPos
        if (heard > 0 || playPos <= 0) pausedHead = heard
        paused = true
        try { synchronized(trackLock) { track?.pause() } } catch (_: Exception) {}
        syncWakeLock()
    }

    fun toggle() = if (paused) play() else pause().let { true }
    fun seek(seconds: Double, userInitiated: Boolean = false) = invalidate(seconds, userInitiated)
    fun replay(forcePlay: Boolean = false): Boolean {
        // 自然播完会先清 playedOnce；自动续播必须显式要求重新起播。
        val resume = playedOnce || forcePlay
        val source = decoded
        stop()
        if (source != null) prepare(source)
        return if (resume) play() else true
    }

    fun stop() {
        main.removeCallbacks(params)
        synchronized(lock) {
            generation++
            cacheEpoch.incrementAndGet()
            prepared = false
            paused = true
            playedOnce = false
            closeCache()
            synchronized(trackLock) {
                track?.let {
                    try { it.pause(); it.flush(); it.setVolume(1f) } catch (_: Exception) {}
                }
            }
            trackStarted = false
            decoded = null
            playPos = 0
            headBase = 0
            pausedHead = 0
            syncWakeLock()
        }
    }

    /** 服务真正销毁时才释放输出轨道；普通切歌和 stop 保持轨道复用。 */
    fun close() {
        stop()
        releaseTrack()
    }

    private fun scheduleParams() {
        // 合并连续滑块事件，避免每个像素都重建 JNI 渲染器和缓存。
        main.removeCallbacks(params)
        main.postDelayed(params, 48)
    }

    private fun invalidate(seconds: Double, restartTrack: Boolean = false) {
        main.removeCallbacks(params)
        synchronized(lock) {
            if (!prepared) return
            val resume = !paused
            // 流式解码尚未到达目标时保留 seek 位置，等待 PCM 到位而不是跳回开头。
            playPos = (seconds.coerceIn(0.0, duration) * sampleRate).toInt().coerceAtLeast(0)
            pausedHead = playPos
            headBase = playPos
            cacheStart.set(playPos.toLong())
            cacheEpoch.incrementAndGet()
            val own = ++generation
            closeCache()
            if (resume && restartTrack) {
                // OPPO 的 seek flush 后需要一次真实的暂停/恢复切换；只建轨道并立即 play 不够。
                // 短延迟可取消，连续 seek 只恢复最后一代，也不会覆盖随后发生的手动暂停。
                paused = true
                syncWakeLock()
                releaseTrack()
                buildTrack()
                main.postDelayed({
                    synchronized(lock) {
                        if (own == generation && prepared && paused) play()
                    }
                }, SEEK_RESUME_DELAY_MS)
            } else synchronized(trackLock) { track?.let {
                try {
                    // flush 清掉旧参数/旧位置的缓冲，否则切换后还会残留旧声音。
                    it.pause(); it.flush()
                    // 新缓存达到 START_FRAMES 后由输出线程统一起播，避免空轨欠载。
                    trackStarted = false
                } catch (_: Exception) {}
            } }
            if (resume && !restartTrack) startWorkers()
        }
    }

    private fun buildTrack() {
        try { track?.setVolume(1f) } catch (_: Exception) {}
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0) { "AudioTrack 不支持采样率 $sampleRate" }
        track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(maxOf(min * 2, OUTPUT_CHUNK * 16))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        check(track?.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack 初始化失败" }
        trackStarted = false
        synchronized(trackLock) { applyPlaybackParams() }
    }

    private fun applyPlaybackParams() {
        // 某些机型在暂停状态设置 PlaybackParams 会意外起播，恢复时再统一应用。
        if (paused || !trackStarted) return
        // speed 保持音高；磁带模式把速度叠加到音高；semi 再按十二平均律换算。
        val pitch = ((if (transform.tape) transform.speed else 1.0) *
            2.0.pow(transform.semi / 12.0)).coerceIn(0.5, 2.0)
        try { track?.playbackParams = PlaybackParams().setSpeed(transform.speed.toFloat()).setPitch(pitch.toFloat()) }
        catch (e: Exception) { report("播放参数设置失败: ${e.message}") }
    }

    private fun startWorkers() = synchronized(lock) {
        if (!prepared || paused || outputThread?.isAlive == true) return
        // 旧线程退出后只接管最新代，不能让两个线程同时写一个 AudioTrack。
        val own = generation
        // 同代暂停恢复复用缓存和混响状态；seek/切参已先关闭缓存，仍会新建渲染器。
        outputThread = Thread({ renderLoop(own, cache != null) }, "spatial-output").also { it.start() }
    }

    private fun renderLoop(own: Int, resumeCache: Boolean) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        try {
            val output = ByteArray(OUTPUT_CHUNK * 4)
            while (own == generation && prepared) {
                if (paused) { Thread.sleep(12); continue }
                // 倒放必须知道真实曲尾，流式解码阶段不能拿当前水位当最后一帧。
                if (reverse && decoded?.decodeDone != true) { Thread.sleep(30); continue }
                val epoch = cacheEpoch.get()
                if (cache?.epoch != epoch && !resumeCache) {
                    closeCache()
                    cache = createCache(own, epoch) ?: run { Thread.sleep(6); continue }
                    // 切换缓存只换数据源，不清空本代已经写入轨道的播放头。
                }
                val current = cache ?: break
                val audio = track ?: break
                if (own == generation && track === audio && !paused && trackStarted && audioIsStopped()) {
                    // 缓存切换可能让 ROM 把轨道留在 STOPPED，write 不写错但也不出声。
                    recoverStoppedTrack(own, audio, current)
                }
                val produced = current.produced.get()
                if (produced <= current.consumed) {
                    if (current.done.get() && drainAndFinish(own, current)) break
                    Thread.sleep(4)
                    continue
                }
                val count = min(OUTPUT_CHUNK, produced - current.consumed)
                if (!current.read(output, count * 4)) { Thread.sleep(4); continue }
                var written = 0
                while (written < count * 4 && own == generation && !paused) {
                    val n = synchronized(trackLock) {
                        if (own != generation || track !== audio || paused) -1
                        else audio.write(output, written, count * 4 - written)
                    }
                    if (n < 0 && (own != generation || track !== audio || paused)) break
                    if (n < 0) error("AudioTrack 写入失败: $n")
                    written += n
                }
                if (own != generation || paused) continue
                onPcm?.invoke(output, count * 4)
                // 在最终 8D PCM 写出后 tee 一份，录音与耳机听到的效果完全一致。
                recorder?.write(output, 0, count * 4)
                current.consumed += count
                playPos = current.startFrame + current.consumed
                // 先写入短预滚再 play，兼顾起播延迟和 AudioTrack 不欠载。
                if (!trackStarted && current.consumed >= min(START_FRAMES, current.produced.get())) {
                    trackStarted = true
                    // AudioTrack 从首次 play 才计数；此前预写帧已经进入轨道，不能漏算播放头。
                    // 预写帧仍包含在 playbackHeadPosition 中，不能重复计入。
                    headBase = current.startFrame
                    applyPlaybackParams()
                    synchronized(trackLock) {
                        if (own == generation && track === audio && !paused) audio.play()
                    }
                }
                if (current.done.get() && current.consumed >= current.produced.get() && drainAndFinish(own, current)) break
            }
        } catch (e: Exception) {
            if (own == generation) report("渲染播放失败: ${e.message}")
        } finally {
            // 旧线程退出时不能清掉已经启动的新一代输出线程。
            val restart = synchronized(lock) {
                if (outputThread === Thread.currentThread()) outputThread = null
                shouldRestartOutput(own, generation, prepared, paused, outputThread?.isAlive == true)
            }
            // 连续切参时旧线程可能活着挡住 startWorkers；退出后必须接管最新代际。
            if (restart) startWorkers()
        }
    }

    private fun recoverStoppedTrack(own: Int, audio: AudioTrack, current: RenderCache) {
        // STOPPED 后 playbackHeadPosition 已清零；先回到真实已听位置，再重建缓存。
        // 已写但未听到的 PCM 必须丢弃，不能把写入进度冒充听到位置。
        synchronized(lock) {
            if (own != generation || track !== audio || paused) return
            val heard = synchronized(trackLock) { headBase + audio.playbackHeadPosition }
                .coerceIn(0, decoded?.frames ?: 0)
            generation++
            cacheEpoch.incrementAndGet()
            playPos = heard
            pausedHead = heard
            headBase = heard
            cacheStart.set(heard.toLong())
            closeCache()
            synchronized(trackLock) {
                if (track !== audio) return
                try { audio.pause(); audio.flush() } catch (_: Exception) {}
            }
            trackStarted = false
            startWorkers()
        }
    }

    private fun createCache(own: Int, epoch: Long): RenderCache? {
        val source = decoded ?: return null
        val start = cacheStart.get().toInt().coerceIn(0, source.frames)
        if (start >= source.frames && !source.decodeDone) return null
        val p = preset
        val native = NativeSpatialPlayer.create(sampleRate.toDouble(), p.raw, p.period, p.elev,
            p.depth, p.phase, p.direction, p.width, p.room, p.rt60, p.wet, transform.denoiseLevel)
        if (native == 0L) return null
        val file = File.createTempFile("spatial-render-", ".pcm")
        val result = RenderCache(epoch, start, file, native)
        Thread({ prerollLoop(own, result) }, "spatial-preroll").start()
        return result
    }

    private fun prerollLoop(own: Int, current: RenderCache) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        try {
            while (own == generation && cache === current && !current.done.get()) {
                val source = decoded ?: break
                val start = current.startFrame + current.produced.get()
                if (start >= source.frames) {
                    if (source.decodeDone) current.finish() else Thread.sleep(8)
                    continue
                }
                val count = min(CHUNK, source.frames - start)
                val input = source.copyFrames(start, count)
                val rendered = current.render {
                    // 每块使用稳定快照：解码扩容后仍读取新 PCM，不持有旧数组。
                    NativeSpatialPlayer.render(current.native, input,
                        source.bytesPerFrame, source.channels, source.floatingPoint, 0, count, reverse,
                        source.frames.toLong(), current.startFrame.toLong(), current.produced.get())
                }
                current.output.write(rendered)
                // 写完文件再发布水位，输出线程绝不会读到尚未生成的 PCM。
                current.produced.addAndGet(count)
                val lead = current.produced.get() - current.consumed
                // 只领先约 2 秒，避免后台一次算完整轨并与音频线程抢 CPU。
                if (lead > sampleRate * 2) Thread.sleep((count * 800L / sampleRate).coerceIn(8, 250))
            }
        } catch (e: Exception) {
            if (own == generation) report("后台渲染失败: ${e.message}")
        } finally {
            current.writerFinished()
        }
    }

    private fun drainAndFinish(own: Int, current: RenderCache): Boolean {
        val source = decoded ?: return false
        if (!source.decodeDone || current.startFrame + current.produced.get() < source.frames) return false
        // 文件读完后仍要等播放头排干，不能提前数百毫秒自动切下一首。
        if (trackStarted && lastHeardFrame() + OUTPUT_CHUNK < playPos) return false
        if (own != generation) return true
        paused = true
        playedOnce = false
        syncWakeLock()
        main.post { if (own == generation) onEnd?.invoke() }
        return true
    }

    private fun lastHeardFrame() = if (trackStarted) synchronized(trackLock) {
        headBase + (track?.playbackHeadPosition ?: 0)
    } else playPos

    private fun audioIsStopped() = track?.playState == AudioTrack.PLAYSTATE_STOPPED

    private fun closeCache() {
        val old = cache ?: return
        cache = null
        old.closeInput()
        // 活跃写入线程完成当前块后自行关流删文件，主线程不能与其竞争。
        old.discardWhenIdle()
    }

    private fun releaseTrack() {
        synchronized(trackLock) {
            val old = track ?: return
            track = null
            // 直接销毁仍在发声的轨道会把波形硬截断。先用短斜坡落到静音再停轨。
            if (trackStarted && old.playState == AudioTrack.PLAYSTATE_PLAYING) {
                try {
                    repeat(8) { step ->
                        old.setVolume(1f - (step + 1) / 8f)
                        Thread.sleep(2)
                    }
                } catch (_: Exception) {}
            }
            try { old.pause(); old.flush(); old.stop() } catch (_: Exception) {}
            old.release()
            trackStarted = false
        }
    }

    private fun report(message: String) = main.post { onError?.invoke(message) }

    fun startRecording(path: String): Boolean {
        if (!isPlaying || recorder?.isRecording == true) return false
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

    fun export(stream: OutputStream, mp3: Boolean = false, wavPath: String? = null,
        mp3Path: String? = null, onProgress: (Int) -> Unit, onDone: (Boolean, String) -> Unit) {
        val source = decoded
        if (source == null) { onDone(false, "没有已加载的音频"); return }
        // 立即回报失败并关闭 SAF 输出流，避免界面只留下一个空文档。
        fun fail(message: String) {
            try { stream.close() } catch (_: Exception) {}
            main.post { onDone(false, message) }
        }
        // 参数和 PCM 在任务启动时快照，导出与用户后续切参、播放互不影响。
        val p = preset
        val tr = transform
        val rev = reverse
        val sr = sampleRate
        Thread {
            var temp: File? = null
            try {
                val decodeStarted = System.currentTimeMillis()
                while (!source.decodeDone) {
                    if (decoded !== source) {
                        fail("当前音频已切换，请重新导出")
                        return@Thread
                    }
                    if (System.currentTimeMillis() - decodeStarted > 10 * 60_000L) {
                        fail("音频解码超时，请确认能完整播放后再导出")
                        return@Thread
                    }
                    // QQ 等在线音频先起播、后解码；必须等完整 PCM，不能导出当前水位。
                    Thread.sleep(200)
                }
                if (source.frames <= 0) {
                    fail("音频没有完整 PCM，导出已取消")
                    return@Thread
                }
                onProgress(10)
                var pcm = source.toByteArray()
                var bpf = source.bytesPerFrame
                var channels = source.channels
                var floating = source.floatingPoint
                var frames = source.frames
                if (tr.active) {
                    // 实时变速交给 AudioTrack；导出则用同源 C++ 离线预处理写入新时长。
                    pcm = NativePreprocess.run(pcm, bpf, channels, floating, frames.toLong(), tr.speed, tr.semi, tr.tape)
                    bpf = 2; channels = 1; floating = false; frames = pcm.size / 2
                }
                onProgress(50)
                temp = File.createTempFile("8d-render-", ".wav")
                NativeSpatialExport.render(pcm, bpf, channels, floating, frames.toLong(), sr.toDouble(), rev,
                    p.raw, p.period, p.elev, p.depth, p.phase, p.direction, p.width, p.room,
                    p.rt60, p.wet, tr.denoiseLevel, temp.absolutePath)
                // 仅检查 RIFF 不够：44 字节空 WAV 也曾被当成导出成功。
                if (!temp.isFile || temp.length() <= 44L || temp.length() < frames * 4L + 44L) {
                    error("空间渲染没有生成完整 PCM")
                }
                if (mp3) {
                    val target = mp3Path ?: error("MP3 缺少临时文件路径")
                    com.jiangyi.spatial8d.Mp3Export.nativeEncode(temp.absolutePath, target)
                    if (!File(target).isFile || File(target).length() <= 1024L) error("MP3 编码没有生成有效音频")
                    File(target).inputStream().use { it.copyTo(stream) }
                } else temp.inputStream().use { it.copyTo(stream) }
                stream.use { it.flush() }
                main.post { onDone(true, if (mp3) "MP3 导出完成" else "WAV 导出完成") }
            } catch (e: Exception) {
                try { stream.close() } catch (_: Exception) {}
                main.post { onDone(false, "导出失败: ${e.message}") }
            } finally { temp?.delete() }
        }.start()
    }

    private class RenderCache(val epoch: Long, val startFrame: Int, val file: File,
        val native: Long) {
        // produced/consumed 均以帧计，磁盘布局固定为 16bit 立体声交错。
        private var input: FileInputStream? = null
        val output = FileOutputStream(file, true)
        private val nativeLock = Any()
        @Volatile private var released = false
        @Volatile private var discard = false
        private var writerActive = true
        val produced = AtomicInteger()
        val done = java.util.concurrent.atomic.AtomicBoolean()
        @Volatile var consumed = 0
        fun finish() { done.set(true); closeOutput() }
        @Synchronized fun closeOutput() {
            if (!writerActive && released) return
            releaseNative()
            try { output.close() } catch (_: Exception) {}
            writerActive = false
            if (discard) file.delete()
        }
        fun writerFinished() = closeOutput()
        fun closeInput() = try { input?.close(); input = null } catch (_: Exception) {}
        @Synchronized fun discardWhenIdle() { discard = true; if (!writerActive) file.delete() }
        fun read(dst: ByteArray, bytes: Int): Boolean {
            val stream = input ?: FileInputStream(file).also {
                it.channel.position(consumed * 4L)
                input = it
            }
            var offset = 0
            while (offset < bytes) {
                val n = stream.read(dst, offset, bytes - offset)
                if (n < 0) return false
                offset += n
            }
            return true
        }
        fun render(block: () -> ByteArray): ByteArray = synchronized(nativeLock) {
            if (released) error("渲染缓存已关闭")
            block()
        }
        fun releaseNative() = synchronized(nativeLock) {
            // JNI 渲染器只在当前 render 返回后释放，防止并发 use-after-free。
            if (!released) { NativeSpatialPlayer.release(native); released = true }
        }
    }

    internal companion object {
        const val CHUNK = 4096
        const val OUTPUT_CHUNK = 1024
        const val SEEK_RESUME_DELAY_MS = 120L
        // 与 UTS 一致：三个输出块约 70ms，避免首个缓冲未填满就起播造成欠载。
        const val START_FRAMES = 3072
        internal fun shouldRestartOutput(own: Int, currentGeneration: Int, prepared: Boolean,
            paused: Boolean, hasLiveOutput: Boolean) =
            own == currentGeneration && prepared && !paused && !hasLiveOutput
    }
}
