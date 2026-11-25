package j5i.spatial8d.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DualAudioTest {
    @Test fun volumeBalanceAttenuatesLouderSideAndRestoresStereoTail() {
        val rate = 1000
        val count = 12000
        val buffer = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(count) { buffer.putShort(12000); buffer.putShort(3000) }
        val bytes = buffer.array()
        DualVolumeBalancer(rate).process(bytes, 0, count, 8000)
        val result = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertTrue(result.getShort(7999 * 4).toInt() in 2900..3300)
        assertEquals(3000, result.getShort(7999 * 4 + 2).toInt())
        assertTrue(result.getShort(11999 * 4).toInt() > 10000)
    }

    @Test fun volumeBalanceDoesNotMuteSongDuringOtherSideSilence() {
        val buffer = ByteBuffer.allocate(4000).order(ByteOrder.LITTLE_ENDIAN)
        repeat(1000) { buffer.putShort(12000); buffer.putShort(0) }
        val bytes = buffer.array()
        DualVolumeBalancer(1000).process(bytes, 0, 1000, 1000)
        assertEquals(12000, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getShort(3996).toInt())
    }

    private fun source(rate: Int, channels: Int, vararg samples: Short): PcmBuffer {
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bytes.putShort(it) }
        return PcmBuffer(rate, channels, false).apply { append(bytes.array()); decodeDone = true }
    }

    @Test fun differentRatesAndStereoTail() {
        val short = source(4, 1, 10000, 10000)
        val long = source(2, 2, 4000, 8000, 12000, 16000, 20000, 24000)
        val output = assertNotNull(DualAudio.render(short, long, 4, 0, 6))
        val frames = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(10000, frames.short.toInt())
        assertEquals(6000, frames.short.toInt())
        frames.position(8)
        assertEquals(12000, frames.short.toInt())
        assertEquals(16000, frames.short.toInt())
    }

    @Test fun swapDoesNotReverseStereoTail() {
        val buffer = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        DualAudio.swap(buffer, 0, 2, 1) { true }
        assertContentEquals(byteArrayOf(3, 4, 1, 2, 5, 6, 7, 8), buffer)
    }

    @Test fun scheduleIsDeterministicForSeekAndBlocks() {
        val sequential = DualSwapTimeline(2, 4, 10)
        val randomAccess = DualSwapTimeline(2, 4, 10)
        val expected = (0L..1000L).map { sequential.swappedAt(it) }
        assertTrue(expected.contains(true) && expected.contains(false))
        for (frame in (0..1000).reversed()) assertEquals(expected[frame], randomAccess.swappedAt(frame.toLong()))
    }

    @Test fun swapFadeContinuesAcrossBlocksAndSeek() {
        fun block(count: Int): ByteArray = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(count) { putShort(10000); putShort(-10000) }
        }.array()
        val fader = DualSwapFader(1000)
        val first = block(8)
        DualAudio.swap(first, 0, 8, Int.MAX_VALUE, fader) { it >= 4 }
        val second = block(10)
        DualAudio.swap(second, 8, 10, Int.MAX_VALUE, fader) { it >= 4 }
        val middle = ByteBuffer.wrap(second).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        assertTrue(middle in 1000..3000)
        val seek = block(10)
        DualAudio.swap(seek, 8, 10, Int.MAX_VALUE, DualSwapFader(1000)) { it >= 4 }
        assertContentEquals(second, seek)
    }

    @Test fun manualSwapFadesWithoutRewritingPastTimeline() {
        val fader = DualSwapFader(1000)
        var manual = false
        repeat(8) { assertEquals(0.0, fader.amountAt(it) { manual }) }
        manual = true
        assertEquals(0.0, fader.amountAt(8) { manual })
        assertTrue(fader.amountAt(9) { manual } in 0.0..0.2)
        repeat(9) { fader.amountAt(10 + it) { manual } }
        assertEquals(1.0, fader.amountAt(19) { manual })
    }
}
