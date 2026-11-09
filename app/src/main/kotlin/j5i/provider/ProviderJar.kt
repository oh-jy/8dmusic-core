package j5i.spatial8d.provider

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.jar.JarFile

/** Verify the complete JAR before its metadata or code can be used. */
internal object ProviderJar {
    const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_ENTRIES = 128
    private val identifier = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val className = Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+")
    // Wildcards are DNS suffixes, not a literal character in the hostname.
    // Keep the dot in `*.` so `*.music.126.net` cannot accidentally become
    // the broader (and invalid) `*music.126.net` form.
    private val domain = Regex("(?i)(?=.{2,253}$)(?:\\*\\.)?(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}")
    private val allowedCapabilities = setOf("search", "resolve", "login", "import-login", "referer", "http-audio")

    data class Descriptor(val id: String, val name: String, val entryClass: String,
                          val domains: List<String>, val capabilities: List<String>,
                          val signerSha256: String, val fileSha256: String)

    fun inspect(file: File): Descriptor = inspectAll(file).single()

    fun inspectAll(file: File): List<Descriptor> {
        require(file.isFile && file.length() in 1..MAX_BYTES.toLong()) { "插件文件过大或为空" }
        var metadata: ByteArray? = null
        var sawDex = false
        var signer: String? = null
        var total = 0
        val names = mutableSetOf<String>()
        JarFile(file, true).use { jar ->
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                require(names.add(entry.name) && names.size <= MAX_ENTRIES) { "插件条目重复或过多" }
                require(!entry.name.startsWith('/') && !entry.name.contains('\\') &&
                    entry.name.split('/').none { it == "." || it == ".." }) { "插件条目路径无效" }
                if (entry.isDirectory) continue
                val output = ByteArrayOutputStream()
                jar.getInputStream(entry).use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= MAX_BYTES) { "插件解压内容过大" }
                        output.write(buffer, 0, n)
                    }
                }
                val upper = entry.name.uppercase()
                val signatureMetadata = upper == "META-INF/MANIFEST.MF" ||
                    (upper.startsWith("META-INF/") && !upper.removePrefix("META-INF/").contains('/') &&
                        listOf(".SF", ".RSA", ".DSA", ".EC").any(upper::endsWith))
                if (signatureMetadata) continue
                require(entry.name == "provider.json" || entry.name == "classes.dex") { "插件包含不允许的条目" }
                val signers = entry.codeSigners ?: throw IllegalArgumentException("插件条目未签名")
                val hashes = signers.map { sha256(it.signerCertPath.certificates.first().encoded) }.toSet()
                require(hashes.size == 1) { "插件必须由单个证书签名" }
                val hash = hashes.single()
                require(signer == null || signer == hash) { "插件签名不一致" }
                signer = hash
                when (entry.name) {
                    "provider.json" -> metadata = output.toByteArray().also { require(it.size <= 16 * 1024) }
                    "classes.dex" -> { require(output.size() > 0); sawDex = true }
                }
            }
        }
        require(sawDex && metadata != null && signer != null) { "插件缺少已签名的描述或 DEX" }
        val root = JSONObject(metadata!!.toString(Charsets.UTF_8))
        val objects = if (root.has("providers")) {
            require(root.keys().asSequence().all { it in setOf("version", "providers") } && root.getInt("version") == 1)
            val providers = root.getJSONArray("providers")
            require(providers.length() in 1..32)
            (0 until providers.length()).map { providers.getJSONObject(it) }
        } else listOf(root)
        val digest = sha256(file.readBytes())
        val descriptors = objects.map { obj ->
        val allowed = setOf("version", "id", "name", "entryClass", "domains", "capabilities")
        require(obj.keys().asSequence().all { it in allowed } && obj.getInt("version") == 1) { "插件描述版本或字段无效" }
        val id = obj.getString("id").also { require(identifier.matches(it)) }
        val name = obj.getString("name").trim().also { require(it.length in 1..80) }
        val entryClass = obj.getString("entryClass").also { require(className.matches(it) && it.length <= 200) }
        fun strings(key: String, max: Int): List<String> {
            val array = obj.getJSONArray(key)
            require(array.length() in 1..max)
            return (0 until array.length()).map { array.getString(it) }.also { require(it.distinct().size == it.size) }
        }
        val domains = strings("domains", 32).map { it.lowercase() }.also { list ->
            require(list.distinct().size == list.size && list.all(domain::matches)) { "插件域名无效" }
        }
        val capabilities = strings("capabilities", 6).also { require(it.all(allowedCapabilities::contains)) }
        require("login" !in capabilities || "import-login" in capabilities) { "登录插件必须支持文件导入" }
        Descriptor(id, name, entryClass, domains, capabilities, signer!!, digest)
        }
        require(descriptors.map { it.id }.distinct().size == descriptors.size) { "插件来源 ID 重复" }
        return descriptors
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
