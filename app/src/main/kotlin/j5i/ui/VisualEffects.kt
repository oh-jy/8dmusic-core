package j5i.spatial8d.ui

import kotlin.math.PI
import kotlin.math.sin

internal val exclusiveVisualModes = listOf(
    "visual.spectral" to 1, "visual.pcm" to 4, "visual.particles" to 2, "visual.travel" to 3, "visual.dream" to 34,
    "visual.rain" to 13, "visual.starburst" to 14, "visual.filaments" to 15,
    "visual.orbits" to 16, "visual.seasons" to 17,
    "visual.3d" to 5, "visual.water" to 6, "visual.strings" to 7,
    "visual.stars" to 8, "visual.constellation" to 10,
    "visual.radial" to 11, "visual.ribbon" to 12, "visual.ripples" to 22,
    "visual.leaves" to 18, "visual.snow" to 19, "visual.fire" to 20,
    "visual.lightning" to 21, "visual.clouds" to 23,
    "visual.nebula" to 25, "visual.tunnel" to 26,
    "visual.prism" to 27, "visual.lightpaint" to 28, "visual.vortex" to 29,
    "visual.notes" to 30, "visual.grid" to 31, "visual.metal" to 32,
    "visual.kaleidoscope" to 33
)

internal val visualModeGroups = listOf(
    "visual.group.analysis" to setOf(1, 4, 5, 7, 11),
    "visual.group.nature" to setOf(6, 13, 17, 18, 19, 20, 21, 23, 25),
    "visual.group.cosmic" to setOf(2, 3, 8, 10, 14, 16, 26, 27, 29, 34),
    "visual.group.abstract" to setOf(12, 15, 22, 28, 30, 31, 32, 33)
)

internal fun nextVisualMode(current: Int, forward: Boolean): Int {
    val index = exclusiveVisualModes.indexOfFirst { it.second == current }
    return exclusiveVisualModes[(index + if (forward) 1 else -1).mod(exclusiveVisualModes.size)].second
}

internal fun isHorizontalVisualSwipe(deltaX: Float, deltaY: Float, threshold: Float): Boolean =
    kotlin.math.abs(deltaX) >= threshold && kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) * 1.25f

internal fun nextVisualizerState(exclusive: Boolean, mode: Int, forward: Boolean): Pair<Boolean, Int> {
    val modes = exclusiveVisualModes.map { it.second }
    val index = if (exclusive) modes.indexOf(mode).takeIf { it >= 0 }?.plus(1) ?: 0 else 0
    val next = (index + if (forward) 1 else -1).mod(modes.size + 1)
    return if (next == 0) false to 0 else true to modes[next - 1]
}

internal fun videoFrameCount(durationUs: Long): Long = (durationUs * 30 + 999_999) / 1_000_000
internal fun videoFrameTimeUs(frame: Long): Long = frame * 1_000_000 / 30

internal fun videoClipFrames(totalFrames: Long, sampleRate: Int, startSeconds: Double, durationSeconds: Double): Pair<Long, Long> {
    require(totalFrames > 0 && sampleRate > 0 && startSeconds.isFinite() && durationSeconds.isFinite())
    require(startSeconds >= 0 && durationSeconds >= 0)
    val first = (startSeconds * sampleRate).toLong()
    require(first < totalFrames)
    val count = if (durationSeconds > 0) minOf(totalFrames - first, (durationSeconds * sampleRate).toLong()) else totalFrames - first
    require(count > 0)
    return first to count
}

internal fun dreamScene(epoch: Int): Int {
    var value = (epoch / 4) * 0x45d9f3b + 0x12345
    value = (value xor (value ushr 16)) * 0x45d9f3b
    val offset = ((value xor (value ushr 16)).ushr(1) % 2) * 2
    return (offset + epoch) % 4
}

internal const val DREAM_SCENE_SECONDS = 7.0

internal fun dreamBlend(seconds: Double): Float {
    val progress = (seconds / DREAM_SCENE_SECONDS).mod(1.0).toFloat()
    val transition = ((progress - .55f) / .45f).coerceIn(0f, 1f)
    return transition * transition * (3f - 2f * transition)
}

internal fun seasonHeight(horizontal: Float, depth: Float, seconds: Double, energy: Float): Float {
    val position = horizontal + seconds * .035 + depth * .24
    val angle = position * 2.0 * PI
    val broad = .5 + .5 * kotlin.math.cos(angle - .8)
    val second = .5 + .5 * kotlin.math.cos(angle * 2.0 + depth * 2.4 + .7)
    val detail = kotlin.math.sin(angle * 5.0 + depth * 5.0) * .025 +
        kotlin.math.sin(angle * 9.0 - depth * 8.0) * .012
    val swell = 1.0 + .10 * sin(seconds * .24 + depth * 2.0) + energy.coerceIn(0f, 1f) * .14
    return ((broad * broad * .34 + second * second * .17 + detail) * swell).toFloat()
}

internal fun stringDisplacement(fraction: Float, seconds: Double, strength: Float, harmonic: Int): Float {
    if (fraction <= 0f || fraction >= 1f) return 0f
    return (sin(fraction * PI) * sin(fraction * PI * harmonic - seconds * (12.0 + harmonic * 2.0)) *
        strength.coerceIn(0f, 1f)).toFloat()
}

internal class SpectrumTerrainHistory(val rows: Int = 15, val columns: Int = 48) {
    private val levels = Array(rows) { FloatArray(columns) }
    private var head = -1
    private var lastSampleNs = 0L
    var size = 0
        private set

    fun accept(spectrum: DoubleArray, nowNs: Long) {
        if (spectrum.isEmpty() || (size > 0 && nowNs - lastSampleNs < 100_000_000L)) return
        head = (head + 1) % rows
        for (column in 0 until columns) {
            levels[head][column] = spectrum[column * spectrum.lastIndex / (columns - 1)].toFloat()
        }
        size = (size + 1).coerceAtMost(rows)
        lastSampleNs = nowNs
    }

    operator fun get(age: Int, column: Int): Float = levels[(head - age).mod(rows)][column]

    fun clear() {
        head = -1
        size = 0
        lastSampleNs = 0L
    }
}
