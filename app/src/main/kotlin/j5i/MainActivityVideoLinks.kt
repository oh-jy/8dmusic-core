package j5i.spatial8d

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.DragEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.provider.OpenableColumns
import j5i.spatial8d.ui.ThemeUi
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import j5i.spatial8d.provider.ProviderNetwork

private const val VIDEO_HISTORY = "video_history"
private const val VIDEO_TITLES = "video_titles"

private fun MainActivity.videoTitles(): JSONObject = runCatching {
    JSONObject(getPreferences(Context.MODE_PRIVATE).getString(VIDEO_TITLES, "{}") ?: "{}")
}.getOrDefault(JSONObject())

private fun MainActivity.videoTitle(uri: String, fallback: String): String =
    videoTitles().optString(uri).takeIf { it.isNotBlank() }
        ?: videoHistory().firstOrNull { it.uri == uri }?.title ?: fallback

private fun MainActivity.videoHistory(): List<VideoHistoryEntry> =
    readVideoHistory(getPreferences(Context.MODE_PRIVATE).getString(VIDEO_HISTORY, "[]") ?: "[]")

private fun MainActivity.saveVideoHistory(entries: List<VideoHistoryEntry>) {
    getPreferences(Context.MODE_PRIVATE).edit().putString(VIDEO_HISTORY, writeVideoHistory(entries)).apply()
}

internal fun MainActivity.rememberVideo(uri: Uri, intent: Intent) {
    val providerMv = intent.getStringExtra("j5i.providerMv")?.takeIf { it.isNotBlank() }
    val historyUri = providerMv ?: uri.toString()
    val filename = if (uri.scheme == "content") runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() else null
    val title = (if (providerMv != null) currentItem?.name?.takeIf { it.isNotBlank() } else null)
        ?: intent.getStringExtra(Intent.EXTRA_TITLE)?.takeIf { it.isNotBlank() }
        ?: filename?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
        ?: uri.host ?: t("video.title")
    saveVideoHistory(recordVideoHistory(videoHistory(), historyUri, videoTitle(historyUri, title)))
}

private fun MainActivity.renameVideoHistory(entry: VideoHistoryEntry) {
    lateinit var dialog: Dialog
    dialog = ThemeUi.showSheet(this, palette, t("video.rename")) {
        val input = ThemeUi.input(this@renameVideoHistory, palette, t("video.title.hint")).apply { setText(entry.title) }
        addView(input)
        addView(ThemeUi.button(this@renameVideoHistory, palette, t("save"), ThemeUi.ROLE_ACCENT_FILL) {
            val title = input.text.toString().trim()
            if (title.isEmpty()) toast(t("video.title.required")) else {
                val titles = videoTitles().put(entry.uri, title)
                getPreferences(Context.MODE_PRIVATE).edit().putString(VIDEO_TITLES, titles.toString()).apply()
                saveVideoHistory(videoHistory().map { if (it.uri == entry.uri) it.copy(title = title) else it })
                dialog.dismiss(); showVideoLinks()
            }
        })
    }
    dialog.setOnCancelListener { showVideoLinks() }
    dialog.show()
}

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
        toast(t("video.favorite.credentials")); return
    }
    val current = videoFavorites()
    val updated = if (url in current) current - url else current + url
    getPreferences(Context.MODE_PRIVATE).edit().putString("video_favorites", JSONArray(updated).toString()).apply()
    toast(t(if (url in current) "video.favorite.removed" else "video.favorite.added"))
}

internal fun MainActivity.showVideoLinks() {
    lateinit var sheet: Dialog
    sheet = ThemeUi.showSheet(this, palette, t("video.links"), fullHeight = true) {
        val favoriteRows = LinearLayout(this@showVideoLinks).apply { orientation = LinearLayout.VERTICAL }
        var draggedUrl: String? = null
        fun row(entry: VideoHistoryEntry, favorite: Boolean): LinearLayout = LinearLayout(this@showVideoLinks).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = ThemeUi.bgDrawable(this@showVideoLinks, palette.control, 8f)
            if (favorite) {
                addView(ThemeUi.text(this@showVideoLinks, "☰", 14f, palette, role = ThemeUi.ROLE_FAINT).apply {
                    gravity = Gravity.CENTER
                    contentDescription = "${t("video.drag")} ${videoTitle(entry.uri, entry.title)}"
                    setOnTouchListener { view, event ->
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            draggedUrl = entry.uri
                            view.startDragAndDrop(ClipData.newPlainText("video favorite", ""), View.DragShadowBuilder(this@apply), entry.uri, 0)
                        } else false
                    }
                }, LinearLayout.LayoutParams(ThemeUi.dp(this@showVideoLinks, 36f), ThemeUi.dp(this@showVideoLinks, 40f)))
                setOnDragListener { view, event ->
                    when (event.action) {
                        DragEvent.ACTION_DRAG_STARTED -> draggedUrl != null
                        DragEvent.ACTION_DRAG_ENTERED -> { view.alpha = 0.65f; true }
                        DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> { view.alpha = 1f; true }
                        DragEvent.ACTION_DRAG_LOCATION -> {
                            var parent = favoriteRows.parent
                            while (parent != null && parent !is android.widget.ScrollView) parent = parent.parent
                            (parent as? android.widget.ScrollView)?.let { scroll ->
                                val location = IntArray(2)
                                view.getLocationOnScreen(location)
                                val scrollLocation = IntArray(2)
                                scroll.getLocationOnScreen(scrollLocation)
                                val y = location[1] + event.y - scrollLocation[1]
                                val edge = ThemeUi.dp(this@showVideoLinks, 48f)
                                if (y < edge) scroll.smoothScrollBy(0, -edge)
                                else if (y > scroll.height - edge) scroll.smoothScrollBy(0, edge)
                            }
                            true
                        }
                        DragEvent.ACTION_DROP -> {
                            val source = draggedUrl
                            val updated = videoFavorites().toMutableList()
                            val from = updated.indexOf(source)
                            val to = updated.indexOf(entry.uri)
                            if (from >= 0 && to >= 0 && from != to) {
                                updated.add(to, updated.removeAt(from))
                                getPreferences(Context.MODE_PRIVATE).edit().putString("video_favorites", JSONArray(updated).toString()).apply()
                                sheet.dismiss(); showVideoLinks()
                            }
                            draggedUrl = null
                            true
                        }
                        else -> true
                    }
                }
            }
            val title = ThemeUi.text(this@showVideoLinks, videoTitle(entry.uri, entry.title), 13f, palette).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setPadding(ThemeUi.dp(this@showVideoLinks, 10f), 0, 0, 0)
            }
            addView(title, LinearLayout.LayoutParams(0, ThemeUi.dp(this@showVideoLinks, 40f), 1f))
            title.gravity = Gravity.CENTER_VERTICAL
            val position = preferences.getInt("video-position:${entry.uri}", 0).coerceAtLeast(0)
            addView(ThemeUi.text(this@showVideoLinks, videoTime(position), 12f, palette, role = ThemeUi.ROLE_SUB).apply {
                gravity = Gravity.CENTER
                isSingleLine = true
            }, LinearLayout.LayoutParams(ThemeUi.dp(this@showVideoLinks, 84f), ThemeUi.dp(this@showVideoLinks, 40f)))
            val more = ThemeUi.text(this@showVideoLinks, "⋯", 20f, palette, role = ThemeUi.ROLE_ACCENT).apply {
                gravity = Gravity.CENTER
                contentDescription = t("video.more")
            }
            addView(more, LinearLayout.LayoutParams(ThemeUi.dp(this@showVideoLinks, 48f), ThemeUi.dp(this@showVideoLinks, 40f)))
            setOnClickListener {
                if (favorite) openVideoLink(entry.uri)
                else openExternalMedia(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(entry.uri), "video/*"))
            }
            more.setOnClickListener {
                val options = LinearLayout(this@showVideoLinks).apply {
                    orientation = LinearLayout.VERTICAL
                    background = ThemeUi.bgDrawable(this@showVideoLinks, palette.surface, 8f)
                }
                val popup = PopupWindow(options, ThemeUi.dp(this@showVideoLinks, 120f), -2, true).apply {
                    setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                    isOutsideTouchable = true
                    elevation = ThemeUi.dp(this@showVideoLinks, 8f).toFloat()
                }
                fun option(label: String, action: () -> Unit) {
                    options.addView(ThemeUi.text(this@showVideoLinks, label, 13f, palette).apply {
                        gravity = Gravity.CENTER
                        setOnClickListener { popup.dismiss(); action() }
                    }, LinearLayout.LayoutParams(-1, ThemeUi.dp(this@showVideoLinks, 44f)))
                }
                option(t("video.rename.action")) { sheet.dismiss(); renameVideoHistory(entry.copy(title = videoTitle(entry.uri, entry.title))) }
                option(t("delete")) {
                    if (favorite) toggleVideoFavorite(entry.uri) else {
                        val titles = videoTitles()
                        if (isVideoFavorite(entry.uri) && !titles.has(entry.uri)) {
                            titles.put(entry.uri, entry.title)
                            getPreferences(Context.MODE_PRIVATE).edit().putString(VIDEO_TITLES, titles.toString()).apply()
                        }
                        saveVideoHistory(videoHistory().filterNot { it.uri == entry.uri })
                        if (!isVideoFavorite(entry.uri)) preferences.edit().remove("video-position:${entry.uri}").apply()
                    }
                    sheet.dismiss(); showVideoLinks()
                }
                popup.showAsDropDown(more, 0, 0, Gravity.END)
            }
        }
        val input = ThemeUi.input(this@showVideoLinks, palette, t("video.url.hint")).apply {
            setPadding(paddingLeft, paddingTop, ThemeUi.dp(this@showVideoLinks, 64f), paddingBottom)
        }
        val inputHeight = ThemeUi.dp(this@showVideoLinks, 44f)
        val inputRow = FrameLayout(this@showVideoLinks).apply {
            layoutParams = LinearLayout.LayoutParams(-1, inputHeight)
        }
        inputRow.addView(input, FrameLayout.LayoutParams(-1, inputHeight))
        inputRow.addView(ThemeUi.text(this@showVideoLinks, t("video.open"), 13f, palette, bold = true, role = ThemeUi.ROLE_ACCENT).apply {
            gravity = Gravity.CENTER
            setPadding(ThemeUi.dp(this@showVideoLinks, 10f), 0, ThemeUi.dp(this@showVideoLinks, 10f), 0)
            setOnClickListener {
            val url = input.text.toString().trim()
            val parsed = Uri.parse(url)
            if (parsed.scheme !in listOf("http", "https") || parsed.host.isNullOrEmpty() || parsed.userInfo != null) {
                toast(t("video.url.invalid"))
            } else {
                openVideoLink(url)
            }
            }
        }, FrameLayout.LayoutParams(-2, -1, Gravity.END or Gravity.CENTER_VERTICAL))
        addView(inputRow)
        addView(ThemeUi.text(this@showVideoLinks, t("video.history"), 15f, palette, bold = true))
        val history = videoHistory()
        if (history.isEmpty()) addView(ThemeUi.text(this@showVideoLinks, t("video.history.empty"), 13f, palette, role = ThemeUi.ROLE_SUB))
        history.forEach { entry -> addView(row(entry, false), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ThemeUi.dp(this@showVideoLinks, 4f) }) }
        addView(ThemeUi.text(this@showVideoLinks, t("video.favorites"), 15f, palette, bold = true))
        val favorites = videoFavorites()
        if (favorites.isEmpty()) addView(ThemeUi.text(this@showVideoLinks, t("video.favorites.empty"), 13f, palette, role = ThemeUi.ROLE_SUB))
        else addView(ThemeUi.text(this@showVideoLinks, t("video.drag.hint"), 11f, palette, role = ThemeUi.ROLE_SUB))
        favorites.forEach { url ->
            val uri = Uri.parse(url)
            val fallback = if (uri.host == "player.bilibili.com") uri.getQueryParameter("bvid").orEmpty() +
                uri.getQueryParameter("cid")?.let { " · $it" }.orEmpty() else uri.host.orEmpty() + uri.path.orEmpty()
            favoriteRows.addView(row(VideoHistoryEntry(url, fallback), true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ThemeUi.dp(this@showVideoLinks, 4f) })
        }
        addView(favoriteRows)
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
                    if (json) parseJsonVideoTargets(text, response.request.url.toUri()) to true
                    else {
                        val targets = parseHtmlVideoTargets(text, response.request.url.toUri())
                        if (targets.isEmpty()) listOf(url) to false else targets to true
                    }
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
    val candidates = (if (parsed == null) emptyList() else findVideoUrls(parsed)) + findVideoUrlsInText(normalized)
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
    else pickOption(t("video.choose_url"), targets.mapIndexed { index, target ->
        val uri = Uri.parse(target)
        "${index + 1}. ${uri.host.orEmpty()}${uri.path.orEmpty()}".take(120)
    }, -1) { index -> play(targets[index]) }
}

private fun MainActivity.loadJsonWithWebView(url: String) {
    val parsed = Uri.parse(url)
    if (parsed.scheme !in listOf("http", "https") || parsed.host.isNullOrEmpty() || parsed.userInfo != null) {
        toast(t("video.url.invalid")); return
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
                    .onFailure {
                        val targets = parseHtmlVideoTargets(body, base)
                        if (targets.isEmpty()) {
                            cleanup(); toast(t("video.response.invalid"))
                        } else {
                            cleanup(); showVideoTargets(targets, true)
                        }
                    }
        } else {
                val targets = parseHtmlVideoTargets(body, base)
                if (targets.isEmpty()) {
                    cleanup(); openWebVideo(url)
                } else {
                    cleanup(); showVideoTargets(targets, true)
                }
        }
    }
    web.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, pageUrl: String) {
            view.evaluateJavascript("JSON.stringify(document.documentElement ? document.documentElement.outerHTML : '')") { raw ->
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
    return findVideoUrls(value, depth).filter { isM3u8Address(it) }
}

private fun findVideoUrls(value: Any?, depth: Int = 0): List<String> {
    if (depth > 20) return emptyList()
    return when (value) {
        is String -> findVideoUrlsInText(value)
        is JSONObject -> value.keys().asSequence().flatMap { findVideoUrls(value.opt(it), depth + 1).asSequence() }.toList()
        is JSONArray -> (0 until value.length()).flatMap { findVideoUrls(value.opt(it), depth + 1) }
        else -> emptyList()
    }
}

private fun isM3u8Address(value: String): Boolean =
    runCatching { java.net.URI(value).path.orEmpty().endsWith(".m3u8", true) }.getOrDefault(false)

private fun isVideoAddress(value: String): Boolean =
    runCatching { java.net.URI(value).path.orEmpty().let { path ->
        path.endsWith(".m3u8", true) || path.endsWith(".mp4", true)
    } }.getOrDefault(false)

private fun findVideoUrlsInText(text: String): List<String> {
    val unescaped = text
        .replace(Regex("\\\\u002[fF]"), "/")
        .replace(Regex("\\\\u002[eE]"), ".")
        .replace(Regex("\\\\u0026"), "&")
        .replace(Regex("\\\\u003[fF]"), "?")
        .replace(Regex("\\\\u003[dD]"), "=")
        .replace("\\\\/", "/")
        .replace("\\/", "/")
        .replace("&amp;", "&")
    val absolute = Regex("https?://[^\\s\"'<>\\\\()\\[\\]]+", RegexOption.IGNORE_CASE)
        .findAll(unescaped)
        .map { it.value.trimEnd(')', ']', '}', ',', ';', '|') }
        .filter(::isVideoAddress)
        .toList()
    if (absolute.isNotEmpty()) return absolute
    return listOfNotNull(unescaped.trim().takeIf {
        isVideoAddress(it)
    })
}

internal fun extractHtmlVideoTargets(html: String, base: java.net.URI): List<String> =
    parseHtmlVideoTargets(html, base)

private fun parseHtmlVideoTargets(html: String, base: java.net.URI): List<String> {
    val candidates = findVideoUrlsInText(html).toMutableList()
    val normalized = html
        .replace(Regex("\\\\u002[fF]"), "/")
        .replace(Regex("\\\\u002[eE]"), ".")
        .replace(Regex("\\\\u0026"), "&")
        .replace(Regex("\\\\u003[fF]"), "?")
        .replace(Regex("\\\\u003[dD]"), "=")
        .replace("\\\\/", "/")
        .replace("\\/", "/")
        .replace("&amp;", "&")
    val relative = Regex("(?i)[\\\"']((?:\\./|\\../|/)[^\\\"'<>\\s]+\\.(?:m3u8|mp4)(?:\\?[^\\\"'<>\\s]*)?)(?=[\\\"'<>\\s]|$)")
        .findAll(normalized)
        .map { it.groupValues[1] }
    candidates += relative
    return candidates.mapNotNull { address ->
        runCatching { base.resolve(address.trimEnd(')', ']', '}', ',', ';', '|')) }.getOrNull()?.takeIf {
            it.scheme in listOf("http", "https") && it.host != null && it.userInfo == null && isVideoAddress(it.toString())
        }?.toString()
    }.distinct()
}

internal fun MainActivity.openWebVideo(url: String) {
    if (recordingKind.isNotEmpty()) { toast(t("video.stop_recording")); return }
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
            addView(ThemeUi.button(this@openWebVideo, palette, t("close")) { dialog.dismiss() })
            addView(ThemeUi.button(this@openWebVideo, palette, t(if (isVideoFavorite(url)) "video.unfavorite" else "video.favorite")).apply {
                setOnClickListener {
                    toggleVideoFavorite(url)
                    text = t(if (isVideoFavorite(url)) "video.unfavorite" else "video.favorite")
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
