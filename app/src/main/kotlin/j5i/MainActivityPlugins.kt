package j5i.spatial8d

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
            require(bytes.size <= ProviderJar.MAX_BYTES) { "插件文件过大" }
            val temporary = java.io.File.createTempFile("provider-preview-", ".jar", cacheDir)
            try { temporary.writeBytes(bytes); ProviderJar.inspectAll(temporary) } finally { temporary.delete() }
        }
        runOnUiThread {
            result.onFailure { toast("插件校验失败：${it.message}") }.onSuccess { descriptors ->
                val cards = descriptors.map { descriptor -> ImportPreviewCard(descriptor.name,
                    "插件来源 · ${descriptor.capabilities.joinToString(" / ")}",
                    "来源 ID\n${descriptor.id}\n\n入口\n${descriptor.entryClass}\n\n请求域名（${descriptor.domains.size}）\n${descriptor.domains.joinToString("\n")}") }
                val fingerprints = "签名证书 SHA-256\n${descriptors.first().signerSha256}\n\n文件 SHA-256\n${descriptors.first().fileSha256}"
                showImportPreview("确认导入 JAR 插件", "包含 ${descriptors.size} 个来源，请逐一核对", cards,
                    "来源允许明文 HTTP 请求，包括登录 Cookie；传输内容和凭据可能被窃听或篡改。\n\n" +
                    "插件是可执行代码，签名只能标识签名者，不代表其可信。请核对指纹并确认来源。\n\n$fingerprints") {
                        Thread {
                            val saved = runCatching {
                                val tmp = java.io.File.createTempFile("provider-import-", ".jar", cacheDir)
                                try { tmp.writeBytes(bytes); pluginStore.install(tmp, descriptors) } finally { tmp.delete() }
                            }
                            runOnUiThread { saved.onSuccess { toast("插件已导入"); refreshProviderUi() }.onFailure { toast("导入失败：${it.message}") } }
                        }.start()
                }
            }
        }
    }.start()
}

internal fun MainActivity.showPluginManager() {
    val entries = runCatching { pluginStore.list() }.getOrElse { toast("插件读取失败：${it.message}"); return }
    if (entries.isEmpty()) { toast("没有已导入的 JAR 插件"); return }
    lateinit var dialog: android.app.Dialog
    dialog = ThemeUi.showSheet(this, palette, "JAR 插件管理", "选择来源管理启用状态和登录信息") {
        entries.forEach { entry ->
            addView(ThemeUi.button(this@showPluginManager, palette, "${if (entry.enabled) "已启用" else "已停用"} · ${entry.descriptor.name}") {
            dialog.dismiss()
            val actions = if ("login" in entry.descriptor.capabilities)
                arrayOf(if (entry.enabled) "停用" else "启用", "删除", "登录 / 导入")
            else arrayOf(if (entry.enabled) "停用" else "启用", "删除")
            AlertDialog.Builder(this@showPluginManager).setTitle(entry.descriptor.name)
                .setItems(actions) { _, action ->
                    if (action == 2) {
                        showPluginLogin(entry.descriptor.id)
                    } else
                    if (action == 0) {
                        runCatching { pluginStore.setEnabled(entry.descriptor.id, !entry.enabled) }
                            .onSuccess { if (entry.enabled) stopPluginPlayback(entry.descriptor.id) }
                            .onFailure { toast("操作失败：${it.message}") }
                        refreshProviderUi()
                        showPluginManager()
                    } else AlertDialog.Builder(this@showPluginManager).setMessage("删除这个插件？")
                        .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                            runCatching { pluginStore.remove(entry.descriptor.id) }
                                .onSuccess { stopPluginPlayback(entry.descriptor.id) }
                                .onFailure { toast("删除失败：${it.message}") }
                            refreshProviderUi()
                        }.show()
                }.show()
            })
        }
        addView(ThemeUi.button(this@showPluginManager, palette, "重新导入合并插件以更新") {
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
        playbackStatus = "来源已停用"
        refreshPlayback()
    }
}

internal fun MainActivity.showPluginSources() {
    val entries = runCatching { pluginStore.list().filter { it.enabled && "search" in it.descriptor.capabilities } }
        .getOrElse { toast("插件读取失败：${it.message}"); return }
    if (entries.isEmpty()) { toast("请先导入并启用歌曲插件"); return }
    AlertDialog.Builder(this).setTitle("选择插件来源")
        .setItems(entries.map { it.descriptor.name }.toTypedArray()) { _, index ->
            val entry = entries[index]
            val input = EditText(this).apply { hint = "搜索歌曲" }
            AlertDialog.Builder(this).setTitle(entry.descriptor.name).setView(input)
                .apply { if ("login" in entry.descriptor.capabilities) setNeutralButton("登录 / 导入") { _, _ -> showPluginLogin(entry.descriptor.id) } }
                .setNegativeButton("取消", null).setPositiveButton("搜索") { _, _ ->
                    val query = input.text.toString().trim()
                    if (query.isEmpty()) { toast("请输入关键词"); return@setPositiveButton }
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
                            result.onFailure { toast("搜索失败：${it.message}") }.onSuccess { tracks ->
                                if (tracks.isEmpty()) toast("没有找到歌曲") else AlertDialog.Builder(this)
                                    .setTitle("${entry.descriptor.name} · 搜索结果")
                                    .setItems(tracks.map { "${it.optString("title")} · ${it.optString("artist")}" }.toTypedArray()) { _, position ->
                                        playPluginTrack(entry.descriptor.id, tracks[position])
                                    }.setNegativeButton("关闭", null).show()
                            }
                        }
                    }.start()
                }.show()
        }.setNegativeButton("取消", null).show()
}

internal fun MainActivity.playPluginTrack(id: String, track: JSONObject) {
    val item = j5i.spatial8d.provider.ProviderTrackRef.plugin(id, track)
    if (recordingKind.isNotEmpty()) { toast("请先停止录音，再切换歌曲"); return }
    if (currentQueue.isEmpty()) currentQueue = listOf(item)
    onlinePlayingUri = item.uri
    dismissSource()
    if (id == "platform.bilibili") {
        j5i.spatial8d.provider.ProviderAudioCache.cached(this, item)?.let { cached ->
            load(cached); refreshPlayback(); return
        }
        val generation = prepareOnlineSelection(item, "正在缓存哔哩哔哩音频…")
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
                    playbackStatus = "哔哩哔哩音频加载失败：${it.message}"
                    toast(playbackStatus); refreshPlayback()
                } }
        }.start()
        return
    }
    load(item)
}

internal fun MainActivity.expandPluginTrack(id: String, track: JSONObject) {
    toast("正在读取合集曲目…")
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
            result.onFailure { toast("合集读取失败：${it.message}") }.onSuccess { parts ->
                if (parts.isEmpty()) { toast("合集曲目为空"); return@onSuccess }
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
    sheet = ThemeUi.showSheet(this, palette, parent.optString("title").take(24), "合集 · ${parts.size} 首分 P", fullHeight = true) {
        val rows = android.widget.LinearLayout(this@showPluginParts).apply { orientation = android.widget.LinearLayout.VERTICAL }
        parts.forEachIndexed { index, part ->
            val item = partQueue[index]
            rows.addView(ThemeUi.row(this@showPluginParts).apply {
                addView(android.widget.LinearLayout(this@showPluginParts).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    addView(ThemeUi.text(this@showPluginParts, "${index + 1}. ${item.name}", 13f, palette, bold = true))
                    addView(ThemeUi.text(this@showPluginParts,
                        "${item.artist} · ${item.duration / 60000}:${"%02d".format(item.duration / 1000 % 60)}" +
                            if (j5i.spatial8d.provider.ProviderAudioCache.cached(this@showPluginParts, item) != null) " · 已缓存" else "",
                        11f, palette, role = ThemeUi.ROLE_SUB))
                }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                addView(ThemeUi.text(this@showPluginParts, "⋯", 18f, palette, role = ThemeUi.ROLE_ACCENT).apply {
                    setPadding(ThemeUi.dp(this@showPluginParts, 10f), 0, ThemeUi.dp(this@showPluginParts, 10f), 0)
                    setOnClickListener { showOnlineTrackActions(item) }
                })
                addView(ThemeUi.text(this@showPluginParts, "播放 ▸", 12f, palette, role = ThemeUi.ROLE_ACCENT))
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
    sheet = ThemeUi.showSheet(this, palette, "哔哩哔哩缓存管理", "${cached.size} 首 · 保存位置 Download/bili", fullHeight = true) {
        if (cached.isEmpty()) addView(ThemeUi.text(this@showBiliCacheManager, "暂无缓存。合集分 P 或单曲菜单可保存音频。", 12f, palette, role = ThemeUi.ROLE_SUB))
        cached.forEach { item ->
            addView(ThemeUi.row(this@showBiliCacheManager).apply {
                addView(ThemeUi.text(this@showBiliCacheManager, item.name, 13f, palette), android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                addView(ThemeUi.button(this@showBiliCacheManager, palette, "删除", ThemeUi.ROLE_CONTROL, 32f).apply {
                    setOnClickListener {
                    val removed = j5i.spatial8d.provider.ProviderAudioCache.delete(this@showBiliCacheManager, item)
                    runCatching { if (removed) musicStore.removePermanently(item) }.onSuccess {
                        songs = musicStore.cached().toMutableList(); queue = musicStore.view(songs)
                        sheet.dismiss(); showBiliCacheManager()
                    }.onFailure { toast("删除失败：${it.message}") }
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
    dialog = ThemeUi.showSheet(this, palette, "$name · 登录管理", "登录后自动加密备份到 Download/8d/login/。") {
        fun action(label: String, detail: String, run: () -> Unit) {
            addView(ThemeUi.button(this@showPluginLogin, palette, label) { dialog.dismiss(); run() })
            addView(ThemeUi.text(this@showPluginLogin, detail, 11f, palette, role = ThemeUi.ROLE_SUB))
        }
        action("官方网页登录", "在平台官方页面完成登录或扫码") {
            runCatching {
                providerLoginDialog?.dismiss()
                providerLoginDialog = j5i.spatial8d.provider.ProviderLogin.open(this@showPluginLogin, id) { error ->
                    runOnUiThread { toast(error ?: "登录凭据已加密保存"); refreshProviderUi() }
                }
            }.onFailure { toast("无法打开登录页面：${it.message}") }
        }
        action("导入登录文件", "支持旧版 Cookie 文本及本平台 .8dp 加密备份") { loginPluginId = id; openLoginFile.launch(arrayOf("*/*")) }
        action("导出全部账号", "统一密码加密，保存到 Download/8d/login/") {
            val input = EditText(this@showPluginLogin).apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD; hint = "设置至少 8 位备份密码" }
            AlertDialog.Builder(this@showPluginLogin).setTitle("导出登录备份").setView(input)
                .setNegativeButton("取消", null).setPositiveButton("导出") { _, _ ->
                    val secret = input.text.toString().toCharArray(); input.text.clear()
                    Thread {
                        val result = try { runCatching {
                            val credentials = ProviderCredentials(this@showPluginLogin)
                            val cookies = pluginStore.list().map { it.descriptor.id }.distinct()
                                .mapNotNull { source -> credentials.get(source).takeIf { it.isNotEmpty() }?.let { source to it } }.toMap()
                            j5i.spatial8d.provider.ProviderLoginBackup.savePortable(this@showPluginLogin, cookies, secret)
                        } } finally { secret.fill('\u0000') }
                        runOnUiThread { result.onSuccess { toast("已导出 $it") }.onFailure { toast("导出失败：${it.message}") } }
                    }.start()
                }.show()
        }
        action("手动输入 Cookie", "适合从可信来源取得的当前平台凭据") {
            val input = EditText(this@showPluginLogin).apply { hint = "粘贴完整 Cookie"; minLines = 3; setTextIsSelectable(true) }
            AlertDialog.Builder(this@showPluginLogin).setTitle("保存 $name 登录信息").setView(input)
                .setNegativeButton("取消", null).setPositiveButton("保存") { _, _ ->
                    runCatching { savePluginCookie(id, input.text.toString()) }
                        .onSuccess { input.text.clear(); toast("登录凭据已保存"); refreshProviderUi() }.onFailure { toast(it.message ?: "保存失败") }
                }.show()
        }
        action("退出登录", "删除此来源保存在本机的登录信息") {
            runCatching { ProviderCredentials(this@showPluginLogin).clear(id); j5i.spatial8d.provider.ProviderLoginBackup.delete(this@showPluginLogin, id) }
                .onSuccess { toast("已退出登录"); refreshProviderUi() }.onFailure { toast("退出失败：${it.message}") }
        }
    }
    dialog.show()
}

internal fun MainActivity.importPluginLogin(id: String, uri: android.net.Uri) {
    Thread {
        val result = runCatching {
            requireNotNull(contentResolver.openInputStream(uri)) { "无法打开登录文件" }.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(out.size() + n <= j5i.spatial8d.provider.ProviderJar.MAX_BYTES + 52) { "登录文件过大" }
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        }
        runOnUiThread {
            result.onFailure { toast("读取登录文件失败：${it.message}") }.onSuccess { bytes ->
                if (!j5i.spatial8d.provider.ProviderArchive.isEncrypted(bytes)) {
                    if (j5i.spatial8d.provider.ProviderLoginBackup.isEncrypted(bytes)) {
                        runCatching { savePluginCookie(id, j5i.spatial8d.provider.ProviderLoginBackup.read(bytes, id)) }
                            .onSuccess { toast("登录凭据已还原"); refreshProviderUi() }.onFailure {
                                android.util.Log.w("8DProvider", "login restore failed source=$id type=${it.javaClass.simpleName}")
                                toast("还原失败：${it.message ?: it.javaClass.simpleName}")
                            }
                    } else
                    runCatching { savePluginCookie(id, String(bytes, Charsets.UTF_8)) }
                        .onSuccess { toast("登录凭据已导入"); refreshProviderUi() }.onFailure { toast("导入失败：${it.message}") }
                } else {
                    val input = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
                    AlertDialog.Builder(this).setTitle("登录文件密码").setView(input)
                        .setNegativeButton("取消", null).setPositiveButton("解密") { _, _ ->
                            val secret = input.text.toString().toCharArray(); input.text.clear()
                            Thread {
                                val plain = try { runCatching { j5i.spatial8d.provider.ProviderLoginBackup.portableCookies(bytes, id, secret) } }
                                    finally { secret.fill('\u0000') }
                                val saved = plain.mapCatching { entries -> entries.forEach { (source, cookie) -> savePluginCookie(source, cookie) }; entries.size }
                                runOnUiThread {
                                    if (plain.isFailure) toast("解密失败：${plain.exceptionOrNull()?.message ?: "密码错误或文件损坏"}")
                                    else saved.onSuccess { toast("已导入 $it 个来源的登录凭据"); refreshProviderUi() }.onFailure { toast("导入失败，部分来源可能已保存：${it.message}") }
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
    require(valid) { "登录凭据缺少该平台必需字段" }
    val cookie = fields.entries.joinToString("; ") { "${it.key}=${it.value}" }
    ProviderCredentials(this).save(id, cookie)
    j5i.spatial8d.provider.ProviderLoginBackup.save(this, id, cookie)
}
