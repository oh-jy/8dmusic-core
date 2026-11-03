package jiangyi.spatial8d.audio

import android.app.Activity
import com.jiangyi.spatial8d.MusicSourceApi
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** 在线曲库的搜索条目；播放地址只在点按时解析，不长期保存第三方链接。 */
data class OnlineTrack(val id: String, val title: String, val artist: String,
    val duration: Double, val provider: String)

/** 0 聚合、1 酷我、2 网易云、3 酷狗、4 QQ、5 Jamendo。 */
class OnlineMusic(private val activity: Activity) {
    companion object {
        private const val JAMENDO_KEY = "8d_jamendo_client_id"
    }

    val loggedIn: Boolean get() = loggedIn(4)
    fun loggedIn(provider: Int): Boolean =
        if (provider == 5) jamendoClientId().isNotEmpty()
        else JSONObject(MusicSourceApi.sessionInfo(activity, providerName(provider))).optBoolean("loggedIn")

    /** Jamendo 公开 API 只需 client_id；它不是用户登录凭证，更不是 client_secret。 */
    fun jamendoClientId(): String = activity.getSharedPreferences("spatial8d", Activity.MODE_PRIVATE)
        .getString(JAMENDO_KEY, "").orEmpty()

    /** 返回 false 表示未保存，调用方可据此提示用户输入有效 client_id。 */
    fun saveJamendoClientId(value: String): Boolean {
        val clientId = value.trim()
        if (clientId.isEmpty()) return false
        return activity.getSharedPreferences("spatial8d", Activity.MODE_PRIVATE)
            .edit().putString(JAMENDO_KEY, clientId).commit()
    }

    fun search(provider: Int, query: String, page: Int, callback: (Result<List<OnlineTrack>>) -> Unit) {
        thread(name = "online-music-search") {
            try {
                deliver(callback, Result.success(if (provider == 0) aggregate(query, page) else searchOne(provider, query, page)))
            } catch (e: Exception) { deliver(callback, Result.failure(e)) }
        }
    }

    fun hot(provider: Int, callback: (Result<List<OnlineTrack>>) -> Unit) =
        if (provider != 4) search(provider, "", 1, callback) else thread(name = "online-music-hot") {
            try {
                val p = JSONObject().put("topId", 26).put("offset", 0).put("num", 30)
                val r = qqRequest("musicToplist.ToplistInfoServer", "GetDetail", p)
                deliver(callback, Result.success(qqTracks(child(r.optJSONObject("req"), "data").optJSONArray("songInfoList"))))
            } catch (e: Exception) { deliver(callback, Result.failure(e)) }
        }

    private fun searchOne(provider: Int, query: String, page: Int): List<OnlineTrack> = when (provider) {
        1 -> kuwo(query, page); 4 -> qqSearch(query, page); 2 -> netease(query, page); 3 -> kugou(query, page)
        5 -> jamendo(query, page)
        else -> throw IllegalArgumentException("未知音乐来源")
    }

    private fun aggregate(query: String, page: Int): List<OnlineTrack> {
        val pool = Executors.newFixedThreadPool(5)
        val providers = intArrayOf(2, 4, 3, 1, 5)
        val calls = providers.map { provider -> pool.submit<List<OnlineTrack>> { searchOne(provider, query, page) } }
        return try {
            val failures = mutableListOf<String>()
            val results = calls.mapIndexed { index, call ->
                try { call.get(25, TimeUnit.SECONDS) }
                catch (e: Exception) { failures += providerLabel(providers[index]); emptyList() }
            }
            if (results.all { it.isEmpty() } && failures.size == providers.size) error("在线曲库暂不可用，请稍后重试")
            // 用户希望可播放优先级：网易云、QQ、酷狗、酷我、Jamendo。
            val providerOrder = listOf("网易云音乐", "QQ音乐", "酷狗音乐", "酷我音乐", "Jamendo")
            val providerBuckets = results.flatten().groupBy { it.provider }
            providerOrder.flatMap { provider -> providerBuckets[provider].orEmpty() }
        } finally { pool.shutdownNow() }
    }

    fun resolve(track: OnlineTrack, callback: (Result<String>) -> Unit) {
        thread(name = "online-music-resolve") {
            try {
                val id = track.id.substringAfter(':', "")
                val url = when (track.provider) {
                    "Jamendo" -> track.id.substringAfter("jamendo:")
                    "酷我音乐" -> request("https://antiserver.kuwo.cn/anti.s?type=convert_url&format=mp3&response=url&rid=MUSIC_$id", provider = "kuwo").trim()
                    "QQ音乐" -> resolveQq(id)
                    "网易云音乐" -> resolveNetease(id)
                    "酷狗音乐" -> resolveKugou(id)
                    else -> ""
                }
                if (!url.startsWith("https://") && !url.startsWith("http://")) error("当前账号暂无该歌曲播放权限")
                deliver(callback, Result.success(url))
            } catch (e: Exception) { deliver(callback, Result.failure(e)) }
        }
    }

    fun login(callback: (Result<Unit>) -> Unit) {
        MusicSourceApi.login(activity) { raw ->
            val r = JSONObject(raw)
            if (r.optBoolean("ok")) callback(Result.success(Unit))
            else callback(Result.failure(Exception(r.optString("message", "登录失败"))))
        }
    }

    fun sendPhoneCode(provider: Int, phone: String, callback: (Result<Unit>) -> Unit) {
        if (provider != 2) return callback(Result.failure(UnsupportedOperationException("该来源暂无可验证的手机号登录接口")))
        require(phone.matches(Regex("^1\\d{10}$"))) { "请输入 11 位手机号" }
        thread(name = "online-music-phone-code") {
            try {
                val r = JSONObject(request("https://music.163.com/api/sms/captcha/sent?cellphone=${phone.trim()}&ctcode=86", provider = "netease", method = "POST"))
                check(r.optInt("code") == 200) { r.optString("message", "验证码发送失败") }
                deliver(callback, Result.success(Unit))
            } catch (e: Exception) { deliver(callback, Result.failure(e)) }
        }
    }

    fun phoneLogin(provider: Int, phone: String, code: String, callback: (Result<Unit>) -> Unit) {
        if (provider != 2) return callback(Result.failure(UnsupportedOperationException("该来源暂无可验证的手机号登录接口")))
        require(phone.matches(Regex("^1\\d{10}$")) && code.trim().isNotEmpty()) { "请输入手机号和短信验证码" }
        thread(name = "online-music-phone-login") {
            try {
                val r = JSONObject(request("https://music.163.com/api/login/cellphone?phone=${phone.trim()}&captcha=${code.trim()}&countrycode=86&rememberLogin=true", provider = "netease", method = "POST"))
                check(r.optInt("code") == 200 && loggedIn(2)) { r.optString("message", "手机号登录未成功，请检查验证码") }
                deliver(callback, Result.success(Unit))
            } catch (e: Exception) { deliver(callback, Result.failure(e)) }
        }
    }

    fun logout() = logout(4)
    fun logout(provider: Int) {
        if (provider == 5) {
            activity.getSharedPreferences("spatial8d", Activity.MODE_PRIVATE).edit().remove(JAMENDO_KEY).apply()
        } else MusicSourceApi.logout(activity, providerName(provider))
    }

    private fun kuwo(query: String, page: Int): List<OnlineTrack> {
        val q = encode(query.trim().ifEmpty { "热门歌曲" })
        val url = "https://search.kuwo.cn/r.s?all=$q&ft=music&itemset=web_2013&client=kt" +
            "&pn=${(page.coerceAtLeast(1) - 1)}&rn=30&rformat=json&encoding=utf8"
        val rows = JSONObject(request(url, provider = "kuwo")).optJSONArray("abslist") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { i ->
            val s = rows.getJSONObject(i); val rid = text(s, "MUSICRID").removePrefix("MUSIC_")
            if (rid.isEmpty() || s.optInt("PAY") != 0) null else OnlineTrack("kuwo:$rid", text(s, "SONGNAME"),
                text(s, "ARTIST"), s.optDouble("DURATION"), "酷我音乐")
        }
    }

    private fun qqSearch(query: String, page: Int): List<OnlineTrack> {
        val p = JSONObject().put("query", query).put("search_type", 0).put("num_per_page", 30)
            .put("page_num", page.coerceAtLeast(1)).put("grp", 0).put("remoteplace", "txt.yqq.song")
        val r = qqRequest("music.search.SearchCgiService", "DoSearchForQQMusicMobile", p)
        val body = child(child(r.optJSONObject("req"), "data"), "body")
        return qqTracks(child(body, "song").optJSONArray("list")?.takeIf { it.length() > 0 } ?: body.optJSONArray("item_song"))
    }

    private fun qqTracks(rows: JSONArray?): List<OnlineTrack> {
        rows ?: return emptyList()
        return (0 until rows.length()).mapNotNull { i ->
            val s = rows.getJSONObject(i); val mid = text(s, "mid")
            if (mid.isEmpty()) return@mapNotNull null
            val singers = s.optJSONArray("singer") ?: JSONArray()
            val artist = (0 until singers.length()).joinToString(" / ") { text(singers.getJSONObject(it), "name", "未知歌手") }
            OnlineTrack("qq:$mid", text(s, "name", "未知歌曲"), artist, s.optDouble("interval"), "QQ音乐")
        }
    }

    private fun resolveQq(mid: String): String {
        val p = JSONObject().put("guid", "10000").put("songmid", JSONArray().put(mid))
            .put("songtype", JSONArray().put(0)).put("uin", cookieValue("wxuin", "uin") ?: "0")
            .put("loginflag", 1).put("platform", "20").put("filename", JSONArray().put("M500$mid$mid.mp3"))
        val r = qqRequest("vkey.GetVkeyServer", "CgiGetVkey", p)
        val data = child(r.optJSONObject("req"), "data")
        val path = data.optJSONArray("midurlinfo")?.optJSONObject(0)?.optString("purl").orEmpty()
        if (path.isEmpty()) error(if (loggedIn(4)) "当前账号无该歌曲播放权限" else "该歌曲需要登录或会员；未登录仅可播放平台允许的免费内容")
        return data.optJSONArray("sip")?.optString(0).orEmpty() + path
    }

    private fun qqRequest(module: String, method: String, param: JSONObject): JSONObject {
        val s = session(); val cookie = s.optString("cookie")
        val comm = JSONObject().put("ct", 24).put("cv", 0).put("uin", cookieValue("wxuin", "uin") ?: "0").put("g_tk", gtk(cookie))
        if (s.optString("loginType") == "wechat") {
            comm.put("login_type", 2).put("tmeLoginType", 2).put("authst", cookieValue("qqmusic_key").orEmpty())
        }
        val root = JSONObject().put("comm", comm).put("req", JSONObject().put("module", module).put("method", method).put("param", param))
        val r = JSONObject(request("https://u.y.qq.com/cgi-bin/musicu.fcg", root.toString(), "qq"))
        val code = r.optJSONObject("req")?.optInt("code") ?: 0
        if (code != 0) error("QQ 音乐接口返回错误 $code")
        return r
    }

    private fun netease(query: String, page: Int): List<OnlineTrack> {
        val body = "s=${encode(query.trim().ifEmpty { "热门歌曲" })}&type=1&offset=${(page.coerceAtLeast(1) - 1) * 30}&limit=30&hlpretag=&hlposttag="
        val r = JSONObject(request("https://music.163.com/api/search/get/web", body, "netease"))
        val rows = r.optJSONObject("result")?.optJSONArray("songs") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { i ->
            val s = rows.getJSONObject(i)
            if (s.optInt("fee", 1) !in intArrayOf(0, 8)) return@mapNotNull null
            val artists = s.optJSONArray("artists") ?: JSONArray()
            OnlineTrack("netease:${s.optLong("id")}", text(s, "name", "未知歌曲"),
                (0 until artists.length()).joinToString(" / ") { text(artists.getJSONObject(it), "name", "未知歌手") },
                s.optDouble("duration") / 1000.0, "网易云音乐")
        }
    }

    private fun resolveNetease(id: String): String {
        val r = JSONObject(request("https://music.163.com/api/song/enhance/player/url/v1?ids=${encode("[$id]")}&level=standard&encodeType=mp3", provider = "netease"))
        val item = r.optJSONArray("data")?.optJSONObject(0) ?: error("网易云未返回播放信息")
        if (item.optInt("code") != 200 || item.optString("url").isEmpty()) error("当前账号无该歌曲播放权限")
        if (item.opt("freeTrialInfo") != JSONObject.NULL && item.opt("freeTrialInfo") != null) error("该歌曲仅提供试听片段，不作为完整免费歌曲")
        return item.optString("url").replace("http://", "https://")
    }

    /** 仅请求 Jamendo 官方公开试听；搜索结果中的 audio 就是可解析的 MP3 地址。 */
    private fun jamendo(query: String, page: Int): List<OnlineTrack> {
        val key = jamendoClientId()
        require(key.isNotEmpty()) { "请先填写 Jamendo client_id" }
        val url = "https://api.jamendo.com/v3.0/tracks/?client_id=${encode(key)}" +
            "&format=json&limit=30&offset=${(page.coerceAtLeast(1) - 1) * 30}" +
            "&search=${encode(query.trim())}&audioformat=mp32&include=licenses"
        val r = JSONObject(request(url, provider = "jamendo"))
        check(child(r, "headers").optString("status") != "failed") {
            child(r, "headers").optString("error_message", "Jamendo client_id 无效")
        }
        val rows = r.optJSONArray("results") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { i ->
            val s = rows.getJSONObject(i)
            val id = text(s, "id"); val audio = text(s, "audio")
            if (id.isEmpty() || audio.isEmpty()) null
            else OnlineTrack("jamendo:$audio", text(s, "name", "未知歌曲"),
                text(s, "artist_name", "未知歌手"), s.optDouble("duration"), "Jamendo")
        }
    }

    private fun kugou(query: String, page: Int): List<OnlineTrack> {
        // The provider's HTTPS CDN serves a different host certificate; keep normal TLS validation elsewhere.
        val url = "http://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=${encode(query.trim().ifEmpty { "免费歌曲" })}" +
            "&page=${page.coerceAtLeast(1)}&pagesize=30&showtype=1"
        val rows = JSONObject(request(url, provider = "kugou")).optJSONObject("data")?.optJSONArray("info") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { i ->
            val s = rows.getJSONObject(i); val hash = text(s, "hash")
            if (hash.isEmpty() || s.optInt("pay_type") != 0 || s.optInt("fail_process") != 0) return@mapNotNull null
            OnlineTrack("kugou:$hash:${s.optLong("album_audio_id")}", text(s, "songname", "未知歌曲"),
                text(s, "singername", "未知歌手"), s.optDouble("duration"), "酷狗音乐")
        }
    }

    private fun resolveKugou(id: String): String {
        val parts = id.split(':'); if (parts.size != 2) error("酷狗歌曲标识无效")
        val url = "https://wwwapi.kugou.com/yy/index.php?r=play/getdata&hash=${parts[0]}&album_id=${parts[1]}&dfid=0&mid=0&platid=4"
        val r = JSONObject(request(url, provider = "kugou")); val data = r.optJSONObject("data") ?: JSONObject()
        if (r.optInt("status") != 1 || data.optString("play_url").isEmpty()) error("酷狗当前未提供该歌曲的免费播放地址")
        if (data.optInt("is_free_part") == 1) error("该歌曲仅提供试听片段")
        return data.optString("play_url")
    }

    private fun session(): JSONObject { MusicSourceApi.restore(activity); return JSONObject(MusicSourceApi.sessionInfo()) }
    private fun request(url: String, body: String = "", provider: String = "", method: String? = null): String {
        var result = ""; var failure: Exception? = null; val lock = Object()
        synchronized(lock) {
            MusicSourceApi.request(activity, url, method ?: if (body.isEmpty()) "GET" else "POST", body, provider) { raw ->
                try {
                    val r = JSONObject(raw)
                    if (r.has("error")) error(r.optString("message", "网络请求失败"))
                    if (r.optInt("status") !in 200..299) error("HTTP ${r.optInt("status")}")
                    result = r.optString("body")
                } catch (e: Exception) { failure = e }
                synchronized(lock) { lock.notifyAll() }
            }
            lock.wait(25_000)
        }
        failure?.let { throw it }; if (result.isEmpty()) error("服务暂不可用"); return result
    }

    private fun <T> deliver(callback: (Result<T>) -> Unit, result: Result<T>) = activity.runOnUiThread { callback(result) }
    private fun providerName(provider: Int) = when (provider) { 4 -> "qq"; 2 -> "netease"; 3 -> "kugou"; 5 -> "jamendo"; else -> "" }
    private fun providerLabel(provider: Int) = when (provider) { 1 -> "酷我"; 4 -> "QQ"; 2 -> "网易云"; 3 -> "酷狗"; 5 -> "Jamendo"; else -> "未知来源" }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun cookieValue(vararg names: String): String? = session().optString("cookie").split(';').map { it.trim() }
        .firstOrNull { item -> names.any { item.startsWith("$it=") } }?.substringAfter('=')
    private fun gtk(cookie: String): Int {
        val key = cookie.split(';').map { it.trim() }.firstOrNull { it.startsWith("qqmusic_key=") || it.startsWith("p_skey=") || it.startsWith("skey=") }?.substringAfter('=') ?: return 5381
        var hash = 5381L; key.forEach { hash = (hash * 33 + it.code) and 0x7fffffffL }; return hash.toInt()
    }
    private fun child(parent: JSONObject?, key: String): JSONObject = parent?.optJSONObject(key) ?: JSONObject()
    private fun text(o: JSONObject, key: String, fallback: String = "") = o.optString(key, fallback).replace("&nbsp;", " ").takeUnless { it == "null" } ?: fallback
}
