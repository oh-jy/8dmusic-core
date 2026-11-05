package j5i.spatial8d.audio

import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.j5i.spatial8d.MusicSourceApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import android.util.Log

/**
 * 哔哩哔哩音源：搜索视频元数据，播放时只取 DASH 音频轨并保存为本地 m4a。
 * 视频流从不下载；未登录可搜索公开内容，登录（扫码或手机号在官方页完成）后会话更稳。
 */
class BiliMusic(private val activity: Activity) {
    companion object {
        private const val PROVIDER = "bilibili"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36"
        private const val REFERER = "https://www.bilibili.com/"
        private const val LOGIN_URL =
            "https://passport.bilibili.com/login?goto=https%3A%2F%2Fwww.bilibili.com%2F"
        private const val MIN_SINGLE_SECONDS = 20.0
        private val BILI_RELATIVE_PATH = Environment.DIRECTORY_DOWNLOADS + "/bili/"
    }

    @Volatile private var imgKey = ""
    @Volatile private var subKey = ""
    private val partCountCache = ConcurrentHashMap<String, Int>()

    val loggedIn: Boolean
        get() = runCatching { JSONObject(MusicSourceApi.sessionInfo(activity, PROVIDER)).optBoolean("loggedIn") }
            .getOrDefault(false)

    fun login(callback: (Result<Unit>) -> Unit) {
        MusicSourceApi.loginWeb(activity, PROVIDER, LOGIN_URL, REFERER, "SESSDATA=") { raw ->
            val r = JSONObject(raw)
            if (r.optBoolean("ok")) callback(Result.success(Unit))
            else callback(Result.failure(Exception(r.optString("message", "登录失败"))))
        }
    }

    fun search(query: String, page: Int): List<OnlineTrack> {
        ensureBuvid()
        ensureKeys()
        val params = linkedMapOf(
            "search_type" to "video",
            "keyword" to query.trim().ifEmpty { "热门歌曲" },
            "page" to page.coerceAtLeast(1).toString(),
            "page_size" to "10"
        )
        val url = "https://api.bilibili.com/x/web-interface/wbi/search/type?" +
            Wbi.signedQuery(params, Wbi.mixinKey(imgKey, subKey), System.currentTimeMillis() / 1000)
        val r = JSONObject(api(url))
        if (r.optInt("code") != 0) throw Exception(friendly(r.optInt("code"), r.optString("message")))
        val rows = r.optJSONObject("data")?.optJSONArray("result") ?: JSONArray()
        val base = (0 until rows.length()).mapNotNull { i ->
            val v = rows.optJSONObject(i) ?: return@mapNotNull null
            val bvid = v.optString("bvid")
            if (!bvid.startsWith("BV")) return@mapNotNull null
            val duration = parseDuration(v.optString("duration"))
            // 过滤几十秒的短视频片段；合集（分 P）保留，点按时展开成曲目列表。
            if (duration in 1.0..<MIN_SINGLE_SECONDS) return@mapNotNull null
            val title = cleanTitle(v.optString("title"))
            val partCount = v.optInt("videos").coerceAtLeast(0)
            SearchRow(bvid, title, v.optString("author").ifEmpty { "哔哩哔哩UP主" }, duration, partCount)
        }
        val probed = probePartCounts(base.map { it.bvid })
        return base.map { row ->
            val partCount = probed[row.bvid] ?: row.searchPartCount
            partCountCache[row.bvid] = partCount
            OnlineTrack(
                "bili:${row.bvid}", row.title, row.author,
                row.duration, "哔哩哔哩", partCount > 1, partCount
            )
        }
    }

    private data class SearchRow(
        val bvid: String,
        val title: String,
        val author: String,
        val duration: Double,
        val searchPartCount: Int
    )

    /** 搜索字段 videos 不稳定；以 pagelist 的真实分 P 数作为合集判定依据。 */
    private fun probePartCounts(bvids: List<String>): Map<String, Int> {
        val missing = bvids.distinct().filterNot { partCountCache.containsKey(it) }
        if (missing.isEmpty()) return partCountCache
        val pool = Executors.newFixedThreadPool(missing.size.coerceAtMost(3))
        return try {
            val futures = missing.associateWith { bvid ->
                pool.submit(object : Callable<Int?> {
                    override fun call(): Int? =
                    runCatching { pageList(bvid).length() }
                        .onFailure { Log.i("8DBili", "part probe failed bvid=$bvid message=${it.message}") }
                        .getOrNull()
                })
            }
            futures.forEach { (bvid, future) ->
                runCatching { future.get(12, TimeUnit.SECONDS) }
                    .getOrNull()?.takeIf { it > 0 }?.let { partCountCache[bvid] = it }
            }
            partCountCache
        } finally {
            pool.shutdownNow()
        }
    }

    /** 公共下载目录的 B 站音频是否已缓存；私有旧缓存也视为已缓存。 */
    fun cached(track: OnlineTrack): Boolean {
        val segments = track.id.substringAfter("bili:").split(':')
        val bvid = segments.firstOrNull().orEmpty()
        if (!bvid.startsWith("BV")) return false
        val cid = segments.getOrNull(1)?.toLongOrNull()
        val key = if (cid != null) "${bvid}_$cid" else bvid
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publicAudio("$key.m4a") != null || privateAudio(key) != null
        } else privateAudio(key) != null
    }

    /** 删除 B 站音频缓存；公共文件与私有旧缓存都删除，不提供恢复。 */
    fun delete(track: OnlineTrack): Boolean {
        val segments = track.id.substringAfter("bili:").split(':')
        val bvid = segments.firstOrNull().orEmpty()
        if (!bvid.startsWith("BV")) return false
        val cid = segments.getOrNull(1)?.toLongOrNull()
        val key = if (cid != null) "${bvid}_$cid" else bvid
        var removed = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publicAudio("$key.m4a")?.let { uri ->
                removed = activity.contentResolver.delete(uri, null, null) > 0 || removed
            }
        }
        File(activity.filesDir, "bili").listFiles()?.forEach { file ->
            if (file.name == "$key.m4a" || file.name == "$key.m4a.part") removed = file.delete() || removed
        }
        return removed
    }

    /** 按已登记到播放列表的 URI 删除公共/私有 B 站音频，兼容旧条目。 */
    fun deleteUri(rawUri: String): Boolean {
        val uri = Uri.parse(rawUri)
        val name = displayName(uri) ?: if (uri.scheme == "file") uri.lastPathSegment else null
        val key = name?.removeSuffix(".m4a")?.takeIf { it.startsWith("BV") } ?: return false
        var removed = false
        if (uri.scheme == "content") {
            removed = runCatching { activity.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publicAudio("$key.m4a")?.let { public ->
                    if (public != uri) removed = activity.contentResolver.delete(public, null, null) > 0 || removed
                }
            }
        }
        if (uri.scheme == "file") {
            removed = runCatching { File(uri.path.orEmpty()).delete() }.getOrDefault(false)
        }
        File(activity.filesDir, "bili").listFiles()?.forEach { file ->
            if (file.name == "$key.m4a" || file.name == "$key.m4a.part") removed = file.delete() || removed
        }
        return removed
    }

    private fun displayName(uri: Uri): String? {
        if (uri.scheme != "content") return null
        return runCatching {
            activity.contentResolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrDefault(null)
    }

    /** 展开分 P 合集：每个分 P 是一首可独立播放和缓存的曲目；单 P 视频返回单元素列表。 */
    fun parts(track: OnlineTrack): List<OnlineTrack> {
        val bvid = track.id.substringAfter("bili:").substringBefore(':')
        require(bvid.startsWith("BV")) { "哔哩哔哩视频标识无效" }
        val pages = pageList(bvid)
        partCountCache[bvid] = pages.length()
        Log.i("8DBili", "parts bvid=$bvid count=${pages.length()}")
        val artist = track.artist
        return (0 until pages.length()).map { i ->
            val page = pages.getJSONObject(i)
            val cid = page.getLong("cid")
            val title = page.getString("part").ifEmpty { track.title }
            OnlineTrack(
                "bili:$bvid:$cid", cleanTitle(title), artist,
                page.optLong("duration").toDouble().coerceAtLeast(0.0), "哔哩哔哩"
            )
        }.ifEmpty { error("合集曲目列表为空") }
    }

    /** 只保存音频；Android 10 以上通过 MediaStore 写入公共下载目录。 */
    fun download(track: OnlineTrack): String {
        val segments = track.id.substringAfter("bili:").split(':')
        val bvid = segments.first()
        require(bvid.startsWith("BV")) { "哔哩哔哩视频标识无效" }
        val explicitCid = segments.getOrNull(1)?.toLongOrNull()
        val cid = explicitCid ?: firstCid(bvid)
        Log.i("8DBili", "download bvid=$bvid cid=$cid")
        val key = if (explicitCid != null) "${bvid}_$cid" else bvid
        val dir = File(activity.filesDir, "bili").apply { mkdirs() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publicAudio("$key.m4a")?.let { return it.toString() }
        } else {
            cached(bvid, explicitCid)?.let { return Uri.fromFile(it).toString() }
        }
        val part = File(dir, "$key.m4a.part")
        val target = File(dir, "$key.m4a")
        // Preserve old private cache and existing library entries; copy on demand.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && target.length() > 100_000) {
            checkContainer(target)
            return publishAudio(target, "$key.m4a").toString()
        }
        val candidates = audioCandidates(bvid, cid)
        var failure: Exception? = null
        for (audioUrl in candidates) {
            try {
                Log.i("8DBili", "audio candidate host=${runCatching { URL(audioUrl).host }.getOrDefault("?")}")
                fetch(audioUrl, part)
                checkContainer(part)
                Log.i("8DBili", "audio saved bytes=${part.length()}")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val uri = publishAudio(part, "$key.m4a")
                    part.delete()
                    return uri.toString()
                }
                if (target.exists()) target.delete()
                check(part.renameTo(target)) { "音频保存失败" }
                return "file://${target.absolutePath}"
            } catch (e: Exception) {
                failure = e
                part.delete()
            }
        }
        throw (failure ?: Exception("哔哩哔哩暂未提供该视频的音频轨"))
    }

    private fun publicAudio(name: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val columns = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.SIZE)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.IS_PENDING}=0"
        activity.contentResolver.query(collection, columns, selection,
            arrayOf(name, BILI_RELATIVE_PATH), null)?.use { cursor ->
            if (cursor.moveToFirst() && cursor.getLong(1) > 100_000)
                return ContentUris.withAppendedId(collection, cursor.getLong(0))
        }
        return null
    }

    private fun publishAudio(file: File, name: String): Uri {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val resolver = activity.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "audio/mp4")
            put(MediaStore.Downloads.RELATIVE_PATH, BILI_RELATIVE_PATH)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法在下载目录创建音频文件")
        try {
            requireNotNull(resolver.openOutputStream(uri)) { "无法写入下载目录" }.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1) {
                "无法完成音频文件保存"
            }
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun cached(bvid: String, cid: Long?): File? {
        val key = if (cid != null) "${bvid}_$cid" else bvid
        return privateAudio(key)
    }

    private fun privateAudio(key: String): File? = File(activity.filesDir, "bili").listFiles()?.firstOrNull {
        it.name == "$key.m4a" && it.length() > 100_000
    }

    private fun pageList(bvid: String): JSONArray {
        val r = JSONObject(api("https://api.bilibili.com/x/player/pagelist?bvid=$bvid"))
        if (r.optInt("code") != 0) throw Exception(friendly(r.optInt("code"), r.optString("message")))
        return r.optJSONArray("data") ?: JSONArray()
    }

    private fun firstCid(bvid: String): Long {
        return pageList(bvid).optJSONObject(0)?.optLong("cid")?.takeIf { it != 0L }
            ?: throw Exception("未获取到视频分 P 信息")
    }

    private fun audioCandidates(bvid: String, cid: Long): List<String> {
        val url = "https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid&fnval=16&fourk=1"
        val r = JSONObject(api(url))
        Log.i("8DBili", "playurl code=${r.optInt("code")} message=${r.optString("message")}")
        if (r.optInt("code") != 0) throw Exception(friendly(r.optInt("code"), r.optString("message")))
        val dash = r.optJSONObject("data")?.optJSONObject("dash")
            ?: throw Exception("该视频没有可提取的音频轨（可能是纯图文或互动视频）")
        val tracks = dash.optJSONArray("audio") ?: JSONArray()
        val choices = (0 until tracks.length()).map { tracks.getJSONObject(it) }
            .filter { it.optString("codecs").startsWith("mp4a") }
            .sortedByDescending { it.optInt("bandwidth") }
            .ifEmpty {
                // 个别视频只有一条非 mp4a 标识的音频轨，仍尝试交给系统解码器。
                (0 until tracks.length()).map { tracks.getJSONObject(it) }
            }
        return choices.flatMap { track ->
            listOf(track.optString("baseUrl")) +
                (track.optJSONArray("backupUrl")?.let { backup ->
                    (0 until backup.length()).map { backup.optString(it) }
                } ?: emptyList())
        }.filter { it.startsWith("https://") || it.startsWith("http://") }
            .ifEmpty { throw Exception("该视频当前没有可播放的音频轨，可能需要登录或为会员专享内容") }
    }

    /** 音频 CDN 强制校验 Referer；cookie 不必须但带上可减少拦截。 */
    private fun fetch(audioUrl: String, target: File) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(audioUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Referer", REFERER)
                setRequestProperty("Accept", "*/*")
                MusicSourceApi.sessionCookie(activity, PROVIDER).takeIf { it.isNotEmpty() }
                    ?.let { setRequestProperty("Cookie", it) }
            }
            val status = connection.responseCode
            Log.i("8DBili", "cdn status=$status bytes=${connection.getHeaderField("Content-Length")}")
            check(status == HttpURLConnection.HTTP_OK || status == HttpURLConnection.HTTP_PARTIAL) { "HTTP $status" }
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
            check(target.length() > 100_000) { "音频文件不完整" }
        } finally {
            connection?.disconnect()
        }
    }

    private fun checkContainer(file: File) {
        // B 站 DASH 音频是自初始化 fMP4（ftyp+moov 开头）；个别线路异常时提前失败并回退备份线路。
        val head = ByteArray(8)
        file.inputStream().use { it.read(head) }
        check(String(head, 4, 4, Charsets.US_ASCII) == "ftyp") { "音频容器格式异常" }
    }

    /** 未登录访问也需要 buvid3/4 设备指纹，否则搜索接口直接风控拒绝。 */
    private fun ensureBuvid() {
        val existing = MusicSourceApi.sessionCookie(activity, PROVIDER)
        if (existing.contains("buvid3=")) return
        val r = JSONObject(api("https://api.bilibili.com/x/frontend/finger/spi", provider = ""))
        if (r.optInt("code") != 0) throw Exception("哔哩哔哩设备校验失败，请稍后重试")
        val data = r.optJSONObject("data") ?: throw Exception("哔哩哔哩设备校验失败，请稍后重试")
        val merged = listOf(existing, "buvid3=${data.optString("b_3")}", "buvid4=${data.optString("b_4")}")
            .filter { it.isNotEmpty() }.joinToString("; ")
        MusicSourceApi.saveSession(activity, PROVIDER, merged)
    }

    /** wbi 签名密钥随服务端轮换，进程内缓存；未登录的 nav 响应也会返回。 */
    private fun ensureKeys() {
        if (imgKey.isNotEmpty() && subKey.isNotEmpty()) return
        val wbi = JSONObject(api("https://api.bilibili.com/x/web-interface/nav"))
            .optJSONObject("data")?.optJSONObject("wbi_img")
            ?: throw Exception("哔哩哔哩签名服务暂不可用")
        imgKey = wbi.optString("img_url").substringAfterLast('/').substringBefore('.')
        subKey = wbi.optString("sub_url").substringAfterLast('/').substringBefore('.')
        if (imgKey.isEmpty() || subKey.isEmpty()) throw Exception("哔哩哔哩签名密钥获取失败")
    }

    private fun api(url: String, provider: String = PROVIDER): String {
        var result = ""
        var failure: Exception? = null
        val lock = Object()
        synchronized(lock) {
            MusicSourceApi.request(activity, url, "GET", "", provider) { raw ->
                try {
                    val r = JSONObject(raw)
                    if (r.has("error")) error(r.optString("message", "网络请求失败"))
                    if (r.optInt("status") !in 200..299) error("HTTP ${r.optInt("status")}")
                    result = r.optString("body")
                } catch (e: Exception) {
                    failure = e
                }
                synchronized(lock) { lock.notifyAll() }
            }
            lock.wait(25_000)
        }
        failure?.let { throw it }
        if (result.isEmpty()) error("哔哩哔哩服务暂不可用")
        return result
    }

    private fun friendly(code: Int, message: String): String = when (code) {
        -352, -412, -799 -> "哔哩哔哩风控校验失败，请登录后重试或稍后再试"
        -101, -102 -> "哔哩哔哩登录已失效，请重新登录"
        -403 -> "当前账号无权访问该视频"
        else -> message.ifEmpty { "哔哩哔哩接口返回错误 $code" }
    }

    private fun cleanTitle(raw: String): String = raw
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .trim()

    private fun parseDuration(value: String): Double {
        val parts = value.trim().split(':').mapNotNull { it.toLongOrNull() }
        if (parts.isEmpty()) return 0.0
        return parts.fold(0L) { acc, segment -> acc * 60 + segment }.toDouble()
    }
}

/** 哔哩哔哩 wbi 签名：img/sub 密钥按固定置换表混成 mixin key，参数排序后 MD5 加签。 */
object Wbi {
    private val MIXIN_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    )

    fun mixinKey(imgKey: String, subKey: String): String {
        val raw = imgKey + subKey
        return buildString { MIXIN_TAB.forEach { append(raw[it]) } }.substring(0, 32)
    }

    fun signedQuery(params: Map<String, String>, mixinKey: String, nowSeconds: Long): String {
        val ordered = (params + ("wts" to nowSeconds.toString()))
            .mapValues { (_, value) -> value.filter { it !in "!'()*" } }
            .toSortedMap()
        val query = ordered.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val wrid = md5(query + mixinKey)
        return "$query&w_rid=$wrid"
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
