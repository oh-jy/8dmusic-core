package j5i.spatial8d.core.dsp

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val WS_WIN = 2048
private const val WS_HOP = 1024
private const val WS_SEARCH = 512
private const val WS_STEP = 8

class Wsola(
    private val blockMax: Int = 8192,
    private val win: Int = WS_WIN,
    private val hop: Int = WS_HOP,
    private val search: Int = WS_SEARCH,
    private val step: Int = WS_STEP
) {
    private val stepSafe = max(1, step)
    private val ll = win - hop
    private val w = DoubleArray(win)

    private val q = DoubleArray(blockMax + 2 * win + 2 * search)
    private var qLen = 0
    private var qBase = 0

    private var ana = 0.0
    private var hasPrev = false
    private var prevSel = 0
    private var ratio = 1.0

    private val num = DoubleArray(win + hop)
    private val den = DoubleArray(win + hop)
    private var olaBase = 0
    private var k = 0

    init {
        for (i in 0 until win) {
            w[i] = 0.5 - 0.5 * cos(2.0 * Math.PI * i.toDouble() / (win - 1).toDouble())
        }
    }

    fun reset() {
        qLen = 0
        qBase = 0
        ana = 0.0
        hasPrev = false
        prevSel = 0
        olaBase = 0
        k = 0
        ratio = 1.0
        num.fill(0.0)
        den.fill(0.0)
    }

    private fun append(src: DoubleArray, n: Int) {
        if (qLen + n > q.size) compactQ()
        for (i in 0 until n) q[qLen + i] = src[i]
        qLen += n
    }

    private fun compactQ() {
        // q 已按 dropBefore 紧凑存放，这里仅作容量兜底。
    }

    private fun dropBefore(boundAbs: Int) {
        val cut = boundAbs - qBase
        if (cut <= 0) return
        val c = min(cut, qLen - 1)
        if (c <= 0) return
        for (i in 0 until qLen - c) q[i] = q[i + c]
        qLen -= c
        qBase += c
    }

    private fun nxc(c0: Int, r0: Int): Double {
        var sa = 0.0
        var sb = 0.0
        var dot = 0.0
        for (j in 0 until ll) {
            val a = q[c0 + j]
            val b = q[r0 + j]
            sa += a * a
            sb += b * b
            dot += a * b
        }
        val d = sa * sb
        if (d <= 1e-12) return -1.0
        return dot / sqrt(d)
    }

    fun process(src: DoubleArray, n: Int, out: DoubleArray, ratio: Double): Int {
        this.ratio = ratio
        append(src, n)
        var produced = 0
        while (emitOne(false, out, produced)) {
            produced += hop
        }
        return produced
    }

    fun flush(out: DoubleArray, outStart: Int): Int {
        var produced = 0
        var guard = 0
        while (guard < 1000000) {
            guard++
            val before = k
            val more = emitOne(true, out, outStart + produced)
            if (k > before) produced += hop
            if (!more) break
        }
        var end = 0
        for (i in 0 until win + hop) {
            if (den[i] >= 0.5) end = i + 1
        }
        for (i in 0 until end) {
            val d = den[i]
            out[outStart + produced + i] = if (d >= 1e-6) num[i] / d else num[i]
        }
        return produced + end
    }

    private fun emitOne(ending: Boolean, out: DoubleArray, op: Int): Boolean {
        val nominal = ana.roundToInt()
        val qEndAbs = qBase + qLen

        if (!ending) {
            val hi = nominal + search + win
            val lo = if (hasPrev) prevSel + hop else nominal - search
            if (qBase > lo || qEndAbs < hi) return false
        }
        if (qEndAbs < nominal + win) return false

        var sel = nominal
        if (hasPrev) {
            val refAbs = prevSel + hop
            val r0 = refAbs - qBase
            if (r0 < 0 || r0 + ll > qLen) {
                if (!ending) return false
            } else {
                var bestD = 0
                var bestV = -2.0
                var d = -search
                while (d <= search) {
                    val c0 = nominal + d - qBase
                    if (c0 >= 0 && c0 + ll <= qLen) {
                        val v = nxc(c0, r0)
                        if (v > bestV) {
                            bestV = v
                            bestD = d
                        }
                    }
                    d += stepSafe
                }
                val dLo = max(-search, bestD - stepSafe)
                val dHi = min(search, bestD + stepSafe)
                var d2 = dLo
                while (d2 <= dHi) {
                    val c0 = nominal + d2 - qBase
                    if (c0 >= 0 && c0 + ll <= qLen) {
                        val v = nxc(c0, r0)
                        if (v > bestV) {
                            bestV = v
                            bestD = d2
                        }
                    }
                    d2++
                }
                sel = nominal + bestD
            }
        }

        val s0 = sel - qBase
        if (s0 < 0 || s0 + win > qLen) return false

        val off = k * hop - olaBase
        for (j in 0 until win) {
            val wv = w[j]
            val x = q[s0 + j]
            num[off + j] += wv * x
            den[off + j] += wv
        }

        prevSel = sel
        hasPrev = true
        ana += hop.toDouble() / ratio
        k++

        val boundary = k * hop
        val cnt = boundary - olaBase
        if (cnt > 0) {
            for (i in 0 until cnt) out[op + i] = num[i]
            val keep = win + hop - cnt
            for (i in 0 until keep) {
                num[i] = num[i + cnt]
                den[i] = den[i + cnt]
            }
            for (i in keep until win + hop) {
                num[i] = 0.0
                den[i] = 0.0
            }
            olaBase += cnt
        }

        val keepLo = prevSel + hop
        val futureLo = ana.roundToInt() - search
        val bound = min(keepLo, futureLo)
        dropBefore(bound)
        return true
    }
}
