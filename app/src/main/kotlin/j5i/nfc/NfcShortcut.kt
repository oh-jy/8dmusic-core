package j5i.spatial8d.nfc

import j5i.spatial8d.audio.Lyrics
import java.net.IDN
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

internal object NfcShortcut {
    const val SCHEME = "spatial8d"
    private const val MAX_URI_LENGTH = 480
    private val identifier = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    private val sensitiveQueryWords = setOf(
        "auth", "authorization", "cookie", "key", "password", "secret", "session", "sign", "token"
    )

    sealed interface Target
    data class Fm(val providerId: String, val stationId: String) : Target
    data class PluginSong(val providerId: String, val trackId: String, val list: String? = null) : Target
    data class ProviderSong(val providerId: String, val trackId: String, val audioUrl: String, val list: String? = null) : Target
    data class LocalSong(val mediaStoreId: Long, val list: String? = null) : Target
    data class Shortcut(val target: Target, val name: String?)

    fun create(target: Target, name: String? = null): String {
        val normalizedName = when (target) {
            is Fm -> name?.trim()?.takeIf { it.isNotEmpty() }
            else -> name?.let(Lyrics::displayTitle)?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("NFC 歌曲缺少名称")
        }
        val base = when (target) {
            is Fm -> buildUri("fm", listOf(
                "src" to target.providerId,
                "sid" to target.stationId
            ))
            is PluginSong -> buildUri("song", listOf(
                "k" to "plugin",
                "src" to target.providerId,
                "id" to target.trackId
            ))
            is ProviderSong -> buildUri("song", listOf(
                "k" to "provider",
                "src" to target.providerId,
                "id" to target.trackId,
                "audio" to target.audioUrl
            ))
            is LocalSong -> buildUri("song", listOf(
                "k" to "local",
                "id" to target.mediaStoreId.toString()
            ))
        }
        val list = when (target) {
            is Fm -> null
            is PluginSong -> target.list
            is ProviderSong -> target.list
            is LocalSong -> target.list
        }
        val uri = if (list == null) base else "$base&list=${URLEncoder.encode(list, Charsets.UTF_8.name())}"
        require(uri.length <= MAX_URI_LENGTH) { "NFC 快捷地址过长" }
        val named = normalizedName?.let {
            "$uri&name=${URLEncoder.encode(it, Charsets.UTF_8.name())}"
        }
        if (target !is Fm) require(named!!.length <= MAX_URI_LENGTH) { "nfc.error.capacity" }
        val result = named?.takeIf { it.length <= MAX_URI_LENGTH } ?: uri
        parse(result)
        return result
    }

    fun parseShortcut(raw: String): Shortcut {
        val target = parse(raw)
        val name = parseQuery(URI(raw).rawQuery)["name"]?.trim()?.takeIf { it.isNotEmpty() }
        if (target !is Fm) require(name != null) { "NFC 歌曲缺少名称" }
        return Shortcut(target, name)
    }

    fun parse(raw: String): Target {
        val uri = URI(raw)
        require(uri.scheme.equals(SCHEME, ignoreCase = true)) { "不支持的 NFC Scheme" }
        require(uri.host == "v1" && uri.rawUserInfo == null && uri.rawFragment == null) { "不支持的 NFC 地址" }
        val query = parseQuery(uri.rawQuery)
        if (uri.rawPath == "/song") {
            require(query["name"]?.trim()?.isNotEmpty() == true) { "NFC 歌曲缺少名称" }
        }
        val list = query["list"]?.also {
            require(it == "all" || it == "favorites" || (it.startsWith("playlist:") && it.length in 10..137)) { "NFC 列表无效" }
        }
        return when (uri.rawPath) {
            "/fm" -> {
                require(list == null) { "NFC 电台不支持列表" }
                requireKeys(query, "src", "sid")
                Fm(validatedId(query.getValue("src")), validatedId(query.getValue("sid")))
            }
            "/song" -> when (val kind = query["k"]) {
                "plugin" -> {
                    requireKeys(query, "k", "src", "id")
                    PluginSong(validatedId(query.getValue("src")), validatedId(query.getValue("id")), list)
                }
                "provider" -> {
                    requireKeys(query, "k", "src", "id", "audio")
                    ProviderSong(
                        validatedId(query.getValue("src")),
                        validatedId(query.getValue("id")),
                        validatedAudioUrl(query.getValue("audio")), list
                    )
                }
                "local" -> {
                    requireKeys(query, "k", "id")
                    val id = query.getValue("id").toLongOrNull()
                    require(id != null && id > 0L) { "本地歌曲 ID 无效" }
                    LocalSong(id, list)
                }
                else -> throw IllegalArgumentException("不支持的 NFC 歌曲类型: $kind")
            }
            else -> throw IllegalArgumentException("不支持的 NFC 路径")
        }
    }

    private fun buildUri(path: String, params: List<Pair<String, String>>): String {
        val query = params.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, Charsets.UTF_8.name())}"
        }
        return "$SCHEME://v1/$path?$query"
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        require(!rawQuery.isNullOrBlank()) { "NFC 缺少查询参数" }
        return rawQuery.split('&').associate { part ->
            require('=' in part) { "NFC 查询参数无效" }
            part.substringBefore('=') to URLDecoder.decode(part.substringAfter('='), Charsets.UTF_8.name())
        }
    }

    private fun requireKeys(query: Map<String, String>, vararg keys: String) {
        require(query.keys - setOf("name", "list") == keys.toSet()) { "NFC 参数不匹配" }
        keys.forEach { require(query[it].orEmpty().isNotBlank()) { "NFC 参数为空: $it" } }
    }

    private fun validatedId(value: String): String {
        require(identifier.matches(value)) { "NFC 标识无效" }
        return value
    }

    private fun validatedAudioUrl(value: String): String {
        val uri = URI(value)
        require(uri.scheme?.lowercase() in setOf("http", "https") && uri.host != null &&
            uri.rawUserInfo == null && uri.rawFragment == null) { "NFC 音频地址无效" }
        require(IDN.toASCII(uri.host).all { it.isLetterOrDigit() || it in ".-_" }) { "NFC 音频域名无效" }
        val queryKeys = uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }
            .map { it.substringBefore('=').lowercase() }
        require(queryKeys.none { key ->
            key.split(Regex("[^a-z0-9]")).any { it in sensitiveQueryWords }
        }) { "NFC 不能保存带凭据的音频地址" }
        return value
    }
}

internal class NfcRepeatGuard(private val intervalMillis: Long = 3000L) {
    private var lastTarget: NfcShortcut.Target? = null
    private var lastSeenMillis = 0L

    @Synchronized
    fun accept(target: NfcShortcut.Target, nowMillis: Long): Boolean {
        val duplicate = target == lastTarget && nowMillis >= lastSeenMillis && nowMillis - lastSeenMillis < intervalMillis
        lastTarget = target
        lastSeenMillis = nowMillis
        return !duplicate
    }
}
