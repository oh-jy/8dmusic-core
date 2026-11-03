package jiangyi.spatial8d

import jiangyi.spatial8d.core.DEFAULT_SPACE_PRESET
import jiangyi.spatial8d.core.PITCH_PRESETS
import jiangyi.spatial8d.MainActivity.Companion.interleavedPitchOptions
import jiangyi.spatial8d.MainActivity.Companion.matchingPresetOrDefault
import jiangyi.spatial8d.audio.SpatialPlayer.Companion.shouldRestartOutput
import jiangyi.spatial8d.audio.SpatialPlayer.Companion.hasReachedEndMarker
import jiangyi.spatial8d.audio.SpatialPlayer.Companion.endDrainTimedOut
import jiangyi.spatial8d.audio.PlaybackQueue
import jiangyi.spatial8d.data.MusicItem
import jiangyi.spatial8d.data.PlayMode
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

    @Test fun supersededOutputThreadHandsOffToLatestGeneration() {
        // 切参淘汰了旧线程，但退出时仍必须接管最新代，不能留下假播放状态。
        check(shouldRestartOutput(1, 2, prepared = true, paused = false, hasLiveOutput = false))
        check(!shouldRestartOutput(2, 2, prepared = true, paused = false, hasLiveOutput = true))
        check(shouldRestartOutput(2, 2, prepared = true, paused = false, hasLiveOutput = false))
        check(!shouldRestartOutput(2, 2, prepared = true, paused = true, hasLiveOutput = false))
    }

    @Test fun drainedOutputCompletesEvenWhenMarkerCallbackWasMissed() {
        check(!hasReachedEndMarker(trackStarted = false, marker = 100, heardFrames = 100))
        check(!hasReachedEndMarker(trackStarted = true, marker = 100, heardFrames = 99))
        check(hasReachedEndMarker(trackStarted = true, marker = 100, heardFrames = 100))
        check(hasReachedEndMarker(trackStarted = true, marker = 100, heardFrames = 101))
    }

    @Test fun stalledHeadCannotBlockNaturalCompletionForever() {
        check(!endDrainTimedOut(3_999, 2_000, speed = 1.0))
        check(endDrainTimedOut(4_000, 2_000, speed = 1.0))
        check(!endDrainTimedOut(2_999, 2_000, speed = 2.0))
        check(endDrainTimedOut(3_000, 2_000, speed = 2.0))
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

    @Test fun serviceQueueContinuesWithoutActivityCallback() {
        val items = listOf(MusicItem(1, "a", "u1"), MusicItem(2, "b", "u2"), MusicItem(3, "c", "u3"))
        check(PlaybackQueue(items, PlayMode.LOOP_LIST).next("u1", auto = true)?.id == 2L)
        check(PlaybackQueue(items, PlayMode.LOOP_LIST).next("u3", auto = true)?.id == 1L)
        check(PlaybackQueue(items, PlayMode.LOOP_ONE).next("u1", auto = true) == null)
        check(PlaybackQueue(items, PlayMode.SHUFFLE) { 0 }.next("u1", auto = true)?.id == 2L)
        check(PlaybackQueue(emptyList(), PlayMode.LOOP_LIST).next("u1", auto = true) == null)
        check(PlaybackQueue(items, PlayMode.LOOP_LIST).previous("u1")?.id == 3L)
        check(PlaybackQueue(items, PlayMode.LOOP_LIST).next("removed", auto = true) == null)
    }
}
