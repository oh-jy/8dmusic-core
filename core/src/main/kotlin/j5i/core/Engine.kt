package j5i.spatial8d.core

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class SpaceEngine(var sr: Double) {
    private val bin = BinauralRenderer(sr)
    private val rev = RealtimeReverb(sr)

    private var period = 8.0
    private var depth01 = 0.45
    private var rawBypass = false

    private var dryL = DoubleArray(0)
    private var dryR = DoubleArray(0)
    private var wetL = DoubleArray(0)
    private var wetR = DoubleArray(0)
    private var cap = 0

    init {
        ensure(8192)
    }

    private fun ensure(n: Int) {
        if (n <= cap) return
        cap = n
        dryL = DoubleArray(n)
        dryR = DoubleArray(n)
        wetL = DoubleArray(n)
        wetR = DoubleArray(n)
    }

    fun setSpace(
        period: Double,
        elevDeg: Double,
        depthPct: Double,
        phaseDeg: Double,
        direction: Double,
        widthPct: Double,
        raw: Boolean
    ) {
        this.period = max(1.0, period)
        this.depth01 = min(1.0, max(0.0, depthPct / 100.0))
        this.rawBypass = raw
        bin.setParams(period, elevDeg, depthPct, phaseDeg, direction, widthPct, raw)
    }

    fun setRoom(sizeM: Double, rt60: Double, wetPct: Double) {
        rev.setParams(sizeM, rt60, wetPct)
    }

    fun rawEnabled(): Boolean = rawBypass

    fun processRawStereo(
        inL: DoubleArray,
        inR: DoubleArray,
        outL: DoubleArray,
        outR: DoubleArray,
        n: Int
    ) {
        for (i in 0 until n) {
            outL[i] = inL[i]
            outR[i] = inR[i]
        }
    }

    fun reset() {
        bin.reset()
        rev.reset()
    }

    fun process(input: DoubleArray, outL: DoubleArray, outR: DoubleArray, n: Int, t0: Double) {
        if (rawBypass) {
            bin.process(input, outL, outR, n, t0)
            return
        }
        ensure(n)
        bin.process(input, dryL, dryR, n, t0)
        rev.setAir(bin.airGain())
        rev.process(input, wetL, wetR, n)

        val d = depth01
        val BLK = 64
        var i = 0
        while (i < n) {
            val m = min(BLK, n - i)
            val f0 = farAt(t0 + i / sr, period)
            val f1 = farAt(t0 + (i + m) / sr, period)
            val gd0 = 1.0 - DRY_CUT * d * f0
            val gd1 = 1.0 - DRY_CUT * d * f1
            val gw0 = 1.0 + WET_LIFT * d * f0
            val gw1 = 1.0 + WET_LIFT * d * f1
            val inv = 1.0 / m
            for (k in 0 until m) {
                val u = (k + 1) * inv
                val gd = gd0 + (gd1 - gd0) * u
                val gw = gw0 + (gw1 - gw0) * u
                val j = i + k
                outL[j] = dryL[j] * gd + wetL[j] * gw
                outR[j] = dryR[j] * gd + wetR[j] * gw
            }
            i += m
        }
    }
}

class SlowLeveler(sr: Double, windowSec: Double) {
    private var env = 0.0
    private var a = exp(-1.0 / (windowSec * sr))
    private var gain = 1.0

    fun reset() {
        env = 0.0
        gain = 1.0
    }

    fun update(refRms: Double, outL: DoubleArray, outR: DoubleArray, n: Int): Double {
        var acc = 0.0
        for (i in 0 until n) {
            acc += (outL[i] * outL[i] + outR[i] * outR[i]) * 0.5
        }
        val rms = sqrt(acc / max(1, n))
        env = env * a + rms * (1.0 - a)
        if (env < 1e-6 || refRms < 1e-6) return 1.0
        val want = refRms / env
        gain = gain * 0.7 + want * 0.3
        return min(2.0, max(0.5, gain))
    }
}
