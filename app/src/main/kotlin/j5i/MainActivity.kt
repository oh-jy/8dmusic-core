package j5i.spatial8d

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.ContentValues
import android.content.pm.PackageManager
import android.app.Dialog
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.provider.OpenableColumns
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
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import android.widget.RadioButton
import android.widget.HorizontalScrollView
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import j5i.spatial8d.audio.AudioDecoder
import j5i.spatial8d.audio.PlaybackService
import j5i.spatial8d.audio.Lyrics
import j5i.spatial8d.audio.LyricsCandidate
import j5i.spatial8d.audio.LyricsResult
import j5i.spatial8d.ui.LyricsView
import j5i.spatial8d.ui.WaveformView
import j5i.spatial8d.ui.VisualizerView
import com.j5i.spatial8d.HttpMediaDataSource
import com.j5i.spatial8d.TsAdtsMediaDataSource
import com.j5i.spatial8d.MicWavRecorder
import j5i.spatial8d.core.DEFAULT_SPACE_PRESET
import j5i.spatial8d.core.RadioCatalog
import j5i.spatial8d.core.RadioStation
import j5i.spatial8d.core.SLIDERS
import j5i.spatial8d.core.SPACE_PRESETS
import j5i.spatial8d.core.SpacePreset
import j5i.spatial8d.core.PARAM_INFO
import j5i.spatial8d.core.PitchPreset
import j5i.spatial8d.core.PITCH_PRESETS
import j5i.spatial8d.core.TransformParams
import j5i.spatial8d.core.clampSlider
import j5i.spatial8d.core.formatValue
import j5i.spatial8d.core.matchPresetIn
import j5i.spatial8d.data.MusicItem
import j5i.spatial8d.data.MusicStore
import j5i.spatial8d.data.PlayMode
import j5i.spatial8d.data.PresetStore
import j5i.spatial8d.ui.CurveView
import j5i.spatial8d.ui.SpatialVisualization
import j5i.spatial8d.ui.PlaylistDialog
import j5i.spatial8d.ui.PlaylistHost
import j5i.spatial8d.ui.ThemeUi
import j5i.spatial8d.ui.AppLanguage
import j5i.spatial8d.ui.DrivingView
import j5i.spatial8d.ui.PlaylistMetadataHost
import java.util.Locale
import org.json.JSONObject
import kotlin.math.roundToInt
import kotlin.math.pow

/** 页面只持有一份空间参数和队列状态；实际解码、DSP、播放统一交给 SpatialPlayer。 */
class MainActivity : AppCompatActivity(), PlaylistHost, PlaylistMetadataHost {
    private lateinit var musicStore: MusicStore
    private lateinit var presetStore: PresetStore
    private var service: PlaybackService? = null
    private var bound = false
    private var songs = mutableListOf<MusicItem>()
    private var queue = listOf<MusicItem>()
    private var currentIndex = -1
    private var currentQueue = listOf<MusicItem>()
    private var preset = DEFAULT_SPACE_PRESET
    private var customPresets = mutableListOf<SpacePreset>()
    private var loading = false
    private var loadGeneration = 0
    private var autoplayRequests = 0
    private var currentUri: String? = null
    private var restoreUri: String? = null
    private var restorePosition = 0.0
    private var currentItem: MusicItem? = null
    private var pageActive = true
    private var exporting = false
    private var dark = true
    /** 0=深色 1=浅色 2=跟随系统。dark 字段始终是"当前生效"的结果，由 themeMode 派生。 */
    private var themeMode = 0
    private var playbackStatus = "就绪"
    private var transform = TransformParams()
    private var reverse = false
    private val main = Handler(Looper.getMainLooper())
    private val preferences by lazy { getSharedPreferences("spatial8d", Context.MODE_PRIVATE) }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))
    private fun t(key: String): String = AppLanguage.text(this, key)

    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var songTitle: TextView
    private lateinit var position: TextView
    private lateinit var seek: SeekBar
    private lateinit var visualization: SpatialVisualization
    private lateinit var curve: CurveView
    private lateinit var lyricsView: LyricsView
    private lateinit var visualizerView: VisualizerView
    private lateinit var curveLegend: LinearLayout
    private lateinit var curveContent: FrameLayout
    private val lyrics by lazy { Lyrics(this) }
    private var lyricsGeneration = 0
    private var currentLyrics: LyricsResult? = null
    private lateinit var presetPicker: TextView
    private lateinit var playButton: View
    private lateinit var parameterPanel: LinearLayout
    private lateinit var palette: ThemeUi.Palette
    private var parameterOpen = false
    private var transformOpen = false
    private lateinit var transformBody: LinearLayout
    private lateinit var favoriteButton: ImageButton
    private lateinit var presetNote: TextView
    private lateinit var speedSlider: SeekBar
    private lateinit var pitchSlider: SeekBar
    private lateinit var speedLabel: TextView
    private lateinit var pitchLabel: TextView
    private lateinit var reverseSwitch: SwitchCompat
    private lateinit var tapeSwitch: SwitchCompat
    private lateinit var denoiseLabel: TextView
    private lateinit var denoiseSlider: SeekBar
    private lateinit var clarityLabel: TextView
    private lateinit var claritySlider: SeekBar
    private lateinit var pitchPicker: TextView
    private var dirButtons = listOf<Button>()
    private var speedChipButtons = listOf<Button>()
    private var pitchTicks = listOf<Button>()
    private val sliderValues = mutableMapOf<String, TextView>()
    private val sliderControls = mutableMapOf<String, SeekBar>()
    private var updatingControls = false
    private lateinit var durationHint: TextView
private var infoDialog: Dialog? = null
private var exportDialog: Dialog? = null
private var visualizerDialog: Dialog? = null
private var drivingDialog: Dialog? = null
private var drivingView: DrivingView? = null
private var drivingAutoDismissed = false
private var lastCarMode = false
private var popupVisualizer: j5i.spatial8d.ui.VisualizerView? = null
    private lateinit var transportRow: LinearLayout
    private lateinit var playerBar: LinearLayout
    private lateinit var exportButton: Button
    private lateinit var settingsButton: ImageButton
    private lateinit var drivingButton: ImageButton
    private lateinit var disc: ImageView
    private lateinit var modeTextView: TextView
    private lateinit var currentTime: TextView
    private lateinit var durationTime: TextView
    private var seeking = false
    private var isLive = false
    private var stationIndex = -1
    private var currentStationId = ""
    private var radioSources = listOf<j5i.spatial8d.core.RadioSource>()
    private var radioSourceIndex = -1
    private var radioGeneration = 0
    private var sourceRegion = "全部"
    private var sourceCity = "全部"
    private var sourceType = "全部"
    private var stationListBody: LinearLayout? = null
    private var curvePanel = 0
    private var sourceSearchSeq = 0
    private var recordingKind = ""
    private var recordingPath = ""
    private var recordingStartedAt = 0L
    private var fmDialog: Dialog? = null
    private var fmUnlocked = false
    private var sourceTapCount = 0
    private var sourceTapAt = 0L
    private var exitDialog: Dialog? = null
    private var lyricsDialog: Dialog? = null
    private lateinit var fmButton: Button
    private lateinit var radioRefreshButton: Button
    private var sourceStatusView: TextView? = null
    private var playlistDialog: PlaylistDialog? = null
    private var seekGuardUntil = 0L
    private var micRecorder: MicWavRecorder? = null
    private lateinit var liveTimeline: LinearLayout
    private lateinit var recordingLabel: TextView
    private lateinit var transformCardRoot: LinearLayout
    private var lastStateSavedAt = 0L

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as PlaybackService.Binder).service
            service?.controls = object : PlaybackService.Controls {
                override fun play() { main.post { if (!isPlaying()) togglePlayback() } }
                override fun pause() { main.post { if (isPlaying()) togglePlayback() } }
                override fun previous() { main.post { service?.previous() } }
                override fun next() { main.post { service?.next() } }
            }
            service?.onPlaybackError = { main.post { toast(it) } }
            service?.onPlaybackChanged = { main.post { refreshPlayback() } }
            service?.onTrackChanged = { item -> main.post { adoptServiceTrack(item) } }
            installPcmRenderer()
            service?.radio?.onReady = { main.post {
                playbackStatus = "电台播放中 · ${currentItem?.name ?: "自定义电台"}"
                refreshPlayback()
            } }
            service?.radio?.onEnd = { main.post { playbackStatus = "电台已断开"; refreshPlayback() } }
            service?.radio?.onError = { main.post { playbackStatus = it; refreshPlayback() } }
            bound = true
            adoptOrRestorePlayback()
            refreshAll()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            bound = false
            service = null
        }
    }

    private val openAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) addAndPlay(uri)
    }
    private val saveWav = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        if (uri != null) exportWav(uri)
    }
    private val requestAudio = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) scanMusic() else toast("未授予音乐库权限，仍可通过“添加音频”选择文件")
    }
    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startMicRecording() else toast("没有麦克风录音权限，请到系统设置开启")
    }
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        musicStore = MusicStore(this)
        presetStore = PresetStore(this)
        customPresets = presetStore.load().toMutableList()
        // 首屏只读缓存；MediaStore 查询统一走后台，避免冷启动阻塞所有点击。
        songs = musicStore.cached().toMutableList()
        queue = musicStore.view(songs)
        // 主题三态：0 深色 / 1 浅色 / 2 跟随系统。首次启动沿用旧 dark 偏好迁移。
        themeMode = if (preferences.contains("themeMode")) preferences.getInt("themeMode", 0)
        else if (preferences.getBoolean("dark", true)) 0 else 1
        dark = resolveDark()
        playbackStatus = t("ready")
        restorePlaybackState()
        palette = ThemeUi.palette(dark)
        buildUi()
        visualization.pcmClock = visualizerView.pcmClock
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (exitDialog?.isShowing == true) { exitDialog?.dismiss(); return }
                var dismissed = false
                listOf(fmDialog, infoDialog, exportDialog, visualizerDialog, lyricsDialog).forEach { dialog ->
                    if (dialog?.isShowing == true) { dialog.dismiss(); dismissed = true }
                }
                if (playlistDialog?.isShowing() == true) { playlistDialog?.dismiss(); dismissed = true }
                if (!dismissed) showExitDialog()
            }
        })
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(0, bars.top, 0, ThemeUi.dp(this, 176f) + bars.bottom)
                playerBar.setPadding(ThemeUi.dp(this, 12f), ThemeUi.dp(this, 6f), ThemeUi.dp(this, 12f), ThemeUi.dp(this, 6f) + bars.bottom)
                insets
            }
        }
        startService(Intent(this, PlaybackService::class.java))
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        bindService(Intent(this, PlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (hasAudioPermission()) scanMusic()
        scheduleLyricsPrefetch()
        main.post(ticker)
    }

    override fun onDestroy() {
        main.removeCallbacks(prefetchLyrics)
        service?.player?.stopRecording()
        service?.radio?.stopRecording()
        micRecorder?.stop()
        main.removeCallbacks(ticker)
        service?.controls = null
        service?.onTrackChanged = null
        service?.onPlaybackError = null
        service?.onPlaybackChanged = null
        service?.player?.onPcm = null
        service?.radio?.onPcm = null
        if (bound) unbindService(connection)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
        }
        root.addView(navbar())
        visualization = SpatialVisualization(this).apply {
            val available = (resources.displayMetrics.heightPixels / resources.displayMetrics.density - 52 - 184 - 216).coerceAtLeast(200f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeUi.dp(this@MainActivity, available * 0.62f))
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

    private fun resizeCurveToRemainingSpace() {
        if (!::curveContent.isInitialized) return
        val card = curveContent.parent as? View ?: return
        val extra = (card.parent as? ViewGroup)?.paddingBottom ?: 0
        val frame = card.parent?.parent as? View ?: return
        val remaining = frame.height - playerBar.height - root.paddingTop - root.paddingBottom - extra
        val height = (remaining - (root.height - curveContent.height)).coerceAtLeast(ThemeUi.dp(this, 140f))
        if (height == curveContent.height) return
        curveContent.layoutParams = curveContent.layoutParams.apply { this.height = height }
    }

    private fun refreshAll() {
        updatingControls = true
        palette = ThemeUi.palette(dark)
        // 下拉、展开控件、方向、曲线共用 preset，任何入口修改后都从这里统一回灌。
        presetPicker.text = preset.name.replaceFirst("标准绕头 · ", "标准模式 · ")
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
        if (::pitchPicker.isInitialized) pitchPicker.text = pitchPickerLabel()
        if (::pitchLabel.isInitialized) {
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
        if (::denoiseLabel.isInitialized) {
            denoiseLabel.text = if (transform.denoiseLevel == 0) t("off") else "${transform.denoiseLevel}%"
            denoiseSlider.progress = transform.denoiseLevel
        }
        if (::clarityLabel.isInitialized) {
            clarityLabel.text = "${transform.clarityLevel}%"
            claritySlider.progress = transform.clarityLevel
        }
        updatingControls = false
        refreshControls()
        rebuildTransport()
        refreshCardState()
        refreshPlayback()
    }

    private fun refreshControls() {
        ThemeUi.configureSeekBar(seek, palette, playback = true)
        sliderControls.values.forEach { ThemeUi.configureSeekBar(it, palette) }
        ThemeUi.configureSeekBar(speedSlider, palette)
        ThemeUi.configureSeekBar(pitchSlider, palette)
        if (::denoiseSlider.isInitialized) ThemeUi.configureSeekBar(denoiseSlider, palette)
        if (::claritySlider.isInitialized) ThemeUi.configureSeekBar(claritySlider, palette)
        ThemeUi.picker(presetPicker, palette)
        ThemeUi.picker(pitchPicker, palette)
    }

    private fun rebuildTransport() {
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

    private fun refreshTheme() {
        palette = ThemeUi.palette(dark)
        tint(root, palette.text)
        root.setBackgroundColor(palette.background)
        (root.parent?.parent as? FrameLayout)?.setBackgroundColor(palette.background)
        root.findViewWithTag<View>("navbar")?.setBackgroundColor(palette.background)
        visualization.dark = dark
        curve.dark = dark
        visualizerView.dark = dark
        popupVisualizer?.dark = dark
        if (::lyricsView.isInitialized) lyricsView.dark = dark
        if (::playerBar.isInitialized) ThemeUi.bg(playerBar, if (dark) 0xff17191d.toInt() else 0xfff7f8fa.toInt(), 0f)
        // 透明圆形应用图标自带颜色，深浅模式下都不叠 tint。
        if (::disc.isInitialized) disc.imageTintList = null
        tint(root, palette.text)
        if (::playerBar.isInitialized) ThemeUi.applyTheme(playerBar, palette)
        // PNG 图标不经过 Button 主题回刷；播放键白色，普通控制键随主题切换。
        fun tintIcons(view: View) {
            if (view is ImageButton && !(::favoriteButton.isInitialized && view === favoriteButton)) view.imageTintList = ColorStateList.valueOf(
                if (view.tag == ThemeUi.ROLE_ACCENT_FILL) 0xffffffff.toInt() else palette.controlText)
            if (view is ViewGroup) for (i in 0 until view.childCount) tintIcons(view.getChildAt(i))
        }
        if (::playerBar.isInitialized) tintIcons(playerBar)
        if (::settingsButton.isInitialized) {
            settingsButton.imageTintList = ColorStateList.valueOf(palette.controlText)
            settingsButton.background = ThemeUi.bgDrawable(this, palette.control, 15f)
        }
        if (::drivingButton.isInitialized) {
            drivingButton.imageTintList = ColorStateList.valueOf(palette.controlText)
            drivingButton.background = ThemeUi.bgDrawable(this, palette.control, 15f)
        }
        tint(root, palette.text)
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is LinearLayout && child.tag == "card") {
                ThemeUi.bg(child, palette.surface, 14f)
            }
        }
        refreshControls()
        refreshAll()
        listOf(fmDialog, infoDialog, exportDialog, visualizerDialog).forEach { dialog ->
            if (dialog?.isShowing == true) {
                val content = dialog.findViewById<ViewGroup>(android.R.id.content)
                if (content != null) ThemeUi.applyTheme(content, palette)
            }
        }
        // 状态栏/导航栏图标随背景切换，浅色的时间、电量必须保持深色可读。
        preferences.edit().putInt("themeMode", themeMode).putBoolean("dark", dark).apply()
    }

    /** 解析当前应生效的深色状态；跟随系统时读 Configuration.uiMode。 */
    private fun resolveDark(): Boolean = when (themeMode) {
        0 -> true
        1 -> false
        else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /** 系统深浅色切换时，跟随模式自动刷新。 */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val carMode = newConfig.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK ==
            android.content.res.Configuration.UI_MODE_TYPE_CAR
        if (carMode && !lastCarMode && !drivingAutoDismissed) showDrivingMode()
        lastCarMode = carMode
        if (themeMode == 2) {
            val newDark = resolveDark()
            if (newDark != dark) { dark = newDark; refreshTheme() }
        }
    }

    private fun tint(view: View, color: Int) {
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

    private fun refreshPlayback() {
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
        if (::lyricsView.isInitialized) lyricsView.setPosition(lyricPositionMs)
        if (::visualizerView.isInitialized) visualizerView.setPosition(lyricPositionMs)
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
        val statusLine = if (curvePanel != 2 && !visualLyrics) {
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
        if (::liveTimeline.isInitialized) {
            seek.visibility = if (isLive) View.GONE else View.VISIBLE
            currentTime.visibility = if (isLive) View.GONE else View.VISIBLE
            durationTime.visibility = if (isLive) View.GONE else View.VISIBLE
            liveTimeline.visibility = if (isLive) View.VISIBLE else View.GONE
            (liveTimeline.getChildAt(0) as TextView).setTextColor(0xffff5d5d.toInt())
            (liveTimeline.getChildAt(1) as TextView).setTextColor(0xffff8a80.toInt())
        }
        if (::exportButton.isInitialized) {
            exportButton.background = ThemeUi.bgDrawable(this,
                if (dark) 0xff263e55.toInt() else 0xffe3efff.toInt(), 8f)
            exportButton.text = if (isLive) (if (recordingKind == "playback") "停止" else "录音") else "导出"
            exportButton.setTextColor(if (isLive && recordingKind == "playback") 0xffff5d5d.toInt() else palette.accent)
            exportButton.contentDescription = if (isLive) "录制电台最终输出" else "导出当前音频"
        }
        if (::radioRefreshButton.isInitialized) radioRefreshButton.visibility = if (isLive) View.VISIBLE else View.GONE
        if (::favoriteButton.isInitialized) {
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
        (liveTimeline.findViewWithTag<TextView>("liveStatus"))?.text = playbackStatus
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

    private fun applyPreset() {
        service?.player?.preset = preset
        service?.controlsPreset = preset
        service?.radio?.preset = preset
        savePlaybackState()
        refreshAll()
    }

    private fun applyTransform() {
        if (isLive) {
            // 直播时间轴不可变速或倒放；与 UTS 一致，只把半音映射到 AudioTrack pitch。
            service?.radio?.semi = transform.semi.roundToInt()
            playbackStatus = if (transform.semi == 0.0) "电台已恢复原声" else "电台变声已更新（${transform.semi.toInt()}半音）"
            refreshAll()
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
        refreshAll()
    }

    private fun updateParameter(key: String, value: Double) {
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
        refreshAll()
    }

    private fun parameter(key: String): Double = when (key) {
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

    private fun addAndPlay(uri: Uri) {
        // SAF 授权跨重启保留，手动添加的歌曲不会因为应用重启而从队列失效。
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        val name = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        } ?: uri.lastPathSegment ?: "音频"
        val item = MusicItem(System.nanoTime(), name, uri.toString())
        musicStore.remember(item)
        songs = (musicStore.cached() + listOf(item)).distinctBy { it.uri }.toMutableList()
        queue = musicStore.view(songs)
        currentIndex = songs.indexOfFirst { it.uri == item.uri }
        load(item)
    }

    override fun isLocalMusicPlaying(): Boolean = !isLive && service?.player?.isPlaying == true

    override fun scanMusic() {
        if (loading) return
        if (!hasAudioPermission()) {
            // 仅在用户主动扫描时申请，冷启动静默扫描不能突然弹权限框。
            requestAudio.launch(audioPermission())
            return
        }
        playbackStatus = "正在扫描音乐库…"
        loading = true
        Thread {
            val result = musicStore.mergeScan(musicStore.scan())
            musicStore.saveLibrary(result)
            main.post {
                loading = false
                songs = result.toMutableList()
                queue = musicStore.view(songs)
                playbackStatus = "音乐库 ${songs.size} 首"
                scheduleLyricsPrefetch()
                refreshPlayback()
            }
        }.start()
    }

    private val prefetchLyrics = Runnable { lyrics.prefetch(songs.toList()) }

    private fun scheduleLyricsPrefetch() {
        main.removeCallbacks(prefetchLyrics)
        // 冷启动先完成恢复与当前歌词请求，再串行补曲库缓存，避免争抢首屏网络。
        main.postDelayed(prefetchLyrics, 8_000)
    }

    private fun load(item: MusicItem) {
        load(item, true)
    }

    private fun load(item: MusicItem, autoplay: Boolean) {
        // 所有选歌、切歌和冷启动恢复共用同一条加载链路，旧代号回调全部丢弃。
        if (recordingKind.isNotEmpty()) { playbackStatus = "请先停止录音，再切换歌曲"; refreshPlayback(); return }
        if (::visualizerView.isInitialized) visualizerView.pcmClock.reset()
        val generation = ++loadGeneration
        ++radioGeneration
        isLive = false
        stationIndex = -1
        currentStationId = ""
        radioSources = emptyList()
        radioSourceIndex = -1
        service?.radio?.stop()
        if (autoplay) autoplayRequests++ else autoplayRequests = 0
        val resumePosition = if (autoplay) 0.0 else restorePosition
        service?.player?.stop()
        currentItem = item
        loadLyrics(item)
        queue = musicStore.view(songs)
        currentIndex = queue.indexOfFirst { it.id == item.id && it.uri == item.uri }
        currentQueue = queue
        currentUri = item.uri
        playbackStatus = t("status.preparing")
        service?.let {
            it.controlsPreset = preset
            it.controlsTransform = transform
            it.controlsReverse = reverse
            it.syncQueue(currentQueue, currentUri)
            it.onTrackChanged = { next -> main.post { adoptServiceTrack(next) } }
            it.load(item, currentQueue, autoplay)
            return
        }
        songTitle.text = Lyrics.displayTitle(item.name)
        playbackStatus = "正在准备播放…"
        Thread {
            try {
                val remote = item.uri.startsWith("https://") || item.uri.startsWith("http://")
                val isOnline = remote && currentQueue.none { it.uri == item.uri }
                val stream = if (remote) {
                    if (item.uri.substringBefore('?').endsWith(".m3u8", ignoreCase = true))
                        TsAdtsMediaDataSource(item.uri) else HttpMediaDataSource(item.uri)
                } else null
                try {
                    if (stream != null) AudioDecoder(this).decode(stream, { percent ->
                        main.post { if (generation == loadGeneration && service?.player?.isPlaying != true) {
                            playbackStatus = t("status.preparing"); refreshPlayback()
                        } }
                    }, { generation == loadGeneration }, onHead = { head, _ ->
                        main.post { prepareLoaded(item, head, autoplay, resumePosition, generation) }
                    }) else AudioDecoder(this).decode(musicStore.readableUri(item), { percent ->
                    main.post { if (generation == loadGeneration && service?.player?.isPlaying != true) {
                        playbackStatus = t("status.preparing"); refreshPlayback()
                    } }
                }, { generation == loadGeneration }, onHead = { head, _ ->
                    main.post { prepareLoaded(item, head, autoplay, resumePosition, generation) }
                })
                } finally { stream?.close() }
                main.post {
                    if (generation == loadGeneration) {
                        if (service?.player?.isPlaying != true) playbackStatus = t("status.ready")
                        refreshPlayback()
                    }
                }
            } catch (e: Exception) {
                main.post {
                    if (generation != loadGeneration) return@post
                    if (e.message?.contains("已取消旧音频解码") == true) return@post
                    service?.player?.stop()
                    playbackStatus = e.message ?: "加载失败"
                    if (autoplay && autoplayRequests <= 3 && currentQueue.isNotEmpty()) {
                        // 自动续播最多容错三首，坏文件不能造成无限跳曲。
                        main.postDelayed({ if (generation == loadGeneration) next(auto = true) }, 300)
                    } else if (autoplay) playbackStatus = "连续加载失败，已停止自动续播：$playbackStatus"
                    refreshPlayback()
                }
            }
        }.start()
    }

    private fun adoptServiceTrack(item: MusicItem) {
        if (currentUri == item.uri) return
        if (::visualizerView.isInitialized) visualizerView.pcmClock.reset()
        currentQueue = service?.queueSnapshot?.ifEmpty { currentQueue } ?: currentQueue
        currentItem = item
        currentUri = item.uri
        currentIndex = currentQueue.indexOfFirst { it.uri == item.uri }
        loadLyrics(item)
        playbackStatus = t("status.auto.next")
        refreshPlayback()
    }

    /** 本地文件与在线音频共用首块起播，后续 PCM 继续写入同一播放缓冲。 */
    private fun prepareLoaded(item: MusicItem, head: j5i.spatial8d.core.PcmBuffer,
        autoplay: Boolean, resumePosition: Double, generation: Int) {
        if (generation != loadGeneration) return
        service?.player?.prepare(head, item.duration / 1000.0)
        service?.player?.preset = preset
        service?.player?.transform = transform
        service?.player?.reverse = reverse
        // prepare 已把起点设为零；恢复位置时再定位一次，避免连续重建两代渲染缓存。
        if (resumePosition > 0.0) service?.player?.seek(resumePosition)
        playbackStatus = "已加载 · ${head.sampleRate} Hz"
        if (service?.player?.isPlaying == true) playbackStatus = t("status.playing")
        seekGuardUntil = SystemClock.elapsedRealtime() + 1500
        if (!item.uri.startsWith("http")) preferences.edit().putString("lastUri", item.uri).apply()
        if (autoplayRequests > 0) {
            requestPlaybackFocus()
            service?.player?.play()
        }
        refreshPlayback()
        savePlaybackState()
    }

    private fun adoptOrRestorePlayback() {
        if (currentItem != null) return
        val active = service?.currentTrack
        if (active != null && service?.player?.isPrepared == true) {
            currentQueue = service?.queueSnapshot ?: queue
            adoptServiceTrack(active)
            refreshPlayback()
            return
        }
        val uri = restoreUri ?: preferences.getString("lastUri", null) ?: return
        val item = songs.firstOrNull { it.uri == uri }
        if (item == null) {
            currentUri = uri
            return
        }
        currentUri = uri
        currentItem = item
        currentQueue = queue
        currentIndex = queue.indexOfFirst { it.id == item.id && it.uri == item.uri }
        songTitle.text = Lyrics.displayTitle(item.name)
        if (service?.player?.isPrepared == true) {
            // Activity 重建时播放器仍在运行，直接接管真实播放头，不能再次 stop/load 清零进度。
            restorePosition = 0.0
            refreshPlayback()
        } else {
            // 进程被回收后服务为空，再从持久化位置恢复为暂停状态。
            load(item, false)
        }
    }

    private fun restorePlaybackState() {
        val text = preferences.getString("playbackState", null) ?: return
        try {
            val state = JSONObject(text)
            preset = try {
                val restored = SpacePreset("标准绕头 · 默认", "", state.getDouble("period"),
                    state.getDouble("elev"), state.getDouble("depth"), state.getDouble("phase"),
                    state.getDouble("direction"), state.getDouble("width"), state.getDouble("room"),
                    state.getDouble("rt60"), state.getDouble("wet"), state.getBoolean("raw"))
                matchingPresetOrDefault(restored, SPACE_PRESETS + customPresets)
            } catch (_: Exception) {
                DEFAULT_SPACE_PRESET
            }
            reverse = state.getBoolean("reverse")
            transform = TransformParams(state.getDouble("speed"), state.getDouble("semi"),
                state.getBoolean("tape"), denoise = state.optInt("denoise", 65),
                clarity = state.optInt("clarity", 50))
            restorePosition = state.getDouble("position")
            restoreUri = state.getString("uri")
        } catch (_: Exception) {}
    }

    private fun savePlaybackState() {
        val player = service?.player ?: return
        val uri = currentUri ?: return
        // 保存歌曲、位置和完整听音参数；冷启动只恢复并暂停，不自动播放。
        preferences.edit().putString("playbackState", JSONObject().apply {
            put("uri", uri); put("position", player.position)
            put("period", preset.period); put("elev", preset.elev); put("depth", preset.depth)
            put("phase", preset.phase); put("direction", preset.direction); put("width", preset.width)
            put("room", preset.room); put("rt60", preset.rt60); put("wet", preset.wet); put("raw", preset.raw)
            put("reverse", reverse); put("speed", transform.speed); put("semi", transform.semi); put("tape", transform.tape)
            put("denoise", transform.denoiseLevel)
            put("clarity", transform.clarityLevel)
        }.toString()).apply()
        lastStateSavedAt = SystemClock.uptimeMillis()
    }

    override fun songs(): List<MusicItem> = songs

    override fun onPause() {
        // 取消而不是只 return，避免后台保留无意义的 UI 任务。
        pageActive = false
        service?.appInForeground = false
        // 后台播放时立刻补一条通知，保持服务存活。
        service?.let { s ->
            val playing = s.player.isPlaying || s.radio.isPlaying
            if (playing) s.updateMedia(s.currentTrack?.name ?: "8D 空间音频", s.currentTrack?.artist ?: "", true,
                s.player.position, s.player.duration)
        }
        main.removeCallbacks(ticker)
        main.removeCallbacks(prefetchLyrics)
        savePlaybackState()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        pageActive = true
        installPcmRenderer()
        service?.appInForeground = true
        // 回到前台立即撤掉播放通知，不打扰使用。
        service?.let { s ->
            val playing = s.player.isPlaying || s.radio.isPlaying
            s.updateMedia(s.currentTrack?.name ?: "8D 空间音频", s.currentTrack?.artist ?: "", playing,
                s.player.position, s.player.duration)
        }
        tint(root, palette.text)
        refreshPlayback()
        // onPause 已取消 ticker；每次回到前台都必须重新建立唯一刷新循环。
        main.removeCallbacks(ticker)
        main.post(ticker)
        scheduleLyricsPrefetch()
    }

    override fun onStop() {
        savePlaybackState()
        super.onStop()
    }

    private fun togglePlayback() {
        if (isLive) {
            val radio = service?.radio ?: return
            if (radio.isPlaying) { radio.pause(); playbackStatus = "电台已暂停" }
            else if (service?.play() == true) playbackStatus = "电台播放中"
            refreshPlayback()
            return
        }
        val player = service?.player ?: return
        if (player.duration == 0.0) {
            currentItem?.let { load(it) } ?: showPlaylist()
            return
        }
        if (player.isPlaying) {
            player.pause()
        } else {
            if (service?.play() != true) {
                songs.getOrNull(currentIndex)?.let { load(it) }
            }
        }
        refreshPlayback()
    }

    private fun next(auto: Boolean = false) {
        if (isLive) { skipStation(1); return }
        if (!auto) autoplayRequests = 0
        val items = if (currentQueue.isNotEmpty()) currentQueue else queue
        if (currentItem != null && items.indexOfFirst { it.uri == currentUri } < 0) {
            if (auto) service?.player?.replay(forcePlay = true) else load(currentItem!!)
            return
        }
        if (items.isEmpty()) {
            playbackStatus = "播放列表为空，请先添加或扫描音频"
            refreshPlayback()
            showPlaylist()
            return
        }
        if (auto && currentMode() == PlayMode.LOOP_ONE) {
            service?.player?.replay(forcePlay = true)
            return
        }
        val current = items.indexOfFirst { it.uri == currentUri }
        val nextIndex = when (currentMode()) {
            PlayMode.SHUFFLE -> if (items.size > 1) (items.indices.filter { it != current }).random() else 0
            else -> (current + 1 + items.size) % items.size
        }
        currentQueue = items
        Log.i("8DQueue", "next auto=$auto index=$nextIndex title=${items[nextIndex].name}")
        load(items[nextIndex])
    }

    private fun previous() {
        if (isLive) { skipStation(-1); return }
        val items = if (currentQueue.isNotEmpty()) currentQueue else queue
        if (items.isEmpty()) {
            playbackStatus = "播放列表为空，请先添加或扫描音频"
            refreshPlayback()
            showPlaylist()
            return
        }
        val current = items.indexOfFirst { it.uri == currentUri }
        val target = if (current < 0) items.lastIndex else (current - 1 + items.size) % items.size
        currentQueue = items
        load(items[target])
    }

    private fun showPlaylist() {
        // 弹层回调的视图队列会成为切歌快照，过滤/排序改变时同步更新。
        playlistDialog = PlaylistDialog(this, musicStore, dark, songs.firstOrNull { it.uri == currentUri }?.id ?: -1L,
            { item -> currentQueue = queue; load(item) }, {
                queue = it
                currentQueue = it
                service?.syncQueue(it, currentUri)
            }, songs)
        playlistDialog?.show()
    }

    private fun togglePlaylist() {
        if (playlistDialog?.isShowing() == true) playlistDialog?.dismiss() else showPlaylist()
    }

    private fun showVisualizerSheet() {
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

    override fun addAudio() = openAudio.launch(arrayOf("audio/*"))

    private fun saveCustomPreset() {
        saveCustomPreset("")
    }

    private fun saveCustomPreset(rawInput: String) {
        val raw = rawInput.ifBlank { "自定义 ${customPresets.size + 1}" }
        var name = if (" · " in raw) raw else "标准模式 · $raw"
        val base = name
        var suffix = 2
        while (customPresets.any { it.name == name }) name = "$base ${suffix++}"
        customPresets.add(preset.copy(name = name, note = "用户保存的自定义空间风格"))
        presetStore.save(customPresets)
        preset = customPresets.last()
        refreshAll()
    }

    @Suppress("DEPRECATION")
    private fun requestPlaybackFocus() {
        service?.requestPlaybackFocus()
    }

    private fun exportWav(uri: Uri, onComplete: () -> Unit = {}) {
        if (exporting) { toast("已有导出任务，请等待完成"); return }
        val player = service?.player ?: return
        exporting = true
        val stream = contentResolver.openOutputStream(uri) ?: run {
            exporting = false
            toast("无法创建导出文件")
            return
        }
        playbackStatus = "正在导出 WAV…"
        var published = false
        player.export(stream,
            onProgress = { percent -> main.post { playbackStatus = "导出 $percent%"; refreshPlayback() } },
            onDone = { ok, message ->
                exporting = false
                if (ok) { onComplete(); published = true }
                if (!published) discardPending(uri)
                playbackStatus = message; refreshPlayback(); toast(message)
            })
    }

    private fun chooseExport() {
        showExportSheet()
    }

    private fun exportPublic(name: String) {
        if (exporting) { toast("已有导出任务，请等待完成"); return }
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$name-8D.wav")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/8D")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        // 应用向公共 Music/8D 写自己创建的条目，不需要全盘存储权限。
        val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return
        val done = {
            values.clear(); values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            Unit
        }
        exportWav(uri, done)
    }

    private fun discardPending(uri: Uri) {
        // 导出失败时撤销未发布条目，避免 Music 里留下 0 字节文件。
        if ("media" == uri.authority) contentResolver.delete(uri, "${MediaStore.Audio.Media.IS_PENDING}=1", null)
    }

    private fun pickOption(title: String, names: List<String>, selected: Int, twoColumns: Boolean = false,
        action: (Int) -> Unit) {
        lateinit var dialog: Dialog
        dialog = ThemeUi.showSheet(this, palette, title, compactPicker = true) {
            fun option(index: Int) = ThemeUi.text(this@MainActivity, names[index],
                if (twoColumns) 11f else 13f, palette,
                    bold = index == selected, role = if (index == selected) ThemeUi.ROLE_ACCENT else ThemeUi.ROLE_TEXT).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    ThemeUi.hideOverflow(this)
                    setPadding(ThemeUi.dp(this@MainActivity, 10f), 0, ThemeUi.dp(this@MainActivity, 10f), 0)
                    background = ThemeUi.bgDrawable(this@MainActivity,
                        if (index == selected) palette.surfaceAlt else palette.surface, 8f)
                    setOnClickListener { dialog.dismiss(); action(index) }
                }
            if (twoColumns) {
                names.indices.chunked(2).forEach { rowIndices ->
                    addView(ThemeUi.row(this@MainActivity).apply {
                        rowIndices.forEach { index ->
                            addView(option(index), LinearLayout.LayoutParams(0,
                                ThemeUi.dp(this@MainActivity, 38f), 1f).apply {
                                if (index % 2 == 0) rightMargin = ThemeUi.dp(this@MainActivity, 4f)
                                bottomMargin = ThemeUi.dp(this@MainActivity, 4f)
                            })
                        }
                        // 奇数个选项时保留右侧空白，不让最后一项拉伸成整行。
                        if (rowIndices.size == 1) addView(View(this@MainActivity),
                            LinearLayout.LayoutParams(0, 1, 1f).apply {
                                leftMargin = ThemeUi.dp(this@MainActivity, 4f)
                            })
                    })
                }
            } else {
                names.forEachIndexed { index, _ ->
                    addView(option(index), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ThemeUi.dp(this@MainActivity, 38f)))
                }
            }
        }
        dialog.show()
    }

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED

    private fun audioPermission() = if (Build.VERSION.SDK_INT >= 33)
        // Android 13 拆分媒体权限；12 及以下仍读取外置存储权限。
        Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun currentMode() = try {
        PlayMode.valueOf(preferences.getString("mode", PlayMode.LOOP_LIST.name)!!)
    } catch (_: Exception) { PlayMode.LOOP_LIST }

    private fun modeText() = when (currentMode()) {
        PlayMode.LOOP_LIST -> t("mode.list")
        PlayMode.LOOP_ONE -> t("mode.one")
        PlayMode.SHUFFLE -> t("mode.shuffle")
    }

    private fun time(seconds: Double): String {
        val value = seconds.toInt().coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d", value / 60, value % 60)
    }

    private fun toast(message: String): Unit { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }

    private val ticker = object : Runnable {
        override fun run() {
            if (!pageActive) return
            refreshPlayback()
            service?.player?.let {
                visualization.position = it.position
                curve.position = it.position
            }
            if (SystemClock.uptimeMillis() - lastStateSavedAt >= 5000) savePlaybackState()
            main.postDelayed(this, 200)
        }
    }
    private fun navbar(): LinearLayout {
        val bar = ThemeUi.row(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ThemeUi.dp(this@MainActivity, 10f), 0, ThemeUi.dp(this@MainActivity, 10f), 0)
            setBackgroundColor(palette.background)
            tag = "navbar"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeUi.dp(this@MainActivity, 52f))
        }
        bar.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ThemeUi.row(this@MainActivity).apply {
                addView(ThemeUi.text(this@MainActivity, t("app.name"), 16f, palette, bold = true).apply {
                    setOnClickListener { unlockSource() }
                })
                addView(ThemeUi.text(this@MainActivity, "ⓘ", 18f, palette, role = ThemeUi.ROLE_SUB).apply {
                    gravity = Gravity.CENTER
                    contentDescription = t("info")
                    layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@MainActivity, 30f), ThemeUi.dp(this@MainActivity, 30f)).apply {
                        leftMargin = ThemeUi.dp(this@MainActivity, 5f)
                    }
                    setOnClickListener { showInfoSheet() }
                })
            })
            addView(ThemeUi.text(this@MainActivity, t("app.subtitle"), 10f, palette, role = ThemeUi.ROLE_FAINT))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        fun navButton(label: String, action: () -> Unit) = ThemeUi.button(this, palette, label, minHeightDp = 30f).apply {
            textSize = 11f
            setPadding(ThemeUi.dp(this@MainActivity, 8f), 0, ThemeUi.dp(this@MainActivity, 8f), 0)
            setOnClickListener { action() }
        }
        fmButton = navButton("FM") { showSourceSheet() }.apply {
            visibility = if (fmUnlocked) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(-2, ThemeUi.dp(this@MainActivity, 30f)).apply { leftMargin = ThemeUi.dp(this@MainActivity, 3f) }
        }
        bar.addView(fmButton)
        drivingButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_driving)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageTintList = ColorStateList.valueOf(palette.controlText)
            background = ThemeUi.bgDrawable(this@MainActivity, palette.control, 15f)
            setPadding(ThemeUi.dp(this@MainActivity, 6f), 0, ThemeUi.dp(this@MainActivity, 6f), 0)
            contentDescription = t("driving")
            layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@MainActivity, 30f), ThemeUi.dp(this@MainActivity, 30f))
            setOnClickListener { showDrivingMode() }
        }
        bar.addView(drivingButton)
        settingsButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageTintList = ColorStateList.valueOf(palette.controlText)
            background = ThemeUi.bgDrawable(this@MainActivity, palette.control, 15f)
            setPadding(ThemeUi.dp(this@MainActivity, 7f), 0, ThemeUi.dp(this@MainActivity, 7f), 0)
            contentDescription = t("settings")
            layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@MainActivity, 30f), ThemeUi.dp(this@MainActivity, 30f)).apply {
                leftMargin = ThemeUi.dp(this@MainActivity, 6f)
            }
            setOnClickListener { showSettingsSheet() }
        }
        bar.addView(settingsButton)
        return bar
    }

    private fun showSettingsSheet() {
        lateinit var dialog: Dialog
        fun settingRow(icon: String, title: String, value: String, action: () -> Unit) = ThemeUi.row(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = ThemeUi.bgDrawable(this@MainActivity, palette.surfaceAlt, 8f)
            layoutParams = LinearLayout.LayoutParams(-1, ThemeUi.dp(this@MainActivity, 52f)).apply {
                topMargin = ThemeUi.dp(this@MainActivity, 6f)
            }
            addView(ThemeUi.text(this@MainActivity, icon, 17f, palette, role = ThemeUi.ROLE_ACCENT).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@MainActivity, 40f), -1)
            })
            addView(ThemeUi.text(this@MainActivity, title, 13f, palette, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ThemeUi.text(this@MainActivity, "$value  ›", 12f, palette, role = ThemeUi.ROLE_SUB).apply {
                setPadding(0, 0, ThemeUi.dp(this@MainActivity, 12f), 0)
            })
            setOnClickListener { action() }
        }
        val theme = when (themeMode) { 0 -> t("theme.dark"); 1 -> t("theme.light"); else -> t("theme.system") }
        val language = when (AppLanguage.current(this)) {
            AppLanguage.SIMPLIFIED -> t("language.simplified")
            AppLanguage.TRADITIONAL -> t("language.traditional")
            AppLanguage.ENGLISH -> t("language.english")
            else -> t("language.system")
        }
        dialog = ThemeUi.showSheet(this, palette, t("settings"), compactPicker = true) {
            addView(settingRow("☾", t("theme"), theme) {
                dialog.dismiss()
                pickOption(t("theme"), listOf(t("theme.dark"), t("theme.light"), t("theme.system")), themeMode) {
                    themeMode = it; dark = resolveDark(); refreshTheme()
                }
            })
            addView(settingRow("A", t("language"), language) { dialog.dismiss(); showLanguagePicker() })
        }
        dialog.show()
    }

    private fun showLanguagePicker() {
        val values = listOf(AppLanguage.SYSTEM, AppLanguage.SIMPLIFIED, AppLanguage.TRADITIONAL, AppLanguage.ENGLISH)
        val labels = listOf(t("language.system"), t("language.simplified"), t("language.traditional"), t("language.english"))
        pickOption(t("language"), labels, values.indexOf(AppLanguage.current(this))) {
            AppLanguage.select(this, values[it])
            recreate()
        }
    }

    private fun showDrivingMode() {
        if (drivingDialog?.isShowing == true) return
        (visualizerView.parent as? ViewGroup)?.removeView(visualizerView)
        visualizerView.showLayerOptions = false
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
                visualizerView.showLayerOptions = false
                visualizerView.drivingLyrics = false
                visualizerView.visibility = if (curvePanel == 1) View.VISIBLE else View.GONE
                drivingView = null
            }
            show()
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateDriving()
    }

    private fun updateDriving() {
        val playing = if (isLive) service?.radio?.isPlaying == true else service?.player?.isPlaying == true
        drivingView?.update(currentItem, currentLyrics,
            ((service?.player?.position ?: 0.0) * 1000).toLong(), playing)
    }

    private fun showMetadataEditor() {
        val item = currentItem ?: return
        if (isLive || item.uri.startsWith("http")) { toast(t("metadata.read.only")); return }
        lateinit var dialog: Dialog
        dialog = ThemeUi.showSheet(this, palette, t("metadata"), fullHeight = false) {
            val title = ThemeUi.input(this@MainActivity, palette, t("metadata.title")).apply { setText(item.name.substringBeforeLast('.')) }
            val artist = ThemeUi.input(this@MainActivity, palette, t("metadata.artist")).apply { setText(item.artist) }
            val album = ThemeUi.input(this@MainActivity, palette, t("metadata.album")).apply { setText(item.album) }
            addView(title); addView(artist); addView(album)
            addView(ThemeUi.button(this@MainActivity, palette, t("metadata.save"), ThemeUi.ROLE_ACCENT_FILL) {
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

    override fun editMetadata(item: MusicItem) {
        currentItem = item
        showMetadataEditor()
    }

    /** 三击开放 FM。 */
    private fun unlockSource() {
        val now = SystemClock.elapsedRealtime()
        sourceTapCount = if (now - sourceTapAt <= 1200) sourceTapCount + 1 else 1
        sourceTapAt = now
        if (sourceTapCount == 3) {
            fmUnlocked = true
            fmButton.visibility = View.VISIBLE
        } else if (sourceTapCount > 3) {
            sourceTapCount = 0
        }
    }

    private fun card(block: LinearLayout.() -> Unit): LinearLayout = ThemeUi.card(this, palette, block)

    private fun spaceSettingsCard(): LinearLayout {
        val card = card {
            val header = FrameLayout(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeUi.dp(this@MainActivity, 32f))
            }
            header.addView(ThemeUi.text(this@MainActivity, t("space.style"), 14f, palette, bold = true), FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
            presetPicker = ThemeUi.text(this@MainActivity, preset.name, 12f, palette, role = ThemeUi.ROLE_CONTROL).apply {
                ThemeUi.hideOverflow(this)
                gravity = Gravity.CENTER
                setPadding(ThemeUi.dp(this@MainActivity, 12f), 0, ThemeUi.dp(this@MainActivity, 24f), 0)
                layoutParams = FrameLayout.LayoutParams(ThemeUi.dp(this@MainActivity, 168f), ThemeUi.dp(this@MainActivity, 32f), Gravity.CENTER)
                setOnClickListener {
                    // 快速选择只调整展示顺序，保持内置和自定义预设原值不变。
                    val pinned = listOfNotNull(SPACE_PRESETS.firstOrNull { it.raw },
                        SPACE_PRESETS.firstOrNull { !it.raw })
                    val all = pinned + (SPACE_PRESETS + customPresets).filter { it !in pinned }
                        .sortedWith(compareBy({ it.room }, { it.name }))
                    pickOption(t("space.choose"), all.map { item ->
                        val index = SPACE_PRESETS.indexOfFirst { it.name == item.name }
                        "${if (index >= 0) AppLanguage.preset(this@MainActivity, index) else item.name.replaceFirst("标准绕头 · ", "标准模式 · ")} · ${item.room.toInt()}${t("unit.meters")}" },
                        all.indexOf(preset), twoColumns = true) {
                        preset = all[it]; applyPreset()
                    }
                }
            }
            header.addView(presetPicker)
            header.addView(ThemeUi.text(this@MainActivity, "▾", 10f, palette, role = ThemeUi.ROLE_SUB).apply {
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(ThemeUi.dp(this@MainActivity, 22f),
                    ThemeUi.dp(this@MainActivity, 32f), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                    rightMargin = ThemeUi.dp(this@MainActivity, 67f)
                }
            })
            header.addView(ThemeUi.text(this@MainActivity, t("expand"), 11f, palette, role = ThemeUi.ROLE_SUB).apply {
                tag = "toggleP"
                layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL)
                setOnClickListener { toggleParameterOpen() }
            })
            addView(header)
            presetNote = ThemeUi.text(this@MainActivity, preset.note, 12f, palette, role = ThemeUi.ROLE_SUB).apply {
                setPadding(0, ThemeUi.dp(this@MainActivity, 5f), 0, 0)
            }
            parameterPanel = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(parameterPanel)
            // 折叠时与音频变换共用相同的一行头部；说明跟随展开面板展示。
            parameterPanel.addView(presetNote)
            parameterPanel.addView(ThemeUi.divider(this@MainActivity, palette).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeUi.dp(this@MainActivity, 1f)).apply {
                    topMargin = ThemeUi.dp(this@MainActivity, 9f); bottomMargin = ThemeUi.dp(this@MainActivity, 9f)
                }
            })
            parameterPanel.addView(ThemeUi.row(this@MainActivity).apply {
                addView(ThemeUi.text(this@MainActivity, t("direction"), 12f, palette).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(dirSegmented(), LinearLayout.LayoutParams(0, ThemeUi.dp(this@MainActivity, 32f), 2.2f))
            })
            parameterPanel.addView(ThemeUi.text(this@MainActivity, t("direction.hint"), 10f, palette, role = ThemeUi.ROLE_FAINT))
            parameterPanel.addView(ThemeUi.row(this@MainActivity).apply {
                addView(ThemeUi.text(this@MainActivity, t("trajectory"), 12f, palette, bold = true))
                addView(ThemeUi.text(this@MainActivity, t("trajectory.hint"), 10f, palette, role = ThemeUi.ROLE_FAINT))
            }.apply {
                setPadding(0, ThemeUi.dp(this@MainActivity, 10f), 0, ThemeUi.dp(this@MainActivity, 4f))
            })
            parameterPanel.addView(sliderGroup(SLIDERS.filter { it.key in setOf("period", "elev", "depth", "phase") }))
            val saveRow = ThemeUi.row(this@MainActivity)
            val saveInput = EditText(this@MainActivity).apply {
                hint = "自定义风格名称"
                setTextColor(palette.text)
                setHintTextColor(palette.faint)
                setSingleLine()
                setPadding(ThemeUi.dp(this@MainActivity, 10f), 0, ThemeUi.dp(this@MainActivity, 10f), 0)
                background = ThemeUi.bgDrawable(this@MainActivity, palette.control, 8f)
                hint = t("preset.name.hint")
                textSize = 11f
            }
            saveRow.addView(saveInput.apply {
                layoutParams = LinearLayout.LayoutParams(0, ThemeUi.dp(this@MainActivity, 36f), 1f).apply {
                    rightMargin = ThemeUi.dp(this@MainActivity, 8f)
                }
            })
            saveRow.addView(ThemeUi.button(this@MainActivity, palette, t("preset.save"), role = ThemeUi.ROLE_ACCENT_FILL) {
                saveCustomPreset(saveInput.text.toString())
            })
            parameterPanel.addView(saveRow)
            parameterPanel.addView(ThemeUi.divider(this@MainActivity, palette).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeUi.dp(this@MainActivity, 1f)).apply {
                    topMargin = ThemeUi.dp(this@MainActivity, 12f); bottomMargin = ThemeUi.dp(this@MainActivity, 9f)
                }
            })
            parameterPanel.addView(ThemeUi.row(this@MainActivity).apply {
                addView(ThemeUi.text(this@MainActivity, t("room.acoustics"), 12f, palette, bold = true))
                addView(ThemeUi.text(this@MainActivity, t("room.hint"), 10f, palette, role = ThemeUi.ROLE_FAINT))
            }.apply {
                setPadding(0, ThemeUi.dp(this@MainActivity, 4f), 0, ThemeUi.dp(this@MainActivity, 4f))
            })
            parameterPanel.addView(sliderGroup(SLIDERS.filter { it.key in setOf("room", "wet", "rt60", "width") }))
            parameterPanel.visibility = View.GONE
        }
        return card
    }

    private fun sliderGroup(definitions: List<j5i.spatial8d.core.SliderDef>): LinearLayout {
        val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        definitions.forEach { definition ->
            val row = ThemeUi.row(this)
            row.addView(ThemeUi.text(this, t("slider.${definition.key}"), 12f, palette).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            sliderValues[definition.key] = ThemeUi.text(this, "", 12f, palette, role = ThemeUi.ROLE_ACCENT)
            row.addView(sliderValues[definition.key])
            group.addView(row)
            val slider = SeekBar(this).apply {
                contentDescription = t("slider.${definition.key}")
                max = 1000
                // 与上方参数名左缘对齐，不使用系统默认的大拇指留白。
                setPadding(ThemeUi.dp(this@MainActivity, 12f), 0, ThemeUi.dp(this@MainActivity, 12f), 0)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser && !updatingControls) {
                            val value = definition.min + progress / 10.0.pow(definition.digits)
                            updateParameter(definition.key, clampSlider(definition.key, value))
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar) {}
                })
            }
            sliderControls[definition.key] = slider
            group.addView(slider)
        }
        return group
    }

    private fun dirSegmented(): LinearLayout {
        val row = ThemeUi.row(this)
        val options = listOf(t("direction.cw") to 1.0, t("direction.ccw") to -1.0, t("direction.random") to 0.0)
        options.forEach { (label, value) ->
            val btn = ThemeUi.button(this, palette, label).apply {
                layoutParams = LinearLayout.LayoutParams(0, ThemeUi.dp(this@MainActivity, 32f), 1f)
                textSize = 11f
                maxLines = 1
                setPadding(ThemeUi.dp(this@MainActivity, 12f), 0, ThemeUi.dp(this@MainActivity, 12f), 0)
                setOnClickListener {
                    updateParameter("direction", value)
                    refreshAll()
                }
            }
            row.addView(btn)
        }
        dirButtons = (0 until row.childCount).map { row.getChildAt(it) as Button }
        return row
    }

    private fun transformCard(): LinearLayout {
        val card = card {
            val header = FrameLayout(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeUi.dp(this@MainActivity, 32f))
            }
            header.addView(ThemeUi.text(this@MainActivity, t("transform"), 14f, palette, bold = true), FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
            pitchPicker = ThemeUi.text(this@MainActivity, "原声", 12f, palette, role = ThemeUi.ROLE_CONTROL).apply {
                gravity = Gravity.CENTER
                ThemeUi.hideOverflow(this)
                layoutParams = FrameLayout.LayoutParams(ThemeUi.dp(this@MainActivity, 168f), ThemeUi.dp(this@MainActivity, 32f), Gravity.CENTER)
                setOnClickListener {
                    val options = interleavedPitchOptions(PITCH_PRESETS)
                    pickOption(t("pitch.choose"), options.map { formatPitchLabel(it.name, it.semi) },
                        options.indexOfFirst { it.semi == transform.semi }, twoColumns = true) {
                        transform = transform.copy(semi = options[it].semi); applyTransform(); refreshAll()
                    }
                }
            }
            header.addView(pitchPicker)
            header.addView(ThemeUi.text(this@MainActivity, "▾", 10f, palette, role = ThemeUi.ROLE_SUB).apply {
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(ThemeUi.dp(this@MainActivity, 22f),
                    ThemeUi.dp(this@MainActivity, 32f), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                    rightMargin = ThemeUi.dp(this@MainActivity, 67f)
                }
            })
            header.addView(ThemeUi.text(this@MainActivity, t("expand"), 11f, palette, role = ThemeUi.ROLE_SUB).apply {
                tag = "toggleT"
                layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL)
                setOnClickListener { toggleTransformOpen() }
            })
            addView(header)
            transformBody = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            transformBody.addView(ThemeUi.divider(this@MainActivity, palette).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeUi.dp(this@MainActivity, 1f)).apply {
                    topMargin = ThemeUi.dp(this@MainActivity, 8f); bottomMargin = ThemeUi.dp(this@MainActivity, 8f)
                }
            })
            val pitchRow = ThemeUi.row(this@MainActivity)
            pitchRow.addView(ThemeUi.text(this@MainActivity, t("pitch"), 12f, palette).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            pitchLabel = ThemeUi.text(this@MainActivity, t("original"), 12f, palette, role = ThemeUi.ROLE_ACCENT)
            pitchRow.addView(pitchLabel)
            transformBody.addView(pitchRow)
            pitchSlider = SeekBar(this@MainActivity).apply {
                contentDescription = t("pitch")
                max = 24
                progress = 12
                setPadding(ThemeUi.dp(this@MainActivity, 12f), 0, ThemeUi.dp(this@MainActivity, 12f), 0)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            val semi = progress - 12
                            if (semi != transform.semi.toInt()) {
                                transform = transform.copy(semi = semi.toDouble())
                                applyTransform()
                            }
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar) {}
                })
            }
            transformBody.addView(pitchSlider)
            val tickRow = ThemeUi.row(this@MainActivity)
            val pitchTickButtons = mutableListOf<Button>()
            listOf(-12.0, -8.0, -4.0, 0.0, 4.0, 8.0, 12.0).forEach { semi ->
                tickRow.addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    addView(View(this@MainActivity).apply {
                        setBackgroundColor(palette.faint)
                        layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@MainActivity, 2f), ThemeUi.dp(this@MainActivity, 3f))
                    })
                    addView(ThemeUi.button(this@MainActivity, palette, semiName(semi), role = ThemeUi.ROLE_TEXT) {
                        transform = transform.copy(semi = semi); applyTransform(); refreshAll()
                    }.apply {
                        textSize = 9f
                        minHeight = 0; minimumHeight = 0
                        setPadding(0, 0, 0, 0)
                        pitchTickButtons.add(this)
                    })
                    layoutParams = LinearLayout.LayoutParams(0, ThemeUi.dp(this@MainActivity, 28f), 1f)
                })
            }
            transformBody.addView(tickRow)
            pitchTicks = pitchTickButtons
            transformBody.addView(switchRow(t("reverse"), t("reverse.hint")) { reverse = it; service?.player?.reverse = it; service?.controlsReverse = reverse }.also {
                reverseSwitch = it.second
            }.first.apply { tag = "localOnly" })
            speedLabel = ThemeUi.text(this@MainActivity, "1.00×", 12f, palette, role = ThemeUi.ROLE_ACCENT)
            transformBody.addView(ThemeUi.row(this@MainActivity).apply {
                tag = "localOnly"
                addView(ThemeUi.text(this@MainActivity, t("speed"), 12f, palette).apply {
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                })
                addView(speedLabel)
            })
            speedSlider = SeekBar(this@MainActivity).apply {
                contentDescription = t("speed")
                max = 30
                progress = 10
                setPadding(0, 0, 0, 0)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            val speed = 0.5 + progress * 0.05
                            transform = transform.copy(speed = speed.toDouble())
                            applyTransform()
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar) {}
                })
            }
            speedSlider.tag = "localOnly"
            transformBody.addView(speedSlider)
            denoiseLabel = ThemeUi.text(this@MainActivity, "65%", 12f, palette, role = ThemeUi.ROLE_ACCENT)
            transformBody.addView(ThemeUi.row(this@MainActivity).apply {
                addView(ThemeUi.text(this@MainActivity, t("denoise"), 12f, palette).apply {
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                })
                addView(denoiseLabel)
            })
            denoiseSlider = SeekBar(this@MainActivity).apply {
                contentDescription = t("denoise")
                max = 100
                progress = transform.denoiseLevel
                setPadding(0, 0, 0, 0)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser && progress != transform.denoiseLevel) {
                            transform = transform.copy(denoise = progress)
                            applyTransform()
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar) {}
                })
            }
            transformBody.addView(denoiseSlider)
            clarityLabel = ThemeUi.text(this@MainActivity, "${transform.clarityLevel}%", 12f, palette, role = ThemeUi.ROLE_ACCENT)
            transformBody.addView(ThemeUi.row(this@MainActivity).apply {
                addView(ThemeUi.text(this@MainActivity, t("sound.clarity"), 12f, palette).apply {
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                })
                addView(clarityLabel)
            })
            claritySlider = SeekBar(this@MainActivity).apply {
                contentDescription = t("sound.clarity")
                max = 100
                progress = transform.clarityLevel
                setPadding(0, 0, 0, 0)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser && progress != transform.clarityLevel) {
                            transform = transform.copy(clarity = progress)
                            applyTransform()
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar) {}
                })
            }
            transformBody.addView(claritySlider)
            val chips = ThemeUi.row(this@MainActivity)
            listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0).forEach { value ->
                chips.addView(ThemeUi.button(this@MainActivity, palette, "${formatSpeedShort(value)}×\n${t("speed.realtime")}", role = if (transform.speed == value) ThemeUi.ROLE_ACCENT_FILL else ThemeUi.ROLE_CONTROL).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ThemeUi.dp(this@MainActivity, 40f), 1f).apply {
                        leftMargin = ThemeUi.dp(this@MainActivity, 3f)
                    }
                    textSize = 9f
                    gravity = Gravity.CENTER
                    setLineSpacing(0f, 0.85f)
                    setPadding(0, 0, 0, 0)
                    setOnClickListener {
                        transform = transform.copy(speed = value)
                        applyTransform()
                    }
                })
            }
            speedChipButtons = (0 until chips.childCount).map { chips.getChildAt(it) as Button }
            chips.tag = "localOnly"
            transformBody.addView(chips)
            transformBody.addView(switchRow(t("tape"), t("tape.hint")) { transform = transform.copy(tape = it); applyTransform() }.also {
                tapeSwitch = it.second
            }.first.apply { tag = "localOnly" })
            durationHint = ThemeUi.text(this@MainActivity, "${t("new.duration")} --:--", 12f, palette, role = ThemeUi.ROLE_ACCENT)
            transformBody.addView(ThemeUi.row(this@MainActivity).apply {
                tag = "localOnly"
                background = ThemeUi.bgDrawable(this@MainActivity, palette.surfaceAlt, 8f)
                setPadding(ThemeUi.dp(this@MainActivity, 10f), ThemeUi.dp(this@MainActivity, 6f),
                    ThemeUi.dp(this@MainActivity, 10f), ThemeUi.dp(this@MainActivity, 6f))
                addView(ThemeUi.text(this@MainActivity, t("new.duration"), 10f, palette, role = ThemeUi.ROLE_FAINT).apply {
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                })
                addView(durationHint)
            })
            addView(transformBody)
            transformBody.visibility = View.GONE
        }
        transformCardRoot = card
        return card
    }

    private fun curveCard(): LinearLayout {
        val card = card {
            addView(tabRow(listOf(t("curve"), t("visualizer"), t("lyrics")), curvePanel, compact = true) {
                curvePanel = it
                if (it == 1) installPcmRenderer()
                curve.visibility = if (it == 0) View.VISIBLE else View.GONE
                visualizerView.visibility = if (it == 1) View.VISIBLE else View.GONE
                lyricsView.visibility = if (it == 2) View.VISIBLE else View.GONE
                // 非"空间曲线" tab 隐藏图例并把它的高度转移给 curveContent，
                // 避免图例行占位造成波形/频谱顶部一片空白。
                val legendHeight = curveLegend.height.takeIf { h -> h > 0 }
                    ?: ThemeUi.dp(this@MainActivity, 22f)
                val showLegend = it == 0
                val wasVisible = curveLegend.visibility == View.VISIBLE
                curveLegend.visibility = if (showLegend) View.VISIBLE else View.GONE
                if (::curveContent.isInitialized && wasVisible != showLegend) {
                    val current = curveContent.layoutParams.height
                    val next = if (showLegend) (current - legendHeight).coerceAtLeast(ThemeUi.dp(this@MainActivity, 80f))
                    else current + legendHeight
                    curveContent.layoutParams = curveContent.layoutParams.apply { height = next }
                }
            })
            curveLegend = ThemeUi.row(this@MainActivity).apply {
                listOf(
                    t("left.ear") to 0xff4ea1ff.toInt(),
                    t("right.ear") to 0xffff7a59.toInt(),
                    t("elevation") to 0xff4fd396.toInt(),
                    t("distance") to 0xffa98ce8.toInt()
                ).forEach { (label, color) ->
                    addView(ThemeUi.row(this@MainActivity).apply {
                        addView(View(this@MainActivity).apply {
                            background = ThemeUi.bgDrawable(this@MainActivity, color, 3f)
                            layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@MainActivity, 6f), ThemeUi.dp(this@MainActivity, 6f))
                        })
                        addView(ThemeUi.text(this@MainActivity, label, 10f, palette, role = ThemeUi.ROLE_SUB).apply {
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                leftMargin = ThemeUi.dp(this@MainActivity, 4f)
                            }
                        })
                    }.apply {
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            leftMargin = ThemeUi.dp(this@MainActivity, 10f)
                        }
                    })
                }
            }
            addView(curveLegend)
            // 用 FrameLayout 叠放四个面板，同一时间只有一个 VISIBLE，MATCH_PARENT 才能占满整个高度。
            // 之前 LinearLayout 中多个 height=-1 的子 view 会被平分/压缩，造成音频图谱/可视化上下留白。
            curveContent = FrameLayout(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeUi.dp(this@MainActivity, 140f))
            }
            curve = CurveView(this@MainActivity)
            curveContent.addView(curve, FrameLayout.LayoutParams(-1, -1))
            lyricsView = LyricsView(this@MainActivity).apply {
                dark = this@MainActivity.dark
                onSearch = { query -> currentItem?.let { loadLyrics(it, query) } }
                onManual = { currentItem?.let { showLyricsCandidates(it) } }
                visibility = View.GONE
            }
            curveContent.addView(lyricsView, FrameLayout.LayoutParams(-1, -1))
            visualizerView = VisualizerView(this@MainActivity).apply {
                dark = this@MainActivity.dark
                visibility = View.GONE
                onExpand = { showVisualizerSheet() }
            }
            curveContent.addView(visualizerView, FrameLayout.LayoutParams(-1, -1))
            addView(curveContent)
        }
        return card
    }

    /** 服务可能先于页面重建；每次恢复可视化都重新挂上输出回调，避免永久失去 PCM 输入。 */
    private fun installPcmRenderer() {
        val render: (ByteArray, Int) -> Unit = { data, bytes ->
            visualizerView.pcmClock.accept(data, bytes)
            if (::visualization.isInitialized) visualization.acceptPcm(data, bytes)
            if (::visualizerView.isInitialized && curvePanel == 1) visualizerView.accept(data, bytes)
            if (drivingDialog?.isShowing == true) visualizerView.accept(data, bytes)
            popupVisualizer?.accept(data, bytes)
        }
        service?.player?.onPcm = render
        service?.radio?.onPcm = render
    }

    /** 文件名和时长共同参与匹配，切歌代号阻止旧请求覆盖新歌歌词。 */
    private fun loadLyrics(item: MusicItem, manualTitle: String? = null) {
        val generation = ++lyricsGeneration
        currentLyrics = null
        pushLyricsToVisualizer()
        if (::lyricsView.isInitialized) lyricsView.clear()
        val title = manualTitle ?: Lyrics.searchTitle(item.name, item.artist)
        lyrics.load(title, item.artist, item.duration / 1000.0, { result ->
            if (generation != lyricsGeneration || currentItem?.uri != item.uri) return@load
            result.onSuccess {
                currentLyrics = it
                lyricsView.setLyrics(it)
                pushLyricsToVisualizer()
                updateDriving()
            }.onFailure { lyricsView.showError(manualTitle ?: Lyrics.displayTitle(item.name)) }
        }, { progress ->
            if (generation == lyricsGeneration && currentItem?.uri == item.uri) lyricsView.showProgress(progress)
        })
    }

    /** 把当前歌词推给音频可视化视图（含弹窗副本），用于穿梭模式叠加字幕。 */
    private fun pushLyricsToVisualizer() {
        if (::visualizerView.isInitialized) visualizerView.setLyrics(currentLyrics)
        popupVisualizer?.setLyrics(currentLyrics)
        updateDriving()
    }

    /** 长按歌词触发手动修正：允许搜索更新，或从多路候选里选择并预览。 */
    private fun showLyricsCandidates(item: MusicItem) {
        if (lyricsDialog?.isShowing == true) return
        val title = Lyrics.searchTitle(item.name, item.artist)
        val artist = item.artist
        val duration = item.duration / 1000.0
        val sheet = ThemeUi.showSheet(this, palette, "修正歌词", "选择正确歌词或重新搜索", fullHeight = true) {
            val titleInput = ThemeUi.input(this@MainActivity, palette, "歌名").apply { setText(title) }
            val artistInput = ThemeUi.input(this@MainActivity, palette, "歌手（可选）").apply {
                setText(artist.takeUnless { it.equals("未知歌手", ignoreCase = true) } ?: "")
            }
            addView(titleInput)
            addView(artistInput)
            addView(ThemeUi.text(this@MainActivity, "或从以下候选中选择：", 12f, palette, role = ThemeUi.ROLE_SUB))

            // LRCLIB 候选自带完整 LRC。
            val sourceOrder = listOf("LRCLIB")
            var bySource: Map<String, List<LyricsCandidate>> = emptyMap()
            var activeSource = "LRCLIB"
            val list = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val status = ThemeUi.text(this@MainActivity, "正在检索候选…", 12f, palette, role = ThemeUi.ROLE_SUB)
            val tabRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            addView(tabRow)
            addView(list)
            addView(status)

            fun renderList() {
                list.removeAllViews()
                val items = bySource[activeSource].orEmpty()
                if (items.isEmpty()) {
                    if (bySource.isNotEmpty()) status.text = "该源暂无候选"
                    return
                }
                status.text = "$activeSource · ${items.size} 个候选"
                items.take(20).forEach { candidate ->
                    list.addView(LyricsCandidateRow(candidate, duration) { applyLyricsCandidate(item, candidate) })
                }
            }

            fun renderTabs() {
                tabRow.removeAllViews()
                sourceOrder.forEach { source ->
                    val count = bySource[source]?.size ?: 0
                    val label = if (count > 0) "$source ($count)" else source
                    val selected = source == activeSource
                    val tab = ThemeUi.text(this@MainActivity, label, 12f, palette,
                        role = if (selected) ThemeUi.ROLE_ACCENT else ThemeUi.ROLE_SUB).apply {
                        setPadding(ThemeUi.dp(this@MainActivity, 12f), ThemeUi.dp(this@MainActivity, 6f),
                            ThemeUi.dp(this@MainActivity, 12f), ThemeUi.dp(this@MainActivity, 6f))
                        background = if (selected) ThemeUi.bgDrawable(this@MainActivity, palette.control, 8f) else null
                        setOnClickListener { activeSource = source; renderTabs(); renderList() }
                    }
                    tabRow.addView(tab)
                }
            }

            fun runSearch() {
                val q = titleInput.text.toString().trim().ifEmpty { title }
                val a = artistInput.text.toString().trim()
                bySource = emptyMap()
                list.removeAllViews()
                status.text = "正在检索候选…"
                renderTabs()
                lyrics.candidates(q, a, duration, { result ->
                    result.onSuccess { all ->
                        bySource = all.groupBy { it.source }
                        // 默认落在第一个有结果的源，避免打开就是空列表。
                        activeSource = sourceOrder.firstOrNull { !bySource[it].isNullOrEmpty() } ?: "LRCLIB"
                        renderTabs()
                        renderList()
                    }.onFailure { status.text = it.message ?: "检索失败" }
                }, { stage -> status.text = stage })
            }
            addView(ThemeUi.button(this@MainActivity, palette, "搜索候选", ThemeUi.ROLE_ACCENT) { runSearch() })
            titleInput.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { runSearch(); true } else false
            }
            artistInput.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { runSearch(); true } else false
            }
            runSearch()
        }
        lyricsDialog = sheet
        sheet.show()
    }

    /** 选中候选后先预览歌词，确认后再写入本地缓存。 */
    private fun applyLyricsCandidate(item: MusicItem, candidate: LyricsCandidate) {
        val title = Lyrics.searchTitle(item.name, item.artist)
        val artist = item.artist
        val duration = item.duration / 1000.0
        var previewView: TextView? = null
        var subtitleView: TextView? = null
        val preview = Dialog(this).apply {
            val body = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ThemeUi.dp(this@MainActivity, 18f), ThemeUi.dp(this@MainActivity, 20f),
                    ThemeUi.dp(this@MainActivity, 18f), ThemeUi.dp(this@MainActivity, 10f))
                background = ThemeUi.bgDrawable(this@MainActivity, palette.surface, 14f)
                addView(ThemeUi.text(this@MainActivity, "预览歌词", 16f, palette, bold = true))
                val subtitle = ThemeUi.text(this@MainActivity,
                    "${candidate.trackName} · ${candidate.artistName}", 12f, palette, role = ThemeUi.ROLE_SUB)
                subtitleView = subtitle
                addView(subtitle)
                val previewLines = TextView(this@MainActivity).apply {
                    textSize = 13f
                    setTextColor(palette.text)
                    setPadding(0, ThemeUi.dp(this@MainActivity, 10f), 0, ThemeUi.dp(this@MainActivity, 10f))
                    maxLines = 14
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                previewView = previewLines
                addView(previewLines)
                addView(ThemeUi.row(this@MainActivity, gravity = Gravity.END).apply {
                    addView(ThemeUi.text(this@MainActivity, "取消", 13f, palette, role = ThemeUi.ROLE_SUB).apply {
                        setPadding(ThemeUi.dp(this@MainActivity, 12f), ThemeUi.dp(this@MainActivity, 10f),
                            ThemeUi.dp(this@MainActivity, 12f), ThemeUi.dp(this@MainActivity, 10f))
                        setOnClickListener { dismiss() }
                    })
                    addView(ThemeUi.text(this@MainActivity, "使用此歌词", 13f, palette, role = ThemeUi.ROLE_ACCENT).apply {
                        setPadding(ThemeUi.dp(this@MainActivity, 12f), ThemeUi.dp(this@MainActivity, 10f),
                            ThemeUi.dp(this@MainActivity, 12f), ThemeUi.dp(this@MainActivity, 10f))
                        setOnClickListener {
                            dismiss()
                            lyricsDialog?.dismiss()
                            lyrics.applyCandidate(title, artist, duration, candidate, { result ->
                                if (currentItem?.uri != item.uri) return@applyCandidate
                                result.onSuccess {
                                    currentLyrics = it
                                    lyricsView.setLyrics(it)
                                    updateDriving()
                                    pushLyricsToVisualizer()
                                }
                                    .onFailure { toast(it.message ?: "歌词获取失败") }
                            })
                        }
                    })
                })
            }
            setContentView(body)
            window?.setBackgroundDrawable(null)
            window?.setDimAmount(0.45f)
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setOnShowListener { window?.setLayout(ThemeUi.dp(this@MainActivity, 320f), ViewGroup.LayoutParams.WRAP_CONTENT) }
        }
        preview.show()
        previewView?.text = "正在获取歌词预览…"
        lyrics.previewCandidate(title, artist, duration, candidate, { result ->
            result.onSuccess { previewResult ->
                previewView?.text = if (previewResult.lines.isNotEmpty())
                    previewResult.lines.take(12).joinToString("\n") { it.text }
                else previewResult.plainLyrics.ifBlank { "暂无歌词内容" }
                // 预览阶段拿到了真实 LRC，补上时长对比，帮助用户判断是否对得上原曲。
                val lrcDuration = previewResult.lines.lastOrNull()?.let { it.timeMs / 1000.0 } ?: 0.0
                val parts = mutableListOf("${candidate.trackName} · ${candidate.artistName}")
                if (duration > 0) parts += "当前 ${time(duration)}"
                if (lrcDuration > 0) parts += "LRC ${time(lrcDuration)}"
                subtitleView?.text = parts.joinToString("  ·  ")
            }.onFailure {
                previewView?.text = "预览失败：${it.message}"
            }
        })
    }

    private inner class LyricsCandidateRow(
        private val candidate: LyricsCandidate,
        private val trackDurationSeconds: Double,
        private val onClick: () -> Unit
    ) : LinearLayout(this@MainActivity) {
        init {
            orientation = LinearLayout.VERTICAL
            setPadding(ThemeUi.dp(this@MainActivity, 10f), ThemeUi.dp(this@MainActivity, 10f),
                ThemeUi.dp(this@MainActivity, 10f), ThemeUi.dp(this@MainActivity, 10f))
            background = ThemeUi.bgDrawable(this@MainActivity, palette.control, 8f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = ThemeUi.dp(this@MainActivity, 6f)
            }
            isClickable = true
            setOnClickListener { onClick() }

            // 主行：歌名 / 歌手 / 来源。
            addView(ThemeUi.text(this@MainActivity,
                "${candidate.trackName}  ·  ${candidate.artistName}  ·  ${candidate.source}",
                13f, palette, bold = true))

            // 副行：同步标记 + 当前曲目时长 vs 歌词/LRC 时长。
            val parts = mutableListOf<String>()
            parts += if (candidate.synced) "同步歌词" else "纯文本"
            if (trackDurationSeconds > 0) parts += "当前 ${time(trackDurationSeconds)}"
            if (candidate.lrcDurationSeconds > 0) parts += "LRC ${time(candidate.lrcDurationSeconds)}"
            else if (candidate.durationSeconds > 0) parts += "时长 ${time(candidate.durationSeconds)}"
            addView(ThemeUi.text(this@MainActivity, parts.joinToString("  ·  "),
                11f, palette, role = ThemeUi.ROLE_SUB))
        }
    }

    private fun nowPlayingBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ThemeUi.dp(this@MainActivity, 12f), ThemeUi.dp(this@MainActivity, 6f), ThemeUi.dp(this@MainActivity, 12f), ThemeUi.dp(this@MainActivity, 4f))
            bg(this, if (dark) 0xff17191d.toInt() else 0xfff7f8fa.toInt(), 0f)
            gravity = Gravity.BOTTOM
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }
        playerBar = bar
        val meta = ThemeUi.row(this)
        disc = ImageView(this).apply {
            contentDescription = t("playlist")
            // 透明圆形应用图标：深浅模式下不再依赖 tint 或背景。
            setImageResource(j5i.spatial8d.R.drawable.ic_app_round_xxxhdpi)
            imageTintList = null
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(0, 0, 0, 0)
            background = null
            layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@MainActivity, 38f), ThemeUi.dp(this@MainActivity, 38f))
        }
        disc.setOnClickListener { showPlaylist() }
        meta.addView(disc)
        songTitle = ThemeUi.text(this, "未加载音频", 14f, palette, bold = true).apply {
            ThemeUi.hideOverflow(this)
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, t("edit.metadata")))
                }
            }
            setOnClickListener { showMetadataEditor() }
        }
        val sub = ThemeUi.row(this)
        position = ThemeUi.text(this, "00:00 / 00:00", 11f, palette, role = ThemeUi.ROLE_SUB)
        position.layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this, 76f), ViewGroup.LayoutParams.WRAP_CONTENT)
        modeTextView = ThemeUi.text(this, "", 11f, palette, role = ThemeUi.ROLE_SUB)
        recordingLabel = ThemeUi.text(this, "", 10f, palette).apply {
            setTextColor(0xffff5d5d.toInt()); visibility = View.GONE
            setPadding(ThemeUi.dp(this@MainActivity, 6f), 0, 0, 0)
        }
        sub.addView(position)
        sub.addView(modeTextView.apply {
            setPadding(ThemeUi.dp(this@MainActivity, 8f), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@MainActivity, 56f), ViewGroup.LayoutParams.WRAP_CONTENT)
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
                leftMargin = ThemeUi.dp(this@MainActivity, 10f)
            }
        })
        radioRefreshButton = ThemeUi.button(this, palette, "↻", ThemeUi.ROLE_ACCENT, minHeightDp = 30f).apply {
            textSize = 21f
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@MainActivity, 32f), ThemeUi.dp(this@MainActivity, 30f)).apply {
                leftMargin = ThemeUi.dp(this@MainActivity, 8f)
            }
            visibility = View.GONE
            contentDescription = "重新连接当前电台"
            setOnClickListener {
                if (recordingKind.isNotEmpty()) toast("请先停止录音，再重新连接电台")
                else if (isLive && radioSources.isNotEmpty()) tryRadioSource(radioSourceIndex.coerceAtLeast(0))
            }
        }
        meta.addView(radioRefreshButton)
        favoriteButton = ImageButton(this).apply {
            setImageDrawable(j5i.spatial8d.ui.HeartDrawable(false, palette.controlText))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageTintList = null
            background = null
            setPadding(ThemeUi.dp(this@MainActivity, 6f), 0, ThemeUi.dp(this@MainActivity, 6f), 0)
            layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@MainActivity, 34f), ThemeUi.dp(this@MainActivity, 30f))
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
            background = ThemeUi.bgDrawable(this@MainActivity, if (dark) 0xff263e55.toInt() else 0xffe3efff.toInt(), 8f)
            textSize = 11f
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@MainActivity, 46f), ThemeUi.dp(this@MainActivity, 30f)).apply {
                leftMargin = ThemeUi.dp(this@MainActivity, 8f)
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
            contentDescription = t("seek.position")
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val target = (service?.player?.duration ?: 0.0) * progress / 1000.0
                        currentTime.text = time(target)
                        if (!seeking && SystemClock.elapsedRealtime() >= seekGuardUntil &&
                            kotlin.math.abs(target - (service?.player?.position ?: 0.0)) >= 0.8)
                            service?.player?.seek(target, userInitiated = true)
                    }
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
                leftMargin = ThemeUi.dp(this@MainActivity, 6f); rightMargin = ThemeUi.dp(this@MainActivity, 6f)
            }
        }
        timeline.addView(seek)
        durationTime = ThemeUi.text(this, "00:00", 11f, palette, role = ThemeUi.ROLE_SUB)
        durationTime.layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this, 38f), ViewGroup.LayoutParams.WRAP_CONTENT)
        durationTime.gravity = Gravity.CENTER
        timeline.addView(durationTime)
        bar.addView(timeline)
        liveTimeline = ThemeUi.row(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, ThemeUi.dp(this@MainActivity, 28f)).apply { topMargin = ThemeUi.dp(this@MainActivity, 8f) }
            addView(ThemeUi.text(this@MainActivity, "●", 9f, palette).apply { setTextColor(0xffff5d5d.toInt()) })
            addView(ThemeUi.text(this@MainActivity, "LIVE · 流媒体实时处理", 10f, palette).apply {
                setTextColor(0xffff8a80.toInt()); setPadding(ThemeUi.dp(this@MainActivity, 5f), 0, 0, 0)
            })
            visibility = View.GONE
        }
        bar.addView(liveTimeline)
        liveTimeline.addView(ThemeUi.text(this, "", 10f, palette, role = ThemeUi.ROLE_SUB).apply {
            tag = "liveStatus"
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ThemeUi.dp(this@MainActivity, 10f) }
            ThemeUi.hideOverflow(this)
        })
        val controls = ThemeUi.row(this, gravity = Gravity.CENTER).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeUi.dp(this@MainActivity, 62f)).apply { topMargin = ThemeUi.dp(this@MainActivity, 4f) }
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
            contentDescription = t("previous")
            layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@MainActivity, 38f), ThemeUi.dp(this@MainActivity, 38f))
        })
        playButton = iconControl(j5i.spatial8d.R.drawable.ic_play, big = true, action = { togglePlayback() }).apply {
            layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@MainActivity, 48f), ThemeUi.dp(this@MainActivity, 48f)).apply { leftMargin = ThemeUi.dp(this@MainActivity, 20f); rightMargin = ThemeUi.dp(this@MainActivity, 20f) }
        }
        controls.addView(playButton)
        controls.addView(iconControl(j5i.spatial8d.R.drawable.ic_next) { next() }.apply {
            contentDescription = t("next")
            layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@MainActivity, 38f), ThemeUi.dp(this@MainActivity, 38f))
        })
        controls.addView(LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.RIGHT
            addView(iconControl(j5i.spatial8d.R.drawable.ic_list) { togglePlaylist() }.apply {
                contentDescription = t("playlist")
            })
        })
        bar.addView(controls)
        return bar
    }

    private fun iconControl(icon: Int, big: Boolean = false, action: () -> Unit): ImageButton = ImageButton(this).apply {
        setImageResource(icon)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        imageTintList = ColorStateList.valueOf(if (big) 0xffffffff.toInt() else palette.controlText)
        tag = if (big) ThemeUi.ROLE_ACCENT_FILL else ThemeUi.ROLE_CONTROL
        bg(this, if (big) palette.accent else palette.control, if (big) 26f else 19f)
        setPadding(ThemeUi.dp(this@MainActivity, if (big) 12f else 9f), 0, ThemeUi.dp(this@MainActivity, if (big) 12f else 9f), 0)
        layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@MainActivity, if (big) 48f else 38f), ThemeUi.dp(this@MainActivity, if (big) 48f else 38f))
        setOnClickListener { action() }
        contentDescription = "播放控制"
    }

    private fun toggleParameterOpen() {
        parameterOpen = !parameterOpen
        refreshCardState()
    }

    private fun toggleTransformOpen() {
        transformOpen = !transformOpen
        refreshCardState()
    }

    private fun refreshCardState() {
        if (::parameterPanel.isInitialized) {
            parameterPanel.visibility = if (parameterOpen) View.VISIBLE else View.GONE
        (root.findViewWithTag<View>("toggleP") as? TextView)?.let {
                it.text = if (parameterOpen) t("collapse") else t("expand")
            }
        }
        if (::transformBody.isInitialized) {
            transformBody.visibility = if (transformOpen) View.VISIBLE else View.GONE
            (root.findViewWithTag<View>("toggleT") as? TextView)?.let {
                it.text = if (transformOpen) t("collapse") else t("expand")
            }
        }
    }

    private fun bg(view: View, color: Int, radiusDp: Float = 12f) {
        ThemeUi.bg(view, color, radiusDp)
    }

    private fun switchRow(title: String, sub: String, action: (Boolean) -> Unit): Pair<View, SwitchCompat> {
        val row = ThemeUi.row(this)
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = ThemeUi.dp(this@MainActivity, 10f)
            }
            addView(ThemeUi.text(this@MainActivity, title, 12f, palette, bold = true))
            addView(ThemeUi.text(this@MainActivity, sub, 10f, palette, role = ThemeUi.ROLE_FAINT))
        }
        row.addView(main)
        val sw = SwitchCompat(this).apply {
            isChecked = false
            setOnCheckedChangeListener { _, checked -> action(checked) }
        }
        row.addView(sw)
        return row to sw
    }

    private fun formatSpeedShort(v: Double): String = when {
        v == 1.0 -> "1"
        v == 2.0 -> "2"
        v == 0.5 -> "0.5"
        v == 0.75 -> "0.75"
        v == 1.25 -> "1.25"
        v == 1.5 -> "1.5"
        else -> v.toString()
    }

    private fun semiName(semi: Double): String = when (semi) {
        -12.0, -8.0, -4.0, 0.0, 4.0, 8.0, 12.0 -> AppLanguage.pitch(this, semi)
        else -> "${if (semi > 0) "+" else ""}${semi.toInt()}"
    }

    private fun formatPitchLabel(name: String, semi: Double): String =
        "${AppLanguage.pitch(this, semi)} · ${if (semi > 0) "+" else ""}${semi.toInt()} ${t("semitone")}"

    private fun pitchPickerLabel(): String = PITCH_PRESETS.firstOrNull { it.semi == transform.semi }
        ?.let { formatPitchLabel(it.name, it.semi) }
        ?: "${t("preset.custom")} · ${if (transform.semi > 0) "+" else ""}${transform.semi.toInt()} ${t("semitone")}"

    /** 用户点击“退出”时彻底停掉音频链路，否则前台服务会让应用继续驻留后台。 */
    private fun shutdownForExit() {
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
    private fun showExitDialog() {
        if (exitDialog?.isShowing == true) return
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ThemeUi.dp(this@MainActivity, 18f), ThemeUi.dp(this@MainActivity, 20f),
                ThemeUi.dp(this@MainActivity, 18f), ThemeUi.dp(this@MainActivity, 10f))
            background = ThemeUi.bgDrawable(this@MainActivity, palette.surface, 14f)
            addView(ThemeUi.text(this@MainActivity, "退出 8D 空间音频", 18f, palette, bold = true))
            addView(ThemeUi.text(this@MainActivity, "退出将停止播放，也可以保留音乐并转入后台。", 12f, palette,
                role = ThemeUi.ROLE_SUB).apply { setPadding(0, ThemeUi.dp(this@MainActivity, 8f), 0, ThemeUi.dp(this@MainActivity, 16f)) })
            addView(ThemeUi.row(this@MainActivity, gravity = Gravity.END).apply {
                addView(ThemeUi.text(this@MainActivity, "后台播放", 13f, palette, role = ThemeUi.ROLE_ACCENT).apply {
                    setPadding(ThemeUi.dp(this@MainActivity, 12f), ThemeUi.dp(this@MainActivity, 10f),
                        ThemeUi.dp(this@MainActivity, 12f), ThemeUi.dp(this@MainActivity, 10f))
                    setOnClickListener { exitDialog?.dismiss(); moveTaskToBack(true) }
                })
                addView(ThemeUi.text(this@MainActivity, "取消", 13f, palette, role = ThemeUi.ROLE_SUB).apply {
                    setPadding(ThemeUi.dp(this@MainActivity, 12f), ThemeUi.dp(this@MainActivity, 10f),
                        ThemeUi.dp(this@MainActivity, 12f), ThemeUi.dp(this@MainActivity, 10f))
                    setOnClickListener { exitDialog?.dismiss() }
                })
                addView(ThemeUi.text(this@MainActivity, "退出", 13f, palette).apply {
                    setTextColor(0xffff7070.toInt())
                    setPadding(ThemeUi.dp(this@MainActivity, 12f), ThemeUi.dp(this@MainActivity, 10f),
                        ThemeUi.dp(this@MainActivity, 12f), ThemeUi.dp(this@MainActivity, 10f))
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
            setOnShowListener { window?.setLayout(ThemeUi.dp(this@MainActivity, 292f), ViewGroup.LayoutParams.WRAP_CONTENT) }
        }
        exitDialog?.show()
    }

    private fun showInfoSheet() {
        if (infoDialog?.isShowing == true) return
        val expandedSections = mutableSetOf(t("info.quick"))
        fun LinearLayout.section(title: String, content: String) {
            lateinit var desc: TextView
            addView(ThemeUi.text(this@MainActivity, title, 14f, palette, bold = true, role = ThemeUi.ROLE_ACCENT).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 0)
                background = null
                minHeight = 0
                minimumHeight = 0
                layoutParams = LinearLayout.LayoutParams(-1, ThemeUi.dp(this@MainActivity, 30f))
                setOnClickListener {
                    val opening = !expandedSections.remove(title)
                    if (opening) expandedSections += title
                    desc.visibility = if (opening) View.VISIBLE else View.GONE
                }
            })
            desc = ThemeUi.text(this@MainActivity, content, 12f, palette, role = ThemeUi.ROLE_SUB).apply {
                setPadding(ThemeUi.dp(this@MainActivity, 10f), ThemeUi.dp(this@MainActivity, 4f),
                    ThemeUi.dp(this@MainActivity, 10f), ThemeUi.dp(this@MainActivity, 6f))
                visibility = if (title in expandedSections) View.VISIBLE else View.GONE
            }
            addView(desc)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ThemeUi.dp(this@MainActivity, 16f), 0, ThemeUi.dp(this@MainActivity, 16f), ThemeUi.dp(this@MainActivity, 12f))
            ThemeUi.bg(this, palette.surface, 18f)
        }
        val header = ThemeUi.row(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, ThemeUi.dp(this@MainActivity, 48f))
            addView(ThemeUi.text(this@MainActivity, t("info"), 16f, palette, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ThemeUi.text(this@MainActivity, t("close"), 12f, palette, role = ThemeUi.ROLE_ACCENT).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@MainActivity, 56f), -1)
                setOnClickListener { infoDialog?.dismiss() }
            })
        }
        content.addView(header)
        content.addView(ThemeUi.divider(this, palette))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ThemeUi.text(this@MainActivity, t("info.app"), 16f, palette, bold = true).apply {
                setPadding(0, ThemeUi.dp(this@MainActivity, 6f), 0, ThemeUi.dp(this@MainActivity, 3f))
            })
            val version = packageManager.getPackageInfo(packageName, 0)
            addView(ThemeUi.text(this@MainActivity, "${t("app.name")}  v${version.versionName}  ·  Version Code ${version.longVersionCode}",
                12f, palette, role = ThemeUi.ROLE_SUB).apply {
                setPadding(0, 0, 0, ThemeUi.dp(this@MainActivity, 4f))
            })
            addView(ThemeUi.divider(this@MainActivity, palette, 2f))
            listOf(
                t("info.quick") to t("info.quick.body"),
                t("info.lyrics") to t("info.lyrics.body"),
                t("info.visualizer") to t("info.visualizer.body"),
                t("info.export") to t("info.export.body"),
                t("info.legal") to t("info.legal.body")
            ).forEach { (title, content) -> section(title, content) }
            addView(ThemeUi.text(this@MainActivity, t("info.params"), 16f, palette, bold = true).apply {
                setPadding(0, ThemeUi.dp(this@MainActivity, 12f), 0, ThemeUi.dp(this@MainActivity, 3f))
            })
            addView(ThemeUi.divider(this@MainActivity, palette, 2f))
            PARAM_INFO.forEach { info ->
                section(t("slider.${info.key}"), t("param.${info.key}"))
            }
            addView(ThemeUi.text(this@MainActivity, t("info.legend"), 12f, palette, role = ThemeUi.ROLE_SUB).apply {
                setPadding(0, ThemeUi.dp(this@MainActivity, 6f), 0, ThemeUi.dp(this@MainActivity, 12f))
            })
        }
        val scroll = ScrollView(this).apply {
            tag = "infoScroll"
            contentDescription = "说明滚动区"
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(ThemeUi.dp(this@MainActivity, 12f))
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

    private fun showExportSheet() {
        if (exportDialog?.isShowing == true) return
        if (exporting) { toast("正在导出，请等待完成"); return }
        if (isLive) { toast("电台直播没有完整时间轴，请先录音再导出"); return }
        if (service?.player?.duration ?: 0.0 <= 0.0) { toast("请先加载音频"); return }
        val sheet = ThemeUi.showSheet(this, palette, "导出 WAV", subtitle = "无损音质 · 仅导出你拥有权利的本地音频") {
            fun destination(title: String, description: String, actionLabel: String, action: () -> Unit) = ThemeUi.row(this@MainActivity).apply {
                background = ThemeUi.bgDrawable(this@MainActivity, palette.surfaceAlt, 8f)
                setPadding(ThemeUi.dp(this@MainActivity, 12f), 0, ThemeUi.dp(this@MainActivity, 12f), 0)
                layoutParams = LinearLayout.LayoutParams(-1, ThemeUi.dp(this@MainActivity, 58f)).apply { topMargin = ThemeUi.dp(this@MainActivity, 8f) }
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    addView(ThemeUi.text(this@MainActivity, title, 13f, palette, bold = true))
                    addView(ThemeUi.text(this@MainActivity, description, 10f, palette, role = ThemeUi.ROLE_SUB))
                })
                addView(ThemeUi.text(this@MainActivity, actionLabel, 12f, palette, role = ThemeUi.ROLE_ACCENT))
                setOnClickListener { action() }
            }
            addView(destination("默认位置", "保存到公共 Music/8D 目录", "导出 ▸") {
                exportDialog?.dismiss()
                exportPublic(currentItem?.name?.substringBeforeLast('.') ?: "8d_export")
            })
            addView(destination("自选位置…", "拉起系统对话框，自选目录与文件名", "选择 ▸") {
                exportDialog?.dismiss()
                val name = currentItem?.name?.substringBeforeLast('.') ?: "8d_export"
                saveWav.launch("$name-8D.wav")
            })
        }
        exportDialog = sheet
        sheet.show()
    }

    private fun isPlaying(): Boolean =
        if (isLive) service?.radio?.isPlaying == true else service?.player?.isPlaying == true

    private fun showSourceSheet() {
        if (!fmUnlocked) return
        if (fmDialog?.isShowing == true) return
        sourceStatusView = null
        val sheet = ThemeUi.showSheet(this, palette, t("fm"), "8D",
            cancelLabel = t("close"), fullHeight = true, scrollable = false) {
            populateSourceSheet(this)
        }
        fmDialog = sheet
        sourceStatusView = ThemeUi.text(this, t("fm.status"), 11f, palette, role = ThemeUi.ROLE_SUB)
        sheet.findViewById<ViewGroup>(android.R.id.content)?.let { content ->
            (content.getChildAt(0) as? LinearLayout)?.addView(sourceStatusView)
        }
        sheet.show()
    }

    /** 筛选只替换当前弹窗内容，不销毁 Dialog，避免整窗闪动。 */
    private fun populateSourceSheet(body: LinearLayout) {
        body.removeAllViews()
        val filters = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        filters.addView(filterScroll(t("region"), RadioCatalog.regions, sourceRegion) {
            sourceRegion = it
            populateSourceSheet(fmDialog?.window?.decorView?.findViewWithTag("sheetBody") ?: return@filterScroll)
        })
        filters.addView(filterScroll(t("genre"), RadioCatalog.types, sourceType) { sourceType = it; refreshStationFilter() })
        body.addView(filters)
        addStationBrowser(body, filters)
    }

    private fun tabRow(labels: List<String>, selected: Int, compact: Boolean = false,
        action: (Int) -> Unit): LinearLayout =
        ThemeUi.row(this).apply {
            val padding = if (compact) 2f else 5f
            setPadding(0, ThemeUi.dp(this@MainActivity, padding), 0, ThemeUi.dp(this@MainActivity, padding))
            background = ThemeUi.bgDrawable(this@MainActivity, palette.control, 10f)
            labels.forEachIndexed { i, label ->
                addView(ThemeUi.button(this@MainActivity, palette, label,
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
                        ThemeUi.dp(this@MainActivity, if (compact) 28f else 36f), 1f)
                })
            }
        }

    private fun filterScroll(label: String, values: List<String>, selected: String, action: (String) -> Unit): LinearLayout =
        ThemeUi.row(this).apply {
            setPadding(0, ThemeUi.dp(this@MainActivity, 5f), 0, ThemeUi.dp(this@MainActivity, 3f))
            addView(ThemeUi.text(this@MainActivity, label, 11f, palette, role = ThemeUi.ROLE_SUB))
            val row = ThemeUi.row(this@MainActivity)
            values.forEach { value ->
                row.addView(ThemeUi.button(this@MainActivity, palette, value,
                    if (value == selected) ThemeUi.ROLE_ACCENT else ThemeUi.ROLE_CONTROL) { action(value) })
            }
            addView(HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun addStationBrowser(parent: LinearLayout, filters: LinearLayout) {
        val stations = RadioCatalog.filter(false, sourceRegion, sourceCity, sourceType)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stationListBody = body
        val search = ThemeUi.input(this, palette, t("fm.search.hint")).apply {
            setOnEditorActionListener { _, _, _ ->
                renderStations(body, RadioCatalog.filter(false, sourceRegion, sourceCity, sourceType, text.toString()))
                true
            }
        }
        parent.addView(search)
        val seq = ++sourceSearchSeq
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString().orEmpty()
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
        val toggle = ThemeUi.text(this, t("fm.custom"), 11f, palette, role = ThemeUi.ROLE_SUB).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ThemeUi.dp(this@MainActivity, 6f), 0, ThemeUi.dp(this@MainActivity, 6f))
            setOnClickListener {
                val open = details.visibility != View.VISIBLE
                details.visibility = if (open) View.VISIBLE else View.GONE
                text = if (open) t("fm.custom").replace("▸", "▾") else t("fm.custom")
            }
        }
        val input = ThemeUi.input(this, palette, t("fm.url.hint")).apply {
            setText(preferences.getString("customRadio", ""))
            setOnEditorActionListener { _, _, _ -> playCustom(text.toString()); true }
        }
        details.addView(ThemeUi.row(this).apply {
            addView(input, LinearLayout.LayoutParams(0, ThemeUi.dp(this@MainActivity, 42f), 1f))
            addView(ThemeUi.button(this@MainActivity, palette, t("play"), ThemeUi.ROLE_ACCENT_FILL) {
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
        details.visibility = View.GONE
        filters.addView(footer)
    }

    private fun refreshStationFilter() {
        val body = stationListBody ?: return
        body.post { renderStations(body, RadioCatalog.filter(false, sourceRegion, sourceCity, sourceType)) }
    }

    private fun renderStations(parent: LinearLayout, stations: List<RadioStation>) {
        parent.removeAllViews()
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stations.forEach { station ->
            val active = currentStationId == station.id && isLive
            rows.addView(ThemeUi.row(this).apply {
                setPadding(ThemeUi.dp(this@MainActivity, 8f), ThemeUi.dp(this@MainActivity, 7f),
                    ThemeUi.dp(this@MainActivity, 8f), ThemeUi.dp(this@MainActivity, 7f))
                background = ThemeUi.bgDrawable(this@MainActivity, if (active) palette.surfaceAlt else palette.surface, 8f)
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(ThemeUi.text(this@MainActivity, "${station.name} · ${station.level}", 13f, palette,
                        bold = true, role = if (active) ThemeUi.ROLE_ACCENT else ThemeUi.ROLE_TEXT))
                    addView(ThemeUi.text(this@MainActivity,
                        "${station.region}${if (station.city == station.region) "" else " · ${station.city}"} · ${station.type} · ${station.sources.size} 路源",
                        10f, palette, role = ThemeUi.ROLE_SUB))
                    addView(ThemeUi.text(this@MainActivity, station.note, 10f, palette, role = ThemeUi.ROLE_FAINT))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ThemeUi.text(this@MainActivity, if (active) "LIVE" else "${t("play")} ▸", 12f, palette, role = ThemeUi.ROLE_ACCENT))
                setOnClickListener { playRadio(station); dismissSource() }
            })
        }
        parent.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(rows)
        }, LinearLayout.LayoutParams(-1, -1))
    }

    private fun dismissSource() { fmDialog?.dismiss() }

    private fun playCustom(raw: String) {
        val url = raw.trim()
        if (!url.startsWith("https://")) {
            toast("仅支持 HTTPS 直播流地址，请确认你有权播放和录制该内容")
            return
        }
        preferences.edit().putString("customRadio", url).apply()
        playRadio(RadioStation("custom", "自定义电台", "自定义", "自定义", "网络台", "自定义", "手动输入的公开直播流",
            listOf(j5i.spatial8d.core.RadioSource("自定义流", url, "自动识别"))))
        dismissSource()
    }

    private fun playRadio(station: RadioStation) {
        if (recordingKind.isNotEmpty()) { toast("请先停止当前录音，再切换音源"); return }
        if (::visualizerView.isInitialized) visualizerView.pcmClock.reset()
        ++loadGeneration
        service?.stopLocal()
        currentItem = MusicItem(-1, station.name, "")
        currentUri = null
        isLive = true
        val generation = ++radioGeneration
        currentStationId = station.id
        radioSources = station.sources
        // 每个频道独立记住最近成功线路；下次先尝试它，失败仍顺序回退其它源。
        val preferredUrl = preferences.getString("radioSource:${station.id}", null)
        val preferred = radioSources.indexOfFirst { it.url == preferredUrl }.coerceAtLeast(0)
        stationIndex = RadioCatalog.filter(false).indexOfFirst { it.id == station.id }
        tryRadioSource(preferred, generation)
        dismissSource()
    }

    /** 单台内部按顺序回退；失败不跳到另一台，避免用户选择了 A 却听到 B。 */
    private fun tryRadioSource(index: Int, generation: Int = radioGeneration) {
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

    private fun skipStation(direction: Int) {
        val stations = RadioCatalog.filter(false)
        if (stations.isEmpty()) return
        val current = stations.indexOfFirst { it.id == currentStationId }
        playRadio(stations[(current + direction + stations.size) % stations.size])
    }

    private fun togglePlaybackRecording(statusView: TextView) {
        if (recordingKind == "playback") {
            service?.player?.stopRecording(); service?.radio?.stopRecording()
            finishRecording("播放录音", statusView)
            return
        }
        if (recordingKind.isNotEmpty()) { toast("请先停止麦克风录音"); return }
        val playing = if (isLive) service?.radio?.isPlaying == true else service?.player?.isPlaying == true
        if (!playing) { statusView.text = "先播放本地音乐或电台，再开始录制最终输出"; return }
        val path = java.io.File(cacheDir, "rec_${System.currentTimeMillis()}.wav").absolutePath
        val started = if (isLive) service?.radio?.startRecording(path) == true else service?.player?.startRecording(path) == true
        if (!started) { statusView.text = "录音启动失败，请稍后重试"; return }
        beginRecording("playback", path, statusView)
    }

    private fun startMicRecording() {
        if (recordingKind.isNotEmpty()) { toast("请先停止当前录音"); return }
        service?.player?.pause(); service?.radio?.pause()
        val path = java.io.File(cacheDir, "mic_${System.currentTimeMillis()}.wav").absolutePath
        Thread {
            val recorder = MicWavRecorder()
            if (!recorder.start(path, 44100, 1)) {
                main.post { toast("麦克风初始化失败，可能被其它应用占用") }
                return@Thread
            }
            micRecorder = recorder
            main.post { sourceStatusView?.let { beginRecording("mic", path, it) } }
        }.start()
    }

    private fun beginRecording(kind: String, path: String, statusView: TextView) {
        recordingKind = kind; recordingPath = path; recordingStartedAt = SystemClock.elapsedRealtime()
        statusView.text = "${if (kind == "mic") "麦克风" else "播放"}录音已开始"
        dismissSource()
        refreshPlayback()
    }

    private fun finishMicRecording(statusView: TextView) {
        val recorder = micRecorder ?: return
        micRecorder = null
        Thread {
            recorder.stop()
            main.post { finishRecording("麦克风录音", statusView) }
        }.start()
    }

    private fun finishRecording(label: String, statusView: TextView) {
        val path = recordingPath
        recordingKind = ""; recordingPath = ""
        statusView.text = "$label 已停止，正在保存…"
        dismissSource()
        publishRecording(java.io.File(path))
        refreshPlayback()
    }

    private fun publishRecording(file: java.io.File) {
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

    companion object {
        internal fun matchingPresetOrDefault(candidate: SpacePreset, presets: List<SpacePreset>): SpacePreset {
            val name = matchPresetIn(presets, candidate.period, candidate.elev, candidate.depth,
                candidate.room, candidate.wet, candidate.rt60, candidate.phase,
                candidate.direction, candidate.width, candidate.raw)
            return presets.firstOrNull { it.name == name } ?: DEFAULT_SPACE_PRESET
        }

        internal fun interleavedPitchOptions(presets: List<PitchPreset>): List<PitchPreset> {
            val original = presets.first { it.semi == 0.0 }
            val negative = presets.filter { it.semi < 0.0 }.sortedByDescending { it.semi }
            val positive = presets.filter { it.semi > 0.0 }.sortedBy { it.semi }
            return buildList {
                add(original); add(original)
                for (i in 0 until maxOf(negative.size, positive.size)) {
                    if (i < negative.size) add(negative[i])
                    if (i < positive.size) add(positive[i])
                }
            }
        }
    }
}
