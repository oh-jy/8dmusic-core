package j5i.spatial8d.feature.source
import j5i.spatial8d.feature.ui.*
import j5i.spatial8d.feature.playback.*
import j5i.spatial8d.feature.video.*
import j5i.spatial8d.feature.update.*
import j5i.spatial8d.feature.cache.*
import j5i.spatial8d.MainActivity
import android.app.AlertDialog
import android.widget.EditText
import j5i.spatial8d.ui.ThemeUi
import j5i.spatial8d.data.MusicItem
import j5i.spatial8d.provider.ProviderBrokerPolicy
import j5i.spatial8d.provider.ProviderJar
import j5i.spatial8d.provider.ProviderPluginRunner
import j5i.spatial8d.provider.ProviderCredentials
import org.json.JSONObject

internal fun MainActivity.previewPlugin(bytes: ByteArray) {
    Thread {
        val result = runCatching {
            require(bytes.size <= ProviderJar.MAX_BYTES) { t("plugin.too_large") }
            val temporary = java.io.File.createTempFile("provider-preview-", ".jar", cacheDir)
            try { temporary.writeBytes(bytes); ProviderJar.inspectAll(temporary) } finally { temporary.delete() }
        }
        runOnUiThread {
            result.onFailure { toast("${t("plugin.verify_failed")}: ${errorDetail(it)}") }.onSuccess { descriptors ->
                val cards = descriptors.map { descriptor -> ImportPreviewCard(descriptor.name,
                    "${t("plugin.source")} · ${descriptor.capabilities.joinToString(" / ")}",
                    "${t("plugin.source_id")}\n${descriptor.id}\n\n${t("plugin.entry")}\n${descriptor.entryClass}\n\n${t("plugin.domains")} (${descriptor.domains.size})\n${descriptor.domains.joinToString("\n")}") }
                val fingerprints = "${t("plugin.cert")}\n${descriptors.first().signerSha256}\n\n${t("plugin.file_hash")}\n${descriptors.first().fileSha256}"
                showImportPreview(t("plugin.import.confirm"), t("plugin.import.count").format(descriptors.size), cards,
                    "${t("plugin.warning.http")}\n\n${t("plugin.warning.code")}\n\n$fingerprints") {
                        Thread {
                            val saved = runCatching {
                                val tmp = java.io.File.createTempFile("provider-import-", ".jar", cacheDir)
                                try { tmp.writeBytes(bytes); pluginStore.install(tmp, descriptors) } finally { tmp.delete() }
                            }
                            runOnUiThread { saved.onSuccess { toast(t("plugin.imported")); refreshProviderUi() }.onFailure { toast("${t("common.import_failed")}: ${errorDetail(it)}") } }
                        }.start()
                }
            }
        }
    }.start()
}

internal fun MainActivity.showPluginManager() {
    val entries = runCatching { pluginStore.list() }.getOrElse { toast("${t("plugin.read_failed")}: ${errorDetail(it)}"); return }
    if (entries.isEmpty()) { toast(t("plugin.empty")); return }
    lateinit var dialog: android.app.Dialog
    dialog = ThemeUi.showSheet(this, palette, t("plugin.manager"), t("plugin.manager.hint")) {
        entries.forEach { entry ->
            addView(ThemeUi.button(this@showPluginManager, palette, "${t(if (entry.enabled) "common.enabled" else "common.disabled")} · ${entry.descriptor.name}") {
            dialog.dismiss()
            val actions = if ("login" in entry.descriptor.capabilities)
                arrayOf(t(if (entry.enabled) "common.disable" else "common.enable"), t("delete"), t("plugin.login_import"))
            else arrayOf(t(if (entry.enabled) "common.disable" else "common.enable"), t("delete"))
            AlertDialog.Builder(this@showPluginManager).setTitle(entry.descriptor.name)
                .setItems(actions) { _, action ->
                    if (action == 2) {
                        showPluginLogin(entry.descriptor.id)
                    } else
                    if (action == 0) {
                        runCatching { pluginStore.setEnabled(entry.descriptor.id, !entry.enabled) }
                            .onSuccess { if (entry.enabled) stopPluginPlayback(entry.descriptor.id) }
                            .onFailure { toast("${t("common.operation_failed")}: ${errorDetail(it)}") }
                        refreshProviderUi()
                        showPluginManager()
                    } else AlertDialog.Builder(this@showPluginManager).setMessage(t("plugin.delete.confirm"))
                        .setNegativeButton(t("cancel"), null).setPositiveButton(t("delete")) { _, _ ->
                            runCatching { pluginStore.remove(entry.descriptor.id) }
                                .onSuccess { stopPluginPlayback(entry.descriptor.id) }
                                .onFailure { toast("${t("common.delete_failed")}: ${errorDetail(it)}") }
                            refreshProviderUi()
                        }.show()
                }.show()
            })
        }
        addView(ThemeUi.button(this@showPluginManager, palette, t("plugin.reimport")) {
            dialog.dismiss(); openProviderFile.launch(arrayOf("*/*"))
        })
    }
    dialog.show()
}

private fun MainActivity.stopPluginPlayback(id: String) {
    providerRequest++
    if (currentItem?.album == "plugin:https:$id") {
        ++loadGeneration
        service?.stopLocal()
        playbackStatus = t("plugin.source.disabled")
        refreshPlayback()
    }
}

internal fun MainActivity.showPluginSources() {
    val entries = runCatching { pluginStore.list().filter { it.enabled && "search" in it.descriptor.capabilities } }
        .getOrElse { toast("${t("plugin.read_failed")}: ${errorDetail(it)}"); return }
    if (entries.isEmpty()) { toast(t("plugin.need_source")); return }
    AlertDialog.Builder(this).setTitle(t("plugin.select"))
        .setItems(entries.map { it.descriptor.name }.toTypedArray()) { _, index ->
            val entry = entries[index]
            val input = EditText(this).apply { hint = t("plugin.search.hint") }
            AlertDialog.Builder(this).setTitle(entry.descriptor.name).setView(input)
                .apply { if ("login" in entry.descriptor.capabilities) setNeutralButton(t("plugin.login_import")) { _, _ -> showPluginLogin(entry.descriptor.id) } }
                .setNegativeButton(t("cancel"), null).setPositiveButton(t("common.search")) { _, _ ->
                    val query = input.text.toString().trim()
                    if (query.isEmpty()) { toast(t("plugin.search.keyword")); return@setPositiveButton }
                    Thread {
                        val result = runCatching {
                            val request = JSONObject().put("version", 1).put("operation", "search").put("query", query).put("page", 1)
                                .put("cookie", ProviderCredentials(this).get(entry.descriptor.id))
                            val response = JSONObject(ProviderPluginRunner.invoke(this, entry, request.toString()))
                            val rows = response.getJSONArray("tracks")
                            require(rows.length() <= 100)
                            (0 until rows.length()).map { rows.getJSONObject(it) }.filter {
                                it.optString("id").isNotEmpty() && it.optString("title").isNotEmpty()
                            }
                        }
                        runOnUiThread {
                            result.onFailure { toast("${t("plugin.search.failed")}: ${errorDetail(it)}") }.onSuccess { tracks ->
                                if (tracks.isEmpty()) toast(t("plugin.search.empty")) else AlertDialog.Builder(this)
                                    .setTitle("${entry.descriptor.name} · ${t("plugin.search.results")}")
                                    .setItems(tracks.map { "${it.optString("title")} · ${it.optString("artist")}" }.toTypedArray()) { _, position ->
                                        playPluginTrack(entry.descriptor.id, tracks[position])
                                    }.setNegativeButton(t("close"), null).show()
                            }
                        }
                    }.start()
                }.show()
        }.setNegativeButton(t("cancel"), null).show()
}

internal fun MainActivity.playPluginTrack(id: String, track: JSONObject) {
    val item = j5i.spatial8d.provider.ProviderTrackRef.plugin(id, track)
    if (recordingKind.isNotEmpty()) { toast(t("plugin.stop_recording")); return }
    if (currentQueue.isEmpty()) currentQueue = listOf(item)
    onlinePlayingUri = item.uri
    dismissSource()
    if (id == "platform.bilibili") {
        j5i.spatial8d.provider.ProviderAudioCache.cached(this, item)?.let { cached ->
            load(cached); refreshPlayback(); return
        }
        val generation = prepareOnlineSelection(item, t("plugin.bili.caching"))
        Thread {
            val result = runCatching { j5i.spatial8d.provider.ProviderAudioCache.cache(this, item, false) }
            runOnUiThread {
                if (generation != loadGeneration) return@runOnUiThread
                result.onSuccess { cached ->
                musicStore.remember(cached)
                songs = (musicStore.cached().filterNot { it.id == cached.id } + cached).toMutableList()
                musicStore.saveLibrary(songs)
                queue = musicStore.view(songs)
                load(cached); refreshPlayback()
            }
                .onFailure {
                    android.util.Log.w("8DProvider", "bilibili audio cache failed: ${it.message}")
                    playbackStatus = "${t("plugin.bili.failed")}: ${errorDetail(it)}"
                    toast(playbackStatus); refreshPlayback()
                } }
        }.start()
        return
    }
    load(item)
}

internal fun MainActivity.expandPluginTrack(id: String, track: JSONObject) {
    toast(t("plugin.parts.loading"))
    Thread {
        val result = runCatching {
            val entry = pluginStore.list().first { it.enabled && it.descriptor.id == id }
            val request = JSONObject().put("version", 1).put("operation", "parts").put("id", track.getString("id"))
                .put("cookie", ProviderCredentials(this).get(id))
            val response = JSONObject(ProviderPluginRunner.invoke(this, entry, request.toString()))
            val rows = response.getJSONArray("tracks")
            (0 until rows.length()).map { rows.getJSONObject(it) }
        }
        runOnUiThread {
            result.onFailure { toast("${t("plugin.parts.failed")}: ${errorDetail(it)}") }.onSuccess { parts ->
                if (parts.isEmpty()) { toast(t("plugin.parts.empty")); return@onSuccess }
                // Search metadata may be stale; the authoritative pagelist decides
                // whether this Bilibili result is a single video or a compilation.
                pluginResults = pluginResults.map { (source, value) ->
                    if (source == id && value.optString("id") == track.optString("id"))
                        source to value.put("partCount", parts.size).put("compilation", parts.size > 1)
                    else source to value
                }
                if (parts.size == 1) { playPluginTrack(id, parts[0]); return@onSuccess }
                parts.forEach { it.put("artist", track.optString("artist")) }
                showPluginParts(id, track, parts)
            }
        }
    }.start()
}

internal fun MainActivity.showPluginParts(id: String, parent: JSONObject, parts: List<JSONObject>) {
    val partQueue = parts.map { j5i.spatial8d.provider.ProviderTrackRef.plugin(id, it) }
    lateinit var sheet: android.app.Dialog
    sheet = ThemeUi.showSheet(this, palette, parent.optString("title").take(24), t("plugin.parts.count").format(parts.size), fullHeight = true) {
        val rows = android.widget.LinearLayout(this@showPluginParts).apply { orientation = android.widget.LinearLayout.VERTICAL }
        parts.forEachIndexed { index, part ->
            val item = partQueue[index]
            rows.addView(ThemeUi.row(this@showPluginParts).apply {
                addView(android.widget.LinearLayout(this@showPluginParts).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    addView(ThemeUi.text(this@showPluginParts, "${index + 1}. ${item.name}", 13f, palette, bold = true))
                    addView(ThemeUi.text(this@showPluginParts,
                        "${item.artist} · ${item.duration / 60000}:${"%02d".format(item.duration / 1000 % 60)}" +
                            if (j5i.spatial8d.provider.ProviderAudioCache.cached(this@showPluginParts, item) != null) " · ${t("plugin.cached")}" else "",
                        11f, palette, role = ThemeUi.ROLE_SUB))
                }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                addView(ThemeUi.text(this@showPluginParts, "⋯", 18f, palette, role = ThemeUi.ROLE_ACCENT).apply {
                    setPadding(ThemeUi.dp(this@showPluginParts, 10f), 0, ThemeUi.dp(this@showPluginParts, 10f), 0)
                    setOnClickListener { showOnlineTrackActions(item) }
                })
                addView(ThemeUi.text(this@showPluginParts, "${t("play")} ▸", 12f, palette, role = ThemeUi.ROLE_ACCENT))
                setOnClickListener {
                    // Keep the expanded P list visible when the online tab is
                    // reopened, so the active part can be highlighted/located.
                    providerSelection = id
                    pluginResults = parts.map { id to it }
                    providerResults = emptyList()
                    stageOnlineQueue(partQueue, id, item.id)
                    sheet.dismiss(); playPluginTrack(id, part)
                }
            })
        }
        addView(android.widget.ScrollView(this@showPluginParts).apply { addView(rows) },
            android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
    }
    sheet.show()
}

internal fun MainActivity.showBiliCacheManager() {
    val cached = songs.filter { it.album == "在线缓存:platform.bilibili" ||
        (it.album == "在线缓存" && it.uri.contains("bili", true)) }
    lateinit var sheet: android.app.Dialog
    sheet = ThemeUi.showSheet(this, palette, t("plugin.cache.manager"), t("plugin.cache.count").format(cached.size), fullHeight = true) {
        if (cached.isEmpty()) addView(ThemeUi.text(this@showBiliCacheManager, t("plugin.cache.empty"), 12f, palette, role = ThemeUi.ROLE_SUB))
        cached.forEach { item ->
            addView(ThemeUi.row(this@showBiliCacheManager).apply {
                addView(ThemeUi.text(this@showBiliCacheManager, item.name, 13f, palette), android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                addView(ThemeUi.button(this@showBiliCacheManager, palette, t("delete"), ThemeUi.ROLE_CONTROL, 32f).apply {
                    setOnClickListener {
                    val removed = j5i.spatial8d.provider.ProviderAudioCache.delete(this@showBiliCacheManager, item)
                    runCatching { if (removed) musicStore.removePermanently(item) }.onSuccess {
                        songs = musicStore.cached().toMutableList(); queue = musicStore.view(songs)
                        sheet.dismiss(); showBiliCacheManager()
                    }.onFailure { toast("${t("common.delete_failed")}: ${errorDetail(it)}") }
                    }
                })
            })
        }
    }
    sheet.show()
}

internal fun MainActivity.showPluginLogin(id: String) {
    val name = runCatching { pluginStore.list().firstOrNull { it.descriptor.id == id }?.descriptor?.name }.getOrNull() ?: id
    lateinit var dialog: android.app.Dialog
    dialog = ThemeUi.showSheet(this, palette, "$name · ${t("plugin.login.manager")}", t("plugin.login.backup_hint")) {
        fun action(label: String, detail: String, run: () -> Unit) {
            addView(ThemeUi.button(this@showPluginLogin, palette, label) { dialog.dismiss(); run() })
            addView(ThemeUi.text(this@showPluginLogin, detail, 11f, palette, role = ThemeUi.ROLE_SUB))
        }
        action(t("plugin.login.web"), t("plugin.login.web_hint")) {
            runCatching {
                providerLoginDialog?.dismiss()
                providerLoginDialog = j5i.spatial8d.provider.ProviderLogin.open(this@showPluginLogin, id) { error ->
                    runOnUiThread { toast(error ?: t("plugin.login.saved_encrypted")); refreshProviderUi() }
                }
            }.onFailure { toast("${t("plugin.login.open_failed")}: ${errorDetail(it)}") }
        }
        action(t("plugin.login.import"), t("plugin.login.import_hint")) { loginPluginId = id; openLoginFile.launch(arrayOf("*/*")) }
        action(t("plugin.login.export_all"), t("plugin.login.export_hint")) {
            val input = EditText(this@showPluginLogin).apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD; hint = t("plugin.login.password_hint") }
            AlertDialog.Builder(this@showPluginLogin).setTitle(t("plugin.login.export_title")).setView(input)
                .setNegativeButton(t("cancel"), null).setPositiveButton(t("plugin.login.export")) { _, _ ->
                    val secret = input.text.toString().toCharArray(); input.text.clear()
                    Thread {
                        val result = try { runCatching {
                            val credentials = ProviderCredentials(this@showPluginLogin)
                            val cookies = pluginStore.list().map { it.descriptor.id }.distinct()
                                .mapNotNull { source -> credentials.get(source).takeIf { it.isNotEmpty() }?.let { source to it } }.toMap()
                            j5i.spatial8d.provider.ProviderLoginBackup.savePortable(this@showPluginLogin, cookies, secret)
                        } } finally { secret.fill('\u0000') }
                        runOnUiThread { result.onSuccess { toast("${t("plugin.login.exported")} $it") }.onFailure { toast("${t("plugin.login.export_failed")}: ${errorDetail(it)}") } }
                    }.start()
                }.show()
        }
        action(t("plugin.login.cookie"), t("plugin.login.cookie_hint")) {
            val input = EditText(this@showPluginLogin).apply { hint = t("plugin.login.cookie_input"); minLines = 3; setTextIsSelectable(true) }
            AlertDialog.Builder(this@showPluginLogin).setTitle(t("plugin.login.save_title").format(name)).setView(input)
                .setNegativeButton(t("cancel"), null).setPositiveButton(t("save")) { _, _ ->
                    runCatching { savePluginCookie(id, input.text.toString()) }
                        .onSuccess { input.text.clear(); toast(t("plugin.login.saved")); refreshProviderUi() }.onFailure { toast(errorDetail(it)) }
                }.show()
        }
        action(t("plugin.login.logout"), t("plugin.login.logout_hint")) {
            runCatching { ProviderCredentials(this@showPluginLogin).clear(id); j5i.spatial8d.provider.ProviderLoginBackup.delete(this@showPluginLogin, id) }
                .onSuccess { toast(t("plugin.login.logged_out")); refreshProviderUi() }.onFailure { toast("${t("plugin.login.logout_failed")}: ${errorDetail(it)}") }
        }
    }
    dialog.show()
}

internal fun MainActivity.importPluginLogin(id: String, uri: android.net.Uri) {
    Thread {
        val result = runCatching {
            requireNotNull(contentResolver.openInputStream(uri)) { t("plugin.login.open_file_failed") }.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(out.size() + n <= j5i.spatial8d.provider.ProviderJar.MAX_BYTES + 52) { t("plugin.login.file_large") }
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        }
        runOnUiThread {
            result.onFailure { toast("${t("plugin.login.read_failed")}: ${errorDetail(it)}") }.onSuccess { bytes ->
                if (!j5i.spatial8d.provider.ProviderArchive.isEncrypted(bytes)) {
                    if (j5i.spatial8d.provider.ProviderLoginBackup.isEncrypted(bytes)) {
                        runCatching { savePluginCookie(id, j5i.spatial8d.provider.ProviderLoginBackup.read(bytes, id)) }
                            .onSuccess { toast(t("plugin.login.restored")); refreshProviderUi() }.onFailure {
                                android.util.Log.w("8DProvider", "login restore failed source=$id type=${it.javaClass.simpleName}")
                                toast("${t("plugin.login.restore_failed")}: ${errorDetail(it)}")
                            }
                    } else
                    runCatching { savePluginCookie(id, String(bytes, Charsets.UTF_8)) }
                        .onSuccess { toast(t("plugin.login.imported")); refreshProviderUi() }.onFailure { toast("${t("common.import_failed")}: ${errorDetail(it)}") }
                } else {
                    val input = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
                    AlertDialog.Builder(this).setTitle(t("plugin.login.file_password")).setView(input)
                        .setNegativeButton(t("cancel"), null).setPositiveButton(t("plugin.login.decrypt")) { _, _ ->
                            val secret = input.text.toString().toCharArray(); input.text.clear()
                            Thread {
                                val plain = try { runCatching { j5i.spatial8d.provider.ProviderLoginBackup.portableCookies(bytes, id, secret) } }
                                    finally { secret.fill('\u0000') }
                                val saved = plain.mapCatching { entries -> entries.forEach { (source, cookie) -> savePluginCookie(source, cookie) }; entries.size }
                                runOnUiThread {
                                    if (plain.isFailure) toast("${t("plugin.login.decrypt_failed")}: ${plain.exceptionOrNull()?.let { errorDetail(it) } ?: t("plugin.login.bad_password")}")
                                    else saved.onSuccess { toast(t("plugin.login.imported_count").format(it)); refreshProviderUi() }.onFailure { toast("${t("plugin.login.partial")}: ${errorDetail(it)}") }
                                }
                            }.start()
                        }.show()
                }
            }
        }
    }.start()
}

private fun MainActivity.savePluginCookie(id: String, raw: String) {
    val normalized = j5i.spatial8d.provider.ProviderCookie.normalize(raw)
    val fields = normalized.split(';').mapNotNull {
        val split = it.trim().indexOf('=')
        if (split <= 0) null else it.trim().substring(0, split) to it.trim().substring(split + 1)
    }.toMap()
    fun has(key: String) = fields[key]?.isNotBlank() == true
    val valid = when (id) {
        "platform.qq" -> has("qqmusic_key") && (has("wxuin") || has("uin"))
        "platform.bilibili" -> has("SESSDATA")
        "platform.netease" -> has("MUSIC_U")
        "platform.kugou" -> has("KugooID") || has("KugooSID")
        else -> fields.isNotEmpty()
    }
    require(valid) { t("plugin.login.missing_fields") }
    val cookie = fields.entries.joinToString("; ") { "${it.key}=${it.value}" }
    ProviderCredentials(this).save(id, cookie)
    j5i.spatial8d.provider.ProviderLoginBackup.save(this, id, cookie)
}
