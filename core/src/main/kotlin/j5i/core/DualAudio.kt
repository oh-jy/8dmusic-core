package j5i.spatial8d.core

import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.exp

class DualVolumeBalancer(private val rate: Int) {
    private var leftGain = 1.0
    private var rightGain = 1.0

    fun process(buffer: ByteArray, startFrame: Int, count: Int, bothUntilFrame: Int) {
        val paired = (bothUntilFrame.toLong() - startFrame).coerceIn(0, count.toLong()).toInt()
        fun sample(offset: Int): Int = ((buffer[offset].toInt() and 255) or (buffer[offset + 1].toInt() shl 8)).toShort().toInt()
        var leftPower = 0.0
        var rightPower = 0.0
        for (index in 0 until paired) {
            val left = sample(index * 4).toDouble()
            val right = sample(index * 4 + 2).toDouble()
            leftPower += left * left
            rightPower += right * right
        }
        val leftRms = if (paired > 0) sqrt(leftPower / paired) else 0.0
        val rightRms = if (paired > 0) sqrt(rightPower / paired) else 0.0
        val audible = leftRms > 328.0 && rightRms > 328.0
        val targetLeft = if (audible && leftRms > rightRms) (rightRms / leftRms).coerceAtLeast(0.25) else 1.0
        val targetRight = if (audible && rightRms > leftRms) (leftRms / rightRms).coerceAtLeast(0.25) else 1.0
        val smoothing = 1.0 - exp(-1.0 / (rate.coerceAtLeast(1) * 2.0))
        for (index in 0 until count) {
            leftGain += ((if (index < paired) targetLeft else 1.0) - leftGain) * smoothing
            rightGain += ((if (index < paired) targetRight else 1.0) - rightGain) * smoothing
            val offset = index * 4
            val left = (sample(offset) * leftGain).roundToInt()
            val right = (sample(offset + 2) * rightGain).roundToInt()
            buffer[offset] = left.toByte(); buffer[offset + 1] = (left shr 8).toByte()
            buffer[offset + 2] = right.toByte(); buffer[offset + 3] = (right shr 8).toByte()
        }
    }
}

/** A stable schedule makes playback, seeking and offline rendering agree. */
class DualSwapTimeline(private val minSeconds: Int, private val maxSeconds: Int, private val rate: Int,
    seed: Long = 0x43f6a888L) {
    private val boundaries = ArrayList<Long>()
    private var state = seed and 0xffffffffL
    @Synchronized
    fun swappedAt(frame: Long): Boolean {
        if (maxSeconds <= 0 || rate <= 0) return false
        val low = minSeconds.coerceAtLeast(1).coerceAtMost(maxSeconds)
        while (boundaries.isEmpty() || boundaries.last() <= frame) {
            state = (state * 1664525L + 1013904223L) and 0xffffffffL
            boundaries.add((boundaries.lastOrNull() ?: 0L) +
                (low + (state % (maxSeconds - low + 1)).toInt()).toLong() * rate)
        }
        val result = boundaries.binarySearch(frame)
        return (if (result >= 0) result + 1 else -result - 1) % 2 != 0
    }
}

class DualSwapFader(rate: Int) {
    private val fadeFrames = (rate / 100).coerceAtLeast(1)
    private var lastFrame = -1
    private var amount = 0.0
    private var target = false
    private var step = 0.0
    private var remaining = 0

    fun amountAt(frame: Int, swappedAt: (Int) -> Boolean): Double {
        if (lastFrame + 1 != frame || lastFrame < 0) {
            val start = (frame - fadeFrames).coerceAtLeast(0)
            target = swappedAt((start - 1).coerceAtLeast(0))
            amount = if (target) 1.0 else 0.0
            remaining = 0
            for (previous in start until frame) advance(swappedAt(previous))
        }
        lastFrame = frame
        return advance(swappedAt(frame))
    }

    private fun advance(next: Boolean): Double {
        if (next != target) {
            target = next
            remaining = fadeFrames
            step = ((if (target) 1.0 else 0.0) - amount) / fadeFrames
        }
        val result = amount
        if (remaining > 0) {
            amount += step
            remaining--
            if (remaining == 0) amount = if (target) 1.0 else 0.0
        }
        return result
    }
}

/** Reads bounded PCM blocks and resamples both inputs onto one output clock. */
object DualAudio {
    fun render(left: PcmBuffer, right: PcmBuffer, rate: Int, start: Int, count: Int): ByteArray? {
        require(rate > 0 && start >= 0 && count >= 0)
        fun read(source: PcmBuffer): Pair<Int, DecodedPcm>? {
            val first = (start.toLong() * source.sampleRate / rate).toInt()
            val end = ceil((start.toLong() + count).toDouble() * source.sampleRate / rate).toInt() + 1
            if (!source.decodeDone && end > source.frames) return null
            val boundedFirst = first.coerceAtMost(source.frames)
            val boundedEnd = end.coerceAtMost(source.frames)
            val bytes = source.copyFrames(boundedFirst, boundedEnd - boundedFirst) ?: return null
            return boundedFirst to DecodedPcm(bytes, source.sampleRate, source.channels, source.floatingPoint, 0)
        }
        val leftData = read(left) ?: return null
        val rightData = read(right) ?: return null
        val output = ByteArray(count * 4)
        fun channel(data: Pair<Int, DecodedPcm>, source: PcmBuffer, outputFrame: Int, channel: Int): Double? {
            val pos = outputFrame.toDouble() * source.sampleRate / rate
            val index = pos.toInt()
            if (index >= source.frames) return null
            val pcm = data.second
            val local = index - data.first
            if (local !in 0 until pcm.frames) return null
            val next = (local + 1).coerceAtMost(pcm.frames - 1)
            fun sample(frame: Int): Double {
                val offset = frame * pcm.bytesPerFrame + channel.coerceAtMost(pcm.channels - 1) * pcm.bytesPerSample
                val bytes = pcm.data
                val bits = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
                return if (pcm.floatingPoint) Float.fromBits(bits or ((bytes[offset + 2].toInt() and 255) shl 16) or
                    ((bytes[offset + 3].toInt() and 255) shl 24)).toDouble() else bits.toShort() / 32768.0
            }
            val first = sample(local)
            val last = sample(next)
            return first + (last - first) * (pos - index)
        }
        for (index in 0 until count) {
            val frame = start + index
            val leftA = channel(leftData, left, frame, 0)
            val rightA = channel(leftData, left, frame, 1)
            val leftB = channel(rightData, right, frame, 0)
            val rightB = channel(rightData, right, frame, 1)
            fun mono(data: Pair<Int, DecodedPcm>, source: PcmBuffer, first: Double?): Double? {
                if (first == null) return null
                var sum = first
                for (channelIndex in 1 until source.channels) sum += channel(data, source, frame, channelIndex) ?: 0.0
                return sum / source.channels
            }
            val a = mono(leftData, left, leftA)
            val b = mono(rightData, right, leftB)
            val outLeft = if (a != null && b != null) a else leftA ?: leftB ?: 0.0
            val outRight = if (a != null && b != null) b else rightA ?: rightB ?: 0.0
            val l = clip16(outLeft).toInt()
            val r = clip16(outRight).toInt()
            val offset = index * 4
            output[offset] = l.toByte(); output[offset + 1] = (l shr 8).toByte()
            output[offset + 2] = r.toByte(); output[offset + 3] = (r shr 8).toByte()
        }
        return output
    }

    fun swap(buffer: ByteArray, startFrame: Int, count: Int, bothUntilFrame: Int, fader: DualSwapFader? = null,
        swappedAt: (Int) -> Boolean) {
        for (index in 0 until count) {
            val frame = startFrame + index
            val amount = fader?.amountAt(frame, swappedAt) ?: if (swappedAt(frame)) 1.0 else 0.0
            if (frame >= bothUntilFrame || amount <= 0.0) continue
            val offset = index * 4
            if (amount >= 1.0) {
                val low = buffer[offset]; val high = buffer[offset + 1]
                buffer[offset] = buffer[offset + 2]; buffer[offset + 1] = buffer[offset + 3]
                buffer[offset + 2] = low; buffer[offset + 3] = high
            } else {
                val left = ((buffer[offset].toInt() and 255) or (buffer[offset + 1].toInt() shl 8)).toShort().toInt()
                val right = ((buffer[offset + 2].toInt() and 255) or (buffer[offset + 3].toInt() shl 8)).toShort().toInt()
                val mixedLeft = (left * (1.0 - amount) + right * amount).roundToInt()
                val mixedRight = (right * (1.0 - amount) + left * amount).roundToInt()
                buffer[offset] = mixedLeft.toByte(); buffer[offset + 1] = (mixedLeft shr 8).toByte()
                buffer[offset + 2] = mixedRight.toByte(); buffer[offset + 3] = (mixedRight shr 8).toByte()
            }
        }
    }
}
