package j5i.spatial8d

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.widget.LinearLayout
import android.widget.ScrollView
import j5i.spatial8d.core.RadioSource
import j5i.spatial8d.core.RadioStation
import j5i.spatial8d.provider.ProviderPluginRunner
import j5i.spatial8d.provider.ProviderPluginStore
import j5i.spatial8d.ui.ThemeUi
import org.json.JSONObject

private fun liveUrl(value: String): Boolean = runCatching {
    val uri = java.net.URI(value)
    uri.scheme in setOf("http", "https") && uri.host != null && uri.rawUserInfo == null && uri.fragment == null
}.getOrDefault(false)

internal fun MainActivity.showTvLive() {
    val entry = runCatching { pluginStore.list().firstOrNull {
        it.enabled && it.descriptor.id == "platform.iyouhun" && "live-tv" in it.descriptor.capabilities
    } }.getOrNull()
    if (entry != null) {
        showLiveCatalog(entry, "tv")
    } else {
        lateinit var dialog: android.app.Dialog
        dialog = ThemeUi.showSheet(this, palette, t("live.tv"), subtitle = t("playback.plugin.missing"), compactPicker = true) {
            addView(ThemeUi.button(this@showTvLive, palette, t("source.manage")) { dialog.dismiss(); showProviderManager() })
            addView(ThemeUi.button(this@showTvLive, palette, t("video.links")) { dialog.dismiss(); showVideoLinks() })
            addView(ThemeUi.button(this@showTvLive, palette, t("video.library")) { dialog.dismiss(); showVideoLibrary() })
        }
        dialog.show()
    }
}

internal fun MainActivity.showLiveCatalog(entry: ProviderPluginStore.Entry, kind: String) {
    val cache = getSharedPreferences("live_catalog_cache", Context.MODE_PRIVATE)
    fun choose(title: String, items: List<String>, action: (Int) -> Unit) {
        lateinit var sheet: android.app.Dialog
        sheet = ThemeUi.showSheet(this, palette, title) {
            items.forEachIndexed { index, item ->
                addView(ThemeUi.button(this@showLiveCatalog, palette, item, ThemeUi.ROLE_TEXT) {
                    sheet.dismiss()
                    action(index)
                })
            }
        }
        sheet.show()
    }
    fun webOnly(address: String) {
        if (liveUrl(address)) openWebVideo(address, live = true)
        else toast(t("live.failed"))
    }
    lateinit var dialog: android.app.Dialog
    var refreshCatalog: () -> Unit = {}
    dialog = ThemeUi.showSheet(this, palette, "${entry.descriptor.name} · ${kind.uppercase()}", fullHeight = true, scrollable = false) {
        val categoryButton = ThemeUi.button(this@showLiveCatalog, palette, t("live.category"))
        val query = ThemeUi.input(this@showLiveCatalog, palette, t("live.search"))
        val status = ThemeUi.text(this@showLiveCatalog, "", 12f, palette, role = ThemeUi.ROLE_SUB)
        val rows = LinearLayout(this@showLiveCatalog).apply { orientation = LinearLayout.VERTICAL }
        val more = ThemeUi.button(this@showLiveCatalog, palette, t("source.more"))
        var categories = emptyList<Pair<String, String>>()
        var channels = emptyList<Pair<String, String>>()
        var next = ""
        var requestGeneration = 0
        var loading = false
        var selectedCategory = ""

        fun invoke(operation: String, url: String, done: (JSONObject?, Boolean) -> Unit) {
            val generation = ++requestGeneration
            loading = true
            status.text = t("live.loading")
            more.isEnabled = false
            val key = "${entry.descriptor.id}:$kind:$operation:$url"
            Thread {
                val response = runCatching {
                    val active = pluginStore.list().first { it.enabled && it.descriptor.id == entry.descriptor.id }
                    val request = JSONObject().put("version", 1).put("operation", operation).put("kind", kind).put("url", url)
                    JSONObject(ProviderPluginRunner.invoke(this@showLiveCatalog, active, request.toString()))
                }
                val value = response.getOrNull()
                if (value != null && operation in setOf("liveCategories", "liveList")) cache.edit().putString(key, value.toString()).apply()
                val cached = if (value == null && operation in setOf("liveCategories", "liveList"))
                    runCatching { JSONObject(cache.getString(key, null) ?: "") }.getOrNull() else null
                runOnUiThread {
                    if (!dialog.isShowing || generation != requestGeneration) return@runOnUiThread
                    loading = false
                    more.isEnabled = true
                    status.text = if (value == null) t(if (cached == null) "live.failed" else "live.cached") else ""
                    done(value ?: cached, value == null)
                }
            }.start()
        }

        fun render() {
            rows.removeAllViews()
            val text = query.text.toString().trim()
            channels.filter { text.isEmpty() || it.second.contains(text, true) }.forEach { (url, title) ->
                rows.addView(ThemeUi.button(this@showLiveCatalog, palette, title, ThemeUi.ROLE_TEXT) {
                    invoke("liveResolve", url) { response, _ ->
                        val lines = response?.optJSONArray("lines") ?: return@invoke
                        if (lines.length() == 0) {
                            webOnly(url)
                        } else if (kind == "tv" && "live-tv" in entry.descriptor.capabilities) {
                            val streams = (0 until lines.length()).map { lines.getJSONObject(it).optString("url") }.filter(::liveUrl).distinct()
                            if (streams.isEmpty()) { toast(t("live.failed")); return@invoke }
                            val visibleChannels = (0 until rows.childCount).mapNotNull { index ->
                                (rows.getChildAt(index) as? android.widget.TextView)?.let { button ->
                                    button.text.toString() to { button.performClick(); Unit }
                                }
                            }
                            openExternalMedia(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(streams.first()), "video/*")
                                .putExtra(Intent.EXTRA_TITLE, title).putExtra("j5i.live", true)
                                .putStringArrayListExtra("j5i.liveStreams", ArrayList(streams)), visibleChannels,
                                visibleChannels.indexOfFirst { it.first == title })
                        } else {
                            val lineItems = (0 until lines.length()).map { lines.getJSONObject(it) }
                            choose(title, lineItems.map { it.optString("title") }) { index ->
                                    val lineUrl = lineItems[index].optString("url")
                                    invoke("liveLine", lineUrl) { result, _ ->
                                        if (result == null) {
                                            toast(t("live.failed"))
                                            return@invoke
                                        }
                                        val stream = result.optString("stream")
                                        val web = result.optString("web").takeIf(::liveUrl)
                                        val fallbackWeb = result.optString("fallbackWeb").takeIf(::liveUrl)
                                        val canPlay = liveUrl(stream)
                                        fun play() {
                                                if (kind == "fm") {
                                                    val station = RadioStation("${entry.descriptor.id}/${url.hashCode()}", title, entry.descriptor.name,
                                                        entry.descriptor.name, "直播", "导入", "", listOf(RadioSource(lineItems[index].optString("title"), stream,
                                                            if (Uri.parse(stream).path.orEmpty().endsWith(".m3u8", true)) "HLS" else "自动")))
                                                    dialog.dismiss(); playRadio(station)
                                                } else {
                                                    dialog.dismiss()
                                                    openExternalMedia(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(stream), "video/*").putExtra(Intent.EXTRA_TITLE, title).putExtra("j5i.live", true).putExtra("j5i.liveWeb", web).putExtra("j5i.liveFallbackWeb", fallbackWeb))
                                                }
                                        }
                                        val webOptions = listOfNotNull(web?.let { t("live.web") to it }, fallbackWeb?.let { t("live.official") to it })
                                        if (canPlay && webOptions.isEmpty()) play()
                                        else if (canPlay) choose(title, listOf(t("play")) + webOptions.map { it.first }) { action ->
                                            if (action == 0) play() else webOnly(webOptions[action - 1].second)
                                        }
                                        else if (webOptions.isNotEmpty()) webOnly(webOptions.first().second)
                                        else toast(t("live.failed"))
                                    }
                                }
                        }
                    }
                })
            }
            more.visibility = if (next.isNotBlank()) android.view.View.VISIBLE else android.view.View.GONE
        }

        fun loadPage(url: String, append: Boolean) {
            invoke("liveList", url) { response, _ ->
                if (response != null) {
                    val array = response.optJSONArray("channels")
                    val result = if (array == null) emptyList() else (0 until minOf(array.length(), 500)).mapNotNull { index ->
                        val item = array.optJSONObject(index) ?: return@mapNotNull null
                        val address = item.optString("url")
                        val title = item.optString("title").take(100)
                        if (liveUrl(address) && title.isNotBlank()) address to title else null
                    }
                    channels = ((if (append) channels else emptyList()) + result).distinctBy { it.first }
                    next = response.optString("next").takeIf(::liveUrl).orEmpty()
                    render()
                }
            }
        }
        categoryButton.setOnClickListener {
            if (!loading && categories.isNotEmpty()) choose(t("live.category"), categories.map { it.second }) { index ->
                    selectedCategory = categories[index].first
                    categoryButton.text = categories[index].second
                    channels = emptyList(); next = ""; query.text.clear(); render()
                    loadPage(selectedCategory, false)
                }
        }
        more.setOnClickListener { if (!loading && next.isNotEmpty()) loadPage(next, true) }
        refreshCatalog = {
            if (!loading) invoke("liveRefresh", "") { result, _ ->
                if (result != null) loadPage(selectedCategory, false)
            }
        }
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { render() }
        })
        addView(ThemeUi.row(this@showLiveCatalog).apply {
            categoryButton.apply {
                setPadding(ThemeUi.dp(this@showLiveCatalog, 3f), 0, ThemeUi.dp(this@showLiveCatalog, 3f), 0)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            addView(categoryButton, LinearLayout.LayoutParams(0, -2, 1.5f))
            if (kind == "tv" && entry.descriptor.id == "platform.iyouhun") {
                fun action(label: String, description: String, onClick: () -> Unit) =
                    ThemeUi.button(this@showLiveCatalog, palette, label) { onClick() }.apply {
                        contentDescription = description
                        setPadding(ThemeUi.dp(this@showLiveCatalog, 2f), 0, ThemeUi.dp(this@showLiveCatalog, 2f), 0)
                        isSingleLine = true
                        if (android.os.Build.VERSION.SDK_INT >= 26) setAutoSizeTextTypeUniformWithConfiguration(8, 12, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                    }
                addView(action(t("video.links.short"), t("video.links")) { showVideoLinks() }, LinearLayout.LayoutParams(ThemeUi.dp(this@showLiveCatalog, 64f), -2))
                addView(action(t("video.library.short"), t("video.library")) { showVideoLibrary() }, LinearLayout.LayoutParams(ThemeUi.dp(this@showLiveCatalog, 64f), -2))
            }
        })
        addView(query); addView(status)
        addView(ScrollView(this@showLiveCatalog).apply { addView(rows) }, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(more)
        post {
            invoke("liveCategories", "") { response, _ ->
                val array = response?.optJSONArray("categories")
                categories = if (array == null) emptyList() else (0 until minOf(array.length(), 200)).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val address = item.optString("url"); val title = item.optString("title").take(80)
                    if (liveUrl(address) && title.isNotBlank()) address to title else null
                }
                categories.firstOrNull()?.let { (address, title) ->
                    selectedCategory = address; categoryButton.text = title; loadPage(address, false)
                }
            }
        }
    }
    if (kind == "tv" && entry.descriptor.id == "platform.iyouhun") {
        val header = dialog.findViewById<android.view.ViewGroup>(android.R.id.content)?.findViewWithTag<android.widget.FrameLayout>("sheetHeader")
        (header?.getChildAt(0) as? android.widget.TextView)?.setPadding(0, 0, ThemeUi.dp(this, 150f), 0)
        header?.addView(ThemeUi.text(this, t("live.refresh.short"), 12f, palette, role = ThemeUi.ROLE_ACCENT).apply {
            gravity = android.view.Gravity.CENTER
            contentDescription = t("live.refresh")
            setPadding(ThemeUi.dp(this@showLiveCatalog, 8f), ThemeUi.dp(this@showLiveCatalog, 8f), ThemeUi.dp(this@showLiveCatalog, 8f), ThemeUi.dp(this@showLiveCatalog, 8f))
            minHeight = ThemeUi.dp(this@showLiveCatalog, 48f)
            setOnClickListener {
                lateinit var confirm: android.app.Dialog
                confirm = ThemeUi.showSheet(this@showLiveCatalog, palette, t("live.refresh"), subtitle = t("live.refresh.confirm"), compactPicker = true) {
                    addView(ThemeUi.button(this@showLiveCatalog, palette, t("live.refresh"), ThemeUi.ROLE_ACCENT_FILL) {
                        confirm.dismiss()
                        if (dialog.isShowing) refreshCatalog()
                    })
                }
                confirm.show()
            }
        }, android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL).apply {
            rightMargin = ThemeUi.dp(this@showLiveCatalog, 70f)
        })
    }
    dialog.show()
}
