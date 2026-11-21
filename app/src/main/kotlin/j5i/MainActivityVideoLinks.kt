package j5i.spatial8d

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import j5i.spatial8d.ui.ThemeUi
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import j5i.spatial8d.provider.ProviderNetwork

private fun MainActivity.videoFavorites(): List<String> {
    val json = runCatching { JSONArray(getPreferences(Context.MODE_PRIVATE).getString("video_favorites", "[]")) }.getOrDefault(JSONArray())
    return (0 until json.length()).mapNotNull { json.optString(it).takeIf(String::isNotBlank) }
}

internal fun MainActivity.isVideoFavorite(url: String) = url in videoFavorites()

internal fun MainActivity.toggleVideoFavorite(url: String) {
    val uri = Uri.parse(url)
    val stableBilibili = uri.host == "player.bilibili.com" && uri.path == "/player.html" &&
        uri.queryParameterNames.all { it in setOf("bvid", "cid") }
    if (uri.userInfo != null || uri.encodedQuery != null && !stableBilibili) {
        toast("带查询参数的链接可能包含临时凭据，不能收藏"); return
    }
    val current = videoFavorites()
    val updated = if (url in current) current - url else current + url
    getPreferences(Context.MODE_PRIVATE).edit().putString("video_favorites", JSONArray(updated).toString()).apply()
    toast(if (url in current) "已取消视频收藏" else "已加入视频收藏")
}

internal fun MainActivity.showVideoLinks() {
    lateinit var sheet: Dialog
    sheet = ThemeUi.showSheet(this, palette, "视频链接", fullHeight = true) {
        val input = ThemeUi.input(this@showVideoLinks, palette, "输入网页、MP4 或 M3U8 链接")
        addView(input)
        addView(ThemeUi.button(this@showVideoLinks, palette, "打开") {
            val url = input.text.toString().trim()
            val parsed = Uri.parse(url)
            if (parsed.scheme !in listOf("http", "https") || parsed.host.isNullOrEmpty() || parsed.userInfo != null) {
                toast("请输入有效的 HTTP 或 HTTPS 链接")
            } else {
                sheet.dismiss(); openVideoLink(url)
            }
        })
        addView(ThemeUi.text(this@showVideoLinks, "视频收藏", 15f, palette, bold = true))
        val favorites = videoFavorites()
        if (favorites.isEmpty()) addView(ThemeUi.text(this@showVideoLinks, "暂无视频收藏", 13f, palette, role = ThemeUi.ROLE_SUB))
        favorites.forEach { url ->
            addView(LinearLayout(this@showVideoLinks).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(ThemeUi.button(this@showVideoLinks, palette, Uri.parse(url).let { saved ->
                    if (saved.host == "player.bilibili.com") saved.getQueryParameter("bvid").orEmpty() +
                        saved.getQueryParameter("cid")?.let { " · $it" }.orEmpty()
                    else (saved.host.orEmpty() + saved.path.orEmpty()).take(60)
                }) {
                    sheet.dismiss(); openVideoLink(url)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ThemeUi.button(this@showVideoLinks, palette, "删除") {
                    toggleVideoFavorite(url); sheet.dismiss(); showVideoLinks()
                })
            })
        }
    }
    sheet.show()
}

internal fun MainActivity.openVideoLink(url: String) {
    val parsedUrl = Uri.parse(url)
    if (parsedUrl.host.equals("b23.tv", true) || parsedUrl.host.equals("www.b23.tv", true)) {
        resolveBilibiliShortLink(url)
        return
    }
    val path = parsedUrl.path.orEmpty()
    if (path.endsWith(".mp4", true) || path.endsWith(".m3u8", true)) {
        openExternalMedia(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*"))
    } else {
        Thread {
            val result = runCatching {
                ProviderNetwork.open(url, allowHttp = true).use { response ->
                    require(response.isSuccessful) { "链接请求失败 (HTTP ${response.code})" }
                    val type = response.header("Content-Type").orEmpty().lowercase()
                    if (type.startsWith("video/") || type.contains("mpegurl")) return@use listOf(response.request.url.toString()) to true
                    val text = response.peekBody(262145).string().trimStart()
                    val json = type.contains("json") || text.startsWith("{") || text.startsWith("[")
                    if (!json) return@use listOf(url) to false
                    parseJsonVideoTargets(text, response.request.url.toUri()) to true
                }
            }
            main.post {
                if (isFinishing || isDestroyed) return@post
                result.onSuccess { (targets, video) -> showVideoTargets(targets, video) }
                    .onFailure { loadJsonWithWebView(url) }
            }
        }.start()
    }
}

private fun MainActivity.resolveBilibiliShortLink(url: String) {
    Thread {
        val result = runCatching {
            ProviderNetwork.open(url, allowHttp = true).use { response ->
                require(response.isSuccessful) { "B 站短链请求失败 (HTTP ${response.code})" }
                val finalUrl = response.request.url.toString()
                val body = response.peekBody(262145).string()
                extractBilibiliBvid(finalUrl) ?: extractBilibiliBvid(body)
                    ?: throw IllegalArgumentException("短链中没有找到 BVID")
            }
        }
        main.post {
            if (isFinishing || isDestroyed) return@post
            result.onSuccess { bvid -> openWebVideo(bilibiliPlayerUrl(bvid)) }
                .onFailure { openWebVideo(url) }
        }
    }.start()
}

internal fun extractBilibiliBvid(value: String): String? =
    Regex("(?i)(?:/video/|\\b)(BV[0-9A-Za-z]{6,})").find(value)?.groupValues?.getOrNull(1)

private fun bilibiliPlayerUrl(bvid: String): String =
    Uri.parse("https://player.bilibili.com/player.html").buildUpon()
        .appendQueryParameter("bvid", bvid).build().toString()

private fun parseJsonVideoTargets(text: String, base: java.net.URI): List<String> {
    val normalized = text.trimStart().removePrefix("\uFEFF").trim()
        .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    require(normalized.length <= 262144) { "JSON 响应过大" }
    val parsed = runCatching { JSONTokener(normalized).nextValue() }.getOrNull()
    val candidates = (if (parsed == null) emptyList() else findM3u8(parsed)) + findM3u8InText(normalized)
    val streams = candidates.distinct().mapNotNull { address ->
        runCatching { base.resolve(address) }.getOrNull()?.takeIf {
            it.scheme in listOf("http", "https") && it.host != null && it.userInfo == null
        }?.toString()
    }.distinct()
    require(streams.isNotEmpty()) { "JSON 中没有找到有效的 M3U8 地址" }
    return streams
}

private fun MainActivity.showVideoTargets(targets: List<String>, video: Boolean) {
    fun play(target: String) = openExternalMedia(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(target), "video/*"))
    if (!video) openWebVideo(targets.first())
    else if (targets.size == 1) play(targets.first())
    else pickOption("选择播放地址", targets.mapIndexed { index, target ->
        val uri = Uri.parse(target)
        "${index + 1}. ${uri.host.orEmpty()}${uri.path.orEmpty()}".take(120)
    }, -1) { index -> play(targets[index]) }
}

private fun MainActivity.loadJsonWithWebView(url: String) {
    val parsed = Uri.parse(url)
    if (parsed.scheme !in listOf("http", "https") || parsed.host.isNullOrEmpty() || parsed.userInfo != null) {
        toast("请输入有效的 HTTP 或 HTTPS 链接"); return
    }
    val host = FrameLayout(this).apply { alpha = 0f }
    val web = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
    }
    host.addView(web, FrameLayout.LayoutParams(1, 1))
    addContentView(host, ViewGroup.LayoutParams(1, 1))
    var finished = false
    fun cleanup() {
        (host.parent as? ViewGroup)?.removeView(host)
        web.stopLoading(); web.webChromeClient = null; web.destroy()
    }
    fun finishWith(text: String, base: java.net.URI) {
        if (finished) return
        finished = true
        val body = text.trimStart()
        if (body.startsWith("{") || body.startsWith("[")) {
            runCatching { parseJsonVideoTargets(body, base) }
                .onSuccess { targets -> cleanup(); showVideoTargets(targets, true) }
                .onFailure { error -> cleanup(); toast(error.message ?: "JSON 中没有找到有效的视频地址") }
        } else {
            cleanup(); openWebVideo(url)
        }
    }
    web.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, pageUrl: String) {
            view.evaluateJavascript("JSON.stringify(document.body ? document.body.innerText : '')") { raw ->
                val text = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
                finishWith(text, runCatching { java.net.URI(pageUrl) }.getOrNull()
                    ?.takeIf { it.scheme in listOf("http", "https") && !it.host.isNullOrEmpty() }
                    ?: java.net.URI(parsed.toString()))
            }
        }
    }
    web.loadUrl(url)
}

internal fun findM3u8(value: Any?, depth: Int = 0): List<String> {
    if (depth > 20) return emptyList()
    return when (value) {
        is String -> findM3u8InText(value)
        is JSONObject -> value.keys().asSequence().flatMap { findM3u8(value.opt(it), depth + 1).asSequence() }.toList()
        is JSONArray -> (0 until value.length()).flatMap { findM3u8(value.opt(it), depth + 1) }
        else -> emptyList()
    }
}

private fun findM3u8InText(text: String): List<String> {
    val unescaped = text.replace("\\\\/", "/").replace("\\/", "/")
    val absolute = Regex("https?://[^\\s\"'<>\\\\()\\[\\]]+", RegexOption.IGNORE_CASE)
        .findAll(unescaped)
        .map { it.value.trimEnd(')', ']', '}', ',', ';', '|') }
        .filter { runCatching { java.net.URI(it).path.orEmpty().endsWith(".m3u8", true) }.getOrDefault(false) }
        .toList()
    if (absolute.isNotEmpty()) return absolute
    return listOfNotNull(unescaped.trim().takeIf {
        runCatching { java.net.URI(it).path.orEmpty().endsWith(".m3u8", true) }.getOrDefault(false)
    })
}

internal fun MainActivity.openWebVideo(url: String) {
    if (recordingKind.isNotEmpty()) { toast("请先停止录音，再播放视频"); return }
    service?.pause()
    lateinit var dialog: Dialog
    val container = FrameLayout(this)
    val web = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean =
                request.url.scheme !in listOf("http", "https")

            override fun shouldOverrideUrlLoading(view: WebView, targetUrl: String): Boolean =
                Uri.parse(targetUrl).scheme !in listOf("http", "https")
        }
    }
    var fullView: View? = null
    var fullCallback: WebChromeClient.CustomViewCallback? = null
    val chrome = object : WebChromeClient() {
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (fullView != null) { callback.onCustomViewHidden(); return }
            fullView = view; fullCallback = callback
            container.addView(view, FrameLayout.LayoutParams(-1, -1))
            web.visibility = View.GONE
        }
        override fun onHideCustomView() {
            fullView?.let { container.removeView(it) }
            fullView = null; web.visibility = View.VISIBLE
            fullCallback?.onCustomViewHidden(); fullCallback = null
        }
    }
    web.webChromeClient = chrome
    val layout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val bar = LinearLayout(this@openWebVideo).apply {
            addView(ThemeUi.button(this@openWebVideo, palette, "关闭") { dialog.dismiss() })
            addView(ThemeUi.button(this@openWebVideo, palette, if (isVideoFavorite(url)) "取消收藏" else "收藏视频").apply {
                setOnClickListener {
                    toggleVideoFavorite(url)
                    text = if (isVideoFavorite(url)) "取消收藏" else "收藏视频"
                }
            })
        }
        addView(bar)
        addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
    }
    container.addView(layout, FrameLayout.LayoutParams(-1, -1))
    dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
        setContentView(container)
        setOnDismissListener {
            chrome.onHideCustomView()
            web.stopLoading(); (web.parent as? ViewGroup)?.removeView(web); web.destroy()
        }
        show()
    }
    web.loadUrl(url)
}
