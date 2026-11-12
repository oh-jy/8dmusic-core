package j5i.spatial8d.ui

import j5i.spatial8d.visual.PerfTrace
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

internal fun starAlpha(envelope: Double, twinkle: Double, intensity: Double, pulse: Double): Int {
    val glow = envelope * (28.0 + twinkle * 130.0 + intensity * 75.0)
    return (glow + pulse * (55.0 + envelope * 80.0)).toInt().coerceIn(0, 255)
}

/** 所有 PCM 动效共用同一延迟队列，避免顶部和可视化各自积压不同的音频历史。 */
internal class BeatDetector {
    private var average = 0.0
    private var started = 0L
    private var lastSample = 0L
    private var lastBeat = 0L

    fun reset() { average = 0.0; started = 0L; lastSample = 0L; lastBeat = 0L }

    fun accept(energy: Double, now: Long): Boolean {
        if (lastSample == 0L || now - lastSample > 500_000_000L) {
            reset(); started = now; lastSample = now; average = energy
            return false
        }
        val dt = ((now - lastSample).coerceAtLeast(0L) / 1e9).coerceAtMost(0.25)
        lastSample = now
        val beat = now - started >= 300_000_000L && energy > 0.025 &&
            energy > average * 1.6 && energy - average > 0.015 &&
            (lastBeat == 0L || now - lastBeat >= 250_000_000L)
        if (beat) lastBeat = now
        average += (energy - average) * (1.0 - kotlin.math.exp(-dt / 0.8))
        return beat
    }
}

class PcmVisualClock {
    private data class Chunk(val atNs: Long, val rms: Double, val peak: Double, val beat: Boolean)
    private val chunks = ArrayDeque<Chunk>()
    private val detector = BeatDetector()
    @Volatile var generation = 0L; private set
    @Volatile var rms = 0.0; private set
    @Volatile var peak = 0.0; private set
    @Volatile var beatAtNs = 0L; private set
    private var lastInputNs = 0L
    private var offlineNs: Long? = null
    fun nowNs(): Long = offlineNs ?: System.nanoTime()
    val active: Boolean
        @Synchronized get() = offlineNs != null || lastInputNs != 0L && System.nanoTime() - lastInputNs <= 500_000_000L

    @Synchronized fun acceptOffline(data: ByteArray, bytes: Int, timeUs: Long) {
        val now = 1_000_000_000L + timeUs * 1_000L
        offlineNs = now
        val input = ByteBuffer.wrap(data, 0, bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var sum = 0.0
        var loudest = 0.0
        var count = 0
        while (input.hasRemaining()) {
            val sample = input.get() / 32768.0
            sum += sample * sample
            loudest = maxOf(loudest, kotlin.math.abs(sample))
            count++
        }
        rms = if (count > 0) sqrt(sum / count) else 0.0
        peak = loudest
        if (count > 0 && detector.accept(rms, now)) beatAtNs = now
    }

    @Synchronized fun accept(data: ByteArray, bytes: Int) {
        val startedAt = System.nanoTime()
        val input = ByteBuffer.wrap(data, 0, bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var sum = 0.0; var peak = 0.0; var count = 0
        while (input.hasRemaining()) {
            val sample = input.get() / 32768.0
            sum += sample * sample; peak = maxOf(peak, kotlin.math.abs(sample)); count++
        }
        if (count == 0) return
        val now = System.nanoTime()
        if (lastInputNs != 0L && now - lastInputNs > 500_000_000L) reset()
        lastInputNs = now
        val rms = sqrt(sum / count)
        chunks.addLast(Chunk(now + DELAY_NS, rms, peak, detector.accept(rms, now)))
        while (chunks.size > 32) chunks.removeFirst()
        PerfTrace.sample("clock.acceptNs", System.nanoTime() - startedAt)
    }

    @Synchronized fun consume() {
        if (offlineNs != null) return
        val now = System.nanoTime()
        while (chunks.firstOrNull()?.atNs?.let { it <= now } == true) {
            val current = chunks.removeFirst()
            rms = current.rms; peak = current.peak
            if (current.beat) beatAtNs = current.atNs
        }
        if (lastInputNs != 0L && now - lastInputNs > 500_000_000L) reset()
    }

    @Synchronized fun reset() {
        chunks.clear(); detector.reset(); rms = 0.0; peak = 0.0
        beatAtNs = 0L; lastInputNs = 0L; offlineNs = null; generation++
    }

    private companion object { const val DELAY_NS = 80_000_000L }
}
