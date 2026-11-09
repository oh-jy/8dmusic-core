package j5i.spatial8d.provider

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import dalvik.system.InMemoryDexClassLoader
import org.eightdmusic.provider.api.MusicProvider
import org.eightdmusic.provider.api.NetworkBroker
import org.eightdmusic.provider.ipc.INetworkBroker
import org.eightdmusic.provider.ipc.IProviderRunner
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.ZipInputStream

/** The host passes a verified JAR descriptor; this process has a separate isolated UID. */
internal class IsolatedProviderService : Service() {
    private val binder = object : IProviderRunner.Stub() {
        override fun invoke(jar: ParcelFileDescriptor, entryClass: String, requestJson: String, broker: INetworkBroker): String {
            require(Build.VERSION.SDK_INT >= 26) { "插件需要 Android 8 或更高版本" }
            require(requestJson.toByteArray(Charsets.UTF_8).size <= 32 * 1024)
            val dex = ParcelFileDescriptor.AutoCloseInputStream(jar).use { stream ->
                ZipInputStream(stream).use { zip ->
                    var bytes: ByteArray? = null
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (entry.name == "classes.dex") {
                            require(bytes == null)
                            val out = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val n = zip.read(buffer)
                                if (n < 0) break
                                require(out.size() + n <= ProviderJar.MAX_BYTES)
                                out.write(buffer, 0, n)
                            }
                            bytes = out.toByteArray()
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                    requireNotNull(bytes) { "插件缺少 DEX" }
                }
            }
            val loader = InMemoryDexClassLoader(ByteBuffer.wrap(dex), MusicProvider::class.java.classLoader)
            val clazz = loader.loadClass(entryClass)
            require(clazz.classLoader === loader && MusicProvider::class.java.isAssignableFrom(clazz)) { "插件入口无效" }
            val provider = clazz.getDeclaredConstructor().newInstance() as MusicProvider
            val response = provider.invoke(requestJson, NetworkBroker { request -> broker.request(request) })
            require(response.toByteArray(Charsets.UTF_8).size <= 128 * 1024) { "插件结果过大" }
            return response
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
