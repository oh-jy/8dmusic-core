package j5i.spatial8d.core

import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

class ReverbTest {
    @Test fun `center input keeps reverb energy balanced`() {
        val sampleRate = 44100.0
        val reverb = RealtimeReverb(sampleRate)
        reverb.setParams(10.0, 1.2, 18.0)
        val blockSize = 4096
        val input = DoubleArray(blockSize)
        val left = DoubleArray(blockSize)
        val right = DoubleArray(blockSize)
        var leftEnergy = 0.0
        var rightEnergy = 0.0
        val blocks = (sampleRate * 20 / blockSize).toInt()
        repeat(blocks) { block ->
            for (i in input.indices) input[i] = 0.2 * kotlin.math.sin(2.0 * PI * 440.0 * (block * blockSize + i) / sampleRate)
            reverb.process(input, left, right, input.size)
            if (block * blockSize >= sampleRate * 2) {
                for (i in input.indices) {
                    leftEnergy += left[i] * left[i]
                    rightEnergy += right[i] * right[i]
                }
            }
        }
        val ratio = sqrt(leftEnergy / rightEnergy)
        assertTrue(ratio in 0.9..1.1, "center reverb L/R ratio=$ratio")
    }
}
