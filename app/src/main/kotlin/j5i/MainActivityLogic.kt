package j5i.spatial8d

import j5i.spatial8d.core.DEFAULT_SPACE_PRESET
import j5i.spatial8d.core.PitchPreset
import j5i.spatial8d.core.SpacePreset
import j5i.spatial8d.core.matchPresetIn

internal fun matchingPresetOrDefault(candidate: SpacePreset, presets: List<SpacePreset>): SpacePreset {
    val name = matchPresetIn(presets, candidate.period, candidate.elev, candidate.depth,
        candidate.room, candidate.wet, candidate.rt60, candidate.phase,
        candidate.direction, candidate.width, candidate.raw)
    return presets.firstOrNull { it.name == name } ?: DEFAULT_SPACE_PRESET
}

internal fun interleavedPitchOptions(presets: List<PitchPreset>): List<PitchPreset> {
    val original = presets.first { it.semi == 0.0 }
    val negative = presets.filter { it.semi < 0.0 }.sortedByDescending { it.semi }
    val positive = presets.filter { it.semi > 0.0 }.sortedBy { it.semi }
    return buildList {
        add(original); add(original)
        for (i in 0 until maxOf(negative.size, positive.size)) {
            if (i < negative.size) add(negative[i])
            if (i < positive.size) add(positive[i])
        }
    }
}
