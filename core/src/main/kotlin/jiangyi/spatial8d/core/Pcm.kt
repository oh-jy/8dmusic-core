package jiangyi.spatial8d.core

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

data class TransformParams(
    val speed: Double = 1.0,
    val semi: Double = 0.0,
    val tape: Boolean = false,
    val reverse: Boolean = false
) {
    val active: Boolean get() = speed != 1.0 || semi != 0.0
}

fun clip16(value: Double): Short =
    (value.coerceIn(-1.0, 1.0) * 32767.0).roundToInt().toShort()
