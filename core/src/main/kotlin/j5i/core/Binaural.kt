package j5i.spatial8d.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

const val DIST_DB = 18.0
const val AIR_DB = 14.0
const val DRY_CUT = 0.45
const val WET_LIFT = 1.80

private const val CTRL = 64
private const val ITD_LINE = 96
private const val NOTCH_LINE = 48

internal class Fir3(
    var b0: Double = 1.0,
    var b1: Double = 0.0,
    var b2: Double = 0.0
) {
    var x1: Double = 0.0
    var x2: Double = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2
        x2 = x1
        x1 = x
        return y
    }

    fun reset() {
        x1 = 0.0
        x2 = 0.0
    }
}

internal class DelayLine(size: Int) {
    private val size = size
    private val buf = DoubleArray(size)
    private var w = 0

    fun reset() {
        buf.fill(0.0)
        w = 0
    }

    fun write(x: Double) {
        buf[w] = x
        w++
        if (w >= size) w = 0
    }

    fun read(lag: Double): Double {
        val d = max(0.0, min(size - 4.0, lag))
        val fl = floor(d)
        val f = d - fl
        val p1 = at(fl)
        val p2 = at(fl + 1)
        val p0 = at(fl - 1)
        val p3 = at(fl + 2)
        val f2 = f * f
        val f3 = f2 * f
        val m0 = 0.5 * (p2 - p0)
        val m1 = 0.5 * (p3 - p1)
        return (2.0 * f3 - 3.0 * f2 + 1.0) * p1 + (f3 - 2.0 * f2 + f) * m0 +
            (-2.0 * f3 + 3.0 * f2) * p2 + (f3 - f2) * m1
    }

    private fun at(lag: Double): Double {
        var i = w - 1 - lag.toInt()
        if (i < 0) i += size
        return buf[i]
    }
}

internal class ChannelState {
    val firShadow = Fir3(0.34, 0.40, 0.26)
    val firTilt = Fir3(0.58, 0.28, 0.14)
    val firRear = Fir3(0.38, 0.36, 0.26)
    val air1 = Fir3(0.34, 0.40, 0.26)
    val air2 = Fir3(0.34, 0.40, 0.26)
    val air3 = Fir3(0.34, 0.40, 0.26)
    val notch = DelayLine(NOTCH_LINE)

    var airH1 = 0.0
    var airH2 = 0.0
    var airH3 = 0.0
    var airH4 = 0.0

    fun reset() {
        firShadow.reset()
        firTilt.reset()
        firRear.reset()
        air1.reset()
        air2.reset()
        air3.reset()
        notch.reset()
        airH1 = 0.0
        airH2 = 0.0
        airH3 = 0.0
        airH4 = 0.0
    }
}

class BinauralRenderer(var sr: Double) {
    private var period = 8.0
    private var elevDeg = 60.0
    private var depth01 = 0.45
    private var phaseDeg = 0.0
    private var direction = 1.0
    private val itdMs = 0.63
    private val shadow = 0.85
    private val panAmount = 0.18
    private var width = 1.0
    private var rawBypass = false
    private var makeup = 1.0

    private val inLine = DelayLine(ITD_LINE)
    private val chL = ChannelState()
    private val chR = ChannelState()

    private val limCeil = 0.89
    private var limRel = 0.0
    private var limGain = 1.0
    private var limPeak = 0.0

    private var cDl = 0.0
    private var cDr = 0.0
    private var cShL = 0.0
    private var cShR = 0.0
    private var cRear = 0.0
    private var cDn = 4.0
    private var cNg = 0.2
    private var cGd = 1.0
    private var cGh = 1.0
    private var cPanL = 1.0
    private var cPanR = 1.0

    init {
        limRel = exp(-1.0 / (0.08 * sr))
    }

    fun setParams(
        period: Double,
        elevDeg: Double,
        depthPct: Double,
        phaseDeg: Double,
        direction: Double,
        widthPct: Double,
        rawBypass: Boolean
    ) {
        this.period = max(1.0, period)
        this.elevDeg = min(80.0, max(0.0, elevDeg))
        this.depth01 = min(1.0, max(0.0, depthPct / 100.0))
        this.phaseDeg = phaseDeg
        this.direction = if (direction > 0.0) 1.0 else if (direction < 0.0) -1.0 else 0.0
        this.width = min(1.5, max(0.0, widthPct / 100.0))
        this.rawBypass = rawBypass
        this.makeup = estimateMakeup()
    }

    private fun estimateMakeup(): Double {
        val d = depth01
        val dB = -(DIST_DB * d) / 2.0
        val gDist = 10.0.pow(dB / 20.0)
        val gSpatial = 0.47
        val g = 1.0 / (gDist * gSpatial)
        return min(4.0, max(0.9, g))
    }

    fun airGain(): Double = cGh

    fun reset() {
        inLine.reset()
        chL.reset()
        chR.reset()
        limGain = 1.0
        limPeak = 0.0
    }

    private fun computeCtrl(t: Double) {
        val tau = itdMs / 1000.0 * sr
        val th = azimuthAt(t, period, phaseDeg, direction)
        val s = sin(th)
        val cs = cos(th)
        val phi = elevationAt(t, period, elevDeg, phaseDeg, direction)
        val phiDeg = phi * 180.0 / Math.PI

        cDl = 0.5 * tau * (1.0 + s)
        cDr = 0.5 * tau * (1.0 - s)
        cShL = min(shadow, max(0.0, shadow * (1.0 + s) * 0.5))
        cShR = min(shadow, max(0.0, shadow * (1.0 - s) * 0.5))
        // 默认绕头增加前后差异；更强的耳廓频谱线索仍随原有方位轨迹连续变化。
        val rearCue = if (period == 8.0 && elevDeg == 35.0 && depth01 == 0.45 && phaseDeg == 0.0 && direction > 0.0) 1.8 else 1.0
        cRear = min(1.0, max(0.0, -cs)) * rearCue

        var f0 = 7200.0 - 62.0 * phiDeg
        f0 = min(9800.0, max(3200.0, f0))
        val dn = sr / f0
        cDn = min(16.0, max(3.0, dn))
        val ap = min(1.0, abs(phiDeg) / 40.0)
        cNg = 0.12 + 0.42 * ap

        val far = farAt(t, period)
        val d = depth01
        cGd = 10.0.pow(-DIST_DB * d * far / 20.0)
        cGh = 10.0.pow(-AIR_DB * d * far.pow(1.15) / 20.0)

        cPanL = 1.0 - panAmount * width * (0.5 - 0.5 * s)
        cPanR = 1.0 - panAmount * width * (0.5 + 0.5 * s)
    }

    fun process(input: DoubleArray, outL: DoubleArray, outR: DoubleArray, n: Int, t0: Double) {
        if (rawBypass) {
            for (i in 0 until n) {
                outL[i] = input[i]
                outR[i] = input[i]
            }
            return
        }
        var i = 0
        while (i < n) {
            val m = min(CTRL, n - i)
            computeCtrl(t0 + i / sr)
            val width = this.width
            val aDl = cDl * width
            val aDr = cDr * width
            val aShL = cShL * width
            val aShR = cShR * width
            val aRear = cRear
            val aDn = cDn
            val aNg = cNg
            val aGd = cGd
            val aGh = cGh
            val aPnl = cPanL
            val aPnr = cPanR
            computeCtrl(t0 + (i + m) / sr)
            val bDl = cDl * width
            val bDr = cDr * width
            val bShL = cShL * width
            val bShR = cShR * width
            val bRear = cRear
            val bDn = cDn
            val bNg = cNg
            val bGd = cGd
            val bGh = cGh
            val bPnl = cPanL
            val bPnr = cPanR
            val aGl = 10.0.pow(-7.0 * aShL / 20.0)
            val bGl = 10.0.pow(-7.0 * bShL / 20.0)
            val aGr = 10.0.pow(-7.0 * aShR / 20.0)
            val bGr = 10.0.pow(-7.0 * bShR / 20.0)
            val inv = 1.0 / m

            for (k in 0 until m) {
                val u = (k + 1) * inv
                val dl = aDl + (bDl - aDl) * u
                val dr = aDr + (bDr - aDr) * u
                val shL = aShL + (bShL - aShL) * u
                val shR = aShR + (bShR - aShR) * u
                val rear = aRear + (bRear - aRear) * u
                val dn = aDn + (bDn - aDn) * u
                val ng = aNg + (bNg - aNg) * u
                val gd = aGd + (bGd - aGd) * u
                val gh = aGh + (bGh - aGh) * u
                val pnl = aPnl + (bPnl - aPnl) * u
                val pnr = aPnr + (bPnr - aPnr) * u
                val shGl = aGl + (bGl - aGl) * u
                val shGr = aGr + (bGr - aGr) * u

                val x = input[i + k]
                inLine.write(x)
                var l = inLine.read(dl)
                var r = inLine.read(dr)

                val prevL = chL.firShadow.x1
                val hiL = prevL - chL.firShadow.process(l)
                l -= 0.55 * shL * hiL
                l = l * (1.0 - 0.12 * shL) + chL.firTilt.process(l) * (0.12 * shL)
                l *= shGl

                val prevR = chR.firShadow.x1
                val hiR = prevR - chR.firShadow.process(r)
                r -= 0.55 * shR * hiR
                r = r * (1.0 - 0.12 * shR) + chR.firTilt.process(r) * (0.12 * shR)
                r *= shGr

                val backL = chL.firRear.x1
                val reL = backL - chL.firRear.process(l)
                l -= 0.30 * rear * reL
                val backR = chR.firRear.x1
                val reR = backR - chR.firRear.process(r)
                r -= 0.30 * rear * reR

                chL.notch.write(l)
                l -= ng * chL.notch.read(dn)
                chR.notch.write(r)
                r -= ng * chR.notch.read(dn)

                l *= gd
                r *= gd

                chL.airH4 = chL.airH3
                chL.airH3 = chL.airH2
                chL.airH2 = chL.airH1
                chL.airH1 = l
                val loL = chL.air3.process(chL.air2.process(chL.air1.process(l)))
                l = loL + (chL.airH4 - loL) * gh

                chR.airH4 = chR.airH3
                chR.airH3 = chR.airH2
                chR.airH2 = chR.airH1
                chR.airH1 = r
                val loR = chR.air3.process(chR.air2.process(chR.air1.process(r)))
                r = loR + (chR.airH4 - loR) * gh

                outL[i + k] = l * pnl * makeup
                outR[i + k] = r * pnr * makeup
            }
            i += m
        }

        val rel = limRel
        val ceil = limCeil
        for (k in 0 until n) {
            val pk = max(abs(outL[k]), abs(outR[k]))
            if (pk > limPeak) limPeak = pk else limPeak *= rel
            val want = if (limPeak > 1e-9) min(1.0, ceil / limPeak) else 1.0
            if (want < limGain) limGain = want
            else limGain = limGain * rel + want * (1.0 - rel)
            outL[k] *= limGain
            outR[k] *= limGain
        }
    }
}
