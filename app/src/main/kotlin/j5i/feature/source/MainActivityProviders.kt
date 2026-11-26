package j5i.spatial8d.feature.source
import j5i.spatial8d.feature.ui.*
import j5i.spatial8d.feature.playback.*
import j5i.spatial8d.feature.video.*
import j5i.spatial8d.feature.update.*
import j5i.spatial8d.feature.cache.*
import j5i.spatial8d.MainActivity
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
import org.json.JSONArray

internal fun orderedRadioStationIds(stationIds: List<String>, pinnedIds: List<String>, unfiltered: Boolean): List<String> {
    if (!unfiltered) return stationIds
    val available = stationIds.toSet()
    val pinned = pinnedIds.distinct().filter { it in available }
    val pinnedSet = pinned.toSet()
    return pinned + stationIds.filterNot { it in pinnedSet }
}

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
        .setNegativeButton(t("cancel"), null).setPositiveButton(t("source.import.confirm")) { _, _ -> confirm() }.create()
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
        toast("${t("source.read_failed")}: ${errorDetail(e)}")
        return
    }
    lateinit var dialog: android.app.Dialog
    dialog = ThemeUi.showSheet(this, palette, t("source.manager"), t("source.manager.hint")) {
        fun action(label: String, run: () -> Unit) {
            addView(ThemeUi.button(this@showProviderManager, palette, label) { dialog.dismiss(); run() })
        }
        action(t("source.import.file")) { openProviderFile.launch(arrayOf("*/*")) }
        action(t("source.import.qr")) {
            scanProvider.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(t("source.qr.prompt")).setBeepEnabled(false))
        }
        action(t("source.plugins")) { showPluginManager() }
        if (entries.isNotEmpty()) addView(ThemeUi.text(this@showProviderManager, t("source.imported"), 12f, palette, bold = true))
        entries.forEach { entry ->
            action("${t(if (entry.enabled) "common.enabled" else "common.disabled")} · ${if (entry.definition.type == ProviderDefinition.Type.FM) "FM" else t("source.music")} · ${entry.definition.name}") {
                    AlertDialog.Builder(this@showProviderManager).setTitle(entry.definition.name)
                        .setItems(arrayOf(t(if (entry.enabled) "common.disable" else "common.enable"), t("delete"))) { _, choice ->
                            if (choice == 0) {
                                runCatching { providerStore.setEnabled(entry.definition.id, !entry.enabled) }
                                    .onSuccess { if (entry.enabled) stopRemovedProvider(entry.definition.id) }
                                    .onFailure { toast("${t("common.operation_failed")}: ${errorDetail(it)}") }
                                refreshProviderUi()
                                showProviderManager()
                            } else AlertDialog.Builder(this@showProviderManager).setMessage(t("source.delete.confirm"))
                                .setNegativeButton(t("cancel"), null).setPositiveButton(t("delete")) { _, _ ->
                                    runCatching { providerStore.remove(entry.definition.id) }
                                        .onSuccess { stopRemovedProvider(entry.definition.id) }
                                        .onFailure { toast("${t("common.delete_failed")}: ${errorDetail(it)}") }
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
        playbackStatus = t("plugin.source.disabled")
        refreshPlayback()
    } else if (currentItem?.album?.endsWith(":$id") == true && currentItem?.album?.startsWith("provider:") == true) {
        ++loadGeneration
        service?.stopLocal()
        playbackStatus = t("plugin.source.disabled")
        refreshPlayback()
    }
}

private fun MainActivity.readBounded(input: InputStream): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        require(out.size() + n <= j5i.spatial8d.provider.ProviderJar.MAX_BYTES + 52) { t("source.too_large") }
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

internal fun MainActivity.importProviderFile(uri: Uri) {
    Thread {
        val result = runCatching { requireNotNull(contentResolver.openInputStream(uri)).use(::readBounded) }
        runOnUiThread { result.onSuccess(::previewProvider).onFailure { toast("${t("common.import_failed")}: ${errorDetail(it)}") } }
    }.start()
}

internal fun MainActivity.importProviderQr(contents: String) {
    val text = contents.trim()
    if (!text.startsWith("https://", ignoreCase = true)) {
        previewProvider(text.toByteArray(Charsets.UTF_8))
        return
    }
    Thread {
        val result = runCatching { ProviderNetwork.get(text, false, ProviderDefinition.MAX_ENCODED_BYTES) }
        runOnUiThread { result.onSuccess(::previewProvider).onFailure { toast("${t("source.read_failed")}: ${errorDetail(it)}") } }
    }.start()
}

private fun MainActivity.previewProvider(bytes: ByteArray) {
    if (ProviderArchive.isEncrypted(bytes)) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = t("source.password")
        }
        AlertDialog.Builder(this).setTitle(t("source.decrypt")).setView(input)
            .setNegativeButton(t("cancel"), null).setPositiveButton(t("plugin.login.decrypt")) { _, _ ->
                val passphrase = input.text.toString().toCharArray()
                input.text.clear()
                Thread {
                    val result = try { runCatching { ProviderArchive.decrypt(bytes, passphrase) } }
                        finally { passphrase.fill('\u0000') }
                    runOnUiThread {
                        result.onSuccess(::previewProvider).onFailure { toast(t("source.decrypt.failed")) }
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
        toast("${t("source.invalid")}: ${errorDetail(e)}")
        return
    }
    val definitions = items.map(ProviderDefinition::parse)
    val cards = definitions.map { definition ->
        val fm = definition.type == ProviderDefinition.Type.FM
        val summary = if (fm) t("source.summary.fm").format(definition.stations.size, definition.stations.sumOf { 1 + it.alternateUrls.size })
            else t("source.summary.music")
        val capability = t(if (fm) "source.capability.fm" else "source.capability.music")
        ImportPreviewCard(definition.name, summary,
            "${t("plugin.source_id")}\n${definition.id}\n\n${t("source.capabilities")}\n$capability\n\n${t("source.domains")} (${definition.domains.size})\n${definition.domains.sorted().joinToString("\n")}")
    }
    val warning = (if (definitions.any { it.usesHttp }) "${t("source.warning.http")}\n\n" else "") + t("source.warning.trust")
    showImportPreview(t("source.import.title"), t("source.import.review"), cards, warning) {
            runCatching { providerStore.putAll(items) }.onSuccess { toast(t("source.import.count").format(items.size)); refreshProviderUi() }
                .onFailure { toast("${t("plugin.login.save_failed")}: ${errorDetail(it)}") }
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
    body.tag = if (online) "online" else "fm"
    body.removeAllViews()
    val type = if (online) ProviderDefinition.Type.MUSIC else ProviderDefinition.Type.FM
    val entries = try { providerStore.list().filter { it.enabled && it.definition.type == type } }
    catch (e: Exception) { toast("${t("source.read_failed")}: ${errorDetail(e)}"); return }
    val plugins = try { pluginStore.list().filter { it.enabled && it.descriptor.id != "platform.guangbomi" && (if (online) "search" else "live") in it.descriptor.capabilities } }
        catch (e: Exception) { toast("${t("plugin.read_failed")}: ${errorDetail(e)}"); return }
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
        addView(ThemeUi.text(this@populateSourceSheet, t(if (online) "source.music.title" else "source.radio.title"), 12f, palette, role = ThemeUi.ROLE_SUB),
            LinearLayout.LayoutParams(0, -2, 1f))
        if (!online) plugins.forEach { entry ->
            addView(compactButton(entry.descriptor.name) { showLiveCatalog(entry, "fm") })
        }
        addView(compactButton(t("source.manage")) { showProviderManager() })
    }
    body.addView(sourceHeader)
    if (entries.isEmpty() && plugins.isEmpty()) {
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            addView(ThemeUi.text(this@populateSourceSheet, t(if (online) "source.music.empty" else "source.radio.empty"), 16f, palette, bold = true))
            addView(ThemeUi.text(this@populateSourceSheet, t(if (online) "source.music.empty_hint" else "source.radio.empty_hint"), 12f, palette, role = ThemeUi.ROLE_SUB))
            addView(ThemeUi.button(this@populateSourceSheet, palette, t("source.import"), ThemeUi.ROLE_ACCENT_FILL) { showProviderManager() })
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        return
    }
    if (!online) {
        val query = ThemeUi.input(this, palette, t("source.radio.search")).apply {
            layoutParams = LinearLayout.LayoutParams(-1, ThemeUi.dp(this@populateSourceSheet, 38f)).apply {
                topMargin = ThemeUi.dp(this@populateSourceSheet, 4f)
                bottomMargin = ThemeUi.dp(this@populateSourceSheet, 6f)
            }
        }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val filters = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val stations = importedStations()
        val generatedTypes = entries.flatMap { entry -> entry.definition.stations.filter { it.category.isEmpty() }.map { "${entry.definition.id}/${it.id}" } }.toSet()
        val generatedLevels = entries.flatMap { entry -> entry.definition.stations.filter { it.level.isEmpty() }.map { "${entry.definition.id}/${it.id}" } }.toSet()
        val pinnedArray = runCatching { JSONArray(preferences.getString("pinnedRadioStations", "[]")) }.getOrDefault(JSONArray())
        val pinnedStations = (0 until pinnedArray.length()).mapNotNull { pinnedArray.optString(it).takeIf(String::isNotEmpty) }.toMutableList()
        var activeStation: android.view.View? = null
        var visibleStationCount = 50
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
                val shown = when {
                    value == "全部" -> t("playlist.all")
                    value == "网络台" && stations.any { it.id in generatedTypes && it.type == value } -> t("ui.radio.network")
                    else -> value
                }
                buttons.addView(compactButton(shown, value == selected) { choose(value) })
            }
            line.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(buttons) },
                LinearLayout.LayoutParams(0, -2, 1f))
            filters.addView(line)
        }
        fun renderFilters() {
            filters.removeAllViews()
            filterRow(t("source.radio.region"), listOf("全部") + stations.map { it.region }.distinct(), sourceRegion) {
                sourceRegion = it; sourceCity = "全部"; populateSourceSheet(body, false)
            }
            if (sourceRegion != "全部" && sourceRegion != "全国") {
                val cities = stations.filter { it.region == sourceRegion }.map { it.city }.distinct()
                if (cities.size > 1) filterRow(t("source.radio.city"), listOf("全部") + cities, sourceCity) {
                    sourceCity = it; populateSourceSheet(body, false)
                }
            }
            filterRow(t("source.radio.type"), listOf("全部") + stations.map { it.type }.distinct(), sourceType) {
                sourceType = it; populateSourceSheet(body, false)
            }
        }
        fun renderStations() {
            rows.removeAllViews()
            activeStation = null
            val text = query.text.toString().trim()
            val filtered = stations.filter { (sourceRegion == "全部" || it.region == sourceRegion) &&
                (sourceCity == "全部" || it.city == sourceCity) && (sourceType == "全部" || it.type == sourceType) &&
                (text.isEmpty() || "${it.name} ${it.region} ${it.city} ${it.type} ${it.note}".contains(text, true)) }
            val unfiltered = sourceRegion == "全部" && sourceCity == "全部" && sourceType == "全部" && text.isEmpty()
            val byId = filtered.associateBy { it.id }
            val ordered = orderedRadioStationIds(filtered.map { it.id }, pinnedStations, unfiltered).mapNotNull(byId::get)
            ordered.take(visibleStationCount).forEach { station ->
                rows.addView(ThemeUi.row(this).apply {
                    val active = isLive && currentStationId == station.id
                    if (active) activeStation = this
                    setPadding(ThemeUi.dp(this@populateSourceSheet, 8f), ThemeUi.dp(this@populateSourceSheet, 4f), ThemeUi.dp(this@populateSourceSheet, 8f), ThemeUi.dp(this@populateSourceSheet, 4f))
                    background = ThemeUi.bgDrawable(this@populateSourceSheet, if (active) palette.surfaceAlt else palette.surface, 8f)
                    addView(LinearLayout(this@populateSourceSheet).apply {
                        orientation = LinearLayout.VERTICAL
                        val level = if (station.id in generatedLevels) t("source.radio.imported") else station.level
                        val type = if (station.id in generatedTypes) t("ui.radio.network") else station.type
                        addView(ThemeUi.text(this@populateSourceSheet, "${if (station.id in pinnedStations) "📌 " else ""}${station.name} · $level", 13f, palette, bold = true, role = if (active) ThemeUi.ROLE_ACCENT else ThemeUi.ROLE_TEXT))
                        addView(ThemeUi.text(this@populateSourceSheet, "${station.region} · ${station.city} · $type · ${t("source.radio.streams").format(station.sources.size)}", 10f, palette, role = ThemeUi.ROLE_SUB))
                        if (station.note.isNotBlank()) addView(ThemeUi.text(this@populateSourceSheet, station.note, 10f, palette, role = ThemeUi.ROLE_FAINT))
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(ThemeUi.text(this@populateSourceSheet, "⋯", 18f, palette, role = ThemeUi.ROLE_ACCENT).apply {
                        contentDescription = "${station.name}, ${t("playlist.more")}"
                        setPadding(ThemeUi.dp(this@populateSourceSheet, 10f), 0, ThemeUi.dp(this@populateSourceSheet, 10f), 0)
                        setOnClickListener {
                            lateinit var menu: android.app.Dialog
                            menu = ThemeUi.showSheet(this@populateSourceSheet, palette, station.name) {
                                addView(ThemeUi.button(this@populateSourceSheet, palette, t(if (station.id in pinnedStations) "source.radio.unpin" else "source.radio.pin")) {
                                    menu.dismiss()
                                    if (!pinnedStations.remove(station.id)) pinnedStations.add(0, station.id)
                                    preferences.edit().putString("pinnedRadioStations", JSONArray(pinnedStations).toString()).apply()
                                    renderStations()
                                })
                                addView(ThemeUi.button(this@populateSourceSheet, palette, t("source.radio.schedule")) {
                                    menu.dismiss(); showRadioSchedule(station)
                                })
                            }
                            menu.show()
                        }
                    })
                    addView(ThemeUi.text(this@populateSourceSheet, if (active) "LIVE ▸" else "${t("play")} ▸", 12f, palette, role = ThemeUi.ROLE_ACCENT).apply {
                        gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                        minWidth = kotlin.math.ceil(maxOf(paint.measureText("LIVE ▸"), paint.measureText("${t("play")} ▸"))).toInt()
                    })
                    setOnClickListener { playRadio(station) }
                })
            }
            if (visibleStationCount < ordered.size) rows.addView(ThemeUi.button(this, palette, t("source.more")) {
                visibleStationCount += 50
                renderStations()
            })
        }
        query.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) { visibleStationCount = 50; renderStations() }
        })
        renderFilters()
        body.addView(filters)
        body.addView(query)
        renderStations()
        body.addView(ScrollView(this).apply {
            addView(rows)
            post { if (pinnedStations.isEmpty() || sourceRegion != "全部" || sourceCity != "全部" || sourceType != "全部") activeStation?.let { active -> smoothScrollTo(0, (active.top - height / 3).coerceAtLeast(0)) } }
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        return
    }
    val sources = listOf("@aggregate" to t("source.aggregate")) + entries.map { it.definition.id to it.definition.name } + plugins.map { it.descriptor.id to it.descriptor.name }
    val selected = sources.firstOrNull { it.first == providerSelection } ?: sources.first()
    providerSelection = selected.first
    sourceHeader.addView(compactButton(t("source.cache")) { showPlaybackCacheManager() }, 1)
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
        sourceHeader.addView(ThemeUi.text(this, t(if (loggedIn) "source.signed_in" else "source.signed_out"), 10f, palette,
            role = ThemeUi.ROLE_SUB), sourceHeader.childCount - 1)
        sourceHeader.addView(compactButton(t("source.account")) { showPluginLogin(selected.first) }, sourceHeader.childCount - 1)
    }
    val query = ThemeUi.input(this, palette, t("plugin.search.hint")).apply {
        setText(onlineQuery)
        background = null
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
    }
    val searchButton = ThemeUi.button(this, palette, t("common.search"), ThemeUi.ROLE_ACCENT_FILL, 34f).apply {
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
    if (pendingNfcOnlineSearch == onlineQuery && onlineQuery.isNotBlank()) {
        val request = providerRequest
        body.post {
            if (!isDestroyed && request == providerRequest && body.tag == "online" &&
                sourceSheetBody === body && providerSelection == selected.first && pendingNfcOnlineSearch == onlineQuery) {
                pendingNfcOnlineSearch = null
                runSearch()
            }
        }
    }
    if (onlineQuery.isBlank() && providerResults.isEmpty() && pluginResults.isEmpty() && onlineAutoSource != selected.first) {
        onlineAutoSource = selected.first
        val request = providerRequest
        body.post { if (!isDestroyed && request == providerRequest && body.tag == "online" &&
            sourceSheetBody === body && providerSelection == selected.first) runSearch() }
    }
    val visibleTracks = pluginResults.filter { selected.first == "@aggregate" || it.first == selected.first }
        .map { (id, track) -> j5i.spatial8d.provider.ProviderTrackRef.plugin(id, track) } +
        providerResults.filter { selected.first == "@aggregate" || it.first == selected.first }
            .map { (id, track) -> j5i.spatial8d.provider.ProviderTrackRef.declarative(id, track) }
    if (visibleTracks.isNotEmpty()) body.addView(ThemeUi.row(this).apply {
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = ThemeUi.dp(this@populateSourceSheet, 4f)
        }
        addView(compactButton(t("source.queue.add_all")) {
            stageOnlineQueue((onlineQueue + visibleTracks).distinctBy { it.id }, providerSelection ?: "")
            toast(t("source.queue.added_count").format(visibleTracks.size))
        })
        addView(compactButton(t("source.playlist.save")) { showOnlinePlaylistPicker(visibleTracks) })
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
                val duration = if (item.duration > 0) " · ${item.duration / 60000}:${"%02d".format(item.duration / 1000 % 60)}" else ""
                addView(ThemeUi.text(this@populateSourceSheet, "$artist · ${sources.firstOrNull { it.first == sourceId }?.second ?: sourceId}$duration", 11f, palette, role = ThemeUi.ROLE_SUB))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ThemeUi.text(this@populateSourceSheet, "⋯", 18f, palette, role = ThemeUi.ROLE_ACCENT).apply {
                contentDescription = "$title, ${t("playlist.more")}"
                setPadding(ThemeUi.dp(this@populateSourceSheet, 10f), 0, ThemeUi.dp(this@populateSourceSheet, 10f), 0)
                setOnClickListener { showOnlineTrackActions(item) }
            })
            addView(ThemeUi.text(this@populateSourceSheet, if (available) "${t("play")} ▸" else t("source.no_audio"), 12f, palette, role = ThemeUi.ROLE_ACCENT))
            setOnClickListener { play() }
        })
    }
    pluginResults.filter { selected.first == "@aggregate" || it.first == selected.first }.forEach { (id, track) ->
        val item = j5i.spatial8d.provider.ProviderTrackRef.plugin(id, track)
        val parts = track.optInt("partCount", 1)
        val badges = buildList {
            if (track.optBoolean("vip")) add("【V】")
            if (track.optString("mvUrl").isNotBlank()) add("MV")
            if (track.optBoolean("compilation")) add(t("source.collection.count").format(parts)) else if (id == "platform.bilibili") add(t("video.title"))
        }
        val badge = badges.joinToString(" ")
            addTrack(item.name, item.artist, id, item, badge = badge) {
                val visibleResults = pluginResults.filter { selected.first == "@aggregate" || it.first == selected.first }
                val visibleItems = visibleResults.map { (providerId, result) ->
                    j5i.spatial8d.provider.ProviderTrackRef.plugin(providerId, result)
                }
                stageOnlineQueue(visibleItems, id, item.id)
            // Search metadata can under-report Bilibili's P count; already
            // expanded BV:cid entries are individual playable parts.
            if (shouldExpandPluginTrack(id, track.optString("id"), track.optBoolean("compilation"))) expandPluginTrack(id, track)
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
            if (entry == null || url == null) { toast(t("source.unavailable")); return@addTrack }
            if (url.startsWith("http://", true) && !entry.definition.usesHttp) {
                toast(t("source.http.denied"))
                return@addTrack
            }
            if (recordingKind.isNotEmpty()) { toast(t("plugin.stop_recording")); return@addTrack }
            onlinePlayingUri = item.uri
            dismissSource()
            load(item)
        }
    }
    if (providerResults.any { selected.first == "@aggregate" || it.first == selected.first } || pluginResults.any { selected.first == "@aggregate" || it.first == selected.first }) rows.addView(ThemeUi.button(this, palette, t("source.more")) {
        onlinePage++
        if (selected.first == "@aggregate") searchAggregateProviders(body, entries.map { it.definition }, plugins, true)
        else if (selectedPlugin != null) searchPluginProvider(body, selectedPlugin, true)
        else searchImportedProvider(body, selectedData!!.definition, true)
    })
    body.addView(ScrollView(this).apply {
        addView(rows)
    }, LinearLayout.LayoutParams(-1, 0, 1f))
}

internal fun shouldExpandPluginTrack(source: String, trackId: String, compilation: Boolean): Boolean =
    if (source == "platform.bilibili") !trackId.matches(Regex("BV[0-9A-Za-z]+:[0-9]+")) else compilation

internal fun MainActivity.showOnlineTrackActions(item: MusicItem) {
    lateinit var sheet: android.app.Dialog
    sheet = ThemeUi.showSheet(this, palette, item.name.take(28), "${item.artist} · ${t("source.online.hint")}") {
        addView(ThemeUi.button(this@showOnlineTrackActions, palette, t("source.queue.add")) {
            stageOnlineQueue((onlineQueue + item).distinctBy { it.id },
                item.album.substringAfter(':').substringAfter(':'))
            sheet.dismiss(); toast(t("source.queue.added"))
        })
        addView(ThemeUi.button(this@showOnlineTrackActions, palette, t("source.playlist.add")) {
            sheet.dismiss(); showOnlinePlaylistPicker(listOf(item))
        })
        if (item.uri.startsWith("online://plugin/")) {
            val mv = Uri.parse(item.uri).getQueryParameter("mv").orEmpty()
            if (mv.isNotBlank()) {
                addView(ThemeUi.button(this@showOnlineTrackActions, palette, t("source.mv.audio")) {
                    sheet.dismiss()
                    playProviderMv(item, audioOnly = true)
                })
                addView(ThemeUi.button(this@showOnlineTrackActions, palette, t("source.mv.video")) {
                    sheet.dismiss(); playProviderMv(item, audioOnly = false)
                })
            }
        }
        if (item.uri.startsWith("online://plugin/") && item.uri.contains("source=platform.bilibili")) {
            addView(ThemeUi.button(this@showOnlineTrackActions, palette, t("source.video.play")) {
                val id = Uri.parse(item.uri).getQueryParameter("id").orEmpty()
                val bvid = id.substringBefore(':')
                val cid = id.substringAfter(':', "").toLongOrNull()
                if (!bvid.matches(Regex("BV[0-9A-Za-z]+"))) toast(t("source.bili.invalid"))
                else {
                    val url = Uri.parse("https://player.bilibili.com/player.html").buildUpon().appendQueryParameter("bvid", bvid)
                    if (cid != null && cid > 0) url.appendQueryParameter("cid", cid.toString())
                    sheet.dismiss(); openWebVideo(url.build().toString())
                }
            })
            addView(ThemeUi.button(this@showOnlineTrackActions, palette, t("source.bili.open")) {
                val id = android.net.Uri.parse(item.uri).getQueryParameter("id").orEmpty()
                val bvid = id.substringBefore(':')
                if (!bvid.matches(Regex("BV[0-9A-Za-z]+"))) {
                    toast(t("source.bili.invalid"))
                    return@button
                }
                runCatching {
                    if (!openBilibiliVideo(bvid)) toast(t("source.bili.open_failed"))
                }.onFailure { toast("${t("source.bili.open_failed")}: ${errorDetail(it)}") }
                sheet.dismiss()
            })
            val cached = j5i.spatial8d.provider.ProviderAudioCache.cached(this@showOnlineTrackActions, item)
            addView(ThemeUi.button(this@showOnlineTrackActions, palette, t(if (cached == null) "source.bili.save" else "source.bili.delete")).apply {
            setOnClickListener {
                sheet.dismiss()
                if (cached != null) {
                    val ok = j5i.spatial8d.provider.ProviderAudioCache.delete(this@showOnlineTrackActions, item)
                    toast(t(if (ok) "source.bili.deleted" else "source.bili.no_cache"))
                } else {
                    Thread {
                    val result = runCatching { j5i.spatial8d.provider.ProviderAudioCache.cache(this@showOnlineTrackActions, item, true) }
                    main.post { result.onSuccess { saved ->
                        musicStore.remember(saved); songs = (songs.filterNot { it.id == saved.id } + saved).toMutableList()
                        musicStore.saveLibrary(songs); queue = musicStore.view(songs); toast(t("source.bili.saved"))
                    }.onFailure { toast("${t("source.cache.failed")}: ${errorDetail(it)}") } }
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
    sheet = ThemeUi.showSheet(this, palette, t("source.playlist.title").format(items.size), t("source.playlist.hint")) {
        musicStore.playlists.forEachIndexed { index, playlist ->
            addView(ThemeUi.button(this@showOnlinePlaylistPicker, palette, playlist.name) {
                val count = addOnlineToPlaylist(index, items); sheet.dismiss()
                toast(if (count > 0) t("source.playlist.added").format(count, playlist.name) else t("source.playlist.exists"))
            })
        }
        addView(ThemeUi.button(this@showOnlinePlaylistPicker, palette, t("source.playlist.download")) {
            sheet.dismiss(); downloadOnlineFavorite(items.first())
        })
        addView(ThemeUi.button(this@showOnlinePlaylistPicker, palette, t("source.playlist.new")) {
            sheet.dismiss()
            val input = ThemeUi.input(this@showOnlinePlaylistPicker, palette, t("source.playlist.name"))
            AlertDialog.Builder(this@showOnlinePlaylistPicker).setTitle(t("source.playlist.new")).setView(input)
                .setNegativeButton(t("cancel"), null).setPositiveButton(t("source.playlist.create")) { _, _ ->
                    val added = addOnlineToPlaylist(-1, items, input.text.toString())
                    toast(if (added > 0) t("source.playlist.created").format(added) else t("source.playlist.invalid"))
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
    if (body.tag != "online" || sourceSheetBody !== body || !body.isAttachedToWindow) return
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
    toast(t("source.search.count").format(providers.size + plugins.size))
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
                if (result == null) { failed += t("source.search.timeout"); continue }
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
                if (failed.isNotEmpty()) toast(t("source.search.unavailable").format(failed.distinct().joinToString(", ")))
                else if (added == 0) toast(t(if (append) "source.search.no_more" else "plugin.search.empty"))
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
                if (tracks.isEmpty()) toast(t(if (append) "source.search.no_more" else "plugin.search.empty"))
                refreshOnlineResults(body, scrollY)
            }.onFailure {
                if (append) onlinePage = (onlinePage - 1).coerceAtLeast(1)
                toast("${t("plugin.search.failed")}: ${errorDetail(it)}")
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
    playbackStatus = t("source.search.progress").format(provider.name)
    Thread {
        val result = runCatching { ProviderSearch.search(provider, query, page) }
        runOnUiThread {
            if (request != providerRequest || providerSelection != provider.id || isDestroyed) return@runOnUiThread
            if (providerStore.list().none { it.enabled && it.definition.id == provider.id }) return@runOnUiThread
            result.onSuccess { tracks ->
                providerResults = (if (append) providerResults else emptyList()) + tracks.map { provider.id to it }
                playbackStatus = t(if (tracks.isEmpty()) "source.search.no_more" else "source.search.done")
                refreshOnlineResults(body, scrollY)
            }.onFailure { error ->
                if (append) onlinePage = (onlinePage - 1).coerceAtLeast(1)
                playbackStatus = t("plugin.search.failed")
                toast("${t("plugin.search.failed")}: ${errorDetail(error)}")
            }
        }
    }.start()
}
