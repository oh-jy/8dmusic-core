package j5i.spatial8d.core

import j5i.spatial8d.core.dsp.Resampler
import j5i.spatial8d.core.dsp.Wsola
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

class CoreSmokeTest {
    @Test
    fun `space engine renders non silence`() {
        val sr = 44100.0
        val engine = SpaceEngine(sr)
        engine.setSpace(8.0, 35.0, 45.0, 0.0, 1.0, 100.0, false)
        engine.setRoom(10.0, 1.2, 18.0)
        val n = 4096
        val input = DoubleArray(n)
        val l = DoubleArray(n)
        val r = DoubleArray(n)
        for (i in 0 until n) {
            input[i] = 0.35 * sin(2.0 * Math.PI * 440.0 * i / sr)
        }
        engine.process(input, l, r, n, 0.0)
        var energy = 0.0
        for (i in 0 until n) {
            energy += l[i] * l[i] + r[i] * r[i]
        }
        assertTrue(energy > 1.0, "SpaceEngine should render audio")
    }

    @Test
    fun `wsola and resampler produce samples`() {
        val sr = 44100.0
        val n = 8192
        val input = DoubleArray(n)
        for (i in 0 until n) input[i] = 0.5 * sin(2.0 * Math.PI * 440.0 * i / sr)

        val wsola = Wsola()
        val outW = DoubleArray(65536)
        val w1 = wsola.process(input, n, outW, 1.5)
        val w2 = wsola.flush(outW, w1)
        assertTrue(w1 + w2 > n, "WSOLA should stretch input")

        val rs = Resampler()
        val outR = DoubleArray(65536)
        val r1 = rs.process(input, n, outR, 1.2)
        assertTrue(r1 > 0 && r1 < n, "Resampler should resample input")
    }
}
