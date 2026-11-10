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

/** Must be called off the main thread. No code from a provider is loaded in the host process. */
internal object ProviderPluginRunner {
    fun invoke(context: Context, entry: ProviderPluginStore.Entry, requestJson: String): String {
        require(Build.VERSION.SDK_INT >= 26) { "插件需要 Android 8 或更高版本" }
        require(entry.enabled) { "来源已停用" }
        require(entry.descriptor in ProviderJar.inspectAll(entry.file)) { "插件签名或内容发生变化" }
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
