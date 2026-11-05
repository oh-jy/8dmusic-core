package j5i.spatial8d

import android.Manifest
import android.content.Intent
import android.content.ContentValues
import android.content.pm.PackageManager
import android.app.Dialog
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import j5i.spatial8d.audio.PlaybackService
import j5i.spatial8d.audio.Lyrics
import j5i.spatial8d.core.RadioCatalog
import j5i.spatial8d.core.SLIDERS
import j5i.spatial8d.core.SPACE_PRESETS
import j5i.spatial8d.core.formatValue
import j5i.spatial8d.core.matchPresetIn
import j5i.spatial8d.data.PlayMode
import j5i.spatial8d.ui.SpatialVisualization
import j5i.spatial8d.ui.ThemeUi
import j5i.spatial8d.ui.AppLanguage
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.pow

internal fun MainActivity.buildUi() {
        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
        }
        root.addView(navbar())
        visualization = SpatialVisualization(this).apply {
            val available = (resources.displayMetrics.heightPixels / resources.displayMetrics.density - 52 - 184 - 216).coerceAtLeast(200f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeUi.dp(this@buildUi, available * 0.62f))
        }
        root.addView(card { addView(visualization) })
        status = ThemeUi.text(this, "请选择音频文件", 13f, palette.copy(faint = palette.sub), role = ThemeUi.ROLE_SUB)
        root.addView(spaceSettingsCard())
        root.addView(transformCard())
        root.addView(curveCard())
        root.setPadding(0, ThemeUi.dp(this, 52f), 0, ThemeUi.dp(this, 176f))
        scroll.addView(root)
        val frame = FrameLayout(this)
        frame.setBackgroundColor(palette.background)
        frame.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(nowPlayingBar())
        setContentView(frame)
        // 首屏用实际测量值分配曲线剩余空间，避免按屏幕高度硬减常量留下空白。
        curveContent.post { resizeCurveToRemainingSpace() }
        frame.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top == oldBottom - oldTop) return@addOnLayoutChangeListener
            frame.post { resizeCurveToRemainingSpace() }
        }
        refreshPlayback()
        tint(root, palette.text)
        ThemeUi.applyTheme(playerBar, palette)
    }

internal fun MainActivity.resizeCurveToRemainingSpace() {
        if (!isViewReady(this@resizeCurveToRemainingSpace::curveContent)) return
        val card = curveContent.parent as? View ?: return
        val extra = (card.parent as? ViewGroup)?.paddingBottom ?: 0
        val frame = card.parent?.parent as? View ?: return
        val remaining = frame.height - playerBar.height - root.paddingTop - root.paddingBottom - extra
        val height = (remaining - (root.height - curveContent.height)).coerceAtLeast(ThemeUi.dp(this, 140f))
        if (height == curveContent.height) return
        curveContent.layoutParams = curveContent.layoutParams.apply { this.height = height }
    }

internal fun MainActivity.refreshAll() {
        updatingControls = true
        palette = ThemeUi.palette(dark)
        // Styling changes SeekBar padding/thumb bounds. Apply it before progress writes,
        // so opening a panel never leaves a deferred visual correction until the first touch.
        refreshControls()
        // 下拉、展开控件、方向、曲线共用 preset，任何入口修改后都从这里统一回灌。
        presetPicker.text = preset.name
        val presetIndex = SPACE_PRESETS.indexOfFirst { it.name == preset.name }
        if (presetIndex >= 0) {
            presetPicker.text = AppLanguage.preset(this, presetIndex)
            presetNote.text = AppLanguage.preset(this, presetIndex, note = true)
        }
        val dirLabels = listOf(t("direction.cw") to 1.0, t("direction.ccw") to -1.0, t("direction.random") to 0.0)
        dirButtons.forEachIndexed { i, btn ->
            val (label, value) = dirLabels[i]
            btn.text = label
            btn.tag = if (preset.direction == value) ThemeUi.ROLE_ACCENT_FILL else ThemeUi.ROLE_CONTROL
            ThemeUi.applyButton(btn, palette)
        }
        sliderControls.forEach { (key, slider) ->
            val value = parameter(key)
            val definition = SLIDERS.first { it.key == key }
            val steps = ((definition.max - definition.min) * 10.0.pow(definition.digits)).roundToInt()
            slider.max = steps
            slider.progress = ((value - definition.min) * 10.0.pow(definition.digits)).roundToInt()
            sliderValues[key]?.text = formatValue(key, value).removeSuffix(definition.unit) + when (key) {
                "period", "rt60" -> t("unit.seconds")
                "room" -> t("unit.meters")
                else -> ""
            }
        }
        visualization.preset = preset
        val playing = if (isLive) service?.radio?.isPlaying == true else service?.player?.isPlaying == true
        visualization.playing = playing
        updateDriving()
        visualization.invalidate()
        curve.preset = preset
        curve.playing = playing
        updateDriving()
        curve.invalidate()
        if (isViewReady(this@refreshAll::pitchPicker)) pitchPicker.text = pitchPickerLabel()
        if (isViewReady(this@refreshAll::pitchLabel)) {
            pitchLabel.text = when {
                transform.semi == 0.0 -> t("original")
                transform.semi > 0 -> "+${transform.semi.toInt()} ${t("semitone")}"
                else -> "${transform.semi.toInt()} ${t("semitone")}"
            }
            pitchSlider.progress = (transform.semi.roundToInt() + 12).coerceIn(0, 24)
                speedLabel.text = String.format(Locale.US, "%.2f×", transform.speed)
            speedSlider.progress = ((transform.speed - 0.5) / 0.05).roundToInt()
            reverseSwitch.isChecked = reverse
            tapeSwitch.isChecked = transform.tape
        }
        if (speedChipButtons.isNotEmpty()) {
            listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0).forEachIndexed { i, v ->
                val btn = speedChipButtons[i]
                btn.text = "${formatSpeedShort(v)}×\n${t("speed.realtime")}"
                btn.gravity = Gravity.CENTER
                btn.setLineSpacing(0f, 0.85f)
                btn.tag = if (transform.speed == v) ThemeUi.ROLE_ACCENT_FILL else ThemeUi.ROLE_CONTROL
                ThemeUi.applyButton(btn, palette)
            }
        }
        pitchTicks.forEachIndexed { index, button ->
            val semi = listOf(-12.0, -8.0, -4.0, 0.0, 4.0, 8.0, 12.0)[index]
            button.setTextColor(if (transform.semi == semi) palette.accent else palette.faint)
            ((button.parent as? LinearLayout)?.getChildAt(0))?.setBackgroundColor(
                if (transform.semi == semi) palette.accent else palette.faint)
        }
        transformCardRoot.visibility = View.VISIBLE
        for (i in 0 until transformBody.childCount) {
            val child = transformBody.getChildAt(i)
            if (child.tag == "localOnly") child.visibility = if (isLive) View.GONE else View.VISIBLE
        }
        if (isViewReady(this@refreshAll::denoiseLabel)) {
            denoiseLabel.text = if (transform.denoiseLevel == 0) t("off") else "${transform.denoiseLevel}%"
            denoiseSlider.progress = transform.denoiseLevel
        }
        updatingControls = false
        rebuildTransport()
        refreshCardState()
        refreshPlayback()
    }

internal fun MainActivity.refreshPresetSummary() {
        presetPicker.text = preset.name
        val presetIndex = SPACE_PRESETS.indexOfFirst { it.name == preset.name }
        if (presetIndex >= 0) {
            presetPicker.text = AppLanguage.preset(this, presetIndex)
            presetNote.text = AppLanguage.preset(this, presetIndex, note = true)
        }
    }

internal fun MainActivity.refreshControls() {
        ThemeUi.configureSeekBar(seek, palette, playback = true)
        sliderControls.values.forEach { ThemeUi.configureSeekBar(it, palette) }
        ThemeUi.configureSeekBar(speedSlider, palette)
        ThemeUi.configureSeekBar(pitchSlider, palette)
        if (isViewReady(this@refreshControls::denoiseSlider)) ThemeUi.configureSeekBar(denoiseSlider, palette)
        ThemeUi.picker(presetPicker, palette)
        ThemeUi.picker(pitchPicker, palette)
    }

internal fun MainActivity.rebuildTransport() {
        val first = transportRow.getChildAt(0) as? LinearLayout ?: return
        val modeButton = first.getChildAt(0) as? ImageButton ?: return
        val icon = when (currentMode()) {
            PlayMode.LOOP_ONE -> j5i.spatial8d.R.drawable.ic_loop1
            PlayMode.SHUFFLE -> j5i.spatial8d.R.drawable.ic_shuffle
            else -> j5i.spatial8d.R.drawable.ic_loop
        }
        modeButton.setImageResource(icon)
        modeButton.contentDescription = modeText()
    }

internal fun MainActivity.refreshTheme() {
        palette = ThemeUi.palette(dark)
        tint(root, palette.text)
        root.setBackgroundColor(palette.background)
        (root.parent?.parent as? FrameLayout)?.setBackgroundColor(palette.background)
        root.findViewWithTag<View>("navbar")?.setBackgroundColor(palette.background)
        visualization.dark = dark
        curve.dark = dark
        visualizerView.dark = dark
        popupVisualizer?.dark = dark
        if (isViewReady(this@refreshTheme::lyricsView)) lyricsView.dark = dark
        if (isViewReady(this@refreshTheme::playerBar)) ThemeUi.bg(playerBar, if (dark) 0xff17191d.toInt() else 0xfff7f8fa.toInt(), 0f)
        // 透明圆形应用图标自带颜色，深浅模式下都不叠 tint。
        if (isViewReady(this@refreshTheme::disc)) disc.imageTintList = null
        tint(root, palette.text)
        if (isViewReady(this@refreshTheme::playerBar)) ThemeUi.applyTheme(playerBar, palette)
        // PNG 图标不经过 Button 主题回刷；播放键白色，普通控制键随主题切换。
        fun tintIcons(view: View) {
            if (view is ImageButton && !(isViewReady(this@refreshTheme::favoriteButton) && view === favoriteButton)) view.imageTintList = ColorStateList.valueOf(
                if (view.tag == ThemeUi.ROLE_ACCENT_FILL) 0xffffffff.toInt() else palette.controlText)
            if (view is ViewGroup) for (i in 0 until view.childCount) tintIcons(view.getChildAt(i))
        }
        if (isViewReady(this@refreshTheme::playerBar)) tintIcons(playerBar)
        tint(root, palette.text)
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is LinearLayout && child.tag == "card") {
                ThemeUi.bg(child, palette.surface, 14f)
            }
        }
        refreshControls()
        refreshAll()
        listOf(fmDialog, onlineDialog, infoDialog, exportDialog, visualizerDialog).forEach { dialog ->
            if (dialog?.isShowing == true) {
                val content = dialog.findViewById<ViewGroup>(android.R.id.content)
                if (content != null) ThemeUi.applyTheme(content, palette)
            }
        }
        // 状态栏/导航栏图标随背景切换，浅色的时间、电量必须保持深色可读。
        preferences.edit().putInt("themeMode", themeMode).putBoolean("dark", dark).apply()
    }

    /** 解析当前应生效的深色状态；跟随系统时读 Configuration.uiMode。 */

internal fun MainActivity.resolveDark(): Boolean = when (themeMode) {
        0 -> true
        1 -> false
        else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /** 系统深浅色切换时，跟随模式自动刷新。 */

internal fun MainActivity.tint(view: View, color: Int) {
        window.statusBarColor = palette.background
        window.navigationBarColor = if (dark) 0xff17191d.toInt() else 0xfff7f8fa.toInt()
        ThemeUi.applyTheme(view, palette)
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(
                if (dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else {
            @Suppress("DEPRECATION")
            var flags = window.decorView.systemUiVisibility
            if (Build.VERSION.SDK_INT >= 23) {
                flags = if (dark) flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                else flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
            if (Build.VERSION.SDK_INT >= 26) {
                flags = if (dark) flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                else flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = flags
        }
    }

internal fun MainActivity.refreshPlayback() {
        val player = service?.player
        val radio = service?.radio
        // 自动续播的真实曲目以服务为准；即使首块通知丢失，也不能让标题和歌词停留在旧曲。
        service?.currentTrack?.let { active ->
            if (!isLive && currentUri != active.uri) adoptServiceTrack(active)
        }
        val playing = if (isLive) radio?.isPlaying == true else player?.isPlaying == true
        if (playButton is ImageButton) {
            (playButton as ImageButton).setImageResource(
                if (playing) j5i.spatial8d.R.drawable.ic_pause else j5i.spatial8d.R.drawable.ic_play
            )
            playButton.contentDescription = if (playing) t("pause") else t("play")
        } else {
            (playButton as Button).text = if (playing) t("pause") else t("play")
        }
        visualization.playing = playing
        curve.playing = playing
        val lyricPositionMs = ((if (isLive) 0.0 else player?.position ?: 0.0) * 1000).toLong()
        if (isViewReady(this@refreshPlayback::lyricsView)) lyricsView.setPosition(lyricPositionMs)
        if (isViewReady(this@refreshPlayback::visualizerView)) visualizerView.setPosition(lyricPositionMs)
        popupVisualizer?.setPosition(lyricPositionMs)
        songTitle.text = currentItem?.name?.let(Lyrics::displayTitle) ?: t("no.song")
        position.visibility = View.GONE
        currentTime.text = time(player?.position ?: 0.0)
        durationTime.text = time(player?.duration ?: 0.0)
        val station = if (isLive) RadioCatalog.filter(false).firstOrNull { it.id == currentStationId } else null
        modeTextView.text = station?.let {
            Regex("(?:FM|AM)\\s?\\d+(?:\\.\\d+)?", RegexOption.IGNORE_CASE).find("${it.name} ${it.note}")?.value
                ?: if (it.type == "网络台") "网络电台" else "电台直播"
        }.orEmpty()
        modeTextView.visibility = if (isLive) View.VISIBLE else View.GONE
        val effectiveStatus = when {
            playing -> t("status.playing")
            playbackStatus == t("status.preparing") && service?.player?.isPrepared == true -> t("status.ready")
            else -> playbackStatus
        }
        status.text = effectiveStatus
        // 歌词只允许出现在一个位置：同步歌词，或可视化的图谱/粒子/穿梭及其弹窗。
        val visualLyrics = curvePanel == 1 && visualizerView.showsLyrics || popupVisualizer?.showsLyrics == true
        val statusLine = if (isLive) {
            "● LIVE · 流媒体实时处理 · $effectiveStatus"
        } else if (curvePanel != 2 && !visualLyrics) {
            val lines = currentLyrics?.lines
            val currentLine = lines?.let { ls ->
                val idx = ls.indexOfLast { it.timeMs <= lyricPositionMs }
                if (idx >= 0) ls[idx].text else null
            }
            currentLine?.takeIf { it.isNotBlank() } ?: effectiveStatus
        } else effectiveStatus
        (playerBar.findViewWithTag<TextView>("playerStatus"))?.text = statusLine
        recordingLabel.visibility = if (recordingKind.isEmpty()) View.GONE else View.VISIBLE
        recordingLabel.text = if (recordingKind.isEmpty()) "" else "● REC ${time((SystemClock.elapsedRealtime() - recordingStartedAt) / 1000.0)}"
        recordingLabel.setTextColor(0xffff5d5d.toInt())
        if (isViewReady(this@refreshPlayback::liveTimeline)) {
            seek.visibility = if (isLive) View.GONE else View.VISIBLE
            currentTime.visibility = if (isLive) View.GONE else View.VISIBLE
            durationTime.visibility = if (isLive) View.GONE else View.VISIBLE
            liveTimeline.visibility = View.GONE
        }
        if (isViewReady(this@refreshPlayback::exportButton)) {
            exportButton.background = ThemeUi.bgDrawable(this,
                if (dark) 0xff263e55.toInt() else 0xffe3efff.toInt(), 8f)
            exportButton.text = if (isLive) (if (recordingKind == "playback") "停止" else "录音") else "导出"
            exportButton.setTextColor(if (isLive && recordingKind == "playback") 0xffff5d5d.toInt() else palette.accent)
            exportButton.contentDescription = if (isLive) "录制电台最终输出" else "导出当前音频"
        }
        if (isViewReady(this@refreshPlayback::favoriteButton)) {
            favoriteButton.background = null
            favoriteButton.tag = "favoriteButton"
            favoriteButton.imageTintList = null
            val item = currentItem
            val local = !isLive && item != null && item.uri.isNotBlank() && !item.uri.startsWith("http")
            favoriteButton.visibility = if (local) View.VISIBLE else View.GONE
            if (local) {
                val favorite = musicStore.isFavorite(item.id)
                val color = if (favorite) 0xffff5c7a.toInt() else palette.controlText
                favoriteButton.setImageDrawable(j5i.spatial8d.ui.HeartDrawable(favorite, color))
                favoriteButton.contentDescription = if (favorite) "取消收藏" else "收藏"
            }
        }
        if (!seeking) seek.progress = if (player?.duration ?: 0.0 > 0)
            (player!!.position / player.duration * 1000).toInt() else 0
        durationHint.text = if (player?.duration ?: 0.0 > 0) time(player!!.duration / transform.speed) else "--:--"
        service?.updateMedia(currentItem?.name ?: "8D 空间音频",
            if (isLive) "电台直播 · 8D 实时处理" else currentItem?.artist ?: playbackStatus,
            playing, if (isLive) 0.0 else player?.position ?: 0.0, if (isLive) 0.0 else player?.duration ?: 0.0)
        // 应用图标旋转（约 30 秒一圈），跟随播放状态。
        if (playing) disc.rotation = (SystemClock.uptimeMillis() / 83f) % 360f
        (transportRow.getChildAt(0) as? LinearLayout)?.let {
            val icon = (it.getChildAt(0) as? ImageButton) ?: return@let
            icon.setImageResource(when (currentMode()) {
                PlayMode.LOOP_ONE -> j5i.spatial8d.R.drawable.ic_loop1
                PlayMode.SHUFFLE -> j5i.spatial8d.R.drawable.ic_shuffle
                else -> j5i.spatial8d.R.drawable.ic_loop
            })
            icon.contentDescription = modeText()
        }
    }

internal fun MainActivity.applyPreset() {
        service?.player?.preset = preset
        service?.controlsPreset = preset
        service?.radio?.preset = preset
        savePlaybackState()
        refreshAll()
    }

internal fun MainActivity.applyTransform(syncControls: Boolean = true) {
        if (isLive) {
            // 直播时间轴不可变速或倒放；与 UTS 一致，只把半音映射到 AudioTrack pitch。
            service?.radio?.semi = transform.semi.roundToInt()
            playbackStatus = if (transform.semi == 0.0) "电台已恢复原声" else "电台变声已更新（${transform.semi.toInt()}半音）"
            if (syncControls) refreshAll()
            return
        }
        service?.player?.transform = transform
        service?.controlsTransform = transform
        service?.controlsReverse = reverse
        service?.radio?.denoise = transform.denoiseLevel
        // 实时播放走 PlaybackParams，导出时才离线重算 PCM，因此这里立即完成。
        playbackStatus = if (transform.active) "音频已更新（${formatSpeedShort(transform.speed)}× · ${transform.semi.toInt()}半音）"
            else "已恢复原始速度"
        savePlaybackState()
        // 所有滑块、快捷标签、下拉和实际 transform 共用同一次回写，避免只改引擎不改文字。
        if (syncControls) refreshAll()
    }

internal fun MainActivity.updateParameter(key: String, value: Double) {
        preset = when (key) {
            "period" -> preset.copy(period = value)
            "elev" -> preset.copy(elev = value)
            "depth" -> preset.copy(depth = value)
            "phase" -> preset.copy(phase = value)
            "room" -> preset.copy(room = value)
            "wet" -> preset.copy(wet = value)
            "rt60" -> preset.copy(rt60 = value)
            "direction" -> preset.copy(direction = value)
            "width" -> preset.copy(width = value)
            else -> return
        }
        val matched = matchPresetIn(SPACE_PRESETS + customPresets, preset.period, preset.elev,
            preset.depth, preset.room, preset.wet, preset.rt60, preset.phase,
            preset.direction, preset.width, preset.raw)
        if (matched != preset.name) {
            preset = preset.copy(name = matched,
                note = if (matched == "自定义") "当前参数为手动调整，未匹配任何预设。" else preset.note)
        }
        // 滑块或方向按钮修改后，同时同步中间下拉文本、全部展开控件与预设说明。
        service?.player?.preset = preset
        service?.radio?.preset = preset
        service?.radio?.denoise = transform.denoiseLevel
        savePlaybackState()
        if (!draggingControl) refreshAll()
    }

internal fun MainActivity.parameter(key: String): Double = when (key) {
        "period" -> preset.period
        "elev" -> preset.elev
        "depth" -> preset.depth
        "phase" -> preset.phase
        "room" -> preset.room
        "wet" -> preset.wet
        "rt60" -> preset.rt60
        "direction" -> preset.direction
        "width" -> preset.width
        else -> 0.0
    }

internal fun MainActivity.togglePlaylist() {
        if (playlistDialog?.isShowing() == true) playlistDialog?.dismiss() else showPlaylist()
    }

internal fun MainActivity.showVisualizerSheet() {
        if (visualizerDialog?.isShowing == true) return
        val dialog = ThemeUi.showSheet(this, palette, t("visualizer.popup"), t("visualizer.popup.subtitle"),
            fullHeight = true, scrollable = false) {
            (visualizerView.parent as? ViewGroup)?.removeView(visualizerView)
            addView(visualizerView, LinearLayout.LayoutParams(-1, 0, 1f))
            visualizerView.showLayerOptions = true
            popupVisualizer = visualizerView
        }
        dialog.setOnDismissListener {
            popupVisualizer = null
            (visualizerView.parent as? ViewGroup)?.removeView(visualizerView)
            curveContent.addView(visualizerView, FrameLayout.LayoutParams(-1, -1))
            visualizerView.showLayerOptions = false
            visualizerView.visibility = if (curvePanel == 1) View.VISIBLE else View.GONE
        }
        visualizerDialog = dialog
        dialog.show()
    }

internal fun MainActivity.saveCustomPreset() {
        saveCustomPreset("")
    }

internal fun MainActivity.saveCustomPreset(rawInput: String) {
        val raw = rawInput.ifBlank { "自定义 ${customPresets.size + 1}" }
        var name = if (" · " in raw) raw else "标准绕头 · $raw"
        val base = name
        var suffix = 2
        while (customPresets.any { it.name == name }) name = "$base ${suffix++}"
        customPresets.add(preset.copy(name = name, note = "用户保存的自定义空间风格"))
        presetStore.save(customPresets)
        preset = customPresets.last()
        refreshAll()
    }

    @Suppress("DEPRECATION")

internal fun MainActivity.chooseExport() {
        showExportSheet()
    }

internal fun MainActivity.pickOption(title: String, names: List<String>, selected: Int, twoColumns: Boolean = false,
        action: (Int) -> Unit) {
        lateinit var dialog: Dialog
        dialog = ThemeUi.showSheet(this, palette, title, compactPicker = true) {
            fun option(index: Int) = ThemeUi.text(this@pickOption, names[index],
                if (twoColumns) 11f else 13f, palette,
                    bold = index == selected, role = if (index == selected) ThemeUi.ROLE_ACCENT else ThemeUi.ROLE_TEXT).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    ThemeUi.hideOverflow(this)
                    setPadding(ThemeUi.dp(this@pickOption, 10f), 0, ThemeUi.dp(this@pickOption, 10f), 0)
                    background = ThemeUi.bgDrawable(this@pickOption,
                        if (index == selected) palette.surfaceAlt else palette.surface, 8f)
                    setOnClickListener { dialog.dismiss(); action(index) }
                }
            if (twoColumns) {
                names.indices.chunked(2).forEach { rowIndices ->
                    addView(ThemeUi.row(this@pickOption).apply {
                        rowIndices.forEach { index ->
                            addView(option(index), LinearLayout.LayoutParams(0,
                                ThemeUi.dp(this@pickOption, 38f), 1f).apply {
                                if (index % 2 == 0) rightMargin = ThemeUi.dp(this@pickOption, 4f)
                                bottomMargin = ThemeUi.dp(this@pickOption, 4f)
                            })
                        }
                        // 奇数个选项时保留右侧空白，不让最后一项拉伸成整行。
                        if (rowIndices.size == 1) addView(View(this@pickOption),
                            LinearLayout.LayoutParams(0, 1, 1f).apply {
                                leftMargin = ThemeUi.dp(this@pickOption, 4f)
                            })
                    })
                }
            } else {
                names.forEachIndexed { index, _ ->
                    addView(option(index), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ThemeUi.dp(this@pickOption, 38f)))
                }
            }
        }
        dialog.show()
    }

internal fun MainActivity.hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED

internal fun MainActivity.audioPermission() = if (Build.VERSION.SDK_INT >= 33)
        // Android 13 拆分媒体权限；12 及以下仍读取外置存储权限。
        Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

internal fun MainActivity.currentMode() = try {
        PlayMode.valueOf(preferences.getString("mode", PlayMode.LOOP_LIST.name)!!)
    } catch (_: Exception) { PlayMode.LOOP_LIST }

internal fun MainActivity.modeText() = when (currentMode()) {
        PlayMode.LOOP_LIST -> t("mode.list")
        PlayMode.LOOP_ONE -> t("mode.one")
        PlayMode.SHUFFLE -> t("mode.shuffle")
    }

internal fun MainActivity.time(seconds: Double): String {
        val value = seconds.toInt().coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d", value / 60, value % 60)
    }

internal fun MainActivity.toast(message: String): Unit { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }

internal fun MainActivity.navbar(): LinearLayout {
        val bar = ThemeUi.row(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ThemeUi.dp(this@navbar, 10f), 0, ThemeUi.dp(this@navbar, 10f), 0)
            setBackgroundColor(palette.background)
            tag = "navbar"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeUi.dp(this@navbar, 52f))
        }
        bar.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ThemeUi.row(this@navbar).apply {
                addView(ThemeUi.text(this@navbar, t("app.name"), 16f, palette, bold = true).apply {
                    setOnClickListener { unlockSource() }
                })
                addView(ThemeUi.text(this@navbar, "ⓘ", 18f, palette, role = ThemeUi.ROLE_SUB).apply {
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@navbar, 30f), ThemeUi.dp(this@navbar, 30f)).apply {
                        leftMargin = ThemeUi.dp(this@navbar, 5f)
                    }
                    setOnClickListener { showInfoSheet() }
                })
            })
            addView(ThemeUi.text(this@navbar, t("app.subtitle"), 10f, palette, role = ThemeUi.ROLE_FAINT))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        fun navButton(label: String, action: () -> Unit) = ThemeUi.button(this, palette, label, minHeightDp = 30f).apply {
            textSize = 11f
            setPadding(ThemeUi.dp(this@navbar, 8f), 0, ThemeUi.dp(this@navbar, 8f), 0)
            setOnClickListener { action() }
        }
        onlineButton = navButton(t("library")) { showSourceSheet(true) }.apply {
            visibility = if (onlineUnlocked) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(-2, ThemeUi.dp(this@navbar, 30f)).apply { leftMargin = ThemeUi.dp(this@navbar, 3f) }
        }
        bar.addView(onlineButton)
        fmButton = navButton("FM") { showSourceSheet(false) }.apply {
            visibility = if (fmUnlocked) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(-2, ThemeUi.dp(this@navbar, 30f)).apply { leftMargin = ThemeUi.dp(this@navbar, 3f) }
        }
        bar.addView(fmButton)
        bar.addView(navButton(t("driving")) { showDrivingMode() })
        bar.addView(ImageButton(this).apply {
            setImageResource(j5i.spatial8d.R.drawable.ic_settings)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageTintList = ColorStateList.valueOf(palette.controlText)
            background = ThemeUi.bgDrawable(this@navbar, palette.control, 15f)
            setPadding(ThemeUi.dp(this@navbar, 7f), 0, ThemeUi.dp(this@navbar, 7f), 0)
            contentDescription = t("settings")
            layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@navbar, 30f), ThemeUi.dp(this@navbar, 30f)).apply {
                leftMargin = ThemeUi.dp(this@navbar, 6f)
            }
            setOnClickListener { showSettingsSheet() }
        })
        return bar
    }

internal fun MainActivity.themeLabel() = when (themeMode) {
        0 -> t("theme.dark"); 1 -> t("theme.light"); else -> t("theme.system")
    }

    /** 统一设置入口：外观与语言先归入此处，后续新增设置项也加入这个弹层。 */

internal fun MainActivity.nowPlayingBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ThemeUi.dp(this@nowPlayingBar, 12f), ThemeUi.dp(this@nowPlayingBar, 6f), ThemeUi.dp(this@nowPlayingBar, 12f), ThemeUi.dp(this@nowPlayingBar, 4f))
            bg(this, if (dark) 0xff17191d.toInt() else 0xfff7f8fa.toInt(), 0f)
            gravity = Gravity.BOTTOM
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }
        playerBar = bar
        val meta = ThemeUi.row(this)
        disc = ImageView(this).apply {
            // 透明圆形应用图标：深浅模式下不再依赖 tint 或背景。
            setImageResource(j5i.spatial8d.R.drawable.ic_app_round_xxxhdpi)
            imageTintList = null
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(0, 0, 0, 0)
            background = null
            layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@nowPlayingBar, 38f), ThemeUi.dp(this@nowPlayingBar, 38f))
        }
        disc.setOnClickListener { showPlaylist() }
        meta.addView(disc)
        songTitle = ThemeUi.text(this, "未加载音频", 14f, palette, bold = true).apply {
            ThemeUi.hideOverflow(this)
            setOnClickListener { showMetadataEditor() }
        }
        val sub = ThemeUi.row(this)
        position = ThemeUi.text(this, "00:00 / 00:00", 11f, palette, role = ThemeUi.ROLE_SUB)
        position.layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this, 76f), ViewGroup.LayoutParams.WRAP_CONTENT)
        modeTextView = ThemeUi.text(this, "", 11f, palette, role = ThemeUi.ROLE_SUB)
        recordingLabel = ThemeUi.text(this, "", 10f, palette).apply {
            setTextColor(0xffff5d5d.toInt()); visibility = View.GONE
            setPadding(ThemeUi.dp(this@nowPlayingBar, 6f), 0, 0, 0)
        }
        sub.addView(position)
        sub.addView(modeTextView.apply {
            setPadding(ThemeUi.dp(this@nowPlayingBar, 8f), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@nowPlayingBar, 56f), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        sub.addView(recordingLabel)
        // 状态栏（含当前行歌词）独立成行，跟歌曲名左缘对齐，不再被时间/模式字段挤到右边。
        val statusTxt = ThemeUi.text(this, status.text.toString(), 11f, palette, role = ThemeUi.ROLE_SUB).apply {
            tag = "playerStatus"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            ThemeUi.hideOverflow(this)
        }
        meta.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(songTitle)
            addView(sub)
            addView(statusTxt)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = ThemeUi.dp(this@nowPlayingBar, 10f)
            }
        })
        favoriteButton = ImageButton(this).apply {
            setImageDrawable(j5i.spatial8d.ui.HeartDrawable(false, palette.controlText))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageTintList = null
            background = null
            setPadding(ThemeUi.dp(this@nowPlayingBar, 6f), 0, ThemeUi.dp(this@nowPlayingBar, 6f), 0)
            layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@nowPlayingBar, 34f), ThemeUi.dp(this@nowPlayingBar, 30f))
            contentDescription = "收藏"
            setOnClickListener {
                val item = currentItem ?: return@setOnClickListener
                if (isLive || item.uri.isBlank() || item.uri.startsWith("http")) return@setOnClickListener
                musicStore.toggleFavorite(item.id)
                playbackStatus = if (musicStore.isFavorite(item.id)) "已加入收藏" else "已取消收藏"
                queue = musicStore.view(songs)
                if (musicStore.playlistIndex == j5i.spatial8d.data.FAVORITES_INDEX) currentQueue = queue
                refreshPlayback()
                if (playlistDialog?.isShowing() == true) playlistDialog?.refreshContent()
            }
        }
        meta.addView(favoriteButton)
        exportButton = ThemeUi.button(this, palette, "导出", ThemeUi.ROLE_ACCENT, minHeightDp = 30f).apply {
            background = ThemeUi.bgDrawable(this@nowPlayingBar, if (dark) 0xff263e55.toInt() else 0xffe3efff.toInt(), 8f)
            textSize = 11f
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@nowPlayingBar, 46f), ThemeUi.dp(this@nowPlayingBar, 30f)).apply {
                leftMargin = ThemeUi.dp(this@nowPlayingBar, 8f)
            }
            setOnClickListener {
                if (isLive) playerBar.findViewWithTag<TextView>("playerStatus")?.let(::togglePlaybackRecording)
                else showExportSheet()
            }
        }
        meta.addView(exportButton)
        bar.addView(meta)
        val timeline = ThemeUi.row(this)
        currentTime = ThemeUi.text(this, "00:00", 11f, palette, role = ThemeUi.ROLE_SUB)
        currentTime.layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this, 38f), ViewGroup.LayoutParams.WRAP_CONTENT)
        currentTime.gravity = Gravity.CENTER
        timeline.addView(currentTime)
        seek = SeekBar(this).apply {
            max = 1000
            contentDescription = "播放进度"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) currentTime.text = time((service?.player?.duration ?: 0.0) * progress / 1000.0)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) { seeking = true }
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    val target = (service?.player?.duration ?: 0.0) * seekBar.progress / 1000.0
                    // 加载回灌产生的伪拖动不能跳曲；真正手势低于 0.8 秒也直接忽略。
                    if (SystemClock.elapsedRealtime() >= seekGuardUntil &&
                        kotlin.math.abs(target - (service?.player?.position ?: 0.0)) >= 0.8)
                        service?.player?.seek(target, userInitiated = true)
                    seeking = false
                    refreshPlayback()
                }
            })
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = ThemeUi.dp(this@nowPlayingBar, 6f); rightMargin = ThemeUi.dp(this@nowPlayingBar, 6f)
            }
        }
        timeline.addView(seek)
        durationTime = ThemeUi.text(this, "00:00", 11f, palette, role = ThemeUi.ROLE_SUB)
        durationTime.layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this, 38f), ViewGroup.LayoutParams.WRAP_CONTENT)
        durationTime.gravity = Gravity.CENTER
        timeline.addView(durationTime)
        bar.addView(timeline)
        liveTimeline = LinearLayout(this).apply { visibility = View.GONE }
        val controls = ThemeUi.row(this, gravity = Gravity.CENTER).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeUi.dp(this@nowPlayingBar, 62f)).apply { topMargin = ThemeUi.dp(this@nowPlayingBar, 4f) }
        }
        transportRow = controls
        controls.addView(LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(iconControl(j5i.spatial8d.R.drawable.ic_loop, action = {
                val modes = PlayMode.entries
                val nextMode = modes[(modes.indexOf(currentMode()) + 1) % modes.size]
                preferences.edit().putString("mode", nextMode.name).apply()
                refreshPlayback()
            }))
        })
        controls.addView(iconControl(j5i.spatial8d.R.drawable.ic_prev) { previous() }.apply {
            layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@nowPlayingBar, 38f), ThemeUi.dp(this@nowPlayingBar, 38f))
        })
        playButton = iconControl(j5i.spatial8d.R.drawable.ic_play, big = true, action = { togglePlayback() }).apply {
            layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@nowPlayingBar, 48f), ThemeUi.dp(this@nowPlayingBar, 48f)).apply { leftMargin = ThemeUi.dp(this@nowPlayingBar, 20f); rightMargin = ThemeUi.dp(this@nowPlayingBar, 20f) }
        }
        controls.addView(playButton)
        controls.addView(iconControl(j5i.spatial8d.R.drawable.ic_next) { next() }.apply {
            layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@nowPlayingBar, 38f), ThemeUi.dp(this@nowPlayingBar, 38f))
        })
        controls.addView(LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.RIGHT
            addView(iconControl(j5i.spatial8d.R.drawable.ic_list) { togglePlaylist() })
        })
        bar.addView(controls)
        return bar
    }

internal fun MainActivity.iconControl(icon: Int, big: Boolean = false, action: () -> Unit): ImageButton = ImageButton(this).apply {
        setImageResource(icon)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        imageTintList = ColorStateList.valueOf(if (big) 0xffffffff.toInt() else palette.controlText)
        tag = if (big) ThemeUi.ROLE_ACCENT_FILL else ThemeUi.ROLE_CONTROL
        bg(this, if (big) palette.accent else palette.control, if (big) 26f else 19f)
        setPadding(ThemeUi.dp(this@iconControl, if (big) 12f else 9f), 0, ThemeUi.dp(this@iconControl, if (big) 12f else 9f), 0)
        layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@iconControl, if (big) 48f else 38f), ThemeUi.dp(this@iconControl, if (big) 48f else 38f))
        setOnClickListener { action() }
        contentDescription = "播放控制"
    }

internal fun MainActivity.bg(view: View, color: Int, radiusDp: Float = 12f) {
        ThemeUi.bg(view, color, radiusDp)
    }

internal fun MainActivity.shutdownForExit() {
        service?.player?.stopRecording()
        service?.radio?.stopRecording()
        service?.radio?.stop()
        service?.stopLocal()
        try {
            stopService(Intent(this, PlaybackService::class.java))
        } catch (_: Exception) {}
        service = null
        bound = false
    }

    /** 返回键没有其它弹层可关闭时，明确由用户选择后台保留或彻底退出。 */

internal fun MainActivity.isPlaying(): Boolean =
        if (isLive) service?.radio?.isPlaying == true else service?.player?.isPlaying == true

internal fun MainActivity.finishRecording(label: String, statusView: TextView) {
        val path = recordingPath
        recordingKind = ""; recordingPath = ""
        statusView.text = "$label 已停止，正在保存…"
        dismissSource()
        publishRecording(java.io.File(path))
        refreshPlayback()
    }

internal fun MainActivity.publishRecording(file: java.io.File) {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/8D")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        try {
            if (uri == null) error("无法创建音乐库条目")
            contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: error("无法写入录音")
            values.clear(); values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            playbackStatus = "录音已保存到 Music/8D/${file.name}"
        } catch (e: Exception) {
            if (uri != null) contentResolver.delete(uri, null, null)
            playbackStatus = "录音保存失败：${e.message}"
        } finally { file.delete(); refreshPlayback() }
    }
