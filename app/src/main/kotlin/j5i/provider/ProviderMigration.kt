package j5i.spatial8d.provider

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One-shot removal of legacy source references; audio files and local library records are untouched. */
internal object ProviderMigration {
    @Synchronized fun run(context: Context) {
        val prefs = context.getSharedPreferences("spatial8d", Context.MODE_PRIVATE)
        if (prefs.getBoolean("provider_migration_v1", false)) {
            removeKuwo(context, prefs)
            return
        }
        val (favorites, playlists) = filteredReferences(
            prefs.getString("songs", "[]") ?: "[]", prefs.getString("added", "[]") ?: "[]",
            prefs.getString("favorites", "[]") ?: "[]", prefs.getString("playlists", "[]") ?: "[]")
        val edit = prefs.edit().putString("favorites", favorites.toString()).putString("playlists", playlists.toString())
        prefs.all.keys.filter { it == "customRadio" || it.startsWith("radioSource:") || it == "8d_jamendo_client_id" }
            .forEach(edit::remove)
        for (key in listOf("playbackState", "servicePlayback")) {
            val state = prefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: continue
            val uri = state.optString("uri")
            if (uri.startsWith("http://") || uri.startsWith("https://")) edit.remove(key)
            else state.optJSONArray("queue")?.let { queue ->
                val kept = JSONArray()
                for (i in 0 until queue.length()) {
                    val item = queue.optJSONObject(i) ?: continue
                    val link = item.optString("uri")
                    if (!link.startsWith("http://") && !link.startsWith("https://")) kept.put(item)
                }
                state.put("queue", kept)
                edit.putString(key, state.toString())
            }
        }
        check(edit.commit()) { "旧来源迁移失败" }
        check(context.getSharedPreferences("online_provider_sessions", Context.MODE_PRIVATE).edit().clear().commit())
        check(context.getSharedPreferences("qq_music_session", Context.MODE_PRIVATE).edit().clear().commit())
        check(prefs.edit().putBoolean("provider_migration_v1", true).commit())
        removeKuwo(context, prefs)
    }

    private fun removeKuwo(context: Context, prefs: android.content.SharedPreferences) {
        if (prefs.getBoolean("provider_migration_remove_kuwo", false)) return
        val store = ProviderPluginStore(context)
        if (store.list().any { it.descriptor.id == "platform.kuwo" }) store.remove("platform.kuwo")
        val imported = ProviderStore(context)
        if (imported.list().any { it.definition.id == "platform.kuwo" }) imported.remove("platform.kuwo")
        ProviderCredentials(context).clear("platform.kuwo")
        ProviderLoginBackup.delete(context, "platform.kuwo")
        check(prefs.edit().putBoolean("provider_migration_remove_kuwo", true).commit())
    }
    internal fun filteredReferences(songs: String, added: String, favorites: String, playlists: String): Pair<JSONArray, JSONArray> {
        val remoteIds = mutableSetOf<Long>()
        for (text in listOf(songs, added)) {
            val rows = JSONArray(text)
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val uri = row.optString("uri")
                if (uri.startsWith("http://") || uri.startsWith("https://") ||
                    row.optString("album") in setOf("在线收藏", "B站音频")) remoteIds += row.optLong("id")
            }
        }
        fun filteredIds(array: JSONArray): JSONArray = JSONArray().apply {
            for (i in 0 until array.length()) if (array.optLong(i) !in remoteIds) put(array.optLong(i))
        }
        val filtered = filteredIds(JSONArray(favorites))
        val lists = JSONArray(playlists)
        for (i in 0 until lists.length()) {
            val item = lists.optJSONObject(i) ?: continue
            item.optJSONArray("ids")?.let { item.put("ids", filteredIds(it)) }
        }
        return filtered to lists
    }

}
