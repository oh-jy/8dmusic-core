package jiangyi.spatial8d

import jiangyi.spatial8d.core.DEFAULT_SPACE_PRESET
import jiangyi.spatial8d.core.PITCH_PRESETS
import jiangyi.spatial8d.MainActivity.Companion.interleavedPitchOptions
import jiangyi.spatial8d.MainActivity.Companion.matchingPresetOrDefault
import jiangyi.spatial8d.audio.SpatialPlayer.Companion.shouldRestartOutput
import jiangyi.spatial8d.data.MusicItem
import kotlin.test.Test

/** 不启动 Android 设备的纯逻辑回归。 */
class MainActivityLogicCheck {
    @Test fun presetAndPitchRules() {
        val custom = DEFAULT_SPACE_PRESET.copy(name = "合法自定义", period = 9.0)
        check(matchingPresetOrDefault(custom, listOf(custom)) == custom)
        check(matchingPresetOrDefault(custom.copy(period = Double.NaN), listOf(custom)) == DEFAULT_SPACE_PRESET)
        check(matchingPresetOrDefault(custom.copy(period = 99.0), listOf(custom)) == DEFAULT_SPACE_PRESET)

        val options = interleavedPitchOptions(PITCH_PRESETS)
        check(options.take(4).map { it.semi } == listOf(0.0, 0.0, -1.0, 1.0))
        check(options.filter { it.semi == 0.0 }.size == 2)
        check(options.filter { it.semi < 0.0 }.map { it.semi } == (-1 downTo -12).map { it.toDouble() })
        check(options.filter { it.semi > 0.0 }.map { it.semi } == (1..12).map { it.toDouble() })
        check(options.count { it.semi != 0.0 } == PITCH_PRESETS.size - 1)
    }

    @Test fun onlyCurrentGenerationRestartsOutput() {
        // 旧代退出不能另起无效线程；当前代异常退出且没有接管者时才重启。
        check(!shouldRestartOutput(1, 2, prepared = true, paused = false, hasLiveOutput = false))
        check(!shouldRestartOutput(2, 2, prepared = true, paused = false, hasLiveOutput = true))
        check(shouldRestartOutput(2, 2, prepared = true, paused = false, hasLiveOutput = false))
        check(!shouldRestartOutput(2, 2, prepared = true, paused = true, hasLiveOutput = false))
    }

    @Test fun timeSortUsesAddedThenModified() {
        val items = listOf(
            MusicItem(3, "a", "u3", addedAt = 10_000L, modifiedAt = 99_000L),
            MusicItem(1, "c", "u1", addedAt = 100L, modifiedAt = 9L),
            MusicItem(4, "d", "u4", addedAt = 100L, modifiedAt = 900L),
            MusicItem(2, "b", "u2", addedAt = 0L, modifiedAt = 0L),
        )
        val sorted = items.sortedWith(compareBy({ it.addedAt }, { it.modifiedAt }))
        check(sorted.map { it.id } == listOf(2L, 1L, 4L, 3L))
        check(sorted.reversed().map { it.id } == listOf(3L, 4L, 1L, 2L))
    }
}
