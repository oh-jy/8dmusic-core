package j5i.spatial8d.provider

import org.json.JSONObject
import java.net.URI

/** Validate plugin request and redirect addresses against declared domains. */
internal object ProviderBrokerPolicy {
    private val allowedHeaders = setOf("accept", "content-type", "referer", "origin", "user-agent", "cookie")
    data class Request(val method: String, val url: String, val headers: Map<String, String>, val body: String)

    fun validateUrl(raw: String, domains: Collection<String>, allowHttp: Boolean = true): URI {
        require(raw.length in 1..4096) { "插件地址长度无效" }
        val uri = URI(raw)
        val https = uri.scheme.equals("https", true)
        val http = uri.scheme.equals("http", true) && allowHttp
        require((https || http) && uri.host != null && uri.rawUserInfo == null && uri.fragment == null &&
            (uri.port == -1 || allowHttp && uri.port in 1..65535 || !allowHttp && https && uri.port == 443)) {
            "插件音频地址协议或端口不允许"
        }
        require(domains.any { domain ->
            domain.equals(uri.host, true) || domain.startsWith("*.") &&
                (uri.host.endsWith(domain.substring(1), true) && uri.host.length > domain.length - 1)
        }) { "插件请求了未授权域名：${uri.host}" }
        return uri
    }

    fun parse(json: String, domains: Collection<String>): Request {
        require(json.toByteArray(Charsets.UTF_8).size <= 32 * 1024) { "插件请求过大" }
        val root = JSONObject(json)
        require(root.keys().asSequence().all { it in setOf("method", "url", "headers", "body") })
        val method = root.getString("method").uppercase()
        require(method in setOf("GET", "POST")) { "插件请求方法不支持" }
        val url = root.getString("url").also { validateUrl(it, domains) }
        val obj = if (root.has("headers")) root.getJSONObject("headers") else JSONObject()
        val headers = linkedMapOf<String, String>()
        obj.keys().forEach { key ->
            val normalized = key.lowercase()
            val value = obj.getString(key)
            require(normalized in allowedHeaders && normalized !in headers &&
                value.length <= (if (normalized == "cookie") 16 * 1024 else 1024) &&
                !value.contains('\r') && !value.contains('\n')) { "插件请求头不允许" }
            if (normalized == "referer") validateUrl(value, domains)
            headers[normalized] = value
        }
        val body = root.optString("body", "")
        require(body.toByteArray(Charsets.UTF_8).size <= 16 * 1024 && (method != "GET" || body.isEmpty()))
        return Request(method, url, headers, body)
    }
}
