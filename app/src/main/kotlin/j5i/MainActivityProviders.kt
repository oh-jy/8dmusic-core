package j5i.spatial8d

import android.app.AlertDialog
import android.net.Uri
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.HorizontalScrollView
import j5i.spatial8d.data.MusicItem
import j5i.spatial8d.core.RadioStation
import j5i.spatial8d.core.RadioSource
import j5i.spatial8d.ui.ThemeUi
import j5i.spatial8d.provider.ProviderSearch
import com.journeyapps.barcodescanner.ScanOptions
import j5i.spatial8d.provider.ProviderDefinition
import j5i.spatial8d.provider.ProviderNetwork
import j5i.spatial8d.provider.ProviderArchive
import j5i.spatial8d.provider.ProviderCredentials
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal data class ImportPreviewCard(val name: String, val summary: String, val details: String)

internal fun MainActivity.showImportPreview(
    title: String, subtitle: String, cards: List<ImportPreviewCard>, warning: String, confirm: () -> Unit
) {
    val dp = { value: Float -> ThemeUi.dp(this, value) }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20f), dp(4f), dp(20f), dp(12f))
    }
    content.addView(ThemeUi.text(this, subtitle, 13f, palette, role = ThemeUi.ROLE_SUB).apply {
        setPadding(0, 0, 0, dp(12f))
    })
    content.addView(ThemeUi.text(this, warning, 12f, palette).apply {
        setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        setLineSpacing(dp(2f).toFloat(), 1f)
        ThemeUi.bg(this, palette.control, 10f)
    }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10f) })
    cards.forEach { item ->
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            ThemeUi.bg(this, palette.surfaceAlt, 10f)
        }
        card.addView(ThemeUi.text(this, item.name, 16f, palette, bold = true))
        card.addView(ThemeUi.text(this, item.summary, 12f, palette, role = ThemeUi.ROLE_ACCENT))
        card.addView(ThemeUi.text(this, item.details, 12f, palette, role = ThemeUi.ROLE_SUB).apply {
            setLineSpacing(dp(2f).toFloat(), 1f)
            setTextIsSelectable(true)
        })
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10f) })
    }
    val scroll = ScrollView(this).apply {
        isFillViewport = false
        addView(content)
    }
    val dialog = AlertDialog.Builder(this).setTitle(title).setView(scroll)
        .setNegativeButton("取消", null).setPositiveButton("确认导入") { _, _ -> confirm() }.create()
    dialog.setOnShowListener {
        val maxHeight = (resources.displayMetrics.heightPixels * 0.55f).toInt()
        scroll.measure(android.view.View.MeasureSpec.makeMeasureSpec(dialog.window!!.decorView.width, android.view.View.MeasureSpec.AT_MOST),
            android.view.View.MeasureSpec.makeMeasureSpec(maxHeight, android.view.View.MeasureSpec.AT_MOST))
        if (scroll.measuredHeight >= maxHeight) scroll.layoutParams = scroll.layoutParams.apply { height = maxHeight }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(palette.accent)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(palette.sub)
    }
    dialog.show()
}

internal fun MainActivity.showProviderManager() {
    val entries = try { providerStore.list() } catch (e: Exception) {
        toast("读取来源失败：${e.message}")
        return
    }
    lateinit var dialog: android.app.Dialog
    dialog = ThemeUi.showSheet(this, palette, "管理在线来源", "来源按需启用；导入前会展示内容与风险。") {
        fun action(label: String, run: () -> Unit) {
            addView(ThemeUi.button(this@showProviderManager, palette, label) { dialog.dismiss(); run() })
        }
        action("从文件导入来源或插件") { openProviderFile.launch(arrayOf("*/*")) }
        action("扫描二维码导入") {
            scanProvider.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("扫描来源 JSON 或 HTTPS 配置地址").setBeepEnabled(false))
        }
        action("管理歌曲插件") { showPluginManager() }
        if (entries.isNotEmpty()) addView(ThemeUi.text(this@showProviderManager, "已导入的配置", 12f, palette, bold = true))
        entries.forEach { entry ->
            action("${if (entry.enabled) "已启用" else "已停用"} · ${if (entry.definition.type == ProviderDefinition.Type.FM) "FM" else "歌曲"} · ${entry.definition.name}") {
                    AlertDialog.Builder(this@showProviderManager).setTitle(entry.definition.name)
                        .setItems(arrayOf(if (entry.enabled) "停用" else "启用", "删除")) { _, choice ->
                            if (choice == 0) {
                                runCatching { providerStore.setEnabled(entry.definition.id, !entry.enabled) }
                                    .onSuccess { if (entry.enabled) stopRemovedProvider(entry.definition.id) }
                                    .onFailure { toast("操作失败：${it.message}") }
                                refreshProviderUi()
                                showProviderManager()
                            } else AlertDialog.Builder(this@showProviderManager).setMessage("删除来源配置？不会删除音频文件。")
                                .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                                    runCatching { providerStore.remove(entry.definition.id) }
                                        .onSuccess { stopRemovedProvider(entry.definition.id) }
                                        .onFailure { toast("删除失败：${it.message}") }
                                    refreshProviderUi()
                                    showProviderManager()
                                }.show()
                        }.show()
            }
        }
    }
    dialog.show()
}

private fun MainActivity.stopRemovedProvider(id: String) {
    providerRequest++
    main.removeCallbacks(prefetchLyrics)
    lyrics.cancelPrefetch()
    providerResults = providerResults.filterNot { it.first == id }
    if (isLive && currentStationId.startsWith("$id/")) {
        ++radioGeneration
        radioProbes.forEach { it.stop() }
        service?.radio?.stop()
        playbackStatus = "来源已停用"
        refreshPlayback()
    } else if (currentItem?.album?.endsWith(":$id") == true && currentItem?.album?.startsWith("provider:") == true) {
        ++loadGeneration
        service?.stopLocal()
        playbackStatus = "来源已停用"
        refreshPlayback()
    }
}

private fun readBounded(input: InputStream): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        require(out.size() + n <= j5i.spatial8d.provider.ProviderJar.MAX_BYTES + 52) { "来源文件过大" }
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

internal fun MainActivity.importProviderFile(uri: Uri) {
    Thread {
        val result = runCatching { requireNotNull(contentResolver.openInputStream(uri)).use(::readBounded) }
        runOnUiThread { result.onSuccess(::previewProvider).onFailure { toast("导入失败：${it.message}") } }
    }.start()
}

internal fun MainActivity.importProviderQr(contents: String) {
    val text = contents.trim()
    if (text.startsWith("{")) {
        previewProvider(text.toByteArray(Charsets.UTF_8))
        return
    }
    if (!text.startsWith("https://", ignoreCase = true)) {
        toast("二维码只支持 JSON 或 HTTPS 配置地址")
        return
    }
    Thread {
        val result = runCatching { ProviderNetwork.get(text, false, ProviderDefinition.MAX_BYTES) }
        runOnUiThread { result.onSuccess(::previewProvider).onFailure { toast("读取来源失败：${it.message}") } }
    }.start()
}

private fun MainActivity.previewProvider(bytes: ByteArray) {
    if (ProviderArchive.isEncrypted(bytes)) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "请输入来源文件密码"
        }
        AlertDialog.Builder(this).setTitle("解密来源文件").setView(input)
            .setNegativeButton("取消", null).setPositiveButton("解密") { _, _ ->
                val passphrase = input.text.toString().toCharArray()
                input.text.clear()
                Thread {
                    val result = try { runCatching { ProviderArchive.decrypt(bytes, passphrase) } }
                        finally { passphrase.fill('\u0000') }
                    runOnUiThread {
                        result.onSuccess(::previewProvider).onFailure { toast("解密失败：密码错误或文件已损坏") }
                    }
                }.start()
            }.show()
        return
    }
    if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) {
        previewPlugin(bytes)
        return
    }
    val items = try { ProviderDefinition.parseImport(bytes) } catch (e: Exception) {
        toast("配置无效：${e.message}")
        return
    }
    val definitions = items.map(ProviderDefinition::parse)
    val cards = definitions.map { definition ->
        val fm = definition.type == ProviderDefinition.Type.FM
        val summary = if (fm) "FM 电台 · ${definition.stations.size} 个电台 · ${definition.stations.sumOf { 1 + it.alternateUrls.size }} 条线路"
            else "在线歌曲 · 搜索与播放"
        val capability = if (fm) "播放配置中的直播地址，不支持自动更新或脚本。"
            else "GET 搜索、读取音频地址，不支持登录、脚本及复杂签名。"
        ImportPreviewCard(definition.name, summary,
            "来源 ID\n${definition.id}\n\n能力\n$capability\n\n配置中的域名（${definition.domains.size}）\n${definition.domains.sorted().joinToString("\n")}")
    }
    val warning = (if (definitions.any { it.usesHttp }) "明文 HTTP 风险：内容可能被窃听或篡改。\n\n" else "") +
        "播放结果或直播列表可能包含其他音频域名。请确认来源可信，并已取得必要的使用权限。"
    showImportPreview("确认导入来源", "请检查来源信息后再导入", cards, warning) {
            runCatching { providerStore.putAll(items) }.onSuccess { toast("已导入 ${items.size} 个来源"); refreshProviderUi() }
                .onFailure { toast("保存失败：${it.message}") }
    }
}


internal fun MainActivity.importedStations(): List<RadioStation> = providerStore.list()
    .filter { it.enabled && it.definition.type == ProviderDefinition.Type.FM }
    .flatMap { entry -> entry.definition.stations.map { station ->
        RadioStation("${entry.definition.id}/${station.id}", station.name,
            station.region.ifEmpty { entry.definition.name }, station.city.ifEmpty { station.region.ifEmpty { entry.definition.name } },
            station.category.ifEmpty { "网络台" }, station.level.ifEmpty { "导入" }, station.note,
            (listOf(station.url) + station.alternateUrls).mapIndexed { index, url ->
                RadioSource("线路 ${index + 1}", url,
                    if (url.substringBefore('?').endsWith(".m3u8", true)) "HLS" else "自动")
            })
    } }

internal fun MainActivity.populateSourceSheet(body: LinearLayout, online: Boolean) {
    sourceSheetBody = body
    body.removeAllViews()
    val type = if (online) ProviderDefinition.Type.MUSIC else ProviderDefinition.Type.FM
    val entries = try { providerStore.list().filter { it.enabled && it.definition.type == type } }
    catch (e: Exception) { toast("来源读取失败：${e.message}"); return }
    val plugins = if (online) try { pluginStore.list().filter { it.enabled && "search" in it.descriptor.capabilities } }
        catch (e: Exception) { toast("插件读取失败：${e.message}"); return } else emptyList()
    fun compactButton(label: String, selected: Boolean = false, action: () -> Unit) =
        ThemeUi.button(this, palette, label, ThemeUi.ROLE_TEXT, 30f).apply {
            setTextColor(if (selected) palette.accent else palette.sub)
            setPadding(ThemeUi.dp(this@populateSourceSheet, 5f), 0, ThemeUi.dp(this@populateSourceSheet, 5f), 0)
            layoutParams = LinearLayout.LayoutParams(-2, ThemeUi.dp(this@populateSourceSheet, 30f)).apply {
                rightMargin = ThemeUi.dp(this@populateSourceSheet, 2f)
            }
            setOnClickListener { action() }
        }
    val sourceHeader = ThemeUi.row(this).apply {
        gravity = android.view.Gravity.CENTER_VERTICAL
        addView(ThemeUi.text(this@populateSourceSheet, if (online) "歌曲来源" else "电台来源", 12f, palette, role = ThemeUi.ROLE_SUB),
            LinearLayout.LayoutParams(0, -2, 1f))
        addView(compactButton("来源管理") { showProviderManager() })
    }
    body.addView(sourceHeader)
    if (entries.isEmpty() && plugins.isEmpty()) {
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            addView(ThemeUi.text(this@populateSourceSheet, if (online) "还没有在线歌曲来源" else "还没有电台来源", 16f, palette, bold = true))
            addView(ThemeUi.text(this@populateSourceSheet, if (online) "导入歌曲插件或来源配置后，即可搜索播放" else "导入 FM 配置后，即可浏览和收听电台", 12f, palette, role = ThemeUi.ROLE_SUB))
            addView(ThemeUi.button(this@populateSourceSheet, palette, "导入来源", ThemeUi.ROLE_ACCENT_FILL) { showProviderManager() })
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        return
    }
    if (!online) {
        val query = ThemeUi.input(this, palette, "搜索电台").apply {
            layoutParams = LinearLayout.LayoutParams(-1, ThemeUi.dp(this@populateSourceSheet, 38f)).apply {
                topMargin = ThemeUi.dp(this@populateSourceSheet, 4f)
                bottomMargin = ThemeUi.dp(this@populateSourceSheet, 6f)
            }
        }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val filters = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val stations = importedStations()
        var activeStation: android.view.View? = null
        fun filterRow(label: String, values: List<String>, selected: String, choose: (String) -> Unit) {
            val line = ThemeUi.row(this).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = ThemeUi.dp(this@populateSourceSheet, 3f)
                }
            }
            line.addView(ThemeUi.text(this, label, 11f, palette, role = ThemeUi.ROLE_SUB).apply {
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                    rightMargin = ThemeUi.dp(this@populateSourceSheet, 8f)
                }
            })
            val buttons = ThemeUi.row(this)
            values.forEach { value ->
                buttons.addView(compactButton(value, value == selected) { choose(value) })
            }
            line.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(buttons) },
                LinearLayout.LayoutParams(0, -2, 1f))
            filters.addView(line)
        }
        fun renderFilters() {
            filters.removeAllViews()
            filterRow("地区", listOf("全部") + stations.map { it.region }.distinct(), sourceRegion) {
                sourceRegion = it; sourceCity = "全部"; populateSourceSheet(body, false)
            }
            if (sourceRegion != "全部" && sourceRegion != "全国") {
                val cities = stations.filter { it.region == sourceRegion }.map { it.city }.distinct()
                if (cities.size > 1) filterRow("城市", listOf("全部") + cities, sourceCity) {
                    sourceCity = it; populateSourceSheet(body, false)
                }
            }
            filterRow("类型", listOf("全部") + stations.map { it.type }.distinct(), sourceType) {
                sourceType = it; populateSourceSheet(body, false)
            }
        }
        fun renderStations() {
            rows.removeAllViews()
            val text = query.text.toString().trim()
            stations.filter { (sourceRegion == "全部" || it.region == sourceRegion) &&
                (sourceCity == "全部" || it.city == sourceCity) && (sourceType == "全部" || it.type == sourceType) &&
                (text.isEmpty() || "${it.name} ${it.region} ${it.city} ${it.type} ${it.note}".contains(text, true)) }.forEach { station ->
                rows.addView(ThemeUi.row(this).apply {
                    val active = isLive && currentStationId == station.id
                    if (active) activeStation = this
                    setPadding(ThemeUi.dp(this@populateSourceSheet, 8f), ThemeUi.dp(this@populateSourceSheet, 4f), ThemeUi.dp(this@populateSourceSheet, 8f), ThemeUi.dp(this@populateSourceSheet, 4f))
                    background = ThemeUi.bgDrawable(this@populateSourceSheet, if (active) palette.surfaceAlt else palette.surface, 8f)
                    addView(LinearLayout(this@populateSourceSheet).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(ThemeUi.text(this@populateSourceSheet, "${station.name} · ${station.level}", 13f, palette, bold = true, role = if (active) ThemeUi.ROLE_ACCENT else ThemeUi.ROLE_TEXT))
                        addView(ThemeUi.text(this@populateSourceSheet, "${station.region} · ${station.city} · ${station.type} · ${station.sources.size} 路源", 10f, palette, role = ThemeUi.ROLE_SUB))
                        if (station.note.isNotBlank()) addView(ThemeUi.text(this@populateSourceSheet, station.note, 10f, palette, role = ThemeUi.ROLE_FAINT))
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(ThemeUi.text(this@populateSourceSheet, if (active) "LIVE" else "播放 ▸", 12f, palette, role = ThemeUi.ROLE_ACCENT))
                    setOnClickListener { playRadio(station) }
                })
            }
        }
        query.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) { renderStations() }
        })
        renderFilters()
        body.addView(filters)
        body.addView(query)
        renderStations()
        body.addView(ScrollView(this).apply {
            addView(rows)
            post { activeStation?.let { active -> smoothScrollTo(0, (active.top - height / 3).coerceAtLeast(0)) } }
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        return
    }
    val sources = listOf("@aggregate" to "聚合") + entries.map { it.definition.id to it.definition.name } + plugins.map { it.descriptor.id to it.descriptor.name }
    val selected = sources.firstOrNull { it.first == providerSelection } ?: sources.first()
    providerSelection = selected.first
    if (selected.first == "platform.bilibili") {
        sourceHeader.addView(compactButton("合集缓存") { showBiliCacheManager() }, 1)
    }
    sourceHeader.layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
        bottomMargin = ThemeUi.dp(this@populateSourceSheet, 4f)
    }
    val sourceButtons = ThemeUi.row(this)
    sources.forEach { source ->
        sourceButtons.addView(compactButton(source.second, source.first == selected.first) {
            if (providerSelection != source.first) onlineAutoSource = null
            providerSelection = source.first
            providerRequest++
            providerResults = emptyList()
            pluginResults = emptyList()
            onlinePage = 1
            populateSourceSheet(body, true)
        })
    }
    body.addView(HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(sourceButtons)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = ThemeUi.dp(this@populateSourceSheet, 4f)
        }
    })
    val selectedPlugin = plugins.firstOrNull { it.descriptor.id == selected.first }
    val selectedData = entries.firstOrNull { it.definition.id == selected.first }
    if (selectedPlugin != null && "login" in selectedPlugin.descriptor.capabilities) {
        val loggedIn = j5i.spatial8d.provider.ProviderCredentials(this).get(selected.first).isNotEmpty()
        sourceHeader.addView(ThemeUi.text(this, if (loggedIn) "已登录" else "未登录", 10f, palette,
            role = ThemeUi.ROLE_SUB), sourceHeader.childCount - 1)
        sourceHeader.addView(compactButton("账号") { showPluginLogin(selected.first) }, sourceHeader.childCount - 1)
    }
    val query = ThemeUi.input(this, palette, "搜索歌曲").apply {
        setText(onlineQuery)
        background = null
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
    }
    val searchButton = ThemeUi.button(this, palette, "搜索", ThemeUi.ROLE_ACCENT_FILL, 34f).apply {
        layoutParams = LinearLayout.LayoutParams(-2, ThemeUi.dp(this@populateSourceSheet, 34f)).apply {
            rightMargin = ThemeUi.dp(this@populateSourceSheet, 5f)
        }
    }
    body.addView(ThemeUi.row(this).apply {
        ThemeUi.bg(this, palette.control, 8f)
        layoutParams = LinearLayout.LayoutParams(-1, ThemeUi.dp(this@populateSourceSheet, 44f)).apply {
            topMargin = ThemeUi.dp(this@populateSourceSheet, 2f)
            bottomMargin = ThemeUi.dp(this@populateSourceSheet, 4f)
        }
        addView(query, LinearLayout.LayoutParams(0, -1, 1f))
        addView(searchButton)
    })
    val runSearch = {
        onlineQuery = query.text.toString().trim()
        onlinePage = 1
        if (selected.first == "@aggregate") searchAggregateProviders(body, entries.map { it.definition }, plugins, false)
        else if (selectedPlugin != null) searchPluginProvider(body, selectedPlugin, false)
        else searchImportedProvider(body, selectedData!!.definition, false)
    }
    searchButton.setOnClickListener { runSearch() }
    query.setOnEditorActionListener { _, action, _ ->
        if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { runSearch(); true } else false
    }
    if (onlineQuery.isBlank() && providerResults.isEmpty() && pluginResults.isEmpty() && onlineAutoSource != selected.first) {
        onlineAutoSource = selected.first
        body.post { if (!isDestroyed && providerSelection == selected.first) runSearch() }
    }
    val visibleTracks = pluginResults.filter { selected.first == "@aggregate" || it.first == selected.first }
        .map { (id, track) -> j5i.spatial8d.provider.ProviderTrackRef.plugin(id, track) } +
        providerResults.filter { selected.first == "@aggregate" || it.first == selected.first }
            .map { (id, track) -> j5i.spatial8d.provider.ProviderTrackRef.declarative(id, track) }
    if (visibleTracks.isNotEmpty()) body.addView(ThemeUi.row(this).apply {
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = ThemeUi.dp(this@populateSourceSheet, 4f)
        }
        addView(compactButton("全部加入队列") {
            stageOnlineQueue((onlineQueue + visibleTracks).distinctBy { it.id }, providerSelection ?: "")
            toast("已加入 ${visibleTracks.size} 首在线歌曲")
        })
        addView(compactButton("保存到歌单") { showOnlinePlaylistPicker(visibleTracks) })
    })
    val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    fun addTrack(title: String, artist: String, sourceId: String, item: MusicItem, available: Boolean = true,
        badge: String = "", play: () -> Unit) {
        rows.addView(ThemeUi.row(this).apply {
            tag = "track:${item.id}"
            val active = !isLive && currentItem?.id == item.id
            setPadding(ThemeUi.dp(this@populateSourceSheet, 8f), ThemeUi.dp(this@populateSourceSheet, 4f),
                ThemeUi.dp(this@populateSourceSheet, 8f), ThemeUi.dp(this@populateSourceSheet, 4f))
            if (active) background = ThemeUi.bgDrawable(this@populateSourceSheet, palette.control, 8f)
            addView(LinearLayout(this@populateSourceSheet).apply {
                orientation = LinearLayout.VERTICAL
                val label = if (badge.isEmpty()) title else "$badge $title"
                addView(ThemeUi.text(this@populateSourceSheet, (if (active) "▶ " else "") + label,
                    13f, palette, bold = true, role = if (active) ThemeUi.ROLE_ACCENT else ThemeUi.ROLE_TEXT))
                addView(ThemeUi.text(this@populateSourceSheet, "$artist · ${sources.firstOrNull { it.first == sourceId }?.second ?: sourceId}", 11f, palette, role = ThemeUi.ROLE_SUB))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ThemeUi.text(this@populateSourceSheet, "⋯", 18f, palette, role = ThemeUi.ROLE_ACCENT).apply {
                contentDescription = "$title，更多操作"
                setPadding(ThemeUi.dp(this@populateSourceSheet, 10f), 0, ThemeUi.dp(this@populateSourceSheet, 10f), 0)
                setOnClickListener { showOnlineTrackActions(item) }
            })
            addView(ThemeUi.text(this@populateSourceSheet, if (available) "播放 ▸" else "暂无音源", 12f, palette, role = ThemeUi.ROLE_ACCENT))
            setOnClickListener { play() }
        })
    }
    pluginResults.filter { selected.first == "@aggregate" || it.first == selected.first }.forEach { (id, track) ->
        val item = j5i.spatial8d.provider.ProviderTrackRef.plugin(id, track)
        val parts = track.optInt("partCount", 1)
        val badges = buildList {
            if (track.optBoolean("vip")) add("【V】")
            if (track.optBoolean("compilation")) add("合集 $parts 首") else if (id == "platform.bilibili") add("视频")
        }
        val badge = badges.joinToString(" ")
            addTrack(item.name, item.artist, id, item, badge = badge) {
                val visibleResults = pluginResults.filter { selected.first == "@aggregate" || it.first == selected.first }
                val visibleItems = visibleResults.map { (providerId, result) ->
                    j5i.spatial8d.provider.ProviderTrackRef.plugin(providerId, result)
                }
                stageOnlineQueue(visibleItems, id, item.id)
            // Search metadata can under-report Bilibili's P count. Always ask
            // the provider for the authoritative pagelist before playing.
            if (id == "platform.bilibili" || track.optBoolean("compilation")) expandPluginTrack(id, track)
            else playPluginTrack(id, track)
        }
    }
    providerResults.filter { selected.first == "@aggregate" || it.first == selected.first }.forEach { (id, track) ->
        val item = j5i.spatial8d.provider.ProviderTrackRef.declarative(id, track)
            addTrack(track.title, track.artist, id, item, track.audioUrl != null) {
                val visibleResults = providerResults.filter { selected.first == "@aggregate" || it.first == selected.first }
                val visibleItems = visibleResults.map { (providerId, result) ->
                    j5i.spatial8d.provider.ProviderTrackRef.declarative(providerId, result)
                }
                stageOnlineQueue(visibleItems, id, item.id)
            val entry = providerStore.list().firstOrNull { it.enabled && it.definition.id == id }
            val url = track.audioUrl
            if (entry == null || url == null) { toast("来源已停用或未提供播放地址"); return@addTrack }
            if (url.startsWith("http://", true) && !entry.definition.usesHttp) {
                toast("该来源未授权明文 HTTP 播放")
                return@addTrack
            }
            onlinePlayingUri = item.uri
            load(item)
        }
    }
    if (providerResults.any { selected.first == "@aggregate" || it.first == selected.first } || pluginResults.any { selected.first == "@aggregate" || it.first == selected.first }) rows.addView(ThemeUi.button(this, palette, "加载更多") {
        onlinePage++
        if (selected.first == "@aggregate") searchAggregateProviders(body, entries.map { it.definition }, plugins, true)
        else if (selectedPlugin != null) searchPluginProvider(body, selectedPlugin, true)
        else searchImportedProvider(body, selectedData!!.definition, true)
    })
    body.addView(ScrollView(this).apply {
        addView(rows)
    }, LinearLayout.LayoutParams(-1, 0, 1f))
}

internal fun MainActivity.showOnlineTrackActions(item: MusicItem) {
    lateinit var sheet: android.app.Dialog
    sheet = ThemeUi.showSheet(this, palette, item.name.take(28), "${item.artist} · 在线歌曲不下载到本机") {
        addView(ThemeUi.button(this@showOnlineTrackActions, palette, "加入当前播放队列") {
            stageOnlineQueue((onlineQueue + item).distinctBy { it.id },
                item.album.substringAfter(':').substringAfter(':'))
            sheet.dismiss(); toast("已加入当前播放队列")
        })
        addView(ThemeUi.button(this@showOnlineTrackActions, palette, "加入 / 新建歌单") {
            sheet.dismiss(); showOnlinePlaylistPicker(listOf(item))
        })
        if (item.uri.startsWith("online://plugin/") && item.uri.contains("source=platform.bilibili")) {
            addView(ThemeUi.button(this@showOnlineTrackActions, palette, "在哔哩哔哩中打开") {
                val id = android.net.Uri.parse(item.uri).getQueryParameter("id").orEmpty()
                val bvid = id.substringBefore(':')
                if (!bvid.matches(Regex("BV[0-9A-Za-z]+"))) {
                    toast("哔哩哔哩视频标识无效")
                    return@button
                }
                runCatching {
                    if (!openBilibiliVideo(bvid)) toast("无法打开哔哩哔哩")
                }.onFailure { toast("无法打开哔哩哔哩：${it.message}") }
                sheet.dismiss()
            })
            val cached = j5i.spatial8d.provider.ProviderAudioCache.cached(this@showOnlineTrackActions, item)
            addView(ThemeUi.button(this@showOnlineTrackActions, palette, if (cached == null) "保存到 Download/bili" else "删除音频缓存").apply {
            setOnClickListener {
                sheet.dismiss()
                if (cached != null) {
                    val ok = j5i.spatial8d.provider.ProviderAudioCache.delete(this@showOnlineTrackActions, item)
                    toast(if (ok) "缓存已删除" else "没有找到可删除的缓存")
                } else {
                    Thread {
                    val result = runCatching { j5i.spatial8d.provider.ProviderAudioCache.cache(this@showOnlineTrackActions, item, true) }
                    main.post { result.onSuccess { saved ->
                        musicStore.remember(saved); songs = (songs.filterNot { it.id == saved.id } + saved).toMutableList()
                        musicStore.saveLibrary(songs); queue = musicStore.view(songs); toast("已保存到 Download/bili")
                    }.onFailure { toast("缓存失败：${it.message}") } }
                    }.start()
                }
            }
            })
            }
        }
    sheet.show()
}

private fun MainActivity.openBilibiliVideo(bvid: String): Boolean {
    val intents = listOf(
        android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("bilibili://video/$bvid"))
            .setPackage("tv.danmaku.bili"),
        android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("bilibili://video/$bvid")),
        android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("https://www.bilibili.com/video/$bvid"))
    )
    val target = intents.firstOrNull { it.resolveActivity(packageManager) != null } ?: return false
    startActivity(target)
    return true
}

internal fun MainActivity.showOnlinePlaylistPicker(items: List<MusicItem>) {
    lateinit var sheet: android.app.Dialog
    sheet = ThemeUi.showSheet(this, palette, "保存 ${items.size} 首在线歌曲", "仅保存歌曲标识，播放时重新解析地址") {
        musicStore.playlists.forEachIndexed { index, playlist ->
            addView(ThemeUi.button(this@showOnlinePlaylistPicker, palette, playlist.name) {
                val count = addOnlineToPlaylist(index, items); sheet.dismiss()
                toast(if (count > 0) "已加入 $count 首到 ${playlist.name}" else "歌曲已在这个歌单中")
            })
        }
        addView(ThemeUi.button(this@showOnlinePlaylistPicker, palette, "收藏并自动下载") {
            sheet.dismiss(); downloadOnlineFavorite(items.first())
        })
        addView(ThemeUi.button(this@showOnlinePlaylistPicker, palette, "新建歌单") {
            sheet.dismiss()
            val input = ThemeUi.input(this@showOnlinePlaylistPicker, palette, "新歌单名称")
            AlertDialog.Builder(this@showOnlinePlaylistPicker).setTitle("新建歌单").setView(input)
                .setNegativeButton("取消", null).setPositiveButton("创建") { _, _ ->
                    val added = addOnlineToPlaylist(-1, items, input.text.toString())
                    toast(if (added > 0) "歌单已创建并保存 $added 首" else "名称为空或歌单已达到 10 个上限")
                }.show()
        })
    }
    sheet.show()
}

internal fun MainActivity.addOnlineToPlaylist(index: Int, items: List<MusicItem>, name: String = ""): Int {
    // 在线引用只进独立在线库和歌单，不污染本地歌曲列表。
    musicStore.rememberOnline(items)
    val count = if (index < 0) {
        if (musicStore.addPlaylist(name, items)) items.size else 0
    } else musicStore.addToPlaylist(index, items)
    playlistDialog?.refreshContent()
    return count
}

private fun MainActivity.pluginSearch(entry: j5i.spatial8d.provider.ProviderPluginStore.Entry, query: String, page: Int): List<org.json.JSONObject> {
    val request = org.json.JSONObject().put("version", 1).put("operation", "search").put("query", query).put("page", page)
        .put("cookie", ProviderCredentials(this).get(entry.descriptor.id))
    val response = org.json.JSONObject(j5i.spatial8d.provider.ProviderPluginRunner.invoke(this, entry, request.toString()))
    val rows = response.getJSONArray("tracks")
    require(rows.length() <= 100)
    return (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.optString("id").isNotEmpty() && it.optString("title").isNotEmpty() }
}

private fun MainActivity.refreshOnlineResults(body: LinearLayout, scrollY: Int) {
    val oldScroll = (0 until body.childCount).mapNotNull { body.getChildAt(it) as? ScrollView }.lastOrNull()
    val oldRows = oldScroll?.getChildAt(0) as? LinearLayout
    var anchor: Pair<String, Int>? = null
    if (oldRows != null && oldScroll != null) {
        for (index in 0 until oldRows.childCount) {
            val row = oldRows.getChildAt(index)
            if (row.tag?.toString()?.startsWith("track:") == true && row.top - oldScroll.scrollY >= 0) {
                anchor = row.tag.toString() to (row.top - oldScroll.scrollY)
                break
            }
        }
    }
    populateSourceSheet(body, true)
    val newScroll = (0 until body.childCount).mapNotNull { body.getChildAt(it) as? ScrollView }.lastOrNull() ?: return
    newScroll.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            newScroll.viewTreeObserver.removeOnPreDrawListener(this)
            val rows = newScroll.getChildAt(0) as? LinearLayout
            var row: android.view.View? = null
            if (rows != null && anchor != null) {
                for (index in 0 until rows.childCount) {
                    val candidate = rows.getChildAt(index)
                    if (candidate.tag == anchor.first) { row = candidate; break }
                }
            }
            val currentAnchor = anchor
            newScroll.scrollTo(0, if (row == null || currentAnchor == null) scrollY else row.top - currentAnchor.second)
            return true
        }
    })
}

private fun MainActivity.searchAggregateProviders(body: LinearLayout, providers: List<ProviderDefinition>,
    plugins: List<j5i.spatial8d.provider.ProviderPluginStore.Entry>, append: Boolean) {
    val requestId = ++providerRequest
    val query = onlineQuery
    val page = onlinePage
    val scrollY = (0 until body.childCount).mapNotNull { body.getChildAt(it) as? ScrollView }
        .lastOrNull()?.scrollY ?: 0
    if (!append) { providerResults = emptyList(); pluginResults = emptyList() }
    toast("正在搜索 ${providers.size + plugins.size} 个来源")
    Thread {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(6, providers.size + plugins.size))
        try {
            val tasks = mutableListOf<java.util.concurrent.Callable<Triple<String, Result<List<ProviderSearch.Track>>?, Result<List<org.json.JSONObject>>?>>>()
            tasks += providers.map { provider -> java.util.concurrent.Callable {
                Triple(provider.id, runCatching { ProviderSearch.search(provider, query, page) }, null as Result<List<org.json.JSONObject>>?)
            } }
            tasks += plugins.map { entry -> java.util.concurrent.Callable {
                Triple(entry.descriptor.id, null as Result<List<ProviderSearch.Track>>?, runCatching { pluginSearch(entry, query, page) })
            } }
            val completion = java.util.concurrent.ExecutorCompletionService<Triple<String, Result<List<ProviderSearch.Track>>?, Result<List<org.json.JSONObject>>?>>(pool)
            tasks.forEach(completion::submit)
            val enabledData = providers.map { it.id }.toSet()
            val enabledPlugins = plugins.map { it.descriptor.id }.toSet()
            var received = 0
            var added = 0
            val failed = mutableListOf<String>()
            val deadline = android.os.SystemClock.elapsedRealtime() + 40_000L
            while (received < tasks.size) {
                val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                if (remaining <= 0) break
                val future = completion.poll(remaining, java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
                received++
                val result = runCatching { future.get() }.getOrNull()
                if (result == null) { failed += "来源超时"; continue }
                val label = providers.firstOrNull { it.id == result.first }?.name
                    ?: plugins.firstOrNull { it.descriptor.id == result.first }?.descriptor?.name ?: result.first
                val data = result.second?.getOrElse { failed += label; emptyList() }
                    ?.takeIf { result.first in enabledData }?.map { result.first to it }.orEmpty()
                val tracks = result.third?.getOrElse { failed += label; emptyList() }
                    ?.takeIf { result.first in enabledPlugins }?.map { result.first to it }.orEmpty()
                if (data.isEmpty() && tracks.isEmpty()) continue
                added += data.size + tracks.size
                runOnUiThread {
                    if (requestId != providerRequest || providerSelection != "@aggregate" || isDestroyed) return@runOnUiThread
                    providerResults += data
                    pluginResults += tracks
                    refreshOnlineResults(body, scrollY)
                }
            }
            runOnUiThread {
                if (requestId != providerRequest || providerSelection != "@aggregate" || isDestroyed) return@runOnUiThread
                if (added == 0 && append) onlinePage = (onlinePage - 1).coerceAtLeast(1)
                if (failed.isNotEmpty()) toast("搜索完成；不可用：${failed.distinct().joinToString("、")}")
                else if (added == 0) toast(if (append) "没有更多结果" else "没有找到歌曲")
            }
        } finally { pool.shutdownNow() }
    }.start()
}

internal fun MainActivity.refreshProviderUi() {
    val body = sourceSheetBody ?: return
    when {
        onlineDialog?.isShowing == true -> populateSourceSheet(body, true)
        fmDialog?.isShowing == true -> populateSourceSheet(body, false)
    }
}

private fun MainActivity.searchPluginProvider(body: LinearLayout, entry: j5i.spatial8d.provider.ProviderPluginStore.Entry, append: Boolean) {
    val requestId = ++providerRequest
    val query = onlineQuery
    val page = onlinePage
    val scrollY = (0 until body.childCount).mapNotNull { body.getChildAt(it) as? ScrollView }
        .lastOrNull()?.scrollY ?: 0
    Thread {
        val result = runCatching {
            pluginSearch(entry, query, page)
        }
        runOnUiThread {
            if (requestId != providerRequest || providerSelection != entry.descriptor.id || isDestroyed) return@runOnUiThread
            if (pluginStore.list().none { it.enabled && it.descriptor.id == entry.descriptor.id }) return@runOnUiThread
            result.onSuccess { tracks ->
                pluginResults = (if (append) pluginResults else emptyList()) + tracks.map { entry.descriptor.id to it }
                if (tracks.isEmpty()) toast(if (append) "没有更多结果" else "没有找到歌曲")
                refreshOnlineResults(body, scrollY)
            }.onFailure {
                if (append) onlinePage = (onlinePage - 1).coerceAtLeast(1)
                toast("搜索失败：${it.message}")
            }
        }
    }.start()
}

private fun MainActivity.searchImportedProvider(body: LinearLayout, provider: ProviderDefinition, append: Boolean) {
    val request = ++providerRequest
    val query = onlineQuery
    val page = onlinePage
    val scrollY = (0 until body.childCount).mapNotNull { body.getChildAt(it) as? ScrollView }
        .lastOrNull()?.scrollY ?: 0
    playbackStatus = "正在搜索 ${provider.name}…"
    Thread {
        val result = runCatching { ProviderSearch.search(provider, query, page) }
        runOnUiThread {
            if (request != providerRequest || providerSelection != provider.id || isDestroyed) return@runOnUiThread
            if (providerStore.list().none { it.enabled && it.definition.id == provider.id }) return@runOnUiThread
            result.onSuccess { tracks ->
                providerResults = (if (append) providerResults else emptyList()) + tracks.map { provider.id to it }
                playbackStatus = if (tracks.isEmpty()) "没有更多结果" else "搜索完成"
                refreshOnlineResults(body, scrollY)
            }.onFailure { error ->
                if (append) onlinePage = (onlinePage - 1).coerceAtLeast(1)
                playbackStatus = "搜索失败"
                toast("搜索失败：${error.message}")
            }
        }
    }.start()
}
