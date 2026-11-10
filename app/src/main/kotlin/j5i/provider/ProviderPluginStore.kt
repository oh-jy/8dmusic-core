package j5i.spatial8d.provider

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Private, content-addressed plugin files. Verification is repeated before every use. */
internal class ProviderPluginStore(private val context: Context) {
    private val directory = File(context.filesDir, "provider-plugins").apply { check(isDirectory || mkdirs()) }
    private val prefs = context.getSharedPreferences("provider_plugins_v1", Context.MODE_PRIVATE)

    data class Entry(val descriptor: ProviderJar.Descriptor, val file: File, val enabled: Boolean)

    @Synchronized fun list(): List<Entry> {
        val root = JSONObject(prefs.getString("plugins", "{}") ?: "{}")
        val order = listOf("platform.bilibili", "platform.netease", "platform.qq", "platform.kugou")
        return root.keys().asSequence().sortedWith(compareBy<String> { order.indexOf(it).let { index -> if (index < 0) order.size else index } }.thenBy { it }).map { id ->
            val item = root.getJSONObject(id)
            val digest = item.getString("digest")
            require(digest.matches(Regex("[0-9a-f]{64}")))
            val file = File(directory, "$digest.jar")
            val descriptor = ProviderJar.inspectAll(file).first { it.id == id }
            check(descriptor.fileSha256 == digest &&
                descriptor.signerSha256 == item.getString("signer")) { "插件文件或签名已变更" }
            Entry(descriptor, file, item.getBoolean("enabled"))
        }.toList()
    }

    /** The caller must preview and explicitly confirm the exact digest before calling this method. */
    @Synchronized fun install(candidate: File, approved: List<ProviderJar.Descriptor>) {
        val actual = ProviderJar.inspectAll(candidate)
        require(actual == approved) { "插件在确认后发生变化" }
        val root = JSONObject(prefs.getString("plugins", "{}") ?: "{}")
        actual.forEach { descriptor ->
            val old = root.optJSONObject(descriptor.id)
            require(old == null || old.getString("signer") == descriptor.signerSha256) { "已有来源的签名不同，请先删除旧插件" }
        }
        val target = File(directory, "${actual.first().fileSha256}.jar")
        if (!target.exists()) {
            val tmp = File.createTempFile("provider-", ".tmp", directory)
            try {
                candidate.inputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                check(ProviderJar.inspectAll(tmp) == actual) { "插件副本校验失败" }
                check(tmp.setReadOnly()) { "无法保护插件文件" }
                check(tmp.renameTo(target)) { "无法保存插件文件" }
            } finally { if (tmp.exists()) tmp.delete() }
        } else check(ProviderJar.inspectAll(target) == actual) { "插件文件校验失败" }
        val oldDigests = actual.mapNotNull { root.optJSONObject(it.id)?.optString("digest") }.toSet()
        actual.forEach { descriptor ->
            val old = root.optJSONObject(descriptor.id)
            root.put(descriptor.id, JSONObject().put("digest", descriptor.fileSha256).put("signer", descriptor.signerSha256)
                .put("enabled", old?.optBoolean("enabled", true) ?: true))
        }
        check(prefs.edit().putString("plugins", root.toString()).commit()) { "无法保存插件信息" }
        oldDigests.forEach { deleteIfUnused(root, it) }
    }

    @Synchronized fun setEnabled(id: String, enabled: Boolean) {
        val root = JSONObject(prefs.getString("plugins", "{}") ?: "{}")
        root.getJSONObject(id).put("enabled", enabled)
        check(prefs.edit().putString("plugins", root.toString()).commit())
    }

    @Synchronized fun remove(id: String) {
        val root = JSONObject(prefs.getString("plugins", "{}") ?: "{}")
        require(root.has(id))
        val digest = root.getJSONObject(id).getString("digest")
        require(digest.matches(Regex("[0-9a-f]{64}")))
        root.remove(id)
        check(prefs.edit().putString("plugins", root.toString()).commit())
        deleteIfUnused(root, digest)
        ProviderCredentials(context).clear(id)
    }

    private fun deleteIfUnused(root: JSONObject, digest: String) {
        if (digest.matches(Regex("[0-9a-f]{64}")) && root.keys().asSequence().none { root.getJSONObject(it).optString("digest") == digest })
            File(directory, "$digest.jar").delete()
    }
}
