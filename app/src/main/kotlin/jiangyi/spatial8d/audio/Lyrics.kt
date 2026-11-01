package jiangyi.spatial8d.audio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

/** 一行带时间轴的歌词；时间以歌曲播放位置为基准，不包含设备延时。 */
data class LyricLine(val timeMs: Long, val text: String)

data class LyricsResult(
    val trackName: String,
    val artistName: String,
    val lines: List<LyricLine>,
    val plainLyrics: String = "",
    val instrumental: Boolean = false
) {
    val synchronized: Boolean get() = lines.isNotEmpty()
}

/**
 * LRCLIB 公开歌词检索。调用方传入实际曲目时长，结果优先从本地 UTF-8 文本缓存读取。
 * 回调始终切回主线程；查不到、无时间轴和网络失败均通过 Result 明确返回。
 */
class Lyrics(context: Context) {
    private val appContext = context.applicationContext
    private val cache = File(appContext.cacheDir, "lyrics")

    fun load(
        title: String,
        artist: String = "",
        durationSeconds: Double = 0.0,
        callback: (Result<LyricsResult>) -> Unit
    ) {
        if (title.isBlank()) {
            appContext.mainExecutor.execute {
                callback(Result.failure(IllegalArgumentException("歌曲名称为空")))
            }
            return
        }
        thread(name = "lyrics-search") {
            val result = try {
                Result.success(fetch(title.trim(), artist.trim(), durationSeconds))
            } catch (e: Exception) {
                Result.failure(IOException("暂时无法获取在线歌词", e))
            }
            appContext.mainExecutor.execute { callback(result) }
        }
    }

    private fun fetch(title: String, artist: String, duration: Double): LyricsResult {
        val file = File(cache, key(title, artist, duration) + ".json")
        cached(file)?.let { return it }
        val query = buildString {
            append("track_name=").append(encode(title))
            if (artist.isNotEmpty()) append("&artist_name=").append(encode(artist))
            if (duration > 0) append("&duration=").append(duration.toInt())
        }
        val exact = try {
            request("https://lrclib.net/api/get?$query")?.let { listOf(JSONObject(it)) }
        } catch (_: NotFoundException) {
            null
        }
        val candidates = exact ?: JSONArray(request("https://lrclib.net/api/search?track_name=${encode(title)}" +
            if (artist.isEmpty()) "" else "&artist_name=${encode(artist)}") ?: "[]").let { rows ->
            (0 until rows.length()).map { rows.getJSONObject(it) }
        }
        val best = candidates.mapNotNull { candidate ->
            val lyric = candidate.optString("syncedLyrics").takeIf { it.isNotBlank() }
                ?: candidate.optString("plainLyrics").takeIf { it.isNotBlank() }
            if (lyric == null && !candidate.optBoolean("instrumental")) return@mapNotNull null
            score(candidate, title, artist, duration)?.let { it to candidate }
        }.maxByOrNull { it.first }?.second
            ?: throw NoSuchElementException("未找到匹配歌词")
        return parse(best).also { writeCache(file, best) }
    }

    private fun cached(file: File): LyricsResult? = try {
        if (file.isFile) parse(JSONObject(file.readText(Charsets.UTF_8))) else null
    } catch (_: Exception) {
        // 损坏缓存按未命中处理，不删除用户目录中的其他文件。
        null
    }

    private fun parse(row: JSONObject): LyricsResult {
        val synced = row.optString("syncedLyrics")
        return LyricsResult(
            row.optString("trackName", row.optString("name")),
            row.optString("artistName"),
            parseLrc(synced),
            row.optString("plainLyrics"),
            row.optBoolean("instrumental")
        )
    }

    private fun score(row: JSONObject, title: String, artist: String, duration: Double): Int? {
        val actualTitle = normalize(row.optString("trackName", row.optString("name")))
        val wantedTitle = normalize(title)
        val titleScore = when {
            actualTitle == wantedTitle -> 100
            actualTitle.contains(wantedTitle) || wantedTitle.contains(actualTitle) -> 60
            else -> return null
        }
        val actualArtist = normalize(row.optString("artistName"))
        val wantedArtist = normalize(artist)
        val artistScore = when {
            wantedArtist.isEmpty() -> 10
            actualArtist == wantedArtist -> 50
            actualArtist.contains(wantedArtist) || wantedArtist.contains(actualArtist) -> 25
            else -> -20
        }
        val reportedDuration = row.optDouble("duration", 0.0)
        val durationScore = if (duration <= 0 || reportedDuration <= 0) 0
        else (30 - abs(duration - reportedDuration) * 5).toInt().coerceAtLeast(-30)
        return titleScore + artistScore + durationScore
    }

    private fun writeCache(file: File, row: JSONObject) {
        try {
            cache.mkdirs()
            val temp = File(cache, file.name + ".tmp")
            temp.writeText(row.toString(), Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText(Charsets.UTF_8), Charsets.UTF_8)
                temp.delete()
            }
        } catch (_: Exception) {
            // 缓存不可写不影响本次歌词展示。
        }
    }

    private fun request(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "8dmusic-kt/1.0")
        }
        return try {
            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_FOUND -> throw NotFoundException()
                in 200..299 -> connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                    val text = it.readText()
                    if (text.length > 1_000_000) throw IOException("歌词响应过大") else text
                }
                else -> throw IOException("LRCLIB HTTP $status")
            }
        } finally {
            connection.disconnect()
        }
    }

    private class NotFoundException : IOException()

    companion object {
        /** 支持 [mm:ss.xx]、[mm:ss:xx]、小时及同一行多个时间戳；标签和空行会忽略。 */
        fun parseLrc(lrc: String): List<LyricLine> {
            val timestamp = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
            val lines = mutableListOf<LyricLine>()
            lrc.lineSequence().forEach { raw ->
                val matches = timestamp.findAll(raw).toList()
                if (matches.isEmpty()) return@forEach
                val text = raw.substring(matches.last().range.last + 1).trim()
                if (text.isEmpty()) return@forEach
                matches.forEach { match ->
                    val minutes = match.groupValues[1].toLongOrNull() ?: return@forEach
                    val seconds = match.groupValues[2].toLongOrNull() ?: return@forEach
                    if (seconds >= 60) return@forEach
                    val fraction = match.groupValues[3]
                    val millis = when (fraction.length) {
                        1 -> (fraction + "00").toLong()
                        2 -> (fraction + "0").toLong()
                        3 -> fraction.toLong()
                        else -> 0L
                    }
                    lines += LyricLine((minutes * 60 + seconds) * 1000 + millis, text)
                }
            }
            return lines.sortedBy { it.timeMs }
        }

        private fun normalize(value: String) = value.lowercase(Locale.ROOT)
            .replace(Regex("[\\s\\p{P}\\p{S}]+"), "")
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        private fun key(title: String, artist: String, duration: Double): String {
            val input = "${normalize(title)}|${normalize(artist)}|${duration.toInt().coerceAtLeast(0)}"
            return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
