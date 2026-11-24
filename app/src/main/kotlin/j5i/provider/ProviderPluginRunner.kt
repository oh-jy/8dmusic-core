package j5i.spatial8d.provider

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import org.eightdmusic.provider.ipc.INetworkBroker
import org.eightdmusic.provider.ipc.IProviderRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import java.io.File

/** Must be called off the main thread. No code from a provider is loaded in the host process. */
internal object ProviderPluginRunner {
    @Volatile private var iptvRetryAfter = 0L
    fun invoke(context: Context, entry: ProviderPluginStore.Entry, requestJson: String): String {
        require(Build.VERSION.SDK_INT >= 26) { "插件需要 Android 8 或更高版本" }
        require(entry.enabled) { "来源已停用" }
        require(entry.descriptor in ProviderJar.inspectAll(entry.file)) { "插件签名或内容发生变化" }
        if (entry.descriptor.id == "platform.iyouhun" && JSONObject(requestJson).optString("operation") == "liveRefresh") {
            val url = "https://www.iyouhun.com/tv/zb"
            val networkRequest = JSONObject().put("method", "GET").put("url", url).put("responseCharset", "UTF-8").toString()
            LivePlaylistCache.refresh(File(context.filesDir, "live-catalog/iyouhun.txt")) {
                val result = JSONObject(ProviderNetwork.pluginRequest(networkRequest, entry.descriptor.domains))
                check(result.getInt("status") == 200) { "直播目录下载失败" }
                result.getString("body")
            }
            runCatching {
                val iptvUrl = "https://api.github.com/repos/vbskycn/iptv/contents/tv/iptv4.txt"
                val iptvRequest = JSONObject().put("method", "GET").put("url", iptvUrl).put("headers", JSONObject().put("User-Agent", "8dmusic")).toString()
                LivePlaylistCache.refresh(File(context.filesDir, "live-catalog/iptv4.json"), { value -> runCatching { JSONObject(value).getString("content").isNotBlank() }.getOrDefault(false) }) {
                    val result = JSONObject(ProviderNetwork.pluginRequest(iptvRequest, entry.descriptor.domains))
                    check(result.getInt("status") == 200)
                    result.getString("body")
                }
                iptvRetryAfter = 0L
            }
            return JSONObject().put("ok", true).toString()
        }
        val latch = CountDownLatch(1)
        var service: IProviderRunner? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = IProviderRunner.Stub.asInterface(binder)
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { service = null; latch.countDown() }
            override fun onBindingDied(name: ComponentName?) { service = null; latch.countDown() }
            override fun onNullBinding(name: ComponentName?) { latch.countDown() }
        }
        val app = context.applicationContext
        check(app.bindService(Intent(app, IsolatedProviderService::class.java), connection, Context.BIND_AUTO_CREATE)) {
            "无法启动隔离插件进程"
        }
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "provider-call").apply { isDaemon = true } }
        val active = AtomicBoolean(true)
        try {
            check(latch.await(10, TimeUnit.SECONDS)) { "插件进程启动超时" }
            val remote = requireNotNull(service) { "插件进程不可用" }
            val count = AtomicInteger()
            val broker = object : INetworkBroker.Stub() {
                override fun request(requestJson: String): String {
                    check(active.get()) { "插件调用已结束" }
                    require(count.incrementAndGet() <= 20) { "插件请求次数过多" }
                    val request = ProviderBrokerPolicy.parse(requestJson, entry.descriptor.domains)
                    if (entry.descriptor.id == "platform.iyouhun" && request.method == "GET" &&
                        request.url == "https://www.iyouhun.com/tv/zb" && request.headers.isEmpty()) {
                        val file = File(app.filesDir, "live-catalog/iyouhun.txt")
                        val body = LivePlaylistCache.get(file) {
                            val result = JSONObject(ProviderNetwork.pluginRequest(requestJson, entry.descriptor.domains))
                            check(result.getInt("status") == 200) { "直播目录下载失败" }
                            result.getString("body")
                        }
                        return JSONObject().put("status", 200).put("body", body).toString()
                    }
                    if (entry.descriptor.id == "platform.iyouhun" && request.method == "GET" &&
                        request.url == "https://api.github.com/repos/vbskycn/iptv/contents/tv/iptv4.txt") {
                        val file = File(app.filesDir, "live-catalog/iptv4.json")
                        if (!file.exists() && System.currentTimeMillis() < iptvRetryAfter) return JSONObject().put("status", 503).toString()
                        val body = try {
                            LivePlaylistCache.get(file, { value -> runCatching { JSONObject(value).getString("content").isNotBlank() }.getOrDefault(false) }) {
                                val result = JSONObject(ProviderNetwork.pluginRequest(requestJson, entry.descriptor.domains))
                                check(result.getInt("status") == 200)
                                result.getString("body")
                            }
                        } catch (failure: Exception) {
                            iptvRetryAfter = System.currentTimeMillis() + 120_000
                            throw failure
                        }
                        return JSONObject().put("status", 200).put("body", body).toString()
                    }
                    return ProviderNetwork.pluginRequest(requestJson, entry.descriptor.domains)
                }
            }
            val call = executor.submit<String> {
                ParcelFileDescriptor.open(entry.file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    remote.invoke(fd, entry.descriptor.entryClass, requestJson, broker)
                }
            }
            return try { call.get(30, TimeUnit.SECONDS) }
            catch (e: ExecutionException) {
                val cause = e.cause
                val message = cause?.message?.removePrefix("java.lang.IllegalStateException: ") ?: "插件调用失败"
                throw IllegalStateException(message, cause)
            } finally { call.cancel(true) }
        } finally {
            active.set(false)
            app.unbindService(connection)
            executor.shutdownNow()
        }
    }
}
