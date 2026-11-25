package j5i.spatial8d.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.j5i.spatial8d.Mp3Export
import j5i.spatial8d.core.DualAudio
import j5i.spatial8d.core.DualSwapFader
import j5i.spatial8d.core.DualVolumeBalancer
import j5i.spatial8d.core.PcmBuffer
import j5i.spatial8d.core.writeWavHeader
import j5i.spatial8d.data.MusicItem
import j5i.spatial8d.data.MusicStore
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal fun exportDualLocal(context: Context, tracks: Pair<MusicItem, MusicItem>, stream: OutputStream,
    mp3: Boolean, mp3Path: String?, swappedAt: (Double) -> Boolean,
    onProgress: (Int) -> Unit, onDone: (Boolean, String) -> Unit) {
    val main = Handler(Looper.getMainLooper())
    Thread {
        val active = AtomicBoolean(true)
        val sources = arrayOf(AtomicReference<PcmBuffer>(), AtomicReference<PcmBuffer>())
        val failure = AtomicReference<Throwable>()
        val store = MusicStore(context)
        val workers = listOf(tracks.first, tracks.second).mapIndexed { index, item ->
            Thread {
                try {
                    AudioDecoder(context).decode(store.readableUri(item), {}, { active.get() },
                        onHead = { source, _ -> sources[index].set(source) })
                    if (sources[index].get() == null) error("音频内容为空: ${item.name}")
                } catch (exception: Exception) {
                    if (active.get()) failure.compareAndSet(null, exception)
                }
            }.apply { start() }
        }
        val wav = File.createTempFile("8d-dual-", ".wav", context.cacheDir)
        try {
            while (sources.any { it.get() == null }) {
                failure.get()?.let { throw it }
                Thread.sleep(20)
            }
            val left = sources[0].get()!!
            val right = sources[1].get()!!
            val rate = left.sampleRate
            val swapFader = DualSwapFader(rate)
            val dualBalancer = DualVolumeBalancer(rate)
            var frame = 0
            RandomAccessFile(wav, "rw").use { output ->
                output.write(ByteArray(44))
                while (true) {
                    failure.get()?.let { throw it }
                    val leftEnd = left.frames.toLong() * rate / left.sampleRate
                    val rightEnd = right.frames.toLong() * rate / right.sampleRate
                    val available = minOf(if (left.decodeDone) Long.MAX_VALUE else leftEnd - 1,
                        if (right.decodeDone) Long.MAX_VALUE else rightEnd - 1)
                    val total = maxOf(leftEnd, rightEnd)
                    if (left.decodeDone && right.decodeDone && frame.toLong() >= total) break
                    val end = if (left.decodeDone && right.decodeDone) total else minOf(available, total)
                    val count = (end - frame).coerceIn(0, 4096).toInt()
                    if (count == 0) { Thread.sleep(20); continue }
                    val block = DualAudio.render(left, right, rate, frame, count)
                    if (block == null) { Thread.sleep(20); continue }
                    val bothUntil = if (left.decodeDone || right.decodeDone) {
                        minOf(if (left.decodeDone) leftEnd else Long.MAX_VALUE,
                            if (right.decodeDone) rightEnd else Long.MAX_VALUE).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    } else Int.MAX_VALUE
                    dualBalancer.process(block, frame, count, bothUntil)
                    DualAudio.swap(block, frame, count, bothUntil, swapFader) { swappedAt(it.toDouble() / rate) }
                    output.write(block)
                    frame += count
                    left.releaseBefore((frame.toLong() * left.sampleRate / rate).toInt())
                    right.releaseBefore((frame.toLong() * right.sampleRate / rate).toInt())
                    val estimate = maxOf(tracks.first.duration, tracks.second.duration)
                    if (estimate > 0) onProgress((frame.toLong() * 90_000L / rate / estimate).toInt().coerceIn(0, 90))
                }
                check(frame > 0) { "音频内容为空" }
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                writeWavHeader(header, frame, rate)
                output.seek(0)
                output.write(header.array())
            }
            if (mp3) {
                val target = mp3Path ?: error("MP3 输出路径不可用")
                Mp3Export.nativeEncode(wav.absolutePath, target)
                check(File(target).length() > 1024L) { "MP3 编码失败" }
                File(target).inputStream().use { input -> stream.use { input.copyTo(it) } }
            } else wav.inputStream().use { input -> stream.use { input.copyTo(it) } }
            main.post { onDone(true, "导出完成") }
        } catch (exception: Throwable) {
            runCatching { stream.close() }
            main.post { onDone(false, "导出失败: ${exception.message}") }
        } finally {
            active.set(false)
            workers.forEach { it.join() }
            sources.forEach { it.get()?.close() }
            wav.delete()
        }
    }.start()
}
