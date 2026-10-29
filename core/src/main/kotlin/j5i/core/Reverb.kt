package j5i.spatial8d.core

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val COMB_D = doubleArrayOf(1116.0, 1188.0, 1277.0, 1356.0, 1422.0, 1491.0, 1557.0, 1617.0)
private val ALLP_D = doubleArrayOf(556.0, 441.0, 341.0, 225.0)
private const val STEREO_SPREAD = 23.0
private const val FREEVERB_SR = 44100.0
private const val MAX_SCALE = 5.0
private const val RT_CAL = 1.30

internal class Comb(maxLen: Int, len: Int) {
    private val maxLen = max(4, maxLen)
    private val buf = DoubleArray(maxLen)
    var len = 0
    private var idx = 0
    private var store = 0.0
    var fb = 0.8
    var damp = 0.3

    init {
        resize(len)
    }

    fun resize(n: Int) {
        val l = min(maxLen, max(4, n))
        if (l == len) return
        len = l
        if (idx >= l) idx = 0
    }

    fun process(x: Double): Double {
        val y = buf[idx]
        store = y * (1.0 - damp) + store * damp
        buf[idx] = x + store * fb
        var n = idx + 1
        if (n >= len) n = 0
        idx = n
        return y
    }

    fun clear() {
        buf.fill(0.0)
        idx = 0
        store = 0.0
    }
}

internal class Allpass(maxLen: Int, len: Int) {
    private val maxLen = max(4, maxLen)
    private val buf = DoubleArray(maxLen)
    var len = 0
    private var idx = 0

    init {
        resize(len)
    }

    fun resize(n: Int) {
        val l = min(maxLen, max(4, n))
        if (l == len) return
        len = l
        if (idx >= l) idx = 0
    }

    fun process(x: Double): Double {
        val bo = buf[idx]
        val y = -x + bo
        buf[idx] = x + bo * 0.5
        var n = idx + 1
        if (n >= len) n = 0
        idx = n
        return y
    }

    fun clear() {
        buf.fill(0.0)
        idx = 0
    }
}

internal class Tri {
    var x1 = 0.0
    var x2 = 0.0

    fun process(x: Double): Double {
        val y = 0.34 * x + 0.40 * x1 + 0.26 * x2
        x2 = x1
        x1 = x
        return y
    }

    fun reset() {
        x1 = 0.0
        x2 = 0.0
    }
}

internal class HighPass(sr: Double, fc: Double) {
    private val a: Double
    private var x1 = 0.0
    private var y1 = 0.0

    init {
        val rc = 1.0 / (2.0 * Math.PI * fc)
        val dt = 1.0 / sr
        a = rc / (rc + dt)
    }

    fun process(x: Double): Double {
        val y = a * (y1 + x - x1)
        x1 = x
        y1 = y
        return y
    }

    fun clear() {
        x1 = 0.0
        y1 = 0.0
    }
}

internal class RevChannel(sr: Double, spread: Double) {
    val combs = mutableListOf<Comb>()
    val aps = mutableListOf<Allpass>()
    var hp = HighPass(sr, 150.0)
    val pre = DoubleArray((0.30 * sr).roundToInt())
    var preIdx = 0
    var preLen = 1

    init {
        val k = sr / FREEVERB_SR
        for (i in COMB_D.indices) {
            val base = (COMB_D[i] + spread) * k
            combs.add(Comb((base * MAX_SCALE).roundToInt(), base.roundToInt()))
        }
        for (i in ALLP_D.indices) {
            val base = (ALLP_D[i] + spread) * k
            aps.add(Allpass((base * MAX_SCALE).roundToInt(), base.roundToInt()))
        }
    }

    fun setPredelay(samples: Double) {
        preLen = max(1, min(pre.size, samples.roundToInt()))
    }

    fun process(x: Double): Double {
        val out = pre[preIdx]
        pre[preIdx] = x
        var nx = preIdx + 1
        if (nx >= preLen) nx = 0
        preIdx = nx
        var s = 0.0
        for (c in combs) s += c.process(out)
        s *= 1.0 / combs.size
        for (p in aps) s = p.process(s)
        return hp.process(s)
    }

    fun clear() {
        for (c in combs) c.clear()
        for (p in aps) p.clear()
        hp.clear()
        pre.fill(0.0)
        preIdx = 0
    }
}

class RealtimeReverb(var sr: Double) {
    private var sizeM = 10.0
    private var rt60 = 1.2
    private var wetPct = 18.0

    private val left = RevChannel(sr, 0.0)
    private val right = RevChannel(sr, STEREO_SPREAD)

    private val air1 = Tri()
    private val air2 = Tri()
    private val air3 = Tri()
    private var ah1 = 0.0
    private var ah2 = 0.0
    private var ah3 = 0.0
    private var ah4 = 0.0
    private var airGh = 1.0

    private var dryEnv = 0.0
    private var wetEnv = 0.0
    private var autoGain = 1.0
    private var envA = 0.0
    private var gainA = 0.0

    init {
        envA = exp(-1.0 / (0.10 * sr))
        gainA = exp(-1.0 / (0.40 * sr))
        setParams(10.0, 1.2, 18.0)
    }

    fun setAir(gh: Double) {
        airGh = min(1.0, max(0.02, gh))
    }

    fun setParams(sizeM: Double, rt60: Double, wetPct: Double) {
        this.sizeM = min(50.0, max(3.0, sizeM))
        this.rt60 = min(5.0, max(0.0, rt60))
        if (this.wetPct < 0.6 && this.rt60 < 0.01) {
            for (c in 0 until 2) {
                val ch = if (c == 0) left else right
                for (i in ch.combs.indices) ch.combs[i].fb = 0.0
            }
            return
        }
        this.wetPct = min(100.0, max(0.0, wetPct))

        val L = this.sizeM
        val pre = L / 343.0 * sr
        left.setPredelay(pre)
        right.setPredelay(pre)

        val scale = min(MAX_SCALE, max(0.55, L / 10.0))
        for (c in 0 until 2) {
            val ch = if (c == 0) left else right
            val spread = if (c == 0) 0.0 else STEREO_SPREAD
            for (i in ch.combs.indices) {
                val base = (COMB_D[i] + spread) * sr / FREEVERB_SR * scale
                ch.combs[i].resize(base.roundToInt())
                val dSec = ch.combs[i].len / sr
                ch.combs[i].fb = min(0.98, 10.0.pow(-3.0 * dSec / (this.rt60 * RT_CAL)))
                val fc = min(9000.0, max(3500.0, 9000.0 - 120.0 * L))
                ch.combs[i].damp = exp(-2.0 * Math.PI * fc / sr)
            }
            for (i in ch.aps.indices) {
                ch.aps[i].resize(((ALLP_D[i] + spread) * sr / FREEVERB_SR * scale).roundToInt())
            }
        }
    }

    fun reset() {
        left.clear()
        right.clear()
        air1.reset()
        air2.reset()
        air3.reset()
        ah1 = 0.0
        ah2 = 0.0
        ah3 = 0.0
        ah4 = 0.0
        dryEnv = 0.0
        wetEnv = 0.0
        autoGain = 1.0
    }

    fun process(input: DoubleArray, wetL: DoubleArray, wetR: DoubleArray, n: Int) {
        val userWet = wetPct / 100.0
        if (userWet <= 0.005) {
            for (i in 0 until n) {
                wetL[i] = 0.0
                wetR[i] = 0.0
            }
            return
        }
        val ea = envA
        val ga = gainA
        val sizeGain = 1.0 + 0.006 * (sizeM - 10.0)
        val target = 1.25 * userWet * sizeGain

        val gh = airGh
        for (i in 0 until n) {
            val lo = air3.process(air2.process(air1.process(input[i])))
            ah4 = ah3
            ah3 = ah2
            ah2 = ah1
            ah1 = input[i]
            val x = lo + (ah4 - lo) * gh

            var l = left.process(x)
            var r = right.process(x)

            dryEnv = dryEnv * ea + x * x * (1.0 - ea)
            val wp = (l * l + r * r) * 0.5
            wetEnv = wetEnv * ea + wp * (1.0 - ea)
            if (wetEnv > 1e-6 && dryEnv > 1e-6) {
                val want = sqrt(dryEnv / wetEnv)
                autoGain = autoGain * ga + want * (1.0 - ga)
            }
            val g = min(2.0, max(0.35, autoGain)) * target
            wetL[i] = l * g
            wetR[i] = r * g
        }
    }
}
