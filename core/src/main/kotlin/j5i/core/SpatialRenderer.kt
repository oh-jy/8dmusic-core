package j5i.spatial8d.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

private const val RENDER_BLOCK = 4096
private const val OUTPUT_BLOCK = 1024
private const val FADE_FRAMES = 512

class SpatialRenderer(sampleRate: Double) {
    private val engine = SpaceEngine(sampleRate)
    private val input = DoubleArray(RENDER_BLOCK)
    private val outL = DoubleArray(RENDER_BLOCK)
    private val outR = DoubleArray(RENDER_BLOCK)
    private var denoise = false
    private var reducer: NoiseReducer? = null

    fun configure(preset: SpacePreset) {
        engine.setSpace(
            preset.period, preset.elev, preset.depth, preset.phase,
            preset.direction, preset.width, preset.raw, preset.trajectory, preset.sourceDistance
        )
        engine.setRoom(preset.room, preset.rt60, preset.wet)
    }

    fun setDenoise(enabled: Boolean) {
        if (denoise != enabled) {
            denoise = enabled
            reducer = null
        }
    }

    fun reset() {
        engine.reset()
        reducer = null
    }

    fun render(
        mono: FloatArray,
        start: Int,
        frames: Int,
        reverse: Boolean,
        raw: DecodedPcm? = null
    ): ByteArray {
        require(start >= 0 && frames >= 0 && start + frames <= mono.size) {
            "渲染帧范围越界: start=$start frames=$frames total=${mono.size}"
        }
        val out = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        if (raw != null && engine.rawEnabled()) {
            for (i in 0 until frames) {
                val source = if (reverse) raw.frames - 1 - start - i else start + i
                out.putShort(clip16(raw.readChannel(source, 0)))
                out.putShort(clip16(raw.readChannel(source, minOf(1, raw.channels - 1))))
            }
            return out.array()
        }
        if (denoise && reducer == null) reducer = NoiseReducer(engine.sr)
        var pos = 0
        while (pos < frames) {
            val n = min(RENDER_BLOCK, frames - pos)
            for (i in 0 until n) {
                val source = if (reverse) mono.size - 1 - start - pos - i else start + pos + i
                input[i] = mono[source].toDouble()
            }
            engine.process(input, outL, outR, n, (start + pos) / engine.sr)
            for (i in 0 until n) {
                var l = outL[i]
                var r = outR[i]
                if (denoise) {
                    val pair = reducer!!.process(l, r)
                    l = pair[0]
                    r = pair[1]
                }
                if (start == 0) {
                    val index = pos + i
                    val fade = when {
                        index >= FADE_FRAMES -> 1.0
                        else -> index.toDouble() / FADE_FRAMES
                    }
                    val smooth = fade * fade * (3.0 - 2.0 * fade)
                    l *= smooth
                    r *= smooth
                }
                out.putShort(clip16(l))
                out.putShort(clip16(r))
            }
            pos += n
        }
        return out.array()
    }

    fun render(
        mono: DoubleArray,
        frames: Int,
        seconds: Double,
        reverse: Boolean,
        raw: PcmBuffer? = null,
        rawFrames: Int = raw?.frames ?: 0
    ): ByteArray {
        require(frames in 0..mono.size)
        val out = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        if (raw != null && engine.rawEnabled()) {
            for (i in 0 until frames) {
                val source = if (reverse) rawFrames - 1 - (seconds * engine.sr).toInt() - i
                    else (seconds * engine.sr).toInt() + i
                out.putShort(clip16(raw.sample(source, 0)))
                out.putShort(clip16(raw.sample(source, minOf(1, raw.channels - 1))))
            }
            return out.array()
        }
        if (denoise && reducer == null) reducer = NoiseReducer(engine.sr)
        engine.process(mono, outL, outR, frames, seconds)
        for (i in 0 until frames) {
            var l = outL[i]
            var r = outR[i]
            if (denoise) {
                val pair = reducer!!.process(l, r)
                l = pair[0]
                r = pair[1]
            }
            out.putShort(clip16(l))
            out.putShort(clip16(r))
        }
        return out.array()
    }
}

private class NoiseReducer(sr: Double) {
    private val hp = exp(-2.0 * Math.PI * 30.0 / sr)
    private val attack = exp(-1.0 / (0.005 * sr))
    private val release = exp(-1.0 / (0.08 * sr))
    private val gainAttack = exp(-1.0 / (0.008 * sr))
    private val gainRelease = exp(-1.0 / (0.12 * sr))
    private var xl = 0.0
    private var xr = 0.0
    private var yl = 0.0
    private var yr = 0.0
    private var envelope = 0.0
    private var gain = 1.0

    fun process(left: Double, right: Double): DoubleArray {
        val l = hp * (yl + left - xl)
        val r = hp * (yr + right - xr)
        xl = left
        xr = right
        yl = l
        yr = r
        val value = max(abs(l), abs(r))
        val rate = if (value > envelope) attack else release
        envelope = envelope * rate + value * (1.0 - rate)
        val wanted = if (envelope < 0.008) 0.42 + 0.58 * (envelope / 0.008).pow(2.0) else 1.0
        val smoothing = if (wanted < gain) gainAttack else gainRelease
        gain = gain * smoothing + wanted * (1.0 - smoothing)
        return doubleArrayOf(l * gain, r * gain)
    }
}

fun renderSpatialWav(
    mono: FloatArray,
    sampleRate: Int,
    preset: SpacePreset,
    reverse: Boolean,
    denoise: Boolean,
    onProgress: ((Int) -> Unit)? = null
): ByteArray {
    val renderer = SpatialRenderer(sampleRate.toDouble())
    renderer.configure(preset)
    renderer.setDenoise(denoise)
    val out = ByteBuffer.allocate(44 + mono.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    out.position(44)
    var pos = 0
    while (pos < mono.size) {
        val n = min(OUTPUT_BLOCK * 4, mono.size - pos)
        val block = renderer.render(mono, pos, n, reverse)
        out.put(block)
        pos += n
        onProgress?.invoke(pos * 100 / mono.size)
    }
    writeWavHeader(out, mono.size, sampleRate)
    return out.array()
}

fun writeWavHeader(target: ByteBuffer, frames: Int, sampleRate: Int) {
    val oldOrder = target.order()
    target.order(ByteOrder.LITTLE_ENDIAN)
    target.position(0)
    target.put("RIFF".toByteArray(Charsets.US_ASCII))
    target.putInt(frames * 4 + 36)
    target.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
    target.putInt(16)
    target.putShort(1)
    target.putShort(2)
    target.putInt(sampleRate)
    target.putInt(sampleRate * 4)
    target.putShort(4)
    target.putShort(16)
    target.put("data".toByteArray(Charsets.US_ASCII))
    target.putInt(frames * 4)
    target.order(oldOrder)
}
