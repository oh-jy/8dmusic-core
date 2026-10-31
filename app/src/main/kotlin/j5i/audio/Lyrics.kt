package j5i.spatial8d.audio

import android.content.Context
import android.icu.text.Transliterator
import android.text.Html
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs
import j5i.spatial8d.data.MusicItem

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

data class LyricsCandidate(
    val trackName: String,
    val artistName: String,
    val durationSeconds: Double = 0.0,
    val source: String,
    val query: String,
    val synced: Boolean = false,
    val lrcDurationSeconds: Double = 0.0
)

/**
 * 仅使用 LRCLIB 公开 API；本地缓存优先。
 * 回调始终切回主线程；查不到、无时间轴和网络失败均通过 Result 明确返回。
 */
class Lyrics(context: Context) {
    private val appContext = context.applicationContext
    // 歌词属于可长期复用的下载内容，不能放在系统可能随时清理的 cacheDir。
    private val cache = File(appContext.filesDir, "lyrics")
    private val legacyCache = File(appContext.cacheDir, "lyrics")
    private val failures = appContext.getSharedPreferences("lyrics_retry", Context.MODE_PRIVATE)
    private val prefetchExecutor = Executors.newSingleThreadExecutor()
    private val prefetchGeneration = AtomicInteger()
    private val requestLock = Any()
    private val requests = mutableMapOf<String, Pair<Long, MutableList<(Result<LyricsResult>) -> Unit>>>()

    /**
     * 手动检索候选列表。自动匹配写错时，允许用户在候选上重新选择，并可通过完整歌手/时长参与打分。
     */
    fun candidates(
        title: String,
        artist: String = "",
        durationSeconds: Double = 0.0,
        callback: (Result<List<LyricsCandidate>>) -> Unit,
        onProgress: (String) -> Unit = {}
    ) {
        fun progress(stage: String) = appContext.mainExecutor.execute { onProgress(stage) }
        if (title.isBlank()) {
            appContext.mainExecutor.execute { callback(Result.failure(IllegalArgumentException("歌曲名称为空"))) }
            return
        }
        progress("正在检索歌词候选…")
        thread(name = "lyrics-candidates") {
            val result = try {
                Result.success(searchCandidates(title.trim(), knownArtist(artist.trim()), durationSeconds))
            } catch (e: NoSuchElementException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(IOException("暂时无法获取在线歌词", e))
            }
            appContext.mainExecutor.execute { callback(result) }
        }
    }

    fun load(
        title: String,
        artist: String = "",
        durationSeconds: Double = 0.0,
        callback: (Result<LyricsResult>) -> Unit,
        onProgress: (String) -> Unit = {}
    ) {
        fun progress(stage: String) = appContext.mainExecutor.execute { onProgress(stage) }
        if (title.isBlank()) {
            appContext.mainExecutor.execute {
                callback(Result.failure(IllegalArgumentException("歌曲名称为空")))
            }
            return
        }
        progress("读取本地歌词缓存…")
        val cleanTitle = title.trim()
        val cleanArtist = knownArtist(artist.trim())
        val requestKey = key(cleanTitle, cleanArtist, durationSeconds)
        val callbacks = synchronized(requestLock) {
            val pending = requests[requestKey]
            if (pending != null && System.currentTimeMillis() - pending.first < 30_000) {
                pending.second += callback
                null
            } else {
                mutableListOf(callback).also { requests[requestKey] = System.currentTimeMillis() to it }
            }
        } ?: return
        thread(name = "lyrics-search") {
            val result = try {
                // 本地持久歌词优先，兼容旧缓存以及元数据变化造成的精确键失配。
                Result.success(cachedLyrics(cleanTitle, cleanArtist, durationSeconds) ?: run {
                    progress("LRCLIB 在线检索…")
                    fetch(title.trim(), artist.trim(), durationSeconds)
                })
            } catch (e: NoSuchElementException) {
                Log.i("8DLyrics", "LRCLIB 无匹配：${e.message}")
                Result.failure(e)
            } catch (e: Exception) {
                Log.w("8DLyrics", "LRCLIB 请求失败", e)
                Result.failure(IOException("暂时无法获取在线歌词", e))
            }
            appContext.mainExecutor.execute {
                synchronized(requestLock) { requests.remove(requestKey) }
                synchronized(requestLock) {
                    if (result.isSuccess) failures.edit().remove(requestKey).apply()
                    else failures.edit().putInt(requestKey, (failures.getInt(requestKey, 0) + 1).coerceAtMost(5)).apply()
                }
                // 最终结果回调先执行；不再发送终态进度覆盖手动检索输入区。
                if (result.isSuccess) progress("解析并缓存歌词…")
                result.onSuccess { Log.i("8DLyrics", "歌词成功：${it.trackName} · ${it.artistName} · ${it.lines.size} 行") }
                callbacks.forEach { it(result) }
            }
        }
    }

    private fun cachedLyrics(title: String, artist: String, duration: Double): LyricsResult? {
        cacheFile(title, artist, duration).let { file ->
            cached(file)?.let { return it }
            File(legacyCache, file.name).let { old ->
                cached(old)?.let { result ->
                    writeCache(file, JSONObject(old.readText(Charsets.UTF_8)))
                    return result
                }
            }
        }
        val candidates = sequenceOf(cache, legacyCache).flatMap { directory ->
            directory.listFiles { file -> file.extension == "json" }?.asSequence() ?: emptySequence()
        }.mapNotNull { file ->
            try {
                val row = JSONObject(file.readText(Charsets.UTF_8))
                val result = parse(row)
                if (normalizeTitle(result.trackName) != normalizeTitle(title)) return@mapNotNull null
                val actualArtist = knownArtist(result.artistName)
                val artistMatches = artist.isEmpty() || actualArtist.isEmpty() ||
                    normalize(actualArtist) == normalize(artist)
                if (!artistMatches) return@mapNotNull null
                val savedDuration = row.optDouble("duration", 0.0)
                Triple(if (savedDuration > 0) abs(savedDuration - duration) else Double.MAX_VALUE,
                    result.synchronized, result)
            } catch (_: Exception) { null }
        }.sortedWith(compareBy<Triple<Double, Boolean, LyricsResult>>({ it.first }, { !it.second }))
        val best = candidates.firstOrNull() ?: return null
        if (best.first > 3.0) return null
        writeCache(cacheFile(title, artist, duration), JSONObject().apply {
            put("trackName", best.third.trackName)
            put("artistName", best.third.artistName)
            put("syncedLyrics", best.third.lines.joinToString("\n") {
                "[%02d:%02d.%02d]%s".format(it.timeMs / 60000, it.timeMs / 1000 % 60, it.timeMs % 1000 / 10, it.text)
            })
            put("plainLyrics", best.third.plainLyrics)
            put("instrumental", best.third.instrumental)
            put("duration", duration)
        })
        return best.third
    }

    private fun fetch(title: String, artist: String, duration: Double): LyricsResult {
        val cleanArtist = knownArtist(artist)
        val file = cacheFile(title, cleanArtist, duration)
        // 歌曲名是可靠锚点；歌手、时长和版本信息仅用于排序，避免被错误元数据或现场版过滤。
        val rows = JSONArray(request("https://lrclib.net/api/search?track_name=${encode(title)}") ?: "[]")
        val candidates = (0 until rows.length()).map { rows.getJSONObject(it) }
        val best = candidates.mapNotNull { candidate ->
            score(candidate, title, cleanArtist, duration)?.let { it to candidate }
        }.maxWithOrNull(compareBy({ it.first }, { synchronizedWeight(it.second) }))?.second
            ?: throw NoSuchElementException("未找到匹配歌词")
        return parse(best).also { writeCache(file, best) }
    }

    private fun searchCandidates(title: String, artist: String, duration: Double): List<LyricsCandidate> {
        val rows = JSONArray(request("https://lrclib.net/api/search?track_name=${encode(title)}") ?: "[]")
        val candidates = (0 until rows.length()).mapNotNull { rowToCandidate(rows.getJSONObject(it), "LRCLIB") }
        if (candidates.isEmpty()) throw NoSuchElementException("未找到可选的歌词候选")
        return candidates.sortedByDescending { scoreCandidate(it, title, artist, duration) }
    }

    /**
     * 使用选中的候选强制写入缓存。手动选择代表当时的意图，不能再次被旧缓存或自动打分覆盖。
     */
    fun applyCandidate(
        title: String,
        artist: String,
        durationSeconds: Double,
        candidate: LyricsCandidate,
        callback: (Result<LyricsResult>) -> Unit,
        onProgress: (String) -> Unit = {}
    ) {
        fun progress(stage: String) = appContext.mainExecutor.execute { onProgress(stage) }
        progress("正在获取所选歌词…")
        thread(name = "lyrics-apply") {
            val result = try {
                val fetched = fetchCandidate(candidate, title, artist, durationSeconds, persist = true)
                failures.edit().remove(key(title, knownArtist(artist), durationSeconds)).apply()
                Result.success(fetched)
            } catch (e: Exception) {
                Result.failure(e)
            }
            appContext.mainExecutor.execute { callback(result) }
        }
    }

    /** 候选确认前的预览读取，不写本地缓存；与 applyCandidate 共用抓取逻辑。 */
    fun previewCandidate(
        title: String,
        artist: String,
        durationSeconds: Double,
        candidate: LyricsCandidate,
        callback: (Result<LyricsResult>) -> Unit,
        onProgress: (String) -> Unit = {}
    ) {
        fun progress(stage: String) = appContext.mainExecutor.execute { onProgress(stage) }
        progress("正在获取所选歌词…")
        thread(name = "lyrics-preview") {
            val result = try {
                Result.success(fetchCandidate(candidate, title, artist, durationSeconds, persist = false))
            } catch (e: Exception) {
                Result.failure(e)
            }
            appContext.mainExecutor.execute { callback(result) }
        }
    }

    private fun fetchCandidate(
        candidate: LyricsCandidate,
        title: String,
        artist: String,
        duration: Double,
        persist: Boolean
    ): LyricsResult = when (candidate.source) {
        "LRCLIB" -> fetchLrclibRow(candidate, title, artist, duration, persist)
        else -> throw NoSuchElementException("无效的歌词来源")
    }

    private fun fetchLrclibRow(
        candidate: LyricsCandidate,
        title: String,
        artist: String,
        duration: Double,
        persist: Boolean
    ): LyricsResult {
        val row = try { JSONObject(candidate.query) } catch (_: Exception) { null }
            ?: throw NoSuchElementException("LRCLIB 候选缺少歌词内容")
        val result = parse(row)
        if (persist) writeCache(cacheFile(title, knownArtist(artist), duration), result.toRow(duration))
        return result
    }

    private fun LyricsResult.toRow(duration: Double) = JSONObject().apply {
        put("trackName", trackName)
        put("artistName", artistName)
        put("syncedLyrics", lines.joinToString("\n") {
            val total = it.timeMs
            "[%02d:%02d.%02d]%s".format(total / 60000, total / 1000 % 60, total % 1000 / 10, it.text)
        })
        put("plainLyrics", plainLyrics)
        put("instrumental", instrumental)
        put("duration", duration)
    }

    private fun rowToCandidate(row: JSONObject, source: String): LyricsCandidate? {
        val trackName = row.optString("trackName", row.optString("name"))
        val artistName = row.optString("artistName")
        val syncedLyrics = row.optString("syncedLyrics")
        val hasLyrics = syncedLyrics.isNotBlank() ||
            row.optString("plainLyrics").isNotBlank() || row.optBoolean("instrumental")
        if (trackName.isBlank() || !hasLyrics) return null
        // LRC 实际覆盖时长取最后一行时间戳；纯文本无时间轴则为 0。
        val lrcDuration = if (syncedLyrics.isNotBlank()) {
            parseLrc(syncedLyrics).lastOrNull()?.let { it.timeMs / 1000.0 } ?: 0.0
        } else 0.0
        return LyricsCandidate(
            trackName = trackName,
            artistName = artistName,
            durationSeconds = row.optDouble("duration", 0.0),
            source = source,
            query = row.toString(),
            synced = syncedLyrics.isNotBlank(),
            lrcDurationSeconds = lrcDuration
        )
    }

    /**
     * 手动候选排序：歌名完全一致 > 歌名包含 > 其它；歌手一致加权；时长接近加权；有同步歌词加权。
     * 用户最关心"歌名 + 歌手都对 + 时长贴得上"，对应分数最高。
     */
    private fun scoreCandidate(candidate: LyricsCandidate, title: String, artist: String, duration: Double): Int {
        val wantedTitle = normalizeTitle(title)
        val actualTitle = normalizeTitle(candidate.trackName)
        var score = when {
            wantedTitle.isEmpty() || actualTitle.isEmpty() -> 0
            actualTitle == wantedTitle -> 200
            actualTitle.contains(wantedTitle) || wantedTitle.contains(actualTitle) -> 80
            else -> 0
        }
        val wantedArtist = normalize(knownArtist(artist))
        val actualArtist = normalize(candidate.artistName)
        score += when {
            wantedArtist.isEmpty() -> 10
            actualArtist == wantedArtist -> 120
            actualArtist.contains(wantedArtist) || wantedArtist.contains(actualArtist) -> 50
            else -> 0
        }
        if (duration > 0 && candidate.durationSeconds > 0) {
            val diff = abs(duration - candidate.durationSeconds)
            score += when {
                diff <= 1.0 -> 60
                diff <= 3.0 -> 30
                diff <= 5.0 -> 10
                else -> 0
            }
        }
        if (candidate.synced) score += 40
        // 来源权重：LRCLIB 候选自带完整 LRC，无需二次抓取，排序略优先。
        if (candidate.source == "LRCLIB") score += 5
        return score
    }

    /** 后台串行预取整个曲库；仅自动任务受失败次数限制，手动搜索始终可用。 */
    fun prefetch(songs: List<MusicItem>) {
        val generation = prefetchGeneration.incrementAndGet()
        prefetchExecutor.execute {
            songs.filterNot { it.uri.startsWith("http") }.forEach { item ->
                if (generation != prefetchGeneration.get()) return@execute
                val title = searchTitle(item.name, item.artist)
                val artist = knownArtist(item.artist)
                val requestKey = key(title, artist, item.duration / 1000.0)
                if (hasCached(title, artist, item.duration / 1000.0) || failures.getInt(requestKey, 0) >= 5) return@forEach
                val done = CountDownLatch(1)
                val result = AtomicReference<Result<LyricsResult>>()
                load(title, artist, item.duration / 1000.0, {
                    result.set(it)
                    done.countDown()
                })
                done.await(45, TimeUnit.SECONDS)
                Log.i("8DLyrics", "后台预取：$title · ${if (result.get()?.isSuccess == true) "成功" else "未命中"}")
            }
        }
    }

    fun hasCached(title: String, artist: String, durationSeconds: Double): Boolean =
        cachedLyrics(title, knownArtist(artist), durationSeconds) != null

    private fun cacheFile(title: String, artist: String, duration: Double) =
        File(cache, key(title, artist, duration) + ".json")

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
            toSimplified(row.optString("plainLyrics")),
            row.optBoolean("instrumental")
        )
    }

    private fun score(row: JSONObject, title: String, artist: String, duration: Double): Int? {
        val hasLyrics = row.optString("syncedLyrics").isNotBlank() ||
            row.optString("plainLyrics").isNotBlank()
        if (!hasLyrics && !row.optBoolean("instrumental")) return null
        val actualTitle = normalizeTitle(row.optString("trackName", row.optString("name")))
        val wantedTitle = normalizeTitle(title)
        if (actualTitle.isEmpty() || wantedTitle.isEmpty()) return null
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
        return titleScore + artistScore + durationScore + synchronizedWeight(row)
    }

    private fun synchronizedWeight(row: JSONObject) = if (row.optString("syncedLyrics").isNotBlank()) 40 else 0

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

    private fun writeCache(file: File, row: JSONObject, result: LyricsResult) {
        writeCache(file, JSONObject().apply {
            put("trackName", result.trackName)
            put("artistName", result.artistName)
            put("syncedLyrics", result.lines.joinToString("\n") {
                val total = it.timeMs
                "[%02d:%02d.%02d]%s".format(total / 60000, total / 1000 % 60, total % 1000 / 10, it.text)
            })
            put("plainLyrics", result.plainLyrics)
            put("instrumental", result.instrumental)
            put("duration", if (row.has("interval")) row.optDouble("interval") else row.optDouble("duration") / 1000.0)
        })
    }

    private fun request(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "8dmusic-core/1.0 (+https://github.com/oh-jy/8dmusic-core)")
        }
        return try {
            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_FOUND -> throw NotFoundException()
                429, 503 -> {
                    val retryAfter = connection.getHeaderField("Retry-After")?.let { " · Retry-After: $it" }.orEmpty()
                    throw IOException("LRCLIB 暂时限流，请稍后重试$retryAfter")
                }
                in 200..299 -> connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                    val text = it.readText()
                    if (text.length > 1_000_000) throw IOException("歌词响应过大") else text
                }
                else -> throw IOException("HTTP $status · ${URL(url).host}")
            }
        } finally {
            connection.disconnect()
        }
    }

    private class NotFoundException : IOException()

    companion object {
        private val traditionalToSimplified by lazy {
            Transliterator.getInstance("Traditional-Simplified")
        }

        private fun toSimplified(value: String) = try {
            traditionalToSimplified.transliterate(value)
        } catch (_: Throwable) {
            // JVM 单测不带 Android ICU；设备上使用平台转换，桌面回归保留原文。
            value
        }

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
                    lines += LyricLine((minutes * 60 + seconds) * 1000 + millis, toSimplified(text))
                }
            }
            return lines.sortedBy { it.timeMs }
        }

        private fun normalize(value: String) = value.lowercase(Locale.ROOT)
            .replace(Regex("[\\s\\p{P}\\p{S}]+"), "")
        private fun normalizeTitle(value: String) = value.substringBeforeLast('.')
            .replace(Regex("(?i)[\\[(（【][^\\])）】]*(?:live|现场|翻唱|cover|remix|伴奏|版|version)[^\\])）】]*[\\])）】]"), "")
            .let(::normalize)
        private fun knownArtist(value: String) = value.trim().takeUnless {
            val artist = normalize(it)
            artist.isEmpty() || artist in setOf("unknown", "unknownartist", "未知歌手", "未知艺术家", "佚名")
        }.orEmpty()
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        private fun decode(value: String) = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
        private fun key(title: String, artist: String, duration: Double): String {
            val input = "${normalize(title)}|${normalize(artist)}"
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            // 文件名仍保留旧格式，时长不参与身份，避免媒体扫描的四舍五入变化使缓存失效。
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun displayTitle(fileName: String): String = fileName.trim()
            .replace(Regex("\\.[a-zA-Z0-9]{1,8}$"), "")

        /** 常见文件名为“歌手 - 歌名”；只调整展示和检索，不修改原始文件名。 */
        fun searchTitle(fileName: String, artist: String): String {
            val name = displayTitle(fileName)
            // 歌手元数据常缺失，但本地文件普遍按“歌手 - 歌名”命名，不能把整段当作歌名。
            val withoutArtist = if (artist.isNotBlank() && name.startsWith(artist, ignoreCase = true))
                name.substring(artist.length).trimStart('-', '–', '—', '·', ' ') else name
            return withoutArtist.substringAfterLast(" - ").substringAfterLast(" · ").trim()
        }
    }
}
