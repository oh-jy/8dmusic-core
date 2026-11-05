package j5i.spatial8d.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
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
    private val durationUs: Long = 0L, private val spillDirectory: File? = null,
    internal val memoryLimitBytes: Int = MEMORY_LIMIT_BYTES,
    internal val diskLimitBytes: Long = DISK_LIMIT_BYTES,
    baseFrame: Int = 0) : Closeable {
    private val initialCapacity = if (durationUs > 0)
        ((durationUs / 1_000_000.0 * sampleRate + sampleRate / 2).toLong() * bytesPerFrame)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0
    // 首块只预留两秒，长歌曲由后续 PCM 块按需增长，不一次性申请整轨内存。
    private var data = ByteArray(minOf(initialCapacity, sampleRate * 2 * bytesPerFrame))
    @Volatile private var disk: RandomAccessFile? = null
    private var diskFile: File? = null
    private val baseByte = baseFrame.toLong() * bytesPerFrame
    private var diskBaseByte = baseByte
    private var diskStoredBytes = 0L
    private var consumedFrame = 0
    private var closed = false
    val isDiskBacked get() = disk != null
    @Volatile var bytes = baseByte
        private set
    val bytesPerSample get() = if (floatingPoint) 4 else 2
    val bytesPerFrame get() = bytesPerSample * channels
    val frames get() = (bytes / bytesPerFrame).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val durationSec get() = frames.toDouble() / sampleRate
    val estimatedDurationSec get() = if (durationUs > 0) durationUs / 1_000_000.0 else durationSec
    @Volatile var decodeDone = false

    @Synchronized
    fun append(source: ByteArray, size: Int = source.size): Boolean {
        check(!closed) { "PCM 缓冲已关闭" }
        require(size in 0..source.size) { "PCM 块大小非法" }
        if (size == 0) return true
        if (disk == null && spillDirectory != null && bytes - baseByte + size > memoryLimitBytes) {
            // 长歌曲保留随机读能力，整轨 PCM 和扩容副本不再占满 Java 堆。
            val directory = spillDirectory.apply { mkdirs() }
            val target = File.createTempFile("track-", ".pcm", directory)
            val output = RandomAccessFile(target, "rw")
            try {
                output.write(data, 0, (bytes - baseByte).toInt())
                disk = output
                diskFile = target
                diskBaseByte = baseByte
                diskStoredBytes = bytes - baseByte
                data = ByteArray(0)
            } catch (e: Exception) {
                try { output.close() } catch (_: Exception) {}
                target.delete()
                throw e
            }
        }
        val output = disk
        if (output != null) {
            // 暂停不消费时，最多再解码 96 MiB；恢复后从原位置继续，不丢弃待播 PCM。
            if (diskStoredBytes + size > diskLimitBytes) {
                compactDisk()
                if (diskStoredBytes + size > diskLimitBytes) return false
            }
            output.seek(diskStoredBytes)
            output.write(source, 0, size)
            diskStoredBytes += size
            bytes += size
            return true
        }
        // 容器时长只是估算，实际输出更长时按需扩容。
        val storedBytes = bytes - baseByte
        if (storedBytes + size > data.size) {
            val doubled = if (data.size > Int.MAX_VALUE / 2) Int.MAX_VALUE else data.size * 2
            val capacity = if (spillDirectory != null && doubled > memoryLimitBytes) memoryLimitBytes
                else maxOf((storedBytes + size).toInt(), doubled)
            require(capacity >= storedBytes + size) { "PCM 缓冲超出 Android 数组上限" }
            data = data.copyOf(capacity)
        }
        source.copyInto(data, storedBytes.toInt(), 0, size)
        bytes += size
        return true
    }

    fun sample(frameIndex: Int, channel: Int): Double {
        val offset = frameIndex.toLong() * bytesPerFrame + channel * bytesPerSample
        val relative = offset - baseByte
        require(relative in 0 until bytes - baseByte) { "PCM 帧尚未解码: $frameIndex" }
        return if (floatingPoint) {
            val index = relative.toInt()
            val bits = (data[index].toInt() and 255) or
                ((data[index + 1].toInt() and 255) shl 8) or
                ((data[index + 2].toInt() and 255) shl 16) or
                ((data[index + 3].toInt() and 255) shl 24)
            Float.fromBits(bits).toDouble()
        } else {
            val index = relative.toInt()
            (((data[index + 1].toInt() shl 8) or (data[index].toInt() and 255)).toShort()).toDouble() / 32768.0
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
    @Synchronized
    fun copyFrames(start: Int, count: Int): ByteArray? {
        val offset = start.toLong() * bytesPerFrame
        val size = count * bytesPerFrame
        require(start >= 0 && count >= 0 && offset + size <= bytes) { "PCM 帧尚未解码: $start + $count" }
        disk?.let {
            val relative = offset - diskBaseByte
            if (relative < 0L || relative + size > diskStoredBytes) return null
            val result = ByteArray(size)
            it.seek(relative)
            it.readFully(result)
            return result
        }
        val relative = offset - baseByte
        if (relative < 0L || relative + size > bytes - baseByte) return null
        return data.copyOfRange(relative.toInt(), (relative + size).toInt())
    }

    /** 磁盘压缩后，仍保留的最早全局帧号；内存缓冲始终从 0 开始。 */
    @Synchronized
    fun retainedStartFrame(): Int =
        disk?.let { (diskBaseByte / bytesPerFrame).toInt() }
            ?: (baseByte / bytesPerFrame).toInt()

    /** 仅在 decodeDone 后用于离线导出；播放中保留同一数组供原生层读取。 */
    fun toByteArray(): ByteArray {
        check(baseByte == 0L && disk == null) { "超长音频使用磁盘窗口，暂不支持整轨导出" }
        return data
    }

    @Synchronized
    fun releaseBefore(frame: Int) {
        consumedFrame = maxOf(consumedFrame, frame.coerceIn(0, frames))
    }

    private fun compactDisk() {
        val file = disk ?: return
        val consumedByte = consumedFrame.toLong() * bytesPerFrame
        val removable = (consumedByte - diskBaseByte).coerceIn(0L, diskStoredBytes)
        // 批量回收，避免窗口满后每个解码块都搬运近 96 MiB。
        if (removable < minOf(8L * 1024 * 1024, diskLimitBytes / 4)) return
        val retained = diskStoredBytes - removable
        val scratch = ByteArray(1024 * 1024)
        var sourceOffset = removable
        var targetOffset = 0L
        while (targetOffset < retained) {
            val count = minOf(scratch.size.toLong(), retained - targetOffset).toInt()
            file.seek(sourceOffset)
            file.readFully(scratch, 0, count)
            file.seek(targetOffset)
            file.write(scratch, 0, count)
            sourceOffset += count
            targetOffset += count
        }
        file.setLength(retained.toLong())
        diskBaseByte += removable
        diskStoredBytes = retained
    }

    @Synchronized
    override fun close() {
        closed = true
        try { disk?.close() } catch (_: Exception) {}
        disk = null
        diskFile?.delete()
        diskFile = null
        data = ByteArray(0)
    }

    private companion object {
        const val MEMORY_LIMIT_BYTES = 64 * 1024 * 1024
        const val DISK_LIMIT_BYTES = 96L * 1024 * 1024
    }
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
