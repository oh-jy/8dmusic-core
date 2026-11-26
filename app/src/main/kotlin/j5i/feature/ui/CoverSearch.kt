package j5i.spatial8d.feature.ui

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class CoverCandidate(
    val title: String,
    val artist: String,
    val album: String,
    val source: String,
    val imageUrl: String,
    val previewUrl: String = imageUrl
)

internal data class CoverSearchResult(val candidates: List<CoverCandidate>, val status: String)

internal object CoverSearch {
    fun search(
        title: String,
        artist: String,
        canonical: (String) -> String = { it },
        log: (() -> String) -> Unit = {}
    ): CoverSearchResult {
        val query = "$title $artist".trim()
        val sources = linkedMapOf<String, () -> List<CoverCandidate>>(
            "QQ音乐" to { qq(query) },
            "酷狗" to { kugou(query) },
            "网易云" to { netease(query, title, artist, canonical) },
            "iTunes" to { itunes(query) }
        )
        val executor = Executors.newFixedThreadPool(sources.size)
        val completion = ExecutorCompletionService<Pair<String, Result<List<CoverCandidate>>>>(executor)
        val rows = mutableListOf<CoverCandidate>()
        val statuses = mutableMapOf<String, String>()
        try {
            sources.forEach { (source, fetch) ->
                completion.submit {
                    log { "search 检索歌曲封面 source=$source title=${title.take(80)} artist=${artist.take(60)}" }
                    source to runCatching(fetch)
                }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(18)
            for (sourceIndex in 0 until sources.size) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) break
                val completed = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: break
                val (source, result) = completed.get()
                result.onSuccess { candidates ->
                    val matched = rankCoverCandidates(title, artist, candidates, canonical)
                    rows += matched
                    statuses[source] = if (matched.isEmpty()) "无匹配" else "${matched.size}项"
                }.onFailure { error ->
                    statuses[source] = "失败"
                    log { "search 封面来源请求失败 source=$source error=${error.javaClass.simpleName}" }
                }
            }
        } finally {
            executor.shutdownNow()
        }
        val ordered = rows.sortedBy { sources.keys.indexOf(it.source) }
        return CoverSearchResult(rankCoverCandidates(title, artist, ordered, canonical),
            sources.keys.joinToString(" · ") { "$it ${statuses[it] ?: "超时"}" })
    }

    private fun qq(query: String): List<CoverCandidate> {
        val json = request("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=${encode(query)}&format=json&n=30&p=1", "https://y.qq.com/")
        require(json.optInt("code", -1) == 0) { "QQ搜索失败" }
        val songs = json.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list") ?: JSONArray()
        return parseQqCovers(songs)
    }

    private fun kugou(query: String): List<CoverCandidate> {
        val json = request("https://songsearch.kugou.com/song_search_v2?keyword=${encode(query)}&page=1&pagesize=30", "https://www.kugou.com/")
        require(json.optInt("status") == 1) { "酷狗搜索失败" }
        return parseKugouCovers(json.optJSONObject("data")?.optJSONArray("lists") ?: JSONArray())
    }

    private fun netease(query: String, title: String, artist: String, canonical: (String) -> String): List<CoverCandidate> {
        val json = request("https://music.163.com/api/search/get/web", "https://music.163.com/",
            "s=${encode(query)}&type=1&offset=0&limit=30")
        require(json.optInt("code") == 200) { "网易云搜索失败" }
        val songs = json.optJSONObject("result")?.optJSONArray("songs") ?: JSONArray()
        val ids = (0 until songs.length()).map { songs.getJSONObject(it) }.filter { song ->
            coverMatchRank(canonical(title), canonical(artist), canonical(song.optString("name")),
                canonical(artistNames(song.optJSONArray("artists")))) >= 0
        }.map { it.optLong("id") }.filter { it > 0 }.distinct().take(15)
        if (ids.isEmpty()) return emptyList()
        val detail = request("https://music.163.com/api/song/detail/?ids=${encode(ids.joinToString(",", "[", "]"))}", "https://music.163.com/")
        require(detail.optInt("code") == 200) { "网易云封面详情失败" }
        return parseNeteaseCovers(detail.optJSONArray("songs") ?: JSONArray())
    }

    private fun itunes(query: String): List<CoverCandidate> {
        val region = if (query.any { it in '\u3400'..'\u9fff' }) "TW" else "US"
        val json = request("https://itunes.apple.com/search?limit=50&entity=song&country=$region&term=${encode(query)}", "https://music.apple.com/")
        val songs = json.optJSONArray("results") ?: JSONArray()
        return (0 until songs.length()).map { songs.getJSONObject(it) }.mapNotNull { song ->
            song.optString("artworkUrl100").takeIf { it.startsWith("https://") }?.let { image ->
                CoverCandidate(song.optString("trackName"), song.optString("artistName"),
                    song.optString("collectionName"), "iTunes", image.replace("100x100", "600x600"), image)
            }
        }
    }

    fun imageBytes(url: String): ByteArray = bytes(url, "", null, 5_000_000)

    private fun request(url: String, referer: String, body: String? = null) =
        JSONObject(bytes(url, referer, body, 2_000_000).toString(Charsets.UTF_8))

    private fun bytes(url: String, referer: String, body: String?, limit: Int): ByteArray {
        require(URL(url).protocol == "https") { "图片地址无效" }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 6_000
            setRequestProperty("User-Agent", "Mozilla/5.0")
            if (referer.isNotEmpty()) setRequestProperty("Referer", referer)
        }
        try {
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    check(!Thread.currentThread().isInterrupted) { "请求已取消" }
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= limit) { "响应过大" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}

internal fun parseQqCovers(songs: JSONArray): List<CoverCandidate> =
    (0 until songs.length()).map { songs.getJSONObject(it) }.mapNotNull { song ->
        val album = song.optJSONObject("album")
        val mid = album?.optString("mid").orEmpty().ifBlank { song.optString("albummid") }
        mid.takeIf { it.matches(Regex("[A-Za-z0-9]+")) }?.let {
            CoverCandidate(song.optString("songname").ifBlank { song.optString("name") },
                artistNames(song.optJSONArray("singer")), album?.optString("name").orEmpty().ifBlank { song.optString("albumname") },
                "QQ音乐", "https://y.gtimg.cn/music/photo_new/T002R800x800M000$mid.jpg",
                "https://y.gtimg.cn/music/photo_new/T002R300x300M000$mid.jpg")
        }
    }

internal fun parseKugouCovers(songs: JSONArray): List<CoverCandidate> =
    (0 until songs.length()).map { songs.getJSONObject(it) }.mapNotNull { song ->
        val image = listOf(song.optString("AlbumImage"), song.optString("Image"),
            song.optJSONObject("trans_param")?.optString("union_cover").orEmpty()).firstOrNull { url ->
            url.contains("/stdmusic/") || (url == song.optString("AlbumImage") && url.isNotBlank() && !url.contains("softhead"))
        }.orEmpty().replace("http://", "https://").replace("{size}", "400")
        image.takeIf { it.startsWith("https://") }?.let {
            CoverCandidate(song.optString("SongName"), song.optString("SingerName"), song.optString("AlbumName"), "酷狗", image)
        }
    }

internal fun parseNeteaseCovers(songs: JSONArray): List<CoverCandidate> =
    (0 until songs.length()).map { songs.getJSONObject(it) }.mapNotNull { song ->
        val album = song.optJSONObject("album") ?: song.optJSONObject("al")
        val image = album?.optString("picUrl").orEmpty().replace("http://", "https://")
        image.takeIf { it.startsWith("https://") }?.let {
            val separator = if (image.contains('?')) "&" else "?"
            CoverCandidate(song.optString("name"), artistNames(song.optJSONArray("artists") ?: song.optJSONArray("ar")),
                album?.optString("name").orEmpty(), "网易云", "${image}${separator}param=800y800", "${image}${separator}param=300y300")
        }
    }

private fun artistNames(artists: JSONArray?): String = (0 until (artists?.length() ?: 0))
    .map { artists!!.getJSONObject(it).optString("name") }.filter { it.isNotBlank() }.joinToString("/")

internal fun rankCoverCandidates(title: String, artist: String, rows: List<CoverCandidate>, canonical: (String) -> String = { it }): List<CoverCandidate> =
    rows.map { it to coverMatchRank(canonical(title), canonical(artist), canonical(it.title), canonical(it.artist)) }
        .filter { it.second >= 0 && it.first.imageUrl.isNotBlank() }
        .sortedByDescending { it.second }.map { it.first }.distinctBy { it.source to it.imageUrl }

internal fun coverMatchRank(title: String, artist: String, candidateTitle: String, candidateArtist: String): Int {
    fun key(value: String) = value.lowercase(java.util.Locale.ROOT).replace(Regex("[\\s\\p{P}\\p{S}]+"), "")
    fun base(value: String) = value.replace(Regex("[（(\\[].*?[）)\\]]"), "").trim()
    val wanted = key(title)
    if (wanted.isEmpty() || key(base(candidateTitle)) != key(base(title))) return -1
    if (artist.isNotBlank() && key(artist) != key(candidateArtist)) return -1
    return if (key(candidateTitle) == wanted) 2 else 1
}
