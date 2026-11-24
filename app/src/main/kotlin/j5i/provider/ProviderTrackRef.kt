package j5i.spatial8d.provider

import android.content.Context
import android.net.Uri
import j5i.spatial8d.data.MusicItem
import j5i.spatial8d.ui.AppLanguage
import org.json.JSONObject
import java.net.URLEncoder

/** Persistent online songs contain stable platform ids; temporary audio URLs are resolved on playback. */
internal object ProviderTrackRef {
    fun plugin(id: String, track: JSONObject) = MusicItem(
        (id + ":" + track.getString("id")).hashCode().toLong(), track.getString("title"),
        uri("plugin", id, track.getString("id"), track.optString("duration"), mv = track.optString("mvUrl")),
        duration = track.optLong("duration") * 1000, artist = track.optString("artist").ifEmpty { "未知歌手" },
        album = "plugin:https:$id")

    fun declarative(id: String, track: ProviderSearch.Track) = MusicItem(
        (id + ":" + track.id).hashCode().toLong(), track.title,
        uri("provider", id, track.id, "", track.audioUrl.orEmpty()), duration = track.duration, artist = track.artist,
        album = "provider:${if (track.audioUrl?.startsWith("http://", true) == true) "http" else "https"}:$id")

    fun shortcut(id: String, trackId: String, title: String, artist: String) = MusicItem(
        (id + ":" + trackId).hashCode().toLong(), title,
        uri("plugin", id, trackId, "", mv = ""), artist = artist,
        album = "plugin:https:$id"
    )

    fun isOnline(item: MusicItem) = item.uri.startsWith("online://plugin/") || item.uri.startsWith("online://provider/")

    fun mvIdentity(reference: String): Pair<String, String> {
        val uri = java.net.URI(reference)
        require(uri.scheme == "online" && uri.host == "plugin" && uri.path == "/play") { "只有来源歌曲支持 MV 解析" }
        val fields = uri.rawQuery.orEmpty().split('&').associate {
            it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
        val source = fields["source"].orEmpty()
        val mv = java.net.URI(fields["mv"].orEmpty())
        require(mv.scheme == "https" && mv.rawUserInfo == null && mv.port == -1 && mv.rawQuery == null && mv.fragment == null) { "MV 地址无效" }
        val id = when (source) {
            "platform.qq" -> {
                require(mv.host == "y.qq.com") { "QQ MV 地址无效" }
                Regex("/n/ryqq/mv/([A-Za-z0-9]+)").matchEntire(mv.path)?.groupValues?.get(1)
            }
            "platform.kugou" -> {
                require(mv.host == "www.kugou.com") { "酷狗 MV 地址无效" }
                Regex("/mvweb/html/mv_([A-Fa-f0-9]{32})\\.html").matchEntire(mv.path)?.groupValues?.get(1)
            }
            "platform.netease" -> {
                require(mv.host == "music.163.com") { "网易云 MV 地址无效" }
                Regex("/mv/([0-9]+)").matchEntire(mv.path)?.groupValues?.get(1)
            }
            else -> null
        }
        require(!id.isNullOrEmpty()) { "未找到有效的来源 MV 标识" }
        return source to id
    }

    data class MvOption(val id: String, val label: String, val size: Long, val url: String)

    fun mvOptions(context: Context, item: MusicItem): List<MvOption> {
        val (source, mvId) = mvIdentity(item.uri)
        val entry = ProviderPluginStore(context).list().firstOrNull { it.enabled && it.descriptor.id == source }
            ?: error(AppLanguage.text(context, "provider.error.plugin_unavailable"))
        val request = JSONObject().put("version", 1).put("operation", "mvOptions").put("id", mvId)
            .put("cookie", ProviderCredentials(context).get(source))
        if (android.util.Log.isLoggable("8DProvider", android.util.Log.INFO))
            android.util.Log.i("8DProvider", "查询可用 MV 清晰度 method=mvOptions source=$source mvId=$mvId")
        val response = JSONObject(ProviderPluginRunner.invoke(context, entry, request.toString()))
        val formats = response.getJSONArray("formats")
        require(formats.length() in 1..12) { AppLanguage.text(context, "provider.error.no_mv_formats") }
        return (0 until formats.length()).map { index ->
            val format = formats.getJSONObject(index)
            val id = format.getString("id")
            require(id.matches(Regex("[A-Za-z0-9_-]{1,32}"))) { AppLanguage.text(context, "provider.error.mv_format_id") }
            val url = format.getString("url")
            ProviderBrokerPolicy.validateUrl(url, entry.descriptor.domains, allowHttp = "http-audio" in entry.descriptor.capabilities)
            MvOption(id, format.getString("label").take(40), format.optLong("size").coerceAtLeast(0), url)
        }.distinctBy { it.id }.sortedBy { if (it.size > 0) it.size else Long.MAX_VALUE }
    }

    fun resolve(context: Context, item: MusicItem): MusicItem {
        val uri = Uri.parse(item.uri)
        val id = uri.getQueryParameter("source").orEmpty()
        val trackId = uri.getQueryParameter("id").orEmpty()
        require(id.isNotEmpty() && trackId.isNotEmpty()) { AppLanguage.text(context, "provider.error.track_id") }
        if (uri.host == "plugin") {
            val store = ProviderPluginStore(context)
            val entry = store.list().firstOrNull { it.enabled && it.descriptor.id == id }
                ?: error(AppLanguage.text(context, "provider.error.plugin_unavailable"))
            val credential = ProviderCredentials(context).get(id)
            val request = JSONObject().put("version", 1).put("operation", "resolve").put("id", trackId)
                .put("cookie", credential)
            val response = try {
                JSONObject(ProviderPluginRunner.invoke(context, entry, request.toString()))
            } catch (e: Exception) {
                android.util.Log.w("8DProvider", "resolve failed source=$id track=$trackId auth=${if (credential.isEmpty()) "none" else "saved"}: ${e.message}")
                throw e
            }
            val url = response.getString("url")
            val address = java.net.URI(url)
            android.util.Log.i("8DProvider", "audio address source=$id scheme=${address.scheme} hostValid=${address.host != null} port=${address.port} userInfo=${address.rawUserInfo != null} fragment=${address.rawFragment != null}")
            ProviderBrokerPolicy.validateUrl(url, entry.descriptor.domains, allowHttp = true)
            android.util.Log.i("8DProvider", "resolved source=$id track=$trackId host=${java.net.URI(url).host}")
            return item.copy(uri = url)
        }
        require(uri.host == "provider") { AppLanguage.text(context, "provider.error.track_type") }
        val entry = ProviderStore(context).list().firstOrNull { it.enabled && it.definition.id == id }
            ?: error(AppLanguage.text(context, "provider.error.source_unavailable"))
        val url = uri.getQueryParameter("audio").orEmpty()
        require(url.isNotEmpty()) { AppLanguage.text(context, "provider.error.no_play_url") }
        val audio = java.net.URI(url)
        require(audio.host != null && audio.host in entry.definition.domains && audio.userInfo == null &&
            (audio.scheme == "https" || entry.definition.usesHttp && audio.scheme == "http")) { AppLanguage.text(context, "provider.error.unauthorized_url") }
        return item.copy(uri = url, album = "provider:${if (entry.definition.usesHttp) "http" else "https"}:$id")
    }

    private fun uri(kind: String, id: String, trackId: String, duration: String, audio: String = "", mv: String = ""): String {
        fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
        return "online://$kind/play?source=${enc(id)}&id=${enc(trackId)}&duration=${enc(duration)}&audio=${enc(audio)}&mv=${enc(mv)}"
    }
}
