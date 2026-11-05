package j5i.spatial8d.core

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

private const val TWO_PI = 2.0 * Math.PI

fun distPeriod(period: Double): Double = max(8.0, min(16.0, period * 1.5))

fun farAt(t: Double, period: Double): Double {
    val w = TWO_PI / distPeriod(period)
    return 0.5 * (1.0 + sin(w * t))
}

fun azimuthAt(t: Double, period: Double, phaseDeg: Double, direction: Double): Double {
    if (direction == 0.0) return randomAzimuthAt(t, period, phaseDeg)
    val w = TWO_PI / max(1.0, period)
    return direction * w * t + phaseDeg * Math.PI / 180.0
}

fun randomAzimuthAt(t: Double, period: Double, phaseDeg: Double): Double {
    var tm = max(0.0, t)
    var k = 0
    var start = 0.0
    var dur = randomDirDuration(0)
    while (tm >= start + dur) {
        start += dur
        k++
        dur = randomDirDuration(k)
    }
    var angle = phaseDeg * Math.PI / 180.0
    for (i in 0 until k) {
        val d = randomDirSign(i) * TWO_PI / max(1.0, period)
        angle += d * randomDirDuration(i)
    }
    val cur = randomDirSign(k) * TWO_PI / max(1.0, period)
    return angle + cur * (tm - start)
}

fun randomDirDuration(index: Int): Double = 2.0 + stableRandom(index * 2.0 + 17.0) * 6.0

fun randomDirSign(index: Int): Double =
    if (stableRandom(index * 2.0 + 31.0) < 0.5) -1.0 else 1.0

private fun stableRandom(seed: Double): Double {
    val x = sin(seed * 12.9898 + 78.233) * 43758.5453
    return x - Math.floor(x)
}

fun elevationAt(
    t: Double,
    period: Double,
    elevDeg: Double,
    phaseDeg: Double,
    direction: Double
): Double {
    val th = azimuthAt(t, period, phaseDeg, direction)
    return (elevDeg * Math.PI / 180.0) * cos(th)
}

// Keep these analytic paths in sync with NativeSpatialExport.cpp for seek/export.
fun trajectoryAngles(t: Double, period: Double, phaseDeg: Double, direction: Double, elevDeg: Double, trajectory: Int): Pair<Double, Double> {
    val phase = phaseDeg * Math.PI / 180.0
    val theta = azimuthAt(t, period, phaseDeg, direction)
    val u = theta - phase
    val e = elevDeg * Math.PI / 180.0
    return when (trajectory) {
        1 -> (phase + Math.PI / 2 * sin(u)) to (e * sin(2 * u))
        2 -> phase to (e * sin(u))
        3 -> theta to (e * sin(u / 2))
        4 -> (phase + 1.3 * sin(.73 * u) + sin(.37 * u)) to (e * sin(.53 * u))
        else -> theta to (e * cos(theta))
    }
}

fun radiusAt(t: Double, period: Double, depth01: Double): Double {
    return 1.0 + depth01 * (2.0 * farAt(t, period) - 1.0)
}

fun sampleFar(period: Double, spanSec: Double, n: Int): DoubleArray {
    val out = DoubleArray(n)
    for (i in 0 until n) {
        val t = spanSec * i / (n - 1)
        out[i] = farAt(t, period)
    }
    return out
}

fun sampleLevels(
    period: Double,
    elevDeg: Double,
    phaseDeg: Double,
    direction: Double,
    spanSec: Double,
    n: Int
): DoubleArray {
    val out = DoubleArray(n * 2)
    for (i in 0 until n) {
        val t = spanSec * i / (n - 1)
        val s = sin(azimuthAt(t, period, phaseDeg, direction))
        val shl = 0.85 * (1.0 + s) * 0.5
        val shr = 0.85 * (1.0 - s) * 0.5
        val gl = 10.0.pow(-7.0 * shl / 20.0) * (1.0 - 0.18 * (0.5 - 0.5 * s))
        val gr = 10.0.pow(-7.0 * shr / 20.0) * (1.0 - 0.18 * (0.5 + 0.5 * s))
        out[i * 2] = max(0.0, min(1.0, (gl - 0.46) / 0.36))
        out[i * 2 + 1] = max(0.0, min(1.0, (gr - 0.46) / 0.36))
    }
    return out
}

fun sampleElev(
    period: Double,
    elevDeg: Double,
    phaseDeg: Double,
    direction: Double,
    spanSec: Double,
    n: Int
): DoubleArray {
    val out = DoubleArray(n)
    val e = max(1.0, elevDeg)
    for (i in 0 until n) {
        val t = spanSec * i / (n - 1)
        val ph = elevationAt(t, period, elevDeg, phaseDeg, direction)
        out[i] = ph / (e * Math.PI / 180.0)
    }
    return out
}

data class SpaceFrame(
    val azimuth: Double,
    val elev: Double,
    val far: Double,
    val radius: Double,
    val x: Double,
    val y: Double,
    val z: Double
)

fun frameAt(
    t: Double,
    period: Double,
    phaseDeg: Double,
    direction: Double,
    elevDeg: Double,
    depth01: Double,
    trajectory: Int = 0
): SpaceFrame {
    val (th, phi) = trajectoryAngles(t, period, phaseDeg, direction, elevDeg, trajectory)
    val f = farAt(t, period)
    val r = 1.0 + depth01 * (2.0 * f - 1.0)
    return SpaceFrame(
        azimuth = th,
        elev = phi,
        far = f,
        radius = r,
        x = sin(th) * r,
        y = cos(th) * r,
        z = sin(phi) * r
    )
}

fun earGains(s: Double): DoubleArray {
    val shl = 0.85 * (1.0 + s) * 0.5
    val shr = 0.85 * (1.0 - s) * 0.5
    val gl = 10.0.pow(-7.0 * shl / 20.0) * (1.0 - 0.18 * (0.5 - 0.5 * s))
    val gr = 10.0.pow(-7.0 * shr / 20.0) * (1.0 - 0.18 * (0.5 + 0.5 * s))
    return doubleArrayOf(gl, gr)
}

fun earRange(): DoubleArray {
    var lo = 9.0
    var hi = -9.0
    for (i in 0..80) {
        val g = earGains(-1.0 + 2.0 * i / 80.0)
        if (g[0] < lo) lo = g[0]
        if (g[0] > hi) hi = g[0]
        if (g[1] < lo) lo = g[1]
        if (g[1] > hi) hi = g[1]
    }
    return doubleArrayOf(lo, hi)
}
