package j5i.spatial8d.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToInt

data class DecodedPcm(
    val data: ByteArray,
    val sampleRate: Int,
    val channels: Int,
    val floatingPoint: Boolean,
    val durationUs: Long
) {
    val bytesPerSample: Int get() = if (floatingPoint) 4 else 2
    val bytesPerFrame: Int get() = bytesPerSample * channels
    val frames: Int get() = data.size / bytesPerFrame
    val durationSec: Double get() = frames.toDouble() / sampleRate

    private fun buffer(): ByteBuffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

    fun frame(start: Int, n: Int, mono: DoubleArray, reverse: Boolean = false) {
        val bb = buffer()
        for (i in 0 until n) {
            val source = if (reverse) frames - 1 - start - i else start + i
            var sum = 0.0
            for (ch in 0 until channels) {
                val offset = source * bytesPerFrame + ch * bytesPerSample
                sum += readSample(bb, offset)
            }
            mono[i] = sum / channels
        }
    }

    fun mono(): FloatArray {
        val result = FloatArray(frames)
        val scratch = DoubleArray(min(8192, frames.coerceAtLeast(1)))
        var pos = 0
        while (pos < frames) {
            val n = min(scratch.size, frames - pos)
            frame(pos, n, scratch)
            for (i in 0 until n) result[pos + i] = scratch[i].toFloat()
            pos += n
        }
        return result
    }

    fun readChannel(frameIndex: Int, channel: Int): Double {
        val bb = buffer()
        val offset = frameIndex * bytesPerFrame + channel * bytesPerSample
        return readSample(bb, offset)
    }

    private fun readSample(bb: ByteBuffer, offset: Int): Double =
        if (floatingPoint) bb.getFloat(offset).toDouble()
        else bb.getShort(offset).toDouble() / 32768.0
}

/**
 * 流式解码缓冲：bytes 是已经解出的连续水位，decodeDone 才代表整轨结束。
 * 容器时长用于首块起播时显示完整进度，不能拿当前水位当作歌曲总时长。
 * 播放按块读取稳定快照，避免流式扩容后 JNI 仍持有旧数组。
 */
class PcmBuffer(val sampleRate: Int, val channels: Int, val floatingPoint: Boolean,
    private val durationUs: Long = 0L) {
    private val initialCapacity = if (durationUs > 0)
        ((durationUs / 1_000_000.0 * sampleRate + sampleRate / 2).toLong() * bytesPerFrame)
            .coerceAtMost(30L * 60 * sampleRate * bytesPerFrame).toInt() else 0
    // 首块只预留两秒，避免长歌尚未出声就先分配并清零整段 PCM。
    private var data = ByteArray(min(initialCapacity, sampleRate * 2 * bytesPerFrame))
    @Volatile var bytes = 0
        private set
    val bytesPerSample get() = if (floatingPoint) 4 else 2
    val bytesPerFrame get() = bytesPerSample * channels
    val frames get() = bytes / bytesPerFrame
    val durationSec get() = frames.toDouble() / sampleRate
    val estimatedDurationSec get() = if (durationUs > 0) durationUs / 1_000_000.0 else durationSec
    @Volatile var decodeDone = false

    @Synchronized
    fun append(source: ByteArray, size: Int = source.size) {
        // 容器时长只是估算，实际输出更长时按需扩容。
        if (bytes + size > data.size) data = data.copyOf(maxOf(bytes + size, data.size * 2))
        source.copyInto(data, bytes, 0, size)
        bytes += size
    }

    fun sample(frameIndex: Int, channel: Int): Double {
        val offset = frameIndex * bytesPerFrame + channel * bytesPerSample
        require(offset in 0 until bytes) { "PCM 帧尚未解码: $frameIndex" }
        return if (floatingPoint) {
            val bits = (data[offset].toInt() and 255) or
                ((data[offset + 1].toInt() and 255) shl 8) or
                ((data[offset + 2].toInt() and 255) shl 16) or
                ((data[offset + 3].toInt() and 255) shl 24)
            Float.fromBits(bits).toDouble()
        } else {
            (((data[offset + 1].toInt() shl 8) or (data[offset].toInt() and 255)).toShort()).toDouble() / 32768.0
        }
    }

    fun frame(start: Int, n: Int, mono: DoubleArray, reverse: Boolean = false) {
        for (i in 0 until n) {
            val source = if (reverse) frames - 1 - start - i else start + i
            var sum = 0.0
            for (channel in 0 until channels) sum += sample(source, channel)
            mono[i] = sum / channels
        }
    }

    fun mono(): FloatArray {
        val result = FloatArray(frames)
        val scratch = DoubleArray(min(8192, frames.coerceAtLeast(1)))
        var start = 0
        while (start < frames) {
            val n = min(scratch.size, frames - start)
            frame(start, n, scratch)
            for (i in 0 until n) result[start + i] = scratch[i].toFloat()
            start += n
        }
        return result
    }

    /** 播放只复制当前块，扩容后也不会让 JNI 继续读取旧数组。 */
    fun copyFrames(start: Int, count: Int): ByteArray {
        val offset = start * bytesPerFrame
        val size = count * bytesPerFrame
        require(start >= 0 && count >= 0 && offset + size <= bytes) { "PCM 帧尚未解码: $start + $count" }
        return data.copyOfRange(offset, offset + size)
    }

    /** 仅在 decodeDone 后用于离线导出；播放中保留同一数组供原生层读取。 */
    fun toByteArray(): ByteArray = data
}

data class TransformParams(
    val speed: Double = 1.0,
    val semi: Double = 0.0,
    val tape: Boolean = false,
    val reverse: Boolean = false,
    val denoise: Int = 65,
    val clarity: Int = 50
) {
    val active: Boolean get() = speed != 1.0 || semi != 0.0
    val denoiseLevel: Int get() = denoise.coerceIn(0, 100)
    val clarityLevel: Int get() = clarity.coerceIn(0, 100)
}

fun clip16(value: Double): Short =
    (value.coerceIn(-1.0, 1.0) * 32767.0).roundToInt().toShort()
