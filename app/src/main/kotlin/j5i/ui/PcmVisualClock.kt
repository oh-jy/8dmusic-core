package j5i.spatial8d.ui

import android.os.SystemClock
import j5i.spatial8d.visual.PerfTrace
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** 所有 PCM 动效共用同一延迟队列，避免顶部和可视化各自积压不同的音频历史。 */
class PcmVisualClock {
    private data class Chunk(val atNs: Long, val rms: Double, val peak: Double)
    private val chunks = ArrayDeque<Chunk>()
    var rms = 0.0; private set
    var peak = 0.0; private set

    @Synchronized fun accept(data: ByteArray, bytes: Int) {
        val startedAt = System.nanoTime()
        val input = ByteBuffer.wrap(data, 0, bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var sum = 0.0; var peak = 0.0; var count = 0
        while (input.hasRemaining()) {
            val sample = input.get() / 32768.0
            sum += sample * sample; peak = maxOf(peak, kotlin.math.abs(sample)); count++
        }
        if (count == 0) return
        chunks.addLast(Chunk(System.nanoTime() + DELAY_NS, sqrt(sum / count), peak))
        while (chunks.size > 32) chunks.removeFirst()
        PerfTrace.sample("clock.acceptNs", System.nanoTime() - startedAt)
    }

    @Synchronized fun consume() {
        val now = System.nanoTime()
        while (chunks.firstOrNull()?.atNs?.let { it <= now } == true) {
            val current = chunks.removeFirst()
            rms = current.rms; peak = current.peak
        }
    }

    @Synchronized fun reset() { chunks.clear(); rms = 0.0; peak = 0.0 }

    private companion object { const val DELAY_NS = 80_000_000L }
}
