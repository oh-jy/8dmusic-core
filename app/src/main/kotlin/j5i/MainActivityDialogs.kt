package j5i.spatial8d

import androidx.core.content.pm.PackageInfoCompat
import android.app.Dialog
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.RadioButton
import android.webkit.WebView
import android.webkit.WebViewClient
import android.content.Intent
import android.net.Uri
import android.content.res.ColorStateList
import j5i.spatial8d.core.PARAM_INFO
import j5i.spatial8d.ui.ThemeUi
import j5i.spatial8d.ui.AppLanguage
import j5i.spatial8d.ui.DrivingView

internal fun MainActivity.showSettingsSheet() {
        lateinit var dialog: Dialog
        fun settingRow(icon: String, title: String, value: String, action: () -> Unit) =
            ThemeUi.row(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = ThemeUi.bgDrawable(this@showSettingsSheet, palette.surfaceAlt, 8f)
                layoutParams = LinearLayout.LayoutParams(-1, ThemeUi.dp(this@showSettingsSheet, 52f)).apply {
                    topMargin = ThemeUi.dp(this@showSettingsSheet, 6f)
                }
                addView(ThemeUi.text(this@showSettingsSheet, icon, 17f, palette, role = ThemeUi.ROLE_ACCENT).apply {
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@showSettingsSheet, 40f), -1)
                })
                addView(ThemeUi.text(this@showSettingsSheet, title, 13f, palette, bold = true),
                    LinearLayout.LayoutParams(0, -2, 1f))
                addView(ThemeUi.text(this@showSettingsSheet, "$value  ›", 12f, palette, role = ThemeUi.ROLE_SUB).apply {
                    gravity = Gravity.CENTER_VERTICAL or Gravity.END
                    setPadding(0, 0, ThemeUi.dp(this@showSettingsSheet, 12f), 0)
                })
                setOnClickListener { action() }
            }
        dialog = ThemeUi.showSheet(this, palette, t("settings"), compactPicker = true) {
            addView(settingRow("☾", t("theme"), themeLabel()) {
                dialog.dismiss(); showThemePicker()
            })
            val colors = listOf("theme.blue", "theme.green", "theme.orange", "theme.neutral", "theme.rose", "theme.violet", "theme.slate").map { t(it) }
            addView(settingRow("●", t("theme.color"), colors[ThemeUi.colorTheme]) {
                dialog.dismiss()
                pickOption(t("theme.color"), colors, ThemeUi.colorTheme) { index ->
                    preferences.edit().putInt("colorTheme", index).apply()
                    ThemeUi.colorTheme = index
                    refreshTheme()
                }
            })
            addView(settingRow("A", t("language"), languageLabel()) {
                dialog.dismiss(); showLanguagePicker()
            })
            addView(settingRow("↑", "更新渠道", if (updateChannel() == "beta") "测试版" else "稳定版") {
                dialog.dismiss()
                pickOption("更新渠道", listOf("稳定版", "测试版"), if (updateChannel() == "beta") 1 else 0) { index ->
                    preferences.edit().putString("updateChannel", if (index == 1) "beta" else "stable").remove("updateLastCheck").apply()
                    checkAppUpdate()
                }
            })
        }
        dialog.show()
    }

internal fun MainActivity.languageLabel(): String = when (AppLanguage.current(this)) {
        AppLanguage.SIMPLIFIED -> t("language.simplified")
        AppLanguage.TRADITIONAL -> t("language.traditional")
        AppLanguage.ENGLISH -> t("language.english")
        else -> t("language.system")
    }

internal fun MainActivity.showThemePicker() {
        val values = listOf(0, 1, 2)
        val labels = listOf(t("theme.dark"), t("theme.light"), t("theme.system"))
        pickOption(t("theme"), labels, values.indexOf(themeMode)) { index ->
            themeMode = values[index]
            dark = resolveDark()
            refreshTheme()
        }
    }

internal fun MainActivity.showLanguagePicker() {
        val values = listOf(AppLanguage.SYSTEM, AppLanguage.SIMPLIFIED, AppLanguage.TRADITIONAL, AppLanguage.ENGLISH)
        val labels = listOf(t("language.system"), t("language.simplified"), t("language.traditional"), t("language.english"))
        pickOption(t("language"), labels, values.indexOf(AppLanguage.current(this))) {
            AppLanguage.select(this, values[it])
            recreate()
        }
    }

internal fun MainActivity.showDrivingMode() {
        if (drivingDialog?.isShowing == true) return
        (visualizerView.parent as? ViewGroup)?.removeView(visualizerView)
        visualizerView.drivingLyrics = true
        visualizerView.visibility = View.VISIBLE
        visualizerView.restoreVisualState(true to 3)
        val view = DrivingView(this, visualizerView).apply {
            onClose = { drivingDialog?.dismiss() }
            onPlayPause = { togglePlayback() }
            onPrevious = { service?.previous() }
            onNext = { service?.next() }
        }
        drivingView = view
        drivingDialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            setContentView(view)
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setOnDismissListener {
                drivingAutoDismissed = true
                (visualizerView.parent as? ViewGroup)?.removeView(visualizerView)
                curveContent.addView(visualizerView, FrameLayout.LayoutParams(-1, -1))
                visualizerView.drivingLyrics = false
                visualizerView.visibility = if (curvePanel == 1) View.VISIBLE else View.GONE
                drivingView = null
            }
            show()
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateDriving()
    }

internal fun MainActivity.updateDriving() {
        val playing = if (isLive) service?.radio?.isPlaying == true else service?.player?.isPlaying == true
        drivingView?.update(currentItem, currentLyrics,
            ((service?.player?.position ?: 0.0) * 1000).toLong(), playing)
    }

internal fun MainActivity.showMetadataEditor() {
        val item = currentItem ?: return
        if (isLive || item.uri.startsWith("http")) { toast(t("metadata.read.only")); return }
        lateinit var dialog: Dialog
        dialog = ThemeUi.showSheet(this, palette, t("metadata"), fullHeight = false) {
            val title = ThemeUi.input(this@showMetadataEditor, palette, t("metadata.title")).apply { setText(item.name.substringBeforeLast('.')) }
            val artist = ThemeUi.input(this@showMetadataEditor, palette, t("metadata.artist")).apply { setText(item.artist) }
            val album = ThemeUi.input(this@showMetadataEditor, palette, t("metadata.album")).apply { setText(item.album) }
            addView(title); addView(artist); addView(album)
            addView(ThemeUi.button(this@showMetadataEditor, palette, t("metadata.save"), ThemeUi.ROLE_ACCENT_FILL) {
                try {
                    val updated = musicStore.updateMetadata(item, title.text.toString(), artist.text.toString(), album.text.toString())
                    currentItem = updated
                    songs = songs.map { if (it.uri == updated.uri) updated else it }.toMutableList()
                    currentQueue = currentQueue.map { if (it.uri == updated.uri) updated else it }
                    dialog.dismiss()
                    refreshAll()
                    toast(t("metadata.saved"))
                } catch (e: Exception) { toast(e.message ?: t("metadata.read.only")) }
            })
        }
        dialog.show()
    }

internal fun MainActivity.unlockSource() {
        val now = SystemClock.elapsedRealtime()
        sourceTapCount = if (now - sourceTapAt <= 1200) sourceTapCount + 1 else 1
        sourceTapAt = now
        if (sourceTapCount >= 3) {
            if (sourceTapCount >= 5) {
                onlineUnlocked = true
                onlineButton.visibility = View.VISIBLE
                sourceTapCount = 0
            } else if (sourceTapCount == 3) {
                fmUnlocked = true
                fmButton.visibility = View.VISIBLE
            }
        }
    }

internal fun MainActivity.showExitDialog() {
        if (exitDialog?.isShowing == true) return
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ThemeUi.dp(this@showExitDialog, 18f), ThemeUi.dp(this@showExitDialog, 20f),
                ThemeUi.dp(this@showExitDialog, 18f), ThemeUi.dp(this@showExitDialog, 10f))
            background = ThemeUi.bgDrawable(this@showExitDialog, palette.surface, 14f)
            addView(ThemeUi.text(this@showExitDialog, "请确认", 18f, palette, bold = true))
            addView(ThemeUi.text(this@showExitDialog, "请确认，选择退出播放或转入后台", 12f, palette,
                role = ThemeUi.ROLE_SUB).apply { setPadding(0, ThemeUi.dp(this@showExitDialog, 8f), 0, ThemeUi.dp(this@showExitDialog, 16f)) })
            addView(ThemeUi.row(this@showExitDialog, gravity = Gravity.END).apply {
                addView(ThemeUi.text(this@showExitDialog, "后台", 13f, palette, role = ThemeUi.ROLE_ACCENT).apply {
                    setPadding(ThemeUi.dp(this@showExitDialog, 12f), ThemeUi.dp(this@showExitDialog, 10f),
                        ThemeUi.dp(this@showExitDialog, 12f), ThemeUi.dp(this@showExitDialog, 10f))
                    setOnClickListener { exitDialog?.dismiss(); moveTaskToBack(true) }
                })
                addView(ThemeUi.text(this@showExitDialog, "取消", 13f, palette, role = ThemeUi.ROLE_SUB).apply {
                    setPadding(ThemeUi.dp(this@showExitDialog, 12f), ThemeUi.dp(this@showExitDialog, 10f),
                        ThemeUi.dp(this@showExitDialog, 12f), ThemeUi.dp(this@showExitDialog, 10f))
                    setOnClickListener { exitDialog?.dismiss() }
                })
                addView(ThemeUi.text(this@showExitDialog, "退出", 13f, palette).apply {
                    setTextColor(0xffff7070.toInt())
                    setPadding(ThemeUi.dp(this@showExitDialog, 12f), ThemeUi.dp(this@showExitDialog, 10f),
                        ThemeUi.dp(this@showExitDialog, 12f), ThemeUi.dp(this@showExitDialog, 10f))
                    setOnClickListener {
                        exitDialog?.dismiss()
                        shutdownForExit()
                        finishAndRemoveTask()
                    }
                })
            })
        }
        exitDialog = Dialog(this).apply {
            setContentView(content)
            window?.setBackgroundDrawable(null)
            window?.setDimAmount(0.45f)
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setOnShowListener { window?.setLayout(ThemeUi.dp(this@showExitDialog, 292f), ViewGroup.LayoutParams.WRAP_CONTENT) }
        }
        exitDialog?.show()
    }

internal fun MainActivity.withUsageGuide(kind: String, action: () -> Unit) {
    if (preferences.getBoolean("guideConfirmed.$kind", false)) action()
    else showUsageGuide(kind, action)
}

internal fun MainActivity.showUsageGuide(kind: String, onConfirmed: (() -> Unit)? = null) {
    lateinit var dialog: Dialog
    dialog = ThemeUi.showSheet(this, palette, t("guide.$kind.title"), cancelLabel = t("guide.later")) {
        addView(ThemeUi.text(this@showUsageGuide, t("guide.$kind.body"), 13f, palette, role = ThemeUi.ROLE_SUB).apply {
            setPadding(0, ThemeUi.dp(this@showUsageGuide, 8f), 0, ThemeUi.dp(this@showUsageGuide, 8f))
        })
        addView(ThemeUi.button(this@showUsageGuide, palette, t("guide.confirm"), ThemeUi.ROLE_ACCENT_FILL) {
            preferences.edit().putBoolean("guideConfirmed.$kind", true).apply()
            dialog.dismiss()
            onConfirmed?.invoke()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(-1, ThemeUi.dp(this@showUsageGuide, 42f)).apply {
                topMargin = ThemeUi.dp(this@showUsageGuide, 10f)
            }
        })
    }
    dialog.show()
}

private val featureGuideSteps = listOf("music", "space", "visuals")

internal fun MainActivity.showPendingFeatureGuide() {
    val index = featureGuideSteps.indexOfFirst { !preferences.getBoolean("featureGuideConfirmed.$it", false) }
    if (index >= 0 && !isFinishing && !isDestroyed) showFeatureGuide(index)
}

internal fun MainActivity.showFeatureGuide(index: Int = 0) {
    val step = featureGuideSteps[index]
    lateinit var dialog: Dialog
    dialog = ThemeUi.showSheet(this, palette, t("featureGuide.$step.title"),
        subtitle = "${index + 1} / ${featureGuideSteps.size}", cancelLabel = t("guide.later")) {
        addView(ThemeUi.text(this@showFeatureGuide, t("featureGuide.$step.body"), 13f, palette, role = ThemeUi.ROLE_SUB).apply {
            setPadding(0, ThemeUi.dp(this@showFeatureGuide, 8f), 0, ThemeUi.dp(this@showFeatureGuide, 8f))
        })
        addView(ThemeUi.button(this@showFeatureGuide, palette, t("featureGuide.$step.action"), ThemeUi.ROLE_CONTROL) {
            preferences.edit().putBoolean("featureGuideConfirmed.$step", true).apply()
            dialog.dismiss()
            when (step) {
                "music" -> showPlaylist()
                "space" -> homePresetButton.performClick()
                "visuals" -> showVisualizerSheet()
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(-1, ThemeUi.dp(this@showFeatureGuide, 42f))
        })
        addView(ThemeUi.button(this@showFeatureGuide, palette,
            t(if (index == featureGuideSteps.lastIndex) "guide.confirm" else "featureGuide.next"), ThemeUi.ROLE_ACCENT_FILL) {
            preferences.edit().putBoolean("featureGuideConfirmed.$step", true).apply()
            dialog.dismiss()
            if (index < featureGuideSteps.lastIndex) showFeatureGuide(index + 1)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(-1, ThemeUi.dp(this@showFeatureGuide, 42f)).apply {
                topMargin = ThemeUi.dp(this@showFeatureGuide, 10f)
            }
        })
    }
    dialog.show()
}

internal fun MainActivity.showInfoSheet() {
        if (infoDialog?.isShowing == true) return
        val expandedSections = mutableSetOf(t("info.quick"))
        fun LinearLayout.section(title: String, content: String) {
            lateinit var desc: TextView
            addView(ThemeUi.text(this@showInfoSheet, title, 14f, palette, bold = true, role = ThemeUi.ROLE_ACCENT).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 0)
                background = null
                minHeight = 0
                minimumHeight = 0
                layoutParams = LinearLayout.LayoutParams(-1, ThemeUi.dp(this@showInfoSheet, 30f))
                setOnClickListener {
                    val opening = !expandedSections.remove(title)
                    if (opening) expandedSections += title
                    desc.visibility = if (opening) View.VISIBLE else View.GONE
                }
            })
            desc = ThemeUi.text(this@showInfoSheet, content, 12f, palette, role = ThemeUi.ROLE_SUB).apply {
                setPadding(ThemeUi.dp(this@showInfoSheet, 10f), ThemeUi.dp(this@showInfoSheet, 4f),
                    ThemeUi.dp(this@showInfoSheet, 10f), ThemeUi.dp(this@showInfoSheet, 6f))
                visibility = if (title in expandedSections) View.VISIBLE else View.GONE
            }
            addView(desc)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ThemeUi.dp(this@showInfoSheet, 16f), 0, ThemeUi.dp(this@showInfoSheet, 16f), ThemeUi.dp(this@showInfoSheet, 12f))
            ThemeUi.bg(this, palette.surface, 18f)
        }
        val header = ThemeUi.row(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, ThemeUi.dp(this@showInfoSheet, 48f))
            addView(ThemeUi.text(this@showInfoSheet, t("info"), 16f, palette, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ThemeUi.text(this@showInfoSheet, t("close"), 12f, palette, role = ThemeUi.ROLE_ACCENT).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@showInfoSheet, 56f), -1)
                setOnClickListener { infoDialog?.dismiss() }
            })
        }
        content.addView(header)
        content.addView(ThemeUi.divider(this, palette))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ThemeUi.text(this@showInfoSheet, t("info.app"), 16f, palette, bold = true).apply {
                setPadding(0, ThemeUi.dp(this@showInfoSheet, 6f), 0, ThemeUi.dp(this@showInfoSheet, 3f))
            })
            val version = packageManager.getPackageInfo(packageName, 0)
            val code = PackageInfoCompat.getLongVersionCode(version)
            addView(ThemeUi.text(this@showInfoSheet, "${t("app.name")}  v${version.versionName}  ·  ${formatReleaseTime(code)}",
                12f, palette, role = ThemeUi.ROLE_SUB).apply {
                setPadding(0, 0, 0, ThemeUi.dp(this@showInfoSheet, 4f))
            })
            addView(ThemeUi.button(this@showInfoSheet, palette, "检查更新") {
                infoDialog?.dismiss()
                checkAppUpdate()
            })
            addView(ThemeUi.row(this@showInfoSheet).apply {
                addView(ThemeUi.text(this@showInfoSheet, t("guide.restart"), 13f, palette, role = ThemeUi.ROLE_ACCENT).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = ThemeUi.dp(this@showInfoSheet, 48f)
                    setPadding(0, 0, ThemeUi.dp(this@showInfoSheet, 24f), 0)
                    setOnClickListener {
                        infoDialog?.dismiss()
                        showUsageGuide("notification") {
                            showUsageGuide("audio") {
                                showUsageGuide("microphone")
                            }
                        }
                    }
                }, LinearLayout.LayoutParams(-2, -2))
                addView(ThemeUi.text(this@showInfoSheet, t("featureGuide.restart"), 13f, palette, role = ThemeUi.ROLE_ACCENT).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = ThemeUi.dp(this@showInfoSheet, 48f)
                    setOnClickListener {
                        infoDialog?.dismiss()
                        showFeatureGuide()
                    }
                }, LinearLayout.LayoutParams(-2, -2))
            })
            addView(ThemeUi.text(this@showInfoSheet, "开源地址：github.com/oh-jy/8dmusic-core",
                12f, palette, role = ThemeUi.ROLE_ACCENT).apply {
                setPadding(0, 0, 0, ThemeUi.dp(this@showInfoSheet, 6f))
                setOnClickListener {
                    val uri = Uri.parse("https://github.com/oh-jy/8dmusic-core")
                    val opened = runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }.isSuccess
                    if (!opened) {
                        val web = WebView(this@showInfoSheet).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = WebViewClient()
                            loadUrl(uri.toString())
                        }
                        val dialog = Dialog(this@showInfoSheet)
                        dialog.setContentView(FrameLayout(this@showInfoSheet).apply {
                            addView(web, FrameLayout.LayoutParams(-1, -1))
                            addView(ThemeUi.text(this@showInfoSheet, "关闭", 13f, palette, role = ThemeUi.ROLE_ACCENT).apply {
                                gravity = Gravity.CENTER
                                setPadding(ThemeUi.dp(this@showInfoSheet, 14f), 0, ThemeUi.dp(this@showInfoSheet, 14f), 0)
                                setOnClickListener { dialog.dismiss() }
                            }, FrameLayout.LayoutParams(-2, ThemeUi.dp(this@showInfoSheet, 48f), Gravity.TOP or Gravity.END))
                        })
                        dialog.window?.setLayout(-1, -1)
                        dialog.setOnDismissListener { web.destroy() }
                        dialog.show()
                        dialog.window?.setLayout(-1, -1)
                    }
                }
            })
            addView(ThemeUi.divider(this@showInfoSheet, palette, 2f))
            listOf(
                t("info.quick") to t("info.quick.body"),
                t("info.lyrics") to t("info.lyrics.body"),
                t("info.visualizer") to t("info.visualizer.body"),
                t("info.export") to t("info.export.body"),
                t("info.privacy") to t("info.privacy.body")
            ).forEach { (title, content) -> section(title, content) }
            addView(ThemeUi.text(this@showInfoSheet, t("info.params"), 16f, palette, bold = true).apply {
                setPadding(0, ThemeUi.dp(this@showInfoSheet, 12f), 0, ThemeUi.dp(this@showInfoSheet, 3f))
            })
            addView(ThemeUi.divider(this@showInfoSheet, palette, 2f))
            PARAM_INFO.forEach { info ->
                section(t("slider.${info.key}"), t("param.${info.key}"))
            }
            addView(ThemeUi.text(this@showInfoSheet, t("info.legend"), 12f, palette, role = ThemeUi.ROLE_SUB).apply {
                setPadding(0, ThemeUi.dp(this@showInfoSheet, 6f), 0, ThemeUi.dp(this@showInfoSheet, 12f))
            })
        }
        val scroll = ScrollView(this).apply {
            tag = "infoScroll"
            contentDescription = "说明滚动区"
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(ThemeUi.dp(this@showInfoSheet, 12f))
            addView(body, ViewGroup.LayoutParams(-1, -2))
        }
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        infoDialog = Dialog(this).apply {
            setContentView(content, ViewGroup.LayoutParams(-1, -1))
            window?.setBackgroundDrawable(null)
            window?.setDimAmount(0.45f)
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.setGravity(Gravity.BOTTOM)
            show()
            ThemeUi.layoutSheet(this, true)
        }
    }

internal fun MainActivity.showExportSheet() {
        if (exportDialog?.isShowing == true) return
        if (exporting) { showExportProgress(); return }
        if (isLive) { toast("电台直播没有完整时间轴，请先录音再导出"); return }
        if (service?.player?.duration ?: 0.0 <= 0.0) { toast("请先加载音频"); return }
        var format = 0
        var landscape = true
        var resolution = 720
        var previewSeconds = 5
        val sheet = ThemeUi.showSheet(this, palette, "导出", subtitle = "选择格式与保存位置") {
            val formatRow = ThemeUi.row(this@showExportSheet)
            val wav = RadioButton(this@showExportSheet).apply { text = "WAV\n无损音质"; textSize = 11f; gravity = Gravity.CENTER; setPadding(0, 0, 0, 0) }
            val mp3Btn = RadioButton(this@showExportSheet).apply { text = "MP3\n192kbps"; textSize = 11f; gravity = Gravity.CENTER; setPadding(0, 0, 0, 0) }
            val mp4Btn = RadioButton(this@showExportSheet).apply { text = "MP4\n可视化"; textSize = 11f; gravity = Gravity.CENTER; setPadding(0, 0, 0, 0) }
            val gifBtn = RadioButton(this@showExportSheet).apply { text = "GIF\n无声动图"; textSize = 11f; gravity = Gravity.CENTER; setPadding(0, 0, 0, 0) }
            val orientationRow = ThemeUi.row(this@showExportSheet)
            val portrait = RadioButton(this@showExportSheet).apply { text = "竖屏"; textSize = 12f }
            val horizontal = RadioButton(this@showExportSheet).apply { text = "横屏"; textSize = 12f }
            val resolutionRow = ThemeUi.row(this@showExportSheet)
            val clipRow = ThemeUi.row(this@showExportSheet)
            val smoothGifLoop = android.widget.CheckBox(this@showExportSheet).apply {
                text = "尽量平滑 GIF 首尾循环"; setTextColor(palette.text)
                buttonTintList = ColorStateList.valueOf(palette.accent)
            }
            val creativeButton = ThemeUi.button(this@showExportSheet, palette, "画面创作设置", ThemeUi.ROLE_CONTROL) {
                showVisualCreative()
            }
            val copyrightHint = ThemeUi.text(this@showExportSheet, "分享前请确认音乐、歌词及图片的使用授权", 11f, palette, role = ThemeUi.ROLE_SUB)
            val clipStart = ThemeUi.input(this@showExportSheet, palette, "起始秒数（默认 0）").apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            }
            val clipDuration = ThemeUi.input(this@showExportSheet, palette, "时长秒数（留空为整曲）").apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            }
            fun clip(): Pair<Double, Double>? {
                val startText = clipStart.text.toString().trim()
                val durationText = clipDuration.text.toString().trim()
                val start = if (startText.isEmpty()) 0.0 else startText.toDoubleOrNull()
                val length = if (durationText.isEmpty()) 0.0 else durationText.toDoubleOrNull()
                if (start == null || length == null || !start.isFinite() || !length.isFinite() || start < 0 || length < 0 ||
                    start >= (service?.player?.duration ?: 0.0) / (service?.player?.transform?.speed ?: 1.0)) {
                    toast("片段范围无效"); return null
                }
                if (format == 3 && length != 0.0 && length !in 1.0..10.0) {
                    toast("GIF 时长需在 1 到 10 秒之间"); return null
                }
                if (format == 3 && visualizerView.creative.outroText.isNotBlank() &&
                    (if (length == 0.0) 5.0 else length) + visualizerView.creative.outroDuration > 10.0) {
                    toast("添加片尾后，GIF 总时长不能超过 10 秒"); return null
                }
                return start to length
            }
            val previewDurationButton = ThemeUi.button(this@showExportSheet, palette, "时长 ${previewSeconds}秒", ThemeUi.ROLE_CONTROL) {
                val options = listOf(2, 5, 10)
                pickOption("预览时长", options.map { "$it 秒" }, options.indexOf(previewSeconds)) { index ->
                    previewSeconds = options[index]
                    (it as android.widget.Button).text = "时长 ${previewSeconds}秒"
                }
            }
            val previewButton = ThemeUi.button(this@showExportSheet, palette, "预览开头", ThemeUi.ROLE_CONTROL) {
                val selectedClip = clip() ?: return@button
                val length = if (format == 3 && selectedClip.second == 0.0) 5.0 else selectedClip.second
                exportMp4(null, landscape, resolution, selectedClip.first,
                    if (length > 0) minOf(previewSeconds.toDouble(), length) else previewSeconds.toDouble(), preview = true)
            }
            val previewEndButton = ThemeUi.button(this@showExportSheet, palette, "预览结尾", ThemeUi.ROLE_CONTROL) {
                val selectedClip = clip() ?: return@button
                val length = if (format == 3 && selectedClip.second == 0.0) 5.0 else selectedClip.second
                exportMp4(null, landscape, resolution, selectedClip.first, length, preview = true,
                    previewTailSeconds = previewSeconds)
            }
            val durationRow = ThemeUi.row(this@showExportSheet)
            val gap = ThemeUi.dp(this@showExportSheet, 4f)
            listOf("15 秒" to "15", "30 秒" to "30", "整曲" to "").forEachIndexed { index, (label, seconds) ->
                durationRow.addView(ThemeUi.button(this@showExportSheet, palette, label, ThemeUi.ROLE_CONTROL) {
                    clipDuration.setText(seconds)
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = gap })
            }
            val previewRow = ThemeUi.row(this@showExportSheet).apply {
                listOf(previewButton, previewEndButton, previewDurationButton).forEachIndexed { index, button ->
                    button.setPadding(gap, 0, gap, 0)
                    button.isSingleLine = true
                    addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = gap })
                }
            }
            val hd = RadioButton(this@showExportSheet).apply { text = "720p"; textSize = 12f }
            val fullHd = RadioButton(this@showExportSheet).apply { text = "1080p"; textSize = 12f }
            fun setResolution(value: Int) {
                resolution = value; hd.isChecked = value == 720; fullHd.isChecked = value == 1080
                listOf(hd, fullHd).forEach { it.setTextColor(palette.text); it.buttonTintList = ColorStateList.valueOf(palette.accent) }
            }
            fun setOrientation(value: Boolean) {
                landscape = value; portrait.isChecked = !value; horizontal.isChecked = value
                listOf(portrait, horizontal).forEach { it.setTextColor(palette.text); it.buttonTintList = ColorStateList.valueOf(palette.accent) }
            }
            fun setFormat(value: Int) {
                format = value
                wav.isChecked = value == 0; mp3Btn.isChecked = value == 1; mp4Btn.isChecked = value == 2; gifBtn.isChecked = value == 3
                orientationRow.visibility = if (value >= 2) View.VISIBLE else View.GONE
                resolutionRow.visibility = if (value == 2) View.VISIBLE else View.GONE
                clipRow.visibility = if (value >= 2) View.VISIBLE else View.GONE
                smoothGifLoop.visibility = if (value == 3) View.VISIBLE else View.GONE
                creativeButton.visibility = if (value >= 2) View.VISIBLE else View.GONE
                copyrightHint.visibility = if (value >= 2) View.VISIBLE else View.GONE
                previewRow.visibility = if (value >= 2) View.VISIBLE else View.GONE
                durationRow.visibility = if (value == 2) View.VISIBLE else View.GONE
                clipDuration.hint = if (value == 3) "时长 1–10 秒（默认 5）" else "时长秒数（留空为整曲）"
                listOf(wav, mp3Btn, mp4Btn, gifBtn).forEach { it.setTextColor(palette.text); it.buttonTintList = ColorStateList.valueOf(palette.accent) }
            }
            wav.setOnClickListener { setFormat(0) }
            mp3Btn.setOnClickListener { setFormat(1) }
            mp4Btn.setOnClickListener { setFormat(2) }
            gifBtn.setOnClickListener { setFormat(3) }
            portrait.setOnClickListener { setOrientation(false) }
            horizontal.setOnClickListener { setOrientation(true) }
            hd.setOnClickListener { setResolution(720) }
            fullHd.setOnClickListener { setResolution(1080) }
            setOrientation(true); setResolution(720); setFormat(0)
            listOf(wav, mp3Btn, mp4Btn, gifBtn).forEachIndexed { index, button ->
                button.maxLines = 2
                button.setAutoSizeTextTypeUniformWithConfiguration(8, 11, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                formatRow.addView(button, LinearLayout.LayoutParams(0, ThemeUi.dp(this@showExportSheet, 54f), 1f).apply {
                    if (index > 0) marginStart = gap
                })
            }
            addView(formatRow)
            orientationRow.addView(portrait, LinearLayout.LayoutParams(0, -2, 1f))
            orientationRow.addView(horizontal, LinearLayout.LayoutParams(0, -2, 1f))
            addView(orientationRow)
            resolutionRow.addView(hd, LinearLayout.LayoutParams(0, -2, 1f))
            resolutionRow.addView(fullHd, LinearLayout.LayoutParams(0, -2, 1f))
            addView(resolutionRow)
            clipRow.addView(clipStart, LinearLayout.LayoutParams(0, -2, 1f))
            clipRow.addView(clipDuration, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = gap })
            addView(clipRow)
            addView(smoothGifLoop)
            addView(durationRow)
            addView(creativeButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
            addView(previewRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
            addView(copyrightHint)
            fun destination(title: String, description: String, actionLabel: String, action: () -> Unit) = ThemeUi.row(this@showExportSheet).apply {
                background = ThemeUi.bgDrawable(this@showExportSheet, palette.surfaceAlt, 8f)
                setPadding(ThemeUi.dp(this@showExportSheet, 12f), 0, ThemeUi.dp(this@showExportSheet, 12f), 0)
                layoutParams = LinearLayout.LayoutParams(-1, ThemeUi.dp(this@showExportSheet, 58f)).apply { topMargin = ThemeUi.dp(this@showExportSheet, 8f) }
                addView(LinearLayout(this@showExportSheet).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    addView(ThemeUi.text(this@showExportSheet, title, 13f, palette, bold = true))
                    addView(ThemeUi.text(this@showExportSheet, description, 10f, palette, role = ThemeUi.ROLE_SUB))
                })
                addView(ThemeUi.text(this@showExportSheet, actionLabel, 12f, palette, role = ThemeUi.ROLE_ACCENT))
                setOnClickListener { action() }
            }
            addView(destination("默认位置", "音频保存到 Music/8D，视频保存到 Movies/8D", "导出 ▸") {
                val selectedClip = if (format >= 2) clip() ?: return@destination else 0.0 to 0.0
                exportDialog?.dismiss()
                val name = currentItem?.name?.substringBeforeLast('.') ?: "8d_export"
                if (format >= 2) exportPublicVideo(name, landscape, resolution, selectedClip.first, selectedClip.second, format == 3,
                    smoothGifLoop.isChecked) else exportPublic(name, format == 1)
            })
            addView(destination("自选位置…", "拉起系统对话框，自选目录与文件名", "选择 ▸") {
                val selectedClip = if (format >= 2) clip() ?: return@destination else 0.0 to 0.0
                exportDialog?.dismiss()
                val name = currentItem?.name?.substringBeforeLast('.') ?: "8d_export"
                when (format) {
                    2 -> { exportVideoLandscape = landscape; exportVideoResolution = resolution; exportVideoStart = selectedClip.first; exportVideoDuration = selectedClip.second; saveMp4.launch("$name-8D.mp4") }
                    3 -> { exportVideoLandscape = landscape; exportVideoResolution = resolution; exportVideoStart = selectedClip.first; exportVideoDuration = selectedClip.second; exportVideoSmoothGifLoop = smoothGifLoop.isChecked; saveGif.launch("$name-8D.gif") }
                    1 -> saveMp3.launch("$name-8D.mp3")
                    else -> saveWav.launch("$name-8D.wav")
                }
            })
        }
        exportDialog = sheet
        sheet.show()
    }

/** versionCode 为 yyMMddHH + 同小时序号（共 9 位），换算成可读发布时间。 */
private fun formatReleaseTime(code: Long): String {
    val text = code.toString()
    if (text.length != 9 || text.any { !it.isDigit() }) return "构建号 $code"
    val year = "20${text.substring(0, 2)}"
    val month = text.substring(2, 4)
    val day = text.substring(4, 6)
    val hour = text.substring(6, 8)
    val sequence = text.substring(8).toInt()
    return "发布于$year-$month-$day ${hour}时 第${sequence}次构建"
}
