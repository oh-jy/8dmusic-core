package j5i.spatial8d.core.dsp

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

private const val RS_ORDER = 32
private const val RS_TAPS = RS_ORDER + 1
private const val RS_HALF = RS_ORDER / 2
private const val RS_ENABLE_LPF = 1.02

class Resampler(private val blockMax: Int = 8192) {
    private var ratio = 1.0
    private var useLpf = false
    private var coefRatio = 0.0
    private val coef = DoubleArray(RS_TAPS)

    private val ring = DoubleArray(RS_TAPS)
    private var wp = 0
    private var rawCount = 0

    private var filteredCount = 0
    private var fPrev = 0.0
    private var fCur = 0.0

    private var outCount = 0

    fun reset() {
        ratio = 1.0
        useLpf = false
        coefRatio = 0.0
        wp = 0
        rawCount = 0
        filteredCount = 0
        outCount = 0
        fPrev = 0.0
        fCur = 0.0
        ring.fill(0.0)
        coef.fill(0.0)
    }

    private fun prepareCoeffs() {
        useLpf = ratio > RS_ENABLE_LPF
        if (!useLpf) return
        if (abs(ratio - coefRatio) <= 1e-12) return

        var sum = 0.0
        for (k in 0 until RS_TAPS) {
            val d = (k.toDouble() - RS_HALF.toDouble()) / ratio
            val sinc = if (k == RS_HALF) 1.0 else sin(Math.PI * d) / (Math.PI * d)
            val window = 0.54 - 0.46 * cos(2.0 * Math.PI * k.toDouble() / RS_ORDER.toDouble())
            val c = sinc / ratio * window
            coef[k] = c
            sum += c
        }
        for (k in 0 until RS_TAPS) coef[k] = coef[k] / sum
        coefRatio = ratio
    }

    private fun emitAvailable(srcIndex: Int, value: Double, out: DoubleArray, outPos: Int): Int {
        if (srcIndex == 0) {
            fPrev = 0.0
            fCur = value
        } else {
            fPrev = fCur
            fCur = value
        }

        var produced = 0
        while (true) {
            val p = outCount.toDouble() * ratio
            val i0 = floor(p).toInt()
            val frac = p - i0.toDouble()

            var y = 0.0
            if (frac == 0.0 && i0 == srcIndex) {
                y = fCur
            } else if (frac > 0.0 && i0 == srcIndex - 1) {
                y = fPrev + (fCur - fPrev) * frac
            } else {
                break
            }

            out[outPos + produced] = y
            produced++
            outCount++
        }
        return produced
    }

    fun process(src: DoubleArray, n: Int, out: DoubleArray, ratio: Double): Int {
        this.ratio = ratio
        prepareCoeffs()

        var produced = 0
        for (si in 0 until n) {
            val x = src[si]
            ring[wp] = x

            var filtered = x
            if (useLpf) {
                filtered = 0.0
                var idx = wp
                for (k in 0 until RS_TAPS) {
                    filtered += coef[k] * ring[idx]
                    idx--
                    if (idx < 0) idx = RS_TAPS - 1
                }
            }

            wp++
            if (wp == RS_TAPS) wp = 0
            rawCount++

            var srcIndex = rawCount - 1
            if (useLpf) {
                if (rawCount <= RS_HALF) continue
                srcIndex = rawCount - 1 - RS_HALF
            }

            filteredCount = srcIndex + 1
            produced += emitAvailable(srcIndex, filtered, out, produced)
        }
        return produced
    }
}
