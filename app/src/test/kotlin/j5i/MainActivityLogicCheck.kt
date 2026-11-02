package j5i.spatial8d

import j5i.spatial8d.core.DEFAULT_SPACE_PRESET
import j5i.spatial8d.core.PITCH_PRESETS
import j5i.spatial8d.MainActivity.Companion.interleavedPitchOptions
import j5i.spatial8d.MainActivity.Companion.matchingPresetOrDefault
import j5i.spatial8d.audio.SpatialPlayer.Companion.shouldRestartOutput
import j5i.spatial8d.audio.SpatialPlayer.Companion.hasReachedEndMarker
import j5i.spatial8d.audio.SpatialPlayer.Companion.endDrainTimedOut
import j5i.spatial8d.audio.SpatialPlayer.Companion.reverseSourceStart
import j5i.spatial8d.audio.RadioPlayer.Companion.shouldStartTrack
import j5i.spatial8d.audio.PlaybackQueue
import j5i.spatial8d.audio.Lyrics
import j5i.spatial8d.data.MusicItem
import j5i.spatial8d.data.PlayMode
import com.j5i.spatial8d.TsAdtsMediaDataSource
import kotlin.test.Test

/** 不启动 Android 设备的纯逻辑回归。 */
class MainActivityLogicCheck {
    @Test fun hlsRawAacIsNotDiscardedAsTs() {
        check(TsAdtsMediaDataSource.isAdts(byteArrayOf(0xff.toByte(), 0xf1.toByte()), 2))
        check(!TsAdtsMediaDataSource.isAdts(byteArrayOf(0x47, 0x40), 2))
    }

    @Test fun localTrackTitleDoesNotBecomeArtistName() {
        check(Lyrics.searchTitle("周杰伦 - 夜曲.mp3", "周杰伦") == "夜曲")
    }

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

    @Test fun reverseReadsFromTailTowardHead() {
        check(reverseSourceStart(startFrame = 10_000, produced = 0) == 10_000)
        check(reverseSourceStart(startFrame = 10_000, produced = 4_096) == 5_904)
        check(reverseSourceStart(startFrame = 2_000, produced = 2_000) == 0)
    }

    @Test fun radioStartsOutputAfterFirstPcmChunk() {
        check(!shouldStartTrack(started = false, writtenFrames = 0))
        check(shouldStartTrack(started = false, writtenFrames = 1))
        check(!shouldStartTrack(started = true, writtenFrames = 4096))
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
