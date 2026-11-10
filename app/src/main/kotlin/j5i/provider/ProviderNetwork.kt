package j5i.spatial8d.provider

import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Network access for imported data only. DNS is resolved and pinned by OkHttp at connection time. */
internal object ProviderNetwork {
    private const val MAX_RESPONSE = 2 * 1024 * 1024

    internal fun forwardHeader(name: String, initialHost: String?, currentHost: String?) =
        !name.equals("cookie", true) || initialHost != null && initialHost.equals(currentHost, true)

    internal fun publicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address.map { it.toInt() and 255 }
        return when (address) {
            is Inet4Address -> {
                val a = b[0]; val c = b[1]; val d = b[2]
                a in 1..223 && a != 10 && a != 127 &&
                    !(a == 100 && c in 64..127) && !(a == 169 && c == 254) &&
                    !(a == 172 && c in 16..31) && !(a == 192 && c == 168) &&
                    !(a == 192 && c == 0 && d == 0) && !(a == 192 && c == 0 && d == 2) &&
                    !(a == 192 && c == 88 && d == 99) && !(a == 198 && c in 18..19) &&
                    !(a == 198 && c == 51 && d == 100) && !(a == 203 && c == 0 && d == 113)
            }
            // Reject transition and special-use prefixes that can tunnel to non-public IPv4.
            is Inet6Address -> b[0] in 0x20..0x3f && !(b[0] == 0x20 && b[1] == 0x02) &&
                !(b[0] == 0x20 && b[1] == 0x01 && b[2] < 2) &&
                !(b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8)
            else -> false
        }
    }

    private val publicDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val name = hostname.trimEnd('.').lowercase()
            if (name == "localhost" || name.endsWith(".localhost") || name.endsWith(".local"))
                throw UnknownHostException("不允许访问本机或局域网")
            val addresses = Dns.SYSTEM.lookup(hostname)
            if (addresses.isEmpty() || addresses.any { !publicAddress(it) })
                throw UnknownHostException("来源地址解析到非公网地址")
            return addresses
        }
    }

    fun client(allowHttp: Boolean): OkHttpClient {
        val verify = Interceptor { chain ->
            val url = chain.request().url
            if ((url.scheme != "https" && !(allowHttp && url.scheme == "http")) ||
                url.username.isNotEmpty() || url.password.isNotEmpty()) throw IOException("不允许的来源地址")
            // OkHttp may bypass Dns for literal addresses, so validate them independently.
            if (url.host.contains(':') || url.host.matches(Regex("[0-9.]+"))) {
                val address = try { InetAddress.getByName(url.host) } catch (_: Exception) { null }
                if (address == null || !publicAddress(address)) throw IOException("不允许访问本机或局域网")
            }
            chain.proceed(chain.request())
        }
        return OkHttpClient.Builder().dns(publicDns).proxy(Proxy.NO_PROXY)
            .addInterceptor(verify).followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    }

    private val httpsClient by lazy { client(false) }
    private val httpClient by lazy { client(true) }

    fun open(url: String, allowHttp: Boolean, headers: Map<String, String> = emptyMap()): Response {
        require(headers.keys.all { it.lowercase() in setOf("accept", "range", "icy-metadata", "user-agent", "referer", "cookie") }) {
            "不支持的请求头"
        }
        val client = if (allowHttp) httpClient else httpsClient
        var current = url
        val initialHost = URI(url).host
        repeat(6) {
            val uri = try { URI(current) } catch (_: Exception) { throw IOException("来源地址无效") }
            if (uri.host == null || uri.rawUserInfo != null || uri.fragment != null ||
                (uri.scheme != "https" && !(allowHttp && uri.scheme == "http"))) throw IOException("不允许的来源地址")
            val request = Request.Builder().url(current).get().apply {
                headers.forEach { (name, value) ->
                    if (forwardHeader(name, initialHost, uri.host)) header(name, value)
                }
            }.build()
            val response = try { client.newCall(request).execute() }
                catch (e: IOException) { throw IOException("来源连接失败", e) }
            if (response.isRedirect) {
                val location = response.header("Location")
                val next = location?.let { response.request.url.resolve(it) }
                response.close()
                current = next?.toString() ?: throw IOException("重定向地址无效")
            } else {
                return response
            }
        }
        throw IOException("重定向次数过多")
    }

    fun get(url: String, allowHttp: Boolean, limit: Int = MAX_RESPONSE): ByteArray {
        require(limit in 1..MAX_RESPONSE)
        open(url, allowHttp).use { response ->
            if (!response.isSuccessful) throw IOException("来源请求失败 (HTTP ${response.code})")
            val body = response.body ?: throw IOException("来源响应为空")
            if (body.contentLength() > limit) throw IOException("来源响应过大")
            val stream = body.byteStream()
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                if (out.size() + n > limit) throw IOException("来源响应过大")
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }

    /** A plugin cannot attach credentials, select its own domains, or bypass redirect checks. */
    fun pluginRequest(json: String, domains: Collection<String>): String {
        val parsed = ProviderBrokerPolicy.parse(json, domains)
        var current = parsed.url
        val initialHost = URI(current).host
        repeat(6) {
            ProviderBrokerPolicy.validateUrl(current, domains)
            val builder = Request.Builder().url(current)
            parsed.headers.forEach { (name, value) ->
                if (forwardHeader(name, initialHost, URI(current).host)) builder.header(name, value)
            }
            if (parsed.method == "POST") {
                val media = parsed.headers["content-type"]?.toMediaTypeOrNull()
                builder.post(parsed.body.toRequestBody(media))
            } else builder.get()
            httpClient.newCall(builder.build()).execute().use { response ->
                if (response.isRedirect) {
                    require(parsed.method == "GET") { "插件 POST 请求不允许重定向" }
                    current = response.header("Location")?.let { response.request.url.resolve(it)?.toString() }
                        ?: throw IOException("插件重定向地址无效")
                } else {
                    val responseBody = response.body ?: throw IOException("插件响应为空")
                    val limit = MAX_RESPONSE
                    require(responseBody.contentLength() <= limit) { "插件响应过大" }
                    val out = java.io.ByteArrayOutputStream()
                    responseBody.byteStream().use { input ->
                        val buffer = ByteArray(4096)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            require(out.size() + n <= limit) { "插件响应过大" }
                            out.write(buffer, 0, n)
                        }
                    }
                    return JSONObject().put("status", response.code)
                        .put("body", out.toString(Charsets.UTF_8.name())).toString()
                }
            }
        }
        throw IOException("插件重定向次数过多")
    }
}
