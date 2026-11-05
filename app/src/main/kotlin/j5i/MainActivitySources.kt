package j5i.spatial8d

import android.Manifest
import android.app.Dialog
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.HorizontalScrollView
import j5i.spatial8d.audio.OnlineTrack
import j5i.spatial8d.core.RadioCatalog
import j5i.spatial8d.core.RadioStation
import j5i.spatial8d.data.MusicItem
import j5i.spatial8d.ui.ThemeUi
import kotlin.math.roundToInt

internal fun MainActivity.showSourceSheet(online: Boolean) {
        if (if (online) !onlineUnlocked else !fmUnlocked) return
        val current = if (online) onlineDialog else fmDialog
        if (current?.isShowing == true) return
        if (!online) syncStationFilterToCurrent()
        sourceStatusView = null
        val sheet = ThemeUi.showSheet(this, palette, if (online) t("online") else t("fm"), "8D",
            cancelLabel = t("close"), fullHeight = true, scrollable = false) {
            populateSourceSheet(this, online)
        }
        if (online) onlineDialog = sheet else fmDialog = sheet
        sourceStatusView = ThemeUi.text(this, t(if (online) "online.status" else "fm.status"), 11f, palette,
            role = ThemeUi.ROLE_SUB)
        sheet.findViewById<ViewGroup>(android.R.id.content)?.let { content ->
            (content.getChildAt(0) as? LinearLayout)?.addView(sourceStatusView)
        }
        sheet.show()
    }

    // 再次打开 FM 时先恢复当前电台所属筛选，避免当前电台被旧筛选条件隐藏。

internal fun MainActivity.syncStationFilterToCurrent() {
        if (!isLive || currentStationId.isBlank()) return
        val station = RadioCatalog.filter(false).firstOrNull { it.id == currentStationId } ?: return
        sourceRegion = station.region
        sourceCity = station.city
        sourceType = station.type
    }

internal fun MainActivity.showSourceSheetRecreate() {
        val dialog = onlineDialog ?: return
        dialog.window?.decorView?.findViewWithTag<LinearLayout>("sheetBody")
            ?.let { populateSourceSheet(it, true) }
    }

    /** 筛选只替换当前独立弹窗内容，不销毁 Dialog，避免整窗闪动。 */

internal fun MainActivity.populateSourceSheet(body: LinearLayout, online: Boolean) {
        body.removeAllViews()
        if (!online) {
            val filters = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            filters.addView(filterScroll(t("region"), RadioCatalog.regions, sourceRegion) {
                sourceRegion = it
                sourceCity = "全部"
                populateSourceSheet(fmDialog?.window?.decorView?.findViewWithTag("sheetBody") ?: return@filterScroll, false)
            })
            if (sourceRegion != "全部" && sourceRegion != "全国") {
                    val cities = listOf(t("all")) + RadioCatalog.filter(false, sourceRegion)
                    .map { it.city }.distinct()
                if (cities.size > 1) filters.addView(filterScroll(t("city"), cities, sourceCity) {
                    sourceCity = it
                    refreshStationFilter()
                })
            }
            filters.addView(filterScroll(t("genre"), RadioCatalog.types, sourceType) {
                sourceType = it
                refreshStationFilter()
            })
            body.addView(filters)
            addStationBrowser(body, filters)
        } else {
            val providers = listOf(t("online.aggregate") to 0, "网易云" to 2, "B站" to 6, "QQ" to 4, "酷狗" to 3, "酷我" to 1, "Jamendo" to 5)
            body.addView(tabRow(providers.map { it.first }, providers.indexOfFirst { it.second == onlineProvider }) {
                onlineProvider = providers[it].second
                onlinePage = 1
                onlineQuery = ""
                showSourceSheetRecreate()
            })
            onlineBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            body.addView(onlineBody, LinearLayout.LayoutParams(-1, 0, 1f))
            refreshOnlineBody()
            loadOnlineTracks(false)
        }
    }

    /** 所有页签单行等宽铺满，文字缩小后仍保持完整可点区域。 */

internal fun MainActivity.tabRow(labels: List<String>, selected: Int, compact: Boolean = false,
        action: (Int) -> Unit): LinearLayout =
        ThemeUi.row(this).apply {
            val padding = if (compact) 2f else 5f
            setPadding(0, ThemeUi.dp(this@tabRow, padding), 0, ThemeUi.dp(this@tabRow, padding))
            background = ThemeUi.bgDrawable(this@tabRow, palette.control, 10f)
            labels.forEachIndexed { i, label ->
                addView(ThemeUi.button(this@tabRow, palette, label,
                    if (i == selected) ThemeUi.ROLE_SURFACE else ThemeUi.ROLE_TEXT) {
                        // 点击后同步整排按钮的角色与颜色，不能只切内容而保留首项高亮。
                        for (index in 0 until childCount) {
                            (getChildAt(index) as? Button)?.let { button ->
                                button.tag = if (index == i) ThemeUi.ROLE_SURFACE else ThemeUi.ROLE_TEXT
                                button.setTextColor(if (index == i) palette.accent else palette.sub)
                                ThemeUi.applyButton(button, palette)
                            }
                        }
                        action(i)
                    }.apply {
                    if (i == selected) setTextColor(palette.accent) else setTextColor(palette.sub)
                    if (compact || labels.size > 4) {
                        textSize = if (compact) 12f else 10f
                        minHeight = 0
                        minimumHeight = 0
                        setPadding(0, 0, 0, 0)
                    }
                    layoutParams = LinearLayout.LayoutParams(0,
                        ThemeUi.dp(this@tabRow, if (compact) 28f else 36f), 1f)
                })
            }
        }

internal fun MainActivity.filterScroll(label: String, values: List<String>, selected: String, action: (String) -> Unit): LinearLayout =
        ThemeUi.row(this).apply {
            setPadding(0, ThemeUi.dp(this@filterScroll, 5f), 0, ThemeUi.dp(this@filterScroll, 3f))
            addView(ThemeUi.text(this@filterScroll, label, 11f, palette, role = ThemeUi.ROLE_SUB))
            val row = ThemeUi.row(this@filterScroll)
            values.forEach { value ->
                row.addView(ThemeUi.button(this@filterScroll, palette, value,
                    if (value == selected) ThemeUi.ROLE_ACCENT_FILL else ThemeUi.ROLE_CONTROL) {
                    for (index in 0 until row.childCount) {
                        (row.getChildAt(index) as? Button)?.let { button ->
                            val active = button.text.toString() == value
                            button.tag = if (active) ThemeUi.ROLE_ACCENT_FILL else ThemeUi.ROLE_CONTROL
                            button.isSelected = active
                            ThemeUi.applyButton(button, palette)
                        }
                    }
                    action(value)
                }.apply { isSelected = value == selected })
            }
            addView(HorizontalScrollView(this@filterScroll).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

internal fun MainActivity.addStationBrowser(parent: LinearLayout, filters: LinearLayout) {
        val stations = RadioCatalog.filter(false, sourceRegion, sourceCity, sourceType)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stationListBody = body
        val search = ThemeUi.input(this, palette, t("fm.search.hint")).apply {
            tag = "stationSearch"
            setOnEditorActionListener { _, _, _ ->
                renderStations(body, RadioCatalog.filter(false, sourceRegion, sourceCity, sourceType, text.toString()))
                true
            }
        }
        parent.addView(search)
        ++sourceSearchSeq
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString().orEmpty()
                val seq = ++sourceSearchSeq
                main.postDelayed({
                    if (seq == sourceSearchSeq && fmDialog?.isShowing == true) {
                        renderStations(body, RadioCatalog.filter(false, sourceRegion, sourceCity, sourceType, q))
                    }
                }, 160)
            }
        })
        // 电台目录占据剩余空间，自定义流与录音固定底部，不再被目录推到屏外。
        parent.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        renderStations(body, stations)
        val footer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val customActive = isLive && currentStationId == "custom"
        val toggle = ThemeUi.text(this, t("fm.custom"), 11f, palette, role = ThemeUi.ROLE_SUB).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ThemeUi.dp(this@addStationBrowser, 6f), 0, ThemeUi.dp(this@addStationBrowser, 6f))
            setOnClickListener {
                val open = details.visibility != View.VISIBLE
                details.visibility = if (open) View.VISIBLE else View.GONE
                text = if (open) t("fm.custom").replace("▸", "▾") else t("fm.custom")
            }
        }
        if (customActive) toggle.text = t("fm.custom").replace("▸", "▾")
        val input = ThemeUi.input(this, palette, t("fm.url.hint")).apply {
            setText(preferences.getString("customRadio", ""))
            setOnEditorActionListener { _, _, _ -> playCustom(text.toString()); true }
        }
        details.addView(ThemeUi.row(this).apply {
            addView(input, LinearLayout.LayoutParams(0, ThemeUi.dp(this@addStationBrowser, 42f), 1f))
            addView(ThemeUi.button(this@addStationBrowser, palette, t("play"), ThemeUi.ROLE_ACCENT_FILL) {
                playCustom(input.text.toString())
            })
        })
        details.addView(ThemeUi.button(this, palette,
            if (recordingKind == "mic") "● 停止麦克风录音" else "● 麦克风录音") {
            if (recordingKind == "mic") sourceStatusView?.let(::finishMicRecording)
            else requestMic.launch(Manifest.permission.RECORD_AUDIO)
        })
        details.addView(ThemeUi.text(this, t("fm.recording.note"),
            11f, palette, role = ThemeUi.ROLE_SUB))
        footer.addView(toggle)
        footer.addView(details)
        details.visibility = if (customActive) View.VISIBLE else View.GONE
        filters.addView(footer)
    }

internal fun MainActivity.refreshStationFilter() {
        val body = stationListBody ?: return
        val query = fmDialog?.window?.decorView?.findViewWithTag<android.widget.EditText>("stationSearch")?.text?.toString().orEmpty()
        ++sourceSearchSeq
        body.post { renderStations(body, RadioCatalog.filter(false, sourceRegion, sourceCity, sourceType, query)) }
    }

internal fun MainActivity.renderStations(parent: LinearLayout, stations: List<RadioStation>) {
        parent.removeAllViews()
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var activeRow: View? = null
        stations.forEach { station ->
            val active = currentStationId == station.id && isLive
            rows.addView(ThemeUi.row(this).apply {
                setPadding(ThemeUi.dp(this@renderStations, 8f), ThemeUi.dp(this@renderStations, 7f),
                    ThemeUi.dp(this@renderStations, 8f), ThemeUi.dp(this@renderStations, 7f))
                background = ThemeUi.bgDrawable(this@renderStations, if (active) palette.surfaceAlt else palette.surface, 8f)
                addView(LinearLayout(this@renderStations).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(ThemeUi.text(this@renderStations, "${station.name} · ${station.level}", 13f, palette,
                        bold = true, role = if (active) ThemeUi.ROLE_ACCENT else ThemeUi.ROLE_TEXT))
                    addView(ThemeUi.text(this@renderStations,
                        "${station.region}${if (station.city == station.region) "" else " · ${station.city}"} · ${station.type} · ${station.sources.size} 路源",
                        10f, palette, role = ThemeUi.ROLE_SUB))
                    addView(ThemeUi.text(this@renderStations, station.note, 10f, palette, role = ThemeUi.ROLE_FAINT))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ThemeUi.text(this@renderStations, if (active) "LIVE" else "${t("play")} ▸", 12f, palette, role = ThemeUi.ROLE_ACCENT))
                setOnClickListener { playRadio(station); dismissSource() }
                if (active) activeRow = this
            })
        }
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            addView(rows)
        }
        parent.addView(scrollView, LinearLayout.LayoutParams(-1, -1))
        activeRow?.let { row -> scrollView.post { scrollView.scrollTo(0, row.top) } }
    }

internal fun MainActivity.refreshOnlineBody() {
        val body = onlineBody ?: return
        body.removeAllViews()
        body.addView(ThemeUi.row(this).apply {
            val authenticated = onlineProvider > 0 && onlineMusic.loggedIn(onlineProvider)
            addView(ThemeUi.text(this@refreshOnlineBody, t(if (authenticated) "online.logged.in" else "online.logged.out"),
                11f, palette, role = ThemeUi.ROLE_SUB), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ThemeUi.button(this@refreshOnlineBody, palette, when {
                onlineProvider == 5 -> "Client ID"
                authenticated -> t("online.logout")
                onlineProvider == 4 -> "登录 / 还原"
                else -> t("online.login")
            }) {
                if (onlineProvider == 5) showJamendoConfig()
                else
                if (authenticated) { onlineMusic.logout(onlineProvider); refreshOnlineBody() }
                else showOnlineAuthentication()
            })
        })
        val input = ThemeUi.input(this, palette, t("online.search.hint")).apply { setText(onlineQuery) }
        body.addView(ThemeUi.row(this).apply {
            addView(input, LinearLayout.LayoutParams(0, ThemeUi.dp(this@refreshOnlineBody, 42f), 1f))
            addView(ThemeUi.button(this@refreshOnlineBody, palette, t("search"), ThemeUi.ROLE_ACCENT_FILL) {
                onlineQuery = input.text.toString().trim()
                onlinePage = 1
                loadOnlineTracks(false)
            })
        })
        onlineStatusView = ThemeUi.text(this, if (onlineProvider == 0) "聚合各平台可公开获取的曲目" else "平台公开搜索；仅播放当前账号有权访问的内容",
            11f, palette, role = ThemeUi.ROLE_SUB).also(body::addView)
        onlineStatusView?.maxLines = 3
        onlineStatusView?.ellipsize = android.text.TextUtils.TruncateAt.END
        val tracks = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        onlineTracks.forEach { track ->
            tracks.addView(ThemeUi.row(this).apply {
                setPadding(0, ThemeUi.dp(this@refreshOnlineBody, 8f), 0, ThemeUi.dp(this@refreshOnlineBody, 8f))
                addView(LinearLayout(this@refreshOnlineBody).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(ThemeUi.row(this@refreshOnlineBody).apply {
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        if (track.provider == "哔哩哔哩" && onlineMusic.biliCached(track)) addView(ThemeUi.text(this@refreshOnlineBody,
                            "已缓存", 10f, palette, role = ThemeUi.ROLE_ACCENT).apply {
                            background = ThemeUi.bgDrawable(this@refreshOnlineBody, palette.control, 6f)
                            setPadding(ThemeUi.dp(this@refreshOnlineBody, 4f), ThemeUi.dp(this@refreshOnlineBody, 1f),
                                ThemeUi.dp(this@refreshOnlineBody, 4f), ThemeUi.dp(this@refreshOnlineBody, 1f))
                            (layoutParams as? LinearLayout.LayoutParams)?.rightMargin = ThemeUi.dp(this@refreshOnlineBody, 6f)
                        })
                        if (track.provider == "哔哩哔哩") addView(ThemeUi.text(this@refreshOnlineBody,
                            if (track.compilation) "合集" else "单曲", 10f, palette,
                            role = if (track.compilation) ThemeUi.ROLE_ACCENT else ThemeUi.ROLE_SUB).apply {
                            background = ThemeUi.bgDrawable(this@refreshOnlineBody, palette.control, 6f)
                            setPadding(ThemeUi.dp(this@refreshOnlineBody, 4f), ThemeUi.dp(this@refreshOnlineBody, 1f),
                                ThemeUi.dp(this@refreshOnlineBody, 4f), ThemeUi.dp(this@refreshOnlineBody, 1f))
                            val lp = layoutParams as? LinearLayout.LayoutParams
                            lp?.rightMargin = ThemeUi.dp(this@refreshOnlineBody, 6f)
                        })
                        addView(ThemeUi.text(this@refreshOnlineBody, track.title, 13f, palette, bold = true),
                            LinearLayout.LayoutParams(0, -2, 1f))
                    })
                    addView(ThemeUi.text(this@refreshOnlineBody,
                        when {
                            track.provider != "哔哩哔哩" -> "${track.artist} · ${track.provider}"
                            track.compilation -> "${track.artist} · ${track.provider} · ${track.partCount} 首分 P · 可展开"
                            else -> "${track.artist} · ${track.provider} · ${formatTrackDuration(track.duration)}"
                        }, 11f, palette, role = ThemeUi.ROLE_SUB))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ThemeUi.text(this@refreshOnlineBody,
                    if (track.provider == "哔哩哔哩" && track.compilation) "展开曲目 › " else t("online.preview"),
                    12f, palette, role = ThemeUi.ROLE_ACCENT))
                setOnClickListener {
                    if (track.provider == "哔哩哔哩") expandBiliTrack(track) else resolveOnline(track)
                }
            })
        }
        if (onlineTracks.isNotEmpty()) tracks.addView(ThemeUi.button(this, palette, "加载更多") {
            onlinePage++
            loadOnlineTracks(true)
        })
        body.addView(ScrollView(this).apply { addView(tracks) }, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(ThemeUi.text(this, t("online.footer"),
            10f, palette, role = ThemeUi.ROLE_FAINT))
    }

internal fun MainActivity.openQqLogin() {
        onlineStatusView?.text = "首次使用请选择登录文件保存目录，再在微信官方页面完成授权"
        onlineMusic.login { result ->
            result.onSuccess { if (onlineDialog?.isShowing == true) { refreshOnlineBody(); loadOnlineTracks(false) } }
                .onFailure { if (onlineDialog?.isShowing == true)
                    onlineStatusView?.text = it.message ?: "登录失败" }
        }
    }

internal fun MainActivity.importQqCookieFile() {
        onlineStatusView?.text = "请选择之前备份的 qq_cookie.txt…"
        onlineMusic.importCookieFile { result ->
            result.onSuccess {
                if (onlineDialog?.isShowing == true) {
                    refreshOnlineBody()
                    loadOnlineTracks(false)
                    toast("QQ 音乐登录已还原")
                }
            }.onFailure {
                if (onlineDialog?.isShowing == true) onlineStatusView?.text = it.message ?: "登录文件还原失败"
            }
        }
    }

internal fun MainActivity.showQqCookieInput() {
        lateinit var dialog: Dialog
        dialog = ThemeUi.showSheet(this, palette, "手动输入 QQ Cookie",
            "粘贴完整 Cookie；仅保存在本机和你选择的备份文件中") {
            val input = ThemeUi.input(this@showQqCookieInput, palette, "qqmusic_key=...; wxuin=...").apply {
                minLines = 4
                gravity = android.view.Gravity.TOP
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            addView(input, LinearLayout.LayoutParams(-1, ThemeUi.dp(this@showQqCookieInput, 104f)))
            addView(ThemeUi.text(this@showQqCookieInput, "必须包含 qqmusic_key，以及 wxuin 或 uin。",
                10f, palette, role = ThemeUi.ROLE_FAINT))
            addView(ThemeUi.button(this@showQqCookieInput, palette, "保存并还原", ThemeUi.ROLE_ACCENT_FILL) {
                onlineStatusView?.text = "正在保存 Cookie…"
                onlineMusic.saveCookieText(input.text.toString()) { result ->
                    result.onSuccess {
                        dialog.dismiss()
                        if (onlineDialog?.isShowing == true) {
                            refreshOnlineBody()
                            loadOnlineTracks(false)
                            toast("QQ 音乐登录已还原")
                        }
                    }.onFailure {
                        toast(it.message ?: "Cookie 保存失败")
                        if (onlineDialog?.isShowing == true) onlineStatusView?.text = it.message ?: "Cookie 保存失败"
                    }
                }
            })
        }
        dialog.show()
    }

internal fun MainActivity.showQqAuthentication() {
        lateinit var dialog: Dialog
        dialog = ThemeUi.showSheet(this, palette, "QQ 音乐登录",
            "可微信扫码授权，也可从备份文件或手动 Cookie 还原") {
            addView(ThemeUi.button(this@showQqAuthentication, palette, "微信扫码登录", ThemeUi.ROLE_ACCENT_FILL) {
                dialog.dismiss()
                openQqLogin()
            })
            addView(ThemeUi.button(this@showQqAuthentication, palette, "选择登录文件还原") {
                dialog.dismiss()
                importQqCookieFile()
            })
            addView(ThemeUi.button(this@showQqAuthentication, palette, "手动输入 Cookie") {
                dialog.dismiss()
                showQqCookieInput()
            })
            addView(ThemeUi.text(this@showQqAuthentication,
                "登录信息默认保存到 Download/8d/login/qq_cookie.txt，启动时自动恢复，也可选择登录文件还原；Cookie 不会上传到本应用服务器。",
                10f, palette, role = ThemeUi.ROLE_FAINT))
        }
        dialog.show()
    }

    /** 所有来源共用一个认证入口；会话按平台隔离，手机号和验证码不落盘。 */

internal fun MainActivity.showOnlineAuthentication() {
        val provider = onlineProvider
        if (provider == 0 || provider !in 1..6) {
            onlineStatusView?.text = "请先选择酷我、网易云、酷狗或 QQ，再管理对应账号"
            return
        }
        if (provider == 4) { showQqAuthentication(); return }
        showProviderAuthentication(provider)
    }

internal fun MainActivity.providerDisplayName(provider: Int) = when (provider) {
        1 -> "酷我"; 2 -> "网易云"; 3 -> "酷狗"; 6 -> "哔哩哔哩"; else -> "该平台"
    }

    /** 网易云、酷狗、酷我、哔哩哔哩统一认证入口；登录文件固定保存到 Download/8d/login。 */

internal fun MainActivity.showProviderAuthentication(provider: Int) {
        val name = providerDisplayName(provider)
        lateinit var dialog: Dialog
        dialog = ThemeUi.showSheet(this, palette, "$name 账号登录",
            "登录信息默认保存到 Download/8d/login，启动时自动恢复") {
            addView(ThemeUi.button(this@showProviderAuthentication, palette, "官方页登录（扫码 / 手机号）", ThemeUi.ROLE_ACCENT_FILL) {
                dialog.dismiss()
                onlineStatusView?.text = "正在打开$name 官方登录页…"
                onlineMusic.providerLogin(provider) { result ->
                    result.onSuccess {
                        if (onlineDialog?.isShowing == true) { refreshOnlineBody(); loadOnlineTracks(false) }
                        toast("$name 已登录")
                    }.onFailure {
                        if (onlineDialog?.isShowing == true)
                            onlineStatusView?.text = it.message ?: "登录失败"
                    }
                }
            })
            if (provider == 2) addView(ThemeUi.button(this@showProviderAuthentication, palette, "手机号短信登录") {
                dialog.dismiss(); showNeteaseSmsLogin()
            })
            addView(ThemeUi.button(this@showProviderAuthentication, palette, "选择登录文件还原") {
                dialog.dismiss()
                onlineStatusView?.text = "请选择 $name 的登录文件…"
                onlineMusic.importCookieFile(provider) { result ->
                    result.onSuccess {
                        if (onlineDialog?.isShowing == true) { refreshOnlineBody(); loadOnlineTracks(false) }
                        toast("$name 登录已还原")
                    }.onFailure {
                        if (onlineDialog?.isShowing == true)
                            onlineStatusView?.text = it.message ?: "登录文件还原失败"
                    }
                }
            })
            addView(ThemeUi.button(this@showProviderAuthentication, palette, "手动输入 Cookie") {
                dialog.dismiss(); showProviderCookieInput(provider)
            })
            addView(ThemeUi.text(this@showProviderAuthentication,
                "启动时自动扫描 Download/8d/login；失效或退出登录才会删除对应文件，Cookie 不会上传到本应用服务器。",
                10f, palette, role = ThemeUi.ROLE_FAINT))
        }
        dialog.show()
    }

internal fun MainActivity.showNeteaseSmsLogin() {
        val provider = 2
        lateinit var dialog: Dialog
        dialog = ThemeUi.showSheet(this, palette, "网易云手机号登录", "验证码由网易云官方发送；仅保存登录会话") {
            val phone = ThemeUi.input(this@showNeteaseSmsLogin, palette, "手机号").apply {
                inputType = android.text.InputType.TYPE_CLASS_PHONE
            }
            val code = ThemeUi.input(this@showNeteaseSmsLogin, palette, "短信验证码").apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
            addView(phone)
            addView(ThemeUi.row(this@showNeteaseSmsLogin).apply {
                addView(code, LinearLayout.LayoutParams(0, ThemeUi.dp(this@showNeteaseSmsLogin, 42f), 1f))
                addView(ThemeUi.button(this@showNeteaseSmsLogin, palette, "发送验证码") {
                    onlineStatusView?.text = "正在发送验证码…"
                    onlineMusic.sendPhoneCode(provider, phone.text.toString()) { result ->
                        result.onSuccess { toast("验证码已发送") }
                            .onFailure { toast(it.message ?: "验证码发送失败") }
                    }
                })
            })
            addView(ThemeUi.button(this@showNeteaseSmsLogin, palette, "登录", ThemeUi.ROLE_ACCENT_FILL) {
                onlineMusic.phoneLogin(provider, phone.text.toString(), code.text.toString()) { result ->
                    result.onSuccess {
                        dialog.dismiss(); refreshOnlineBody(); toast("网易云已登录")
                    }.onFailure { toast(it.message ?: "登录失败") }
                }
            })
        }
        dialog.show()
    }

internal fun MainActivity.showProviderCookieInput(provider: Int) {
        val name = providerDisplayName(provider)
        lateinit var dialog: Dialog
        dialog = ThemeUi.showSheet(this, palette, "手动输入 $name Cookie",
            "粘贴完整 Cookie；保存到 Download/8d/login 并自动还原") {
            val input = ThemeUi.input(this@showProviderCookieInput, palette, "完整 Cookie").apply {
                minLines = 4
                gravity = android.view.Gravity.TOP
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            addView(input, LinearLayout.LayoutParams(-1, ThemeUi.dp(this@showProviderCookieInput, 104f)))
            addView(ThemeUi.text(this@showProviderCookieInput, onlineMusic.cookieMarkerHint(provider),
                10f, palette, role = ThemeUi.ROLE_FAINT))
            addView(ThemeUi.button(this@showProviderCookieInput, palette, "保存并还原", ThemeUi.ROLE_ACCENT_FILL) {
                onlineStatusView?.text = "正在保存 Cookie…"
                onlineMusic.saveCookieText(provider, input.text.toString()) { result ->
                    result.onSuccess {
                        dialog.dismiss()
                        if (onlineDialog?.isShowing == true) { refreshOnlineBody(); loadOnlineTracks(false) }
                        toast("$name 登录已还原")
                    }.onFailure {
                        toast(it.message ?: "Cookie 保存失败")
                        if (onlineDialog?.isShowing == true) onlineStatusView?.text = it.message ?: "Cookie 保存失败"
                    }
                }
            })
        }
        dialog.show()
    }

internal fun MainActivity.showJamendoConfig() {
        lateinit var dialog: Dialog
        dialog = ThemeUi.showSheet(this, palette, "Jamendo 官方音源", "使用你自己的 client_id；不保存 client_secret") {
            val input = ThemeUi.input(this@showJamendoConfig, palette, "client_id").apply {
                setText(onlineMusic.jamendoClientId())
            }
            addView(input)
            addView(ThemeUi.button(this@showJamendoConfig, palette, "保存并检索", ThemeUi.ROLE_ACCENT_FILL) {
                onlineMusic.saveJamendoClientId(input.text.toString())
                dialog.dismiss(); refreshOnlineBody(); loadOnlineTracks(false)
            })
        }
        dialog.show()
    }

internal fun MainActivity.loadOnlineTracks(append: Boolean) {
        val seq = ++onlineSearchSeq
        onlineStatusView?.text = t("online.searching")
        if (onlineDialog?.isShowing != true) return
        val callback: (Result<List<OnlineTrack>>) -> Unit = { result ->
            if (seq == onlineSearchSeq && onlineDialog?.isShowing == true) {
                result.onSuccess {
                    onlineTracks = if (append) onlineTracks + it else it
                    refreshOnlineBody()
                    onlineStatusView?.text = if (onlineTracks.isEmpty()) t("online.no.tracks") else String.format(t("online.tracks"), onlineTracks.size)
                }.onFailure {
                    onlineStatusView?.text = it.message ?: t("online.unavailable")
                }
            }
        }
        if (onlineQuery.isEmpty() && !append) onlineMusic.hot(onlineProvider, callback)
        else onlineMusic.search(onlineProvider, onlineQuery, onlinePage, callback)
    }

internal fun MainActivity.resolveOnline(track: OnlineTrack) {
        onlineStatusView?.text =
            if (track.provider == "哔哩哔哩") "正在缓存音频《${track.title}》，首次需要下载整首，请稍候…"
            else String.format(t("online.address"), track.title)
        android.util.Log.i("8DBili", "resolve start ${track.id}")
        onlineMusic.resolve(track) { result ->
            result.onSuccess { playOnline(track, it) }
                .onFailure {
                    android.util.Log.w("8DBili", "resolve failed: ${it.message}", it)
                    toast(it.message ?: t("online.address.failed"))
                    if (onlineDialog?.isShowing == true)
                        onlineStatusView?.text = it.message ?: t("online.address.failed")
                }
        }
    }

    /** 哔哩哔哩合集视频展开分 P；单 P 直接进入音频缓存与播放。 */

internal fun MainActivity.expandBiliTrack(track: OnlineTrack) {
        onlineStatusView?.text = "正在读取《${track.title}》的曲目列表…"
        onlineMusic.biliParts(track) { result ->
            result.onSuccess { parts ->
                if (parts.size == 1) {
                    if (track.compilation) {
                        onlineTracks = onlineTracks.map { if (it.id == track.id) it.copy(compilation = false, partCount = 1) else it }
                        refreshOnlineBody()
                    }
                    resolveOnline(parts[0])
                } else {
                    if (!track.compilation || track.partCount != parts.size) {
                        onlineTracks = onlineTracks.map {
                            if (it.id == track.id) it.copy(compilation = true, partCount = parts.size) else it
                        }
                        refreshOnlineBody()
                    }
                    onlineStatusView?.text = "合集共 ${parts.size} 首，请选择曲目"
                    showBiliParts(track, parts)
                }
            }.onFailure {
                if (onlineDialog?.isShowing == true)
                    onlineStatusView?.text = it.message ?: "曲目列表读取失败"
            }
        }
    }

internal fun MainActivity.showBiliParts(parent: OnlineTrack, parts: List<OnlineTrack>) {
        lateinit var sheet: Dialog
        lateinit var status: TextView
        sheet = ThemeUi.showSheet(this, palette, parent.title.take(24), "共 ${parts.size} 首") {
            val rows = LinearLayout(this@showBiliParts).apply { orientation = LinearLayout.VERTICAL }
            status = ThemeUi.text(this@showBiliParts, "", 11f, palette, role = ThemeUi.ROLE_SUB)
            parts.forEachIndexed { index, part ->
                rows.addView(ThemeUi.row(this@showBiliParts).apply {
                    setPadding(0, ThemeUi.dp(this@showBiliParts, 6f), 0, ThemeUi.dp(this@showBiliParts, 6f))
                    addView(LinearLayout(this@showBiliParts).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(ThemeUi.text(this@showBiliParts,
                            "${index + 1}. ${part.title}${if (onlineMusic.biliCached(part)) " · 已缓存" else ""}",
                            13f, palette, bold = true))
                        addView(ThemeUi.text(this@showBiliParts, formatTrackDuration(part.duration),
                            11f, palette, role = ThemeUi.ROLE_SUB))
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(ThemeUi.text(this@showBiliParts, t("online.preview"), 12f, palette, role = ThemeUi.ROLE_ACCENT))
                    setOnClickListener {
                        status.text = "正在缓存《${part.title}》，首次需下载整首音频，请稍候…"
                        isEnabled = false
                        onlineMusic.resolve(part) { result ->
                            result.onSuccess { sheet.dismiss(); playOnline(part, it) }
                                .onFailure { e ->
                                    android.util.Log.w("8DBili", "part resolve failed: ${e.message}", e)
                                    isEnabled = true
                                    status.text = "《${part.title}》播放失败：${e.message ?: "未知错误"}"
                                    toast(e.message ?: t("online.address.failed"))
                                }
                        }
                    }
                })
            }
            addView(ScrollView(this@showBiliParts).apply { addView(rows) }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(status)
        }
        sheet.show()
    }

internal fun MainActivity.formatTrackDuration(seconds: Double): String {
        val total = seconds.toLong()
        val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

internal fun MainActivity.playOnline(track: OnlineTrack, url: String) {
        // 哔哩哔哩只保存音频文件：下载完成后登记进本地曲库，离线也可播放，不保留视频数据。
        val localFile = if (url.startsWith("file://")) java.io.File(url.removePrefix("file://")) else null
        val item = MusicItem((track.provider + track.id).hashCode().toLong(), "${track.title} · ${track.artist}",
            url, size = localFile?.length() ?: 0L,
            duration = (track.duration * 1000).toLong(), artist = track.artist,
            album = if (track.provider == "哔哩哔哩") "B站音频" else "未知专辑")
        if ((localFile != null || (track.provider == "哔哩哔哩" && url.startsWith("content://media/"))) &&
            musicStore.cached().none { it.uri == item.uri }) {
            musicStore.remember(item)
            val merged = (musicStore.cached() + listOf(item)).distinctBy { it.uri }
            musicStore.saveLibrary(merged)
            songs = merged.toMutableList()
        }
        dismissSource()
        load(item)
    }

internal fun MainActivity.dismissSource() { fmDialog?.dismiss(); onlineDialog?.dismiss() }

internal fun MainActivity.playCustom(raw: String) {
        val url = raw.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast("请输入以 http:// 或 https:// 开头的直播流地址")
            return
        }
        preferences.edit().putString("customRadio", url).apply()
        playRadio(RadioStation("custom", "自定义电台", "自定义", "自定义", "网络台", "自定义", "手动输入的公开直播流",
            listOf(j5i.spatial8d.core.RadioSource("自定义流", url, "自动识别"))))
        dismissSource()
    }

internal fun MainActivity.playRadio(station: RadioStation) {
        if (recordingKind.isNotEmpty()) { toast("请先停止当前录音，再切换音源"); return }
        if (isViewReady(this@playRadio::visualizerView)) visualizerView.pcmClock.reset()
        ++loadGeneration
        radioProbes.forEach { it.stop() }
        radioProbes = emptyList()
        ++lyricsGeneration
        currentLyrics = null
        if (isViewReady(this@playRadio::lyricsView)) lyricsView.clear()
        pushLyricsToVisualizer()
        service?.stopLocal()
        currentItem = MusicItem(-1, station.name, "")
        currentUri = null
        isLive = true
        val generation = ++radioGeneration
        currentStationId = station.id
        // 蜻蜓 MP3 的 MediaExtractor 会持续探测十几秒；同源 AAC 已可直接逐帧解码。
        radioSources = station.sources.sortedBy { if (it.quality.contains("HLS")) 0 else 1 }
        // 每个频道独立记住最近成功线路；下次先尝试它，失败仍顺序回退其它源。
        val preferredUrl = preferences.getString("radioSource:${station.id}", null)
        val preferred = radioSources.indexOfFirst { it.url == preferredUrl }.coerceAtLeast(0)
        stationIndex = RadioCatalog.filter(false).indexOfFirst { it.id == station.id }
        raceRadioSources(preferred, generation)
        dismissSource()
    }

    /** 同一频道多路并行探测；正式解码失败时仍回退其他源。 */

internal fun MainActivity.raceRadioSources(preferred: Int, generation: Int) {
        val allIndices = listOf(preferred) + radioSources.indices.filterNot { it == preferred }
        val indices = allIndices.take(3)
        playbackStatus = "并行连接 ${indices.size} 路音源…"
        var selected = false
        var failed = 0
        val probes = indices.map { index ->
            val source = radioSources[index]
            j5i.spatial8d.audio.RadioPlayer(probe = true).also { probe ->
                probe.preset = preset
                probe.semi = transform.semi.roundToInt()
                probe.onReady = { main.post {
                    if (generation != radioGeneration || selected) return@post
                    selected = true
                    radioSourceIndex = index
                    preferences.edit().putString("radioSource:$currentStationId", source.url).apply()
                    radioProbes.forEach { it.stop() }
                    radioProbes = emptyList()
                    radioSources = listOf(source) + radioSources.filterIndexed { i, _ -> i != index }
                    tryRadioSource(0, generation)
                } }
                probe.onError = { main.post {
                    if (generation == radioGeneration && !selected && ++failed == indices.size) {
                        selected = true
                        radioProbes.forEach { it.stop() }
                        radioProbes = emptyList()
                        radioSources = allIndices.drop(indices.size).map { radioSources[it] }
                        tryRadioSource(0, generation)
                    }
                } }
            }
        }
        radioProbes = probes
        requestPlaybackFocus()
        indices.forEachIndexed { i, index -> probes[i].load(radioSources[index].url) }
        refreshAll()
    }

    /** 单台内部按顺序回退；失败不跳到另一台，避免用户选择了 A 却听到 B。 */

internal fun MainActivity.tryRadioSource(index: Int, generation: Int = radioGeneration) {
        if (generation != radioGeneration) return
        if (index >= radioSources.size) {
            playbackStatus = "所有备用源均不可用"
            service?.radio?.stop()
            refreshAll()
            return
        }
        radioSourceIndex = index
        val source = radioSources[index]
        val name = currentItem?.name ?: "电台"
        playbackStatus = "连接 ${index + 1}/${radioSources.size} · ${source.label}"
        service?.radio?.preset = preset
        service?.radio?.denoise = transform.denoiseLevel
        service?.radio?.semi = transform.semi.roundToInt()
        service?.radio?.onReady = { main.post {
            if (generation == radioGeneration) preferences.edit().putString("radioSource:$currentStationId", source.url).apply()
            playbackStatus = "电台播放中 · $name · ${source.label}"
            refreshPlayback()
        } }
        service?.radio?.onError = { main.post { if (generation == radioGeneration) tryRadioSource(index + 1, generation) } }
        service?.radio?.onEnd = { main.post { if (generation == radioGeneration) tryRadioSource(index + 1, generation) } }
        service?.radio?.onReconnecting = { main.post {
            if (generation == radioGeneration) { playbackStatus = it; refreshPlayback() }
        } }
        requestPlaybackFocus()
        service?.radio?.load(source.url)
        refreshAll()
    }

internal fun MainActivity.skipStation(direction: Int) {
        val stations = RadioCatalog.filter(false)
        if (stations.isEmpty()) return
        val current = stations.indexOfFirst { it.id == currentStationId }
        playRadio(stations[(current + direction + stations.size) % stations.size])
    }
