package jiangyi.spatial8d.core

import java.nio.ByteBuffer
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PipelineTest {
    @Test
    fun `pcm handles stereo and reverse without byte reversal`() {
        val bytes = ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        repeat(4) { frame ->
            bytes.putShort((frame + 1).toShort())
            bytes.putShort((frame + 101).toShort())
        }
        val pcm = DecodedPcm(bytes.array(), 44100, 2, false, 0)
        val out = DoubleArray(4)
        pcm.frame(0, 4, out, reverse = true)
        val expected = doubleArrayOf(54.0, 53.0, 52.0, 51.0)
        out.forEachIndexed { index, value ->
            assertEquals(expected[index] / 32768.0, value, 1e-12)
        }
        assertEquals(104.0 / 32768.0, pcm.readChannel(3, 1), 1e-12)
    }

    @Test
    fun `identity preprocessing preserves signal`() {
        val input = FloatArray(8192) { sin(it / 32.0).toFloat() * 0.4f }
        val output = PretreatChain().run(input, TransformParams())
        assertContentEquals(input, output)
    }

    @Test
    fun `wav contains pcm stereo data`() {
        val input = FloatArray(2048) { sin(it / 32.0).toFloat() * 0.4f }
        val wav = renderSpatialWav(input, 44100, DEFAULT_SPACE_PRESET, false, false)
        val buffer = ByteBuffer.wrap(wav).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(44100, buffer.getInt(24))
        assertEquals(input.size * 4, buffer.getInt(40))
        assertEquals(44 + input.size * 4, wav.size)
    }

    @Test
    fun `raw renderer retains stereo`() {
        val pcm = ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        repeat(4) { frame ->
            pcm.putShort((frame + 1).toShort())
            pcm.putShort((frame + 101).toShort())
        }
        val raw = DecodedPcm(pcm.array(), 44100, 2, false, 0)
        val renderer = SpatialRenderer(44100.0)
        renderer.configure(SPACE_PRESETS[0])
        val output = ByteBuffer.wrap(renderer.render(raw.mono(), 0, 4, false, raw))
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(1.0, output.short.toDouble(), 1.0)
        assertEquals(101.0, output.short.toDouble(), 1.0)
        assertEquals(2.0, output.short.toDouble(), 1.0)
        assertEquals(102.0, output.short.toDouble(), 1.0)
    }
}
