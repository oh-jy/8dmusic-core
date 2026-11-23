package j5i.spatial8d

import android.content.Context
import org.json.JSONObject
import java.io.File

internal fun cacheMetadataValue(value: String): String = if (value.contains("://")) "" else value.take(200)
internal fun isVideoCacheName(name: String): Boolean = name.matches(Regex("[a-f0-9]{64}\\.mp4"))

internal object PlaybackCacheMetadata {
    data class Entry(val title: String, val source: String, val quality: String)
    private val active = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun begin(file: File) { active.compute(file.absolutePath) { _, count -> (count ?: 0) + 1 } }
    fun tryBegin(file: File) = active.putIfAbsent(file.absolutePath, 1) == null
    fun end(file: File) { active.computeIfPresent(file.absolutePath) { _, count -> if (count <= 1) null else count - 1 } }
    fun isActive(file: File) = active.containsKey(file.absolutePath)

    private fun preferences(context: Context) = context.getSharedPreferences("playback_cache_metadata", Context.MODE_PRIVATE)

    fun put(context: Context, file: File, title: String, source: String, quality: String = "") {
        runCatching {
            val old = get(context, file)
            val value = JSONObject().put("title", cacheMetadataValue(title).ifEmpty { old?.title.orEmpty() })
                .put("source", cacheMetadataValue(source).ifEmpty { old?.source.orEmpty() })
                .put("quality", cacheMetadataValue(quality).ifEmpty { old?.quality.orEmpty() })
            preferences(context).edit().putString(file.name, value.toString()).apply()
        }
    }

    fun get(context: Context, file: File): Entry? = runCatching {
        val value = JSONObject(preferences(context).getString(file.name, null) ?: return null)
        Entry(value.optString("title"), value.optString("source"), value.optString("quality"))
    }.getOrNull()

    fun remove(context: Context, file: File) {
        runCatching { preferences(context).edit().remove(file.name).apply() }
    }
}
