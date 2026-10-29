package j5i.spatial8d.core

import j5i.spatial8d.core.dsp.Resampler
import j5i.spatial8d.core.dsp.Wsola
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

private const val PB_BLOCK = 8192
private const val PB_CAP = PB_BLOCK * 4 + 8 * 2048

class PretreatChain {
    fun run(
        mono: FloatArray,
        params: TransformParams,
        onProgress: ((Int) -> Unit)? = null
    ): FloatArray {
        val doSpeed = !params.tape && abs(params.speed - 1.0) >= 1e-9
        val doTape = params.tape && abs(params.speed - 1.0) >= 1e-9
        val pitchRatio = 2.0.pow(params.semi / 12.0)
        val doPitch = params.semi != 0.0
        val output = FloatArray(mono.size * 4 + 65536)
        if (!doSpeed && !doTape && !doPitch) {
            onProgress?.invoke(100)
            return mono.copyOf()
        }

        val wsSpeed = Wsola(PB_BLOCK)
        val rsTape = Resampler(PB_BLOCK)
        val rsPitch = Resampler(PB_BLOCK)
        val wsPitch = Wsola(PB_BLOCK)
        val inB = DoubleArray(PB_BLOCK)
        val b1 = DoubleArray(PB_CAP)
        val b2 = DoubleArray(PB_CAP)
        val b3 = DoubleArray(PB_CAP)
        var written = 0
        var pos = 0
        while (pos < mono.size) {
            val n = min(PB_BLOCK, mono.size - pos)
            val last = pos + n >= mono.size
            for (i in 0 until n) inB[i] = mono[pos + i].toDouble()
            val l1 = when {
                doSpeed -> wsolaPush(wsSpeed, inB, n, last, b1, 1.0 / params.speed)
                doTape -> rsTape.process(inB, n, b1, params.speed)
                else -> n.also { inB.copyInto(b1, 0, 0, n) }
            }
            val l2 = if (doPitch) rsPitch.process(b1, l1, b2, pitchRatio)
            else l1.also { b1.copyInto(b2, 0, 0, l1) }
            val l3 = if (doPitch) wsolaPush(wsPitch, b2, l2, last, b3, pitchRatio)
            else l2.also { b2.copyInto(b3, 0, 0, l2) }
            check(written + l3 <= output.size) { "预处理输出超出预期容量" }
            for (i in 0 until l3) output[written + i] = b3[i].toFloat()
            written += l3
            pos += n
            onProgress?.invoke(min(99, pos * 100 / mono.size))
        }
        onProgress?.invoke(100)
        return output.copyOf(written)
    }

    private fun wsolaPush(
        wsola: Wsola,
        src: DoubleArray,
        n: Int,
        last: Boolean,
        out: DoubleArray,
        ratio: Double
    ): Int {
        var produced = wsola.process(src, n, out, ratio)
        if (last) produced += wsola.flush(out, produced)
        return produced
    }
}
