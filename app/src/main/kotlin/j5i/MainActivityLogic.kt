package j5i.spatial8d

import j5i.spatial8d.core.DEFAULT_SPACE_PRESET
import j5i.spatial8d.core.PitchPreset
import j5i.spatial8d.core.SpacePreset
import j5i.spatial8d.core.matchPresetIn

internal fun playbackStatusWithInfo(status: String, technicalInfo: String, album: String): String =
    listOf(status, technicalInfo, album).filter { it.isNotBlank() }.joinToString(" · ")

internal fun audioDetails(mime: String, sampleRate: Int, bitrate: Int): String {
    val codec = when (mime) {
        "audio/mp4a-latm" -> "AAC"
        "audio/mpeg" -> "MP3"
        "audio/flac" -> "FLAC"
        "audio/raw" -> "PCM"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/alac" -> "ALAC"
        else -> mime.removePrefix("audio/").uppercase(java.util.Locale.ROOT)
    }
    return listOf(codec, if (sampleRate > 0) "${sampleRate} Hz" else "",
        if (bitrate > 0) "${(bitrate + 500) / 1000} kbps" else "")
        .filter { it.isNotBlank() }.joinToString(" · ")
}

internal fun matchingPresetOrDefault(candidate: SpacePreset, presets: List<SpacePreset>): SpacePreset {
    val name = matchPresetIn(presets, candidate.period, candidate.elev, candidate.depth,
        candidate.room, candidate.wet, candidate.rt60, candidate.phase,
        candidate.direction, candidate.width, candidate.raw, candidate.trajectory, candidate.sourceDistance)
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
