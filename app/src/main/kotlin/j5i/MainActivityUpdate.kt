package j5i.spatial8d

import android.content.Intent
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import j5i.spatial8d.ui.ThemeUi
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Dns
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val UPDATE_URL = "https://api.github.com/repos/oh-jy/8dmusic-core/releases/latest"
private const val BETA_UPDATE_URL = "https://api.github.com/repos/oh-jy/8dmusic-core/releases/tags/beta-latest"
private const val MAX_APK_BYTES = 300L * 1024 * 1024
private val updateClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS).callTimeout(5, TimeUnit.MINUTES).build()
private val updateBusy = AtomicBoolean(false)

private fun clientForWifi(wifi: Network): OkHttpClient = updateClient.newBuilder()
    .socketFactory(wifi.socketFactory)
    .dns(object : Dns {
        override fun lookup(hostname: String) = wifi.getAllByName(hostname).toList()
    }).build()

internal data class AppRelease(val version: String, val notes: String, val apkUrl: String?, val sha256: String?)

internal fun updateDownloadSources(apkUrl: String): List<String> {
    val uri = java.net.URI(apkUrl)
    require(uri.scheme == "https" && uri.host == "github.com" &&
        uri.path.startsWith("/oh-jy/8dmusic-core/releases/download/") && uri.userInfo == null && uri.port == -1 &&
        uri.query == null && uri.fragment == null) { "更新下载地址无效" }
    return listOf(apkUrl, "https://ghfast.top/$apkUrl", "https://gh-proxy.com/$apkUrl")
}

internal fun parseAppRelease(json: String, withAsr: Boolean): AppRelease {
    val release = JSONObject(json)
    val versionPattern = Regex("(?:^|[^0-9])(\\d+\\.\\d+\\.\\d+)$")
    val tag = release.getString("tag_name")
    require(tag != "beta-latest" || release.optBoolean("prerelease")) { "测试渠道必须是预发布版本" }
    val versionSource = if (tag == "beta-latest") release.optString("name") else tag
    val version = versionPattern.find(versionSource)?.groupValues?.get(1) ?: error("发布版本号格式无效")
    val assetName = if (withAsr) "app-release-asr.apk" else "app-release-no-asr.apk"
    val assets = release.getJSONArray("assets")
    for (index in 0 until assets.length()) {
        val asset = assets.getJSONObject(index)
        if (asset.optString("name") != assetName) continue
        val url = asset.getString("browser_download_url")
        updateDownloadSources(url)
        val digest = asset.optString("digest").removePrefix("sha256:")
        require(digest.matches(Regex("[a-fA-F0-9]{64}"))) { "更新包缺少 SHA-256 校验值" }
        return AppRelease(version, release.optString("body").ifBlank { "暂无更新说明" }, url, digest.lowercase())
    }
    return AppRelease(version, release.optString("body").ifBlank { "暂无更新说明" }, null, null)
}

internal fun isNewerRelease(latest: String, current: String): Boolean {
    val pattern = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)$")
    val left = pattern.matchEntire(latest) ?: error("最新版本号格式无效")
    val right = pattern.matchEntire(current) ?: error("当前版本号格式无效")
    for (index in 1..3) {
        val comparison = left.groupValues[index].toLong().compareTo(right.groupValues[index].toLong())
        if (comparison != 0) return comparison > 0
    }
    return false
}

internal fun MainActivity.updateChannel(): String = preferences.getString("updateChannel", "stable") ?: "stable"

private fun MainActivity.activeWifi(): Network? {
    val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = manager.activeNetwork ?: return null
    val caps = manager.getNetworkCapabilities(network) ?: return null
    return network.takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
}

internal fun MainActivity.checkAppUpdateAutomatically() {
    val recent = System.currentTimeMillis() - preferences.getLong("updateLastCheck", 0L) < 6 * 60 * 60 * 1000L
    if (recent && (activeWifi() == null || preferences.getBoolean("updateLastCheckWifi", false))) return
    checkAppUpdate(automatic = true)
}

internal fun MainActivity.startUpdateNetworkMonitor() {
    if (updateNetworkCallback != null) return
    val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                main.postDelayed({ if (pageActive && !isFinishing && !isDestroyed) checkAppUpdateAutomatically() }, 4000)
            }
        }
    }
    runCatching { manager.registerDefaultNetworkCallback(callback) }.onSuccess { updateNetworkCallback = callback }
}

internal fun MainActivity.stopUpdateNetworkMonitor() {
    val callback = updateNetworkCallback ?: return
    updateNetworkCallback = null
    val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    runCatching { manager.unregisterNetworkCallback(callback) }
}

private fun MainActivity.recordUpdateCheck() {
    preferences.edit().putLong("updateLastCheck", System.currentTimeMillis())
        .putBoolean("updateLastCheckWifi", activeWifi() != null).apply()
}

internal fun validUpdateCache(file: File, sha256: String): Boolean {
    if (!file.isFile || file.length() <= 0 || file.length() > MAX_APK_BYTES) return false
    return runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == sha256
    }.getOrDefault(false)
}

private fun MainActivity.updateCacheFile(release: AppRelease): File =
    File(File(cacheDir, "updates"), "update-${requireNotNull(release.sha256)}.apk")

internal fun MainActivity.checkAppUpdate(automatic: Boolean = false) {
    if (!updateBusy.compareAndSet(false, true)) {
        if (!automatic) showUpdateMessage("检查更新", "更新检查或下载正在进行。")
        return
    }
    val channel = updateChannel()
    val wifi = activeWifi()
    var status: TextView? = null
    val dialog = if (automatic) null else ThemeUi.showSheet(this, palette, "检查更新", compactPicker = true) {
        status = ThemeUi.text(this@checkAppUpdate, "正在检查 GitHub 最新版本…", 14f, palette)
        addView(status)
    }
    dialog?.show()
    thread(name = "update-check") {
        val result = runCatching {
            val client = if (wifi == null) updateClient else clientForWifi(wifi)
            client.newCall(Request.Builder().url(if (channel == "beta") BETA_UPDATE_URL else UPDATE_URL).header("Accept", "application/vnd.github+json")
                .header("User-Agent", "8dmusic-android").build()).execute().use { response ->
                check(response.isSuccessful) { "GitHub 返回 ${response.code}" }
                val release = parseAppRelease(response.body?.string() ?: error("更新信息为空"), BuildConfig.WITH_ASR)
                val file = if (release.sha256 != null) updateCacheFile(release) else null
                release to file?.takeIf { validUpdateCache(it, release.sha256!!) }
            }
        }
        runOnUiThread {
            updateBusy.set(false)
            if (isFinishing || isDestroyed || updateChannel() != channel || (automatic && !pageActive) || (!automatic && dialog?.isShowing != true)) return@runOnUiThread
            result.fold(onSuccess = { (release, cached) ->
                dialog?.dismiss()
                val newer = runCatching { isNewerRelease(release.version, BuildConfig.VERSION_NAME) }
                val currentWifi = activeWifi()
                if (newer.isFailure) {
                    if (!automatic) showUpdateMessage("检查失败", newer.exceptionOrNull()?.message ?: "版本号无效")
                } else if (newer.getOrDefault(false) && cached != null) {
                    recordUpdateCheck()
                    offerInstall(cached, release)
                } else if (newer.getOrDefault(false) && release.apkUrl != null && currentWifi != null) {
                    downloadAppUpdate(release, silent = true, wifi = currentWifi, channel = channel, automatic = automatic)
                } else {
                    recordUpdateCheck()
                    if (!automatic || newer.getOrDefault(false)) showRelease(release, newer.getOrDefault(false))
                }
            }, onFailure = { status?.text = if (channel == "beta" && it.message?.contains("404") == true) "测试版暂未发布。" else "检查失败：${it.message ?: "网络不可用"}，请稍后重试。" })
        }
    }
}

private fun MainActivity.showUpdateMessage(title: String, message: String) {
    ThemeUi.showSheet(this, palette, title, compactPicker = true) {
        addView(ThemeUi.text(this@showUpdateMessage, message, 14f, palette))
    }.show()
}

private fun MainActivity.showRelease(release: AppRelease, newer: Boolean) {
    lateinit var dialog: android.app.Dialog
    dialog = ThemeUi.showSheet(this, palette, if (newer) "发现新版本" else "已是最新版本", compactPicker = true) {
        addView(ThemeUi.text(this@showRelease, "当前 ${BuildConfig.VERSION_NAME}  ·  最新 ${release.version}", 14f, palette, bold = true))
        addView(ThemeUi.text(this@showRelease, release.notes, 13f, palette).apply {
            setPadding(0, ThemeUi.dp(this@showRelease, 10f), 0, ThemeUi.dp(this@showRelease, 10f))
        })
        if (newer && release.apkUrl == null) {
            addView(ThemeUi.text(this@showRelease, "当前版本对应的安装包尚未发布，请稍后再试。", 13f, palette))
        } else if (newer) {
            addView(ThemeUi.button(this@showRelease, palette, "下载并安装") {
                dialog.dismiss()
                downloadAppUpdate(release)
            })
        }
    }
    dialog.show()
}

private fun MainActivity.downloadAppUpdate(release: AppRelease, silent: Boolean = false, wifi: Network? = null,
    channel: String = updateChannel(), automatic: Boolean = false) {
    if (!updateBusy.compareAndSet(false, true)) return
    val directory = File(cacheDir, "updates").apply { mkdirs() }
    val file = updateCacheFile(release)
    val partial = File(directory, "download-${System.nanoTime()}.part")
    var status: TextView? = null
    val dialog = if (silent) null else ThemeUi.showSheet(this, palette, "下载更新", compactPicker = true) {
        status = ThemeUi.text(this@downloadAppUpdate, "正在下载…", 14f, palette)
        addView(status, LinearLayout.LayoutParams(-1, -2))
    }
    dialog?.show()
    thread(name = "update-download") {
        val result = runCatching {
            if (validUpdateCache(file, requireNotNull(release.sha256))) return@runCatching
            val client = if (wifi == null) updateClient else clientForWifi(wifi)
            val sources = updateDownloadSources(requireNotNull(release.apkUrl))
            var lastError: Exception? = null
            var completed = false
            for ((index, source) in sources.withIndex()) {
                try {
                    check(wifi == null || activeWifi() == wifi) { "Wi-Fi 已断开，下载已停止" }
                    if (index > 0) Thread.sleep(500L * index)
                    client.newCall(Request.Builder().url(source).build()).execute().use { response ->
                check(response.isSuccessful) { "下载失败 (${response.code})" }
                val body = response.body ?: error("下载内容为空")
                val length = body.contentLength()
                require(length <= MAX_APK_BYTES) { "更新包过大" }
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                var lastPercent = -1
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            check(wifi == null || activeWifi() == wifi) { "Wi-Fi 已断开，下载已停止" }
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_APK_BYTES) { "更新包过大" }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            if (length > 0) {
                                val percent = (total * 100 / length).toInt().coerceAtMost(100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    runOnUiThread { if (dialog?.isShowing == true) status?.text = "正在下载：$percent%" }
                                }
                            }
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                check(actual == release.sha256) { "更新包校验失败" }
                if (file.exists()) check(file.delete()) { "无法替换无效缓存" }
                check(partial.renameTo(file)) { "无法保存更新包" }
                    }
                    completed = true
                    break
                } catch (error: Exception) {
                    partial.delete()
                    if (error is InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw error
                    }
                    if (wifi != null && activeWifi() != wifi) throw error
                    lastError = error
                }
            }
            if (!completed) throw lastError ?: IllegalStateException("所有下载源均不可用")
        }
        runOnUiThread {
            updateBusy.set(false)
            if (isFinishing || isDestroyed || updateChannel() != channel || (automatic && !pageActive)) {
                partial.delete()
                return@runOnUiThread
            }
            dialog?.dismiss()
            result.fold(onSuccess = {
                recordUpdateCheck()
                offerInstall(file, release)
            }, onFailure = {
                partial.delete()
                if (!automatic) showUpdateMessage("下载失败", it.message ?: "请检查网络后重试")
            })
        }
    }
}

private fun MainActivity.offerInstall(file: File, release: AppRelease) {
    lateinit var dialog: android.app.Dialog
    val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()
    dialog = ThemeUi.showSheet(this, palette, "发现新版本 ${release.version}", compactPicker = true) {
        addView(ThemeUi.text(this@offerInstall, release.notes, 13f, palette).apply {
            setPadding(0, 0, 0, ThemeUi.dp(this@offerInstall, 10f))
        })
        addView(ThemeUi.text(this@offerInstall, if (needsPermission) "请先允许本应用安装未知应用，返回后点击安装。" else "系统将校验签名并确认安装。", 13f, palette))
        if (needsPermission) addView(ThemeUi.button(this@offerInstall, palette, "允许安装") {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        })
        addView(ThemeUi.button(this@offerInstall, palette, "安装更新") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                showUpdateMessage("需要安装权限", "请先允许本应用安装未知应用。")
            } else {
                val uri = FileProvider.getUriForFile(this@offerInstall, "$packageName.updates", file)
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    dialog.dismiss()
                }.onFailure { showUpdateMessage("无法打开安装器", it.message ?: "请稍后重试") }
            }
        })
    }
    dialog.show()
}
