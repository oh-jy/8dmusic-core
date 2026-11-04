package j5i.spatial8d.core

import j5i.spatial8d.core.dsp.Resampler
import j5i.spatial8d.core.dsp.Wsola
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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

    @Test
    fun `long track pcm grows by decoded chunks instead of duration cap`() {
        val pcm = PcmBuffer(48_000, 2, false, 4_145_368_000L)
        val twoSeconds = ByteArray(48_000 * 2 * 4) { (it % 251).toByte() }
        val nextFrame = byteArrayOf(1, 2, 3, 4)

        pcm.append(twoSeconds)
        pcm.append(nextFrame)

        assertEquals(96_001, pcm.frames)
        assertEquals(4_145.368, pcm.estimatedDurationSec, 0.001)
        assertContentEquals(nextFrame, pcm.copyFrames(96_000, 1))
    }

    @Test
    fun `seeked pcm buffer uses global frame positions`() {
        val pcm = PcmBuffer(48_000, 2, false, 60_000_000L, baseFrame = 12_000)
        val chunk = ByteArray(8000) { (it % 199).toByte() }
        pcm.append(chunk)

        assertEquals(14_000, pcm.frames)
        assertEquals(12_000, pcm.retainedStartFrame())
        assertContentEquals(chunk, pcm.copyFrames(12_000, 2000))
        assertEquals(null, pcm.copyFrames(11_999, 1))
    }

    @Test
    fun `long track pcm spills to disk without losing frames`() {
        val directory = java.nio.file.Files.createTempDirectory("pcm-test-").toFile()
        val pcm = PcmBuffer(48_000, 2, false, 4_145_368_000L, directory,
            memoryLimitBytes = 64 * 1024 * 1024)
        val chunk = ByteArray(1024 * 1024) { (it % 251).toByte() }
        repeat(64) { assertTrue(pcm.append(chunk)) }
        assertTrue(!pcm.isDiskBacked)
        assertEquals(64L * chunk.size, pcm.bytes)
        assertTrue(pcm.append(chunk))
        assertTrue(pcm.isDiskBacked)
        assertEquals(65L * chunk.size, pcm.bytes)
        assertEquals(65 * chunk.size.toLong(), directory.listFiles().orEmpty().single().length())
        assertContentEquals(chunk, pcm.copyFrames(64 * chunk.size / 4, chunk.size / 4),
            "bytes=${pcm.bytes} diskBytes=${directory.listFiles().orEmpty().single().length()}")

        pcm.close()
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        directory.delete()
    }

    @Test
    fun `disk cache is bounded and resumes after backpressure`() {
        val directory = java.nio.file.Files.createTempDirectory("pcm-window-").toFile()
        val pcm = PcmBuffer(48_000, 2, false, 0L, directory, memoryLimitBytes = 1024 * 1024,
            diskLimitBytes = 2L * 1024 * 1024)
        val chunk = ByteArray(1024 * 1024) { (it % 251).toByte() }
        val framesPerChunk = chunk.size / 4
        assertTrue(pcm.append(chunk))
        assertTrue(pcm.append(chunk))
        assertTrue(pcm.isDiskBacked)
        assertTrue(!pcm.append(chunk), "decoder must retain the codec block when disk is full")
        assertEquals(2L * chunk.size, pcm.bytes)
        pcm.releaseBefore(2 * framesPerChunk)
        assertTrue(pcm.append(chunk), "append resumes after played PCM is released")

        repeat(125) {
            pcm.releaseBefore(pcm.frames)
            assertTrue(pcm.append(chunk))
        }

        assertEquals(128L * chunk.size, pcm.bytes)
        assertEquals(128 * framesPerChunk, pcm.frames)
        assertTrue(directory.listFiles().orEmpty().single().length() <= 2L * 1024 * 1024)
        assertContentEquals(chunk, pcm.copyFrames(127 * framesPerChunk, framesPerChunk))
        assertEquals(null, pcm.copyFrames(0, framesPerChunk))
        pcm.close()
        directory.delete()
    }
}
