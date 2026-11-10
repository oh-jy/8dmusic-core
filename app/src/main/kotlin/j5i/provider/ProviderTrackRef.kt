package j5i.spatial8d.provider

import android.content.Context
import android.net.Uri
import j5i.spatial8d.data.MusicItem
import org.json.JSONObject
import java.net.URLEncoder

/** Persistent online songs contain stable platform ids; temporary audio URLs are resolved on playback. */
internal object ProviderTrackRef {
    fun plugin(id: String, track: JSONObject) = MusicItem(
        (id + ":" + track.getString("id")).hashCode().toLong(), track.getString("title"),
        uri("plugin", id, track.getString("id"), track.optString("duration")),
        duration = track.optLong("duration") * 1000, artist = track.optString("artist").ifEmpty { "未知歌手" },
        album = "plugin:https:$id")

    fun declarative(id: String, track: ProviderSearch.Track) = MusicItem(
        (id + ":" + track.id).hashCode().toLong(), track.title,
        uri("provider", id, track.id, "", track.audioUrl.orEmpty()), artist = track.artist,
        album = "provider:${if (track.audioUrl?.startsWith("http://", true) == true) "http" else "https"}:$id")

    fun isOnline(item: MusicItem) = item.uri.startsWith("online://plugin/") || item.uri.startsWith("online://provider/")

    fun resolve(context: Context, item: MusicItem): MusicItem {
        val uri = Uri.parse(item.uri)
        val id = uri.getQueryParameter("source").orEmpty()
        val trackId = uri.getQueryParameter("id").orEmpty()
        require(id.isNotEmpty() && trackId.isNotEmpty()) { "在线歌曲标识无效" }
        if (uri.host == "plugin") {
            val store = ProviderPluginStore(context)
            val entry = store.list().firstOrNull { it.enabled && it.descriptor.id == id }
                ?: error("插件来源不存在或已停用")
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
        require(uri.host == "provider") { "不支持的在线歌曲类型" }
        val entry = ProviderStore(context).list().firstOrNull { it.enabled && it.definition.id == id }
            ?: error("在线来源不存在或已停用")
        val url = uri.getQueryParameter("audio").orEmpty()
        require(url.isNotEmpty()) { "此声明式来源没有持久播放地址，请重新搜索播放" }
        val audio = java.net.URI(url)
        require(audio.host != null && audio.host in entry.definition.domains && audio.userInfo == null &&
            (audio.scheme == "https" || entry.definition.usesHttp && audio.scheme == "http")) { "播放地址不在来源授权范围内" }
        return item.copy(uri = url, album = "provider:${if (entry.definition.usesHttp) "http" else "https"}:$id")
    }

    private fun uri(kind: String, id: String, trackId: String, duration: String, audio: String = ""): String {
        fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
        return "online://$kind/play?source=${enc(id)}&id=${enc(trackId)}&duration=${enc(duration)}&audio=${enc(audio)}"
    }
}
