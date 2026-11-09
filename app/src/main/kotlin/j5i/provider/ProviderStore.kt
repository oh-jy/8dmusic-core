package j5i.spatial8d.provider

import android.content.Context
import org.json.JSONObject

/** Imported definitions are stored as data; reading the store does not perform network I/O. */
internal class ProviderStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("imported_providers_v1", Context.MODE_PRIVATE)
    companion object { private val lock = Any() }

    data class Entry(val definition: ProviderDefinition, val enabled: Boolean)

    fun list(): List<Entry> = synchronized(lock) {
        val root = read()
        root.keys().asSequence().sorted().map { id ->
            val item = root.getJSONObject(id)
            val definition = ProviderDefinition.parse(item.getString("json").toByteArray(Charsets.UTF_8))
            check(definition.id == id) { "保存的来源 ID 不匹配" }
            Entry(definition, item.getBoolean("enabled"))
        }.toList()
    }

    /** Caller must show the parsed definition and obtain confirmation before saving. */
    fun put(bytes: ByteArray): ProviderDefinition = synchronized(lock) {
        val definition = ProviderDefinition.parse(bytes)
        val root = read()
        val old = root.optJSONObject(definition.id)
        root.put(definition.id, JSONObject().put("json", bytes.toString(Charsets.UTF_8))
            .put("enabled", old?.optBoolean("enabled", true) ?: true))
        write(root)
        definition
    }

    fun putAll(items: List<ByteArray>) = synchronized(lock) {
        val definitions = items.map(ProviderDefinition::parse)
        require(definitions.map { it.id }.distinct().size == definitions.size) { "来源 ID 重复" }
        val root = read()
        definitions.zip(items).forEach { (definition, bytes) ->
            val old = root.optJSONObject(definition.id)
            root.put(definition.id, JSONObject().put("json", bytes.toString(Charsets.UTF_8))
                .put("enabled", old?.optBoolean("enabled", true) ?: true))
        }
        write(root)
    }

    fun setEnabled(id: String, enabled: Boolean) = synchronized(lock) {
        val root = read()
        val item = root.optJSONObject(id) ?: throw IllegalArgumentException("来源不存在")
        item.put("enabled", enabled)
        write(root)
    }

    fun remove(id: String) = synchronized(lock) {
        val root = read()
        require(root.has(id)) { "来源不存在" }
        root.remove(id)
        write(root)
    }

    private fun read() = JSONObject(prefs.getString("providers", "{}") ?: "{}")
    private fun write(value: JSONObject) {
        check(prefs.edit().putString("providers", value.toString()).commit()) { "保存来源失败" }
    }
}
