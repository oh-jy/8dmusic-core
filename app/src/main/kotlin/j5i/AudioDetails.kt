package j5i.spatial8d

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

