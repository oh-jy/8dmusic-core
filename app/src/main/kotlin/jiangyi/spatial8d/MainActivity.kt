package jiangyi.spatial8d

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.ContentValues
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.app.Dialog
import android.graphics.Typeface
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
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
import jiangyi.spatial8d.audio.AudioDecoder
import jiangyi.spatial8d.audio.PlaybackService
import jiangyi.spatial8d.audio.OnlineMusic
import jiangyi.spatial8d.audio.OnlineTrack
import jiangyi.spatial8d.audio.Lyrics
import jiangyi.spatial8d.ui.LyricsView
import com.jiangyi.spatial8d.HttpMediaDataSource
import com.jiangyi.spatial8d.TsAdtsMediaDataSource
import com.jiangyi.spatial8d.MusicSourceApi
import com.jiangyi.spatial8d.MicWavRecorder
import jiangyi.spatial8d.core.DEFAULT_SPACE_PRESET
import jiangyi.spatial8d.core.RadioCatalog
import jiangyi.spatial8d.core.RadioStation
import jiangyi.spatial8d.core.SLIDERS
import jiangyi.spatial8d.core.SPACE_PRESETS
import jiangyi.spatial8d.core.SpacePreset
import jiangyi.spatial8d.core.PARAM_INFO
import jiangyi.spatial8d.core.PITCH_PRESETS
import jiangyi.spatial8d.core.TransformParams
import jiangyi.spatial8d.core.clampSlider
import jiangyi.spatial8d.core.formatValue
import jiangyi.spatial8d.core.matchPresetIn
import jiangyi.spatial8d.data.MusicItem
import jiangyi.spatial8d.data.MusicStore
import jiangyi.spatial8d.data.PlayMode
import jiangyi.spatial8d.data.PresetStore
import jiangyi.spatial8d.ui.CurveView
import jiangyi.spatial8d.ui.SpatialVisualization
import jiangyi.spatial8d.ui.PlaylistDialog
import jiangyi.spatial8d.ui.PlaylistHost
import jiangyi.spatial8d.ui.ThemeUi
import java.util.Locale
import org.json.JSONObject
import kotlin.math.roundToInt
import kotlin.math.pow

/** 页面只持有一份空间参数和队列状态；实际解码、DSP、播放统一交给 SpatialPlayer。 */
class MainActivity : AppCompatActivity(), PlaylistHost {
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
    private var playbackStatus = "就绪"
    private var transform = TransformParams()
    private var reverse = false
    private val main = Handler(Looper.getMainLooper())
    private val onlineMusic by lazy { OnlineMusic(this) }
    private val preferences by lazy { getSharedPreferences("spatial8d", Context.MODE_PRIVATE) }

    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var songTitle: TextView
    private lateinit var position: TextView
    private lateinit var seek: SeekBar
    private lateinit var visualization: SpatialVisualization
    private lateinit var curve: CurveView
    private lateinit var lyricsView: LyricsView
    private lateinit var curveLegend: LinearLayout
    private val lyrics by lazy { Lyrics(this) }
    private var lyricsGeneration = 0
    private lateinit var presetPicker: TextView
    private lateinit var playButton: View
    private lateinit var parameterPanel: LinearLayout
    private lateinit var palette: ThemeUi.Palette
    private var parameterOpen = false
    private var transformOpen = false
    private lateinit var transformBody: LinearLayout
    private lateinit var presetNote: TextView
    private lateinit var speedSlider: SeekBar
    private lateinit var pitchSlider: SeekBar
    private lateinit var speedLabel: TextView
    private lateinit var pitchLabel: TextView
    private lateinit var reverseSwitch: SwitchCompat
    private lateinit var tapeSwitch: SwitchCompat
    private lateinit var denoiseLabel: TextView
    private lateinit var denoiseSlider: SeekBar
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
    private var infoExpanded = -1
    private lateinit var transportRow: LinearLayout
    private lateinit var playerBar: LinearLayout
    private lateinit var themeButton: Button
    private lateinit var exportButton: Button
    private lateinit var disc: ImageView
    private lateinit var modeTextView: TextView
    private lateinit var currentTime: TextView
    private lateinit var durationTime: TextView
    private var seeking = false
    private var isLive = false
    private var stationIndex = -1
    private var currentStationId = ""
    private var radioSources = listOf<jiangyi.spatial8d.core.RadioSource>()
    private var radioSourceIndex = -1
    private var radioGeneration = 0
    private var sourceTab = 0
    private var sourceRegion = "全部"
    private var sourceCity = "全部"
    private var sourceType = "全部"
    private var onlineProvider = 0
    private var curvePanel = 0
    private var onlinePage = 1
    private var onlineQuery = ""
    private var onlineTracks = listOf<OnlineTrack>()
    private var onlineBody: LinearLayout? = null
    private var onlineStatusView: TextView? = null
    private var onlineSearchSeq = 0
    private var sourceSearchSeq = 0
    private var recordingKind = ""
    private var recordingPath = ""
    private var recordingStartedAt = 0L
    private var sourceDialog: Dialog? = null
    private var sourceUnlocked = false
    private var sourceTapCount = 0
    private var sourceTapAt = 0L
    private var exitDialog: Dialog? = null
    private lateinit var sourceButton: Button
    private lateinit var radioRefreshButton: Button
    private var sourceStatusView: TextView? = null
    private var playlistDialog: PlaylistDialog? = null
    private var seekGuardUntil = 0L
    private var micRecorder: MicWavRecorder? = null
    private lateinit var liveTimeline: LinearLayout
    private lateinit var recordingLabel: TextView
    private lateinit var transformCardRoot: LinearLayout
    private var pausedByHeadset = false
    private var lastStateSavedAt = 0L
    // 只恢复耳机造成的暂停；手动暂停后重新插入耳机不能擅自出声。
    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val unplugged = intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY ||
                (intent?.action == Intent.ACTION_HEADSET_PLUG && intent.getIntExtra("state", 0) == 0)
            val plugged = intent?.action == Intent.ACTION_HEADSET_PLUG && intent.getIntExtra("state", 0) == 1
            val playing = if (isLive) service?.radio?.isPlaying == true else service?.player?.isPlaying == true
            if (unplugged && playing) {
                pausedByHeadset = true
                if (isLive) service?.radio?.pause() else service?.player?.pause()
                playbackStatus = "耳机已断开，播放已暂停"
                refreshPlayback()
            } else if (plugged && pausedByHeadset) {
                pausedByHeadset = false
                requestPlaybackFocus()
                val resumed = if (isLive) service?.radio?.play() == true else service?.player?.play() == true
                if (resumed) playbackStatus = "耳机已接入，继续播放"
                refreshPlayback()
            }
        }
    }

    private val audioFocus by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setOnAudioFocusChangeListener(focusListener).build()
    }
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            main.post { if (isLive) service?.radio?.pause() else service?.player?.pause(); refreshPlayback() }
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as PlaybackService.Binder).service
            service?.controls = object : PlaybackService.Controls {
                override fun play() { main.post { if (!isPlaying()) togglePlayback() } }
                override fun pause() { main.post { if (isPlaying()) togglePlayback() } }
                override fun previous() { main.post { previous() } }
                override fun next() { main.post { next() } }
            }
            service?.player?.onEnd = { main.post { next(auto = true) } }
            service?.player?.onError = { main.post { toast(it) } }
            service?.radio?.onReady = { main.post {
                playbackStatus = "电台播放中 · ${currentItem?.name ?: "自定义电台"}"
                refreshPlayback()
            } }
            service?.radio?.onEnd = { main.post { playbackStatus = "电台已断开"; refreshPlayback() } }
            service?.radio?.onError = { main.post { playbackStatus = it; refreshPlayback() } }
            bound = true
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
    private val saveMp3 = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/mpeg")) { uri ->
        if (uri != null) exportMp3(uri)
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
        dark = preferences.getBoolean("dark", true)
        playbackStatus = "就绪"
        restorePlaybackState()
        palette = ThemeUi.palette(dark)
        buildUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (exitDialog?.isShowing == true) { exitDialog?.dismiss(); return }
                var dismissed = false
                listOf(sourceDialog, infoDialog, exportDialog).forEach { dialog ->
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
                playerBar.setPadding(ThemeUi.dp(this, 12f), ThemeUi.dp(this, 10f), ThemeUi.dp(this, 12f), ThemeUi.dp(this, 18f) + bars.bottom)
                insets
            }
        }
        startService(Intent(this, PlaybackService::class.java))
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        registerReceiver(headsetReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }, Context.RECEIVER_NOT_EXPORTED)
        bindService(Intent(this, PlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (hasAudioPermission()) scanMusic()
        restoreLast()
        main.post(ticker)
    }

    override fun onDestroy() {
        service?.player?.stopRecording()
        service?.radio?.stopRecording()
        micRecorder?.stop()
        main.removeCallbacks(ticker)
        try { unregisterReceiver(headsetReceiver) } catch (_: Exception) {}
        if (bound) unbindService(connection)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (MusicSourceApi.onActivityResult(this, requestCode, resultCode, data)) refreshOnlineBody()
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
        refreshPlayback()
        tint(root, palette.text)
        ThemeUi.applyTheme(playerBar, palette)
    }

    private fun refreshAll() {
        updatingControls = true
        palette = ThemeUi.palette(dark)
        // 下拉、展开控件、方向、曲线共用 preset，任何入口修改后都从这里统一回灌。
        presetPicker.text = preset.name
        presetNote.text = preset.note
        val dirLabels = listOf("顺时针" to 1.0, "逆时针" to -1.0, "随机" to 0.0)
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
            sliderValues[key]?.text = formatValue(key, value)
        }
        visualization.preset = preset
        val playing = if (isLive) service?.radio?.isPlaying == true else service?.player?.isPlaying == true
        visualization.playing = playing
        visualization.invalidate()
        curve.preset = preset
        curve.playing = playing
        curve.invalidate()
        if (::pitchPicker.isInitialized) pitchPicker.text = pitchPickerLabel()
        if (::pitchLabel.isInitialized) {
            pitchLabel.text = when {
                transform.semi == 0.0 -> "原声"
                transform.semi > 0 -> "+${transform.semi.toInt()} 半音"
                else -> "${transform.semi.toInt()} 半音"
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
                btn.text = "${formatSpeedShort(v)}×\n实时"
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
            denoiseLabel.text = if (transform.denoiseLevel == 0) "关闭" else "${transform.denoiseLevel}%"
            denoiseSlider.progress = transform.denoiseLevel
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
        ThemeUi.picker(presetPicker, palette)
        ThemeUi.picker(pitchPicker, palette)
    }

    private fun rebuildTransport() {
        val first = transportRow.getChildAt(0) as? LinearLayout ?: return
        val modeButton = first.getChildAt(0) as? ImageButton ?: return
        val icon = when (currentMode()) {
            PlayMode.LOOP_ONE -> jiangyi.spatial8d.R.drawable.ic_loop1
            PlayMode.SHUFFLE -> jiangyi.spatial8d.R.drawable.ic_shuffle
            else -> jiangyi.spatial8d.R.drawable.ic_loop
        }
        modeButton.setImageResource(icon)
        modeButton.contentDescription = modeText()
    }

    private fun refreshTheme() {
        palette = ThemeUi.palette(dark)
        window.statusBarColor = palette.background
        window.navigationBarColor = if (dark) 0xff17191d.toInt() else 0xfff7f8fa.toInt()
        tint(root, palette.text)
        root.setBackgroundColor(palette.background)
        (root.parent?.parent as? FrameLayout)?.setBackgroundColor(palette.background)
        root.findViewWithTag<View>("navbar")?.setBackgroundColor(palette.background)
        visualization.dark = dark
        curve.dark = dark
        if (::lyricsView.isInitialized) lyricsView.dark = dark
        if (::playerBar.isInitialized) ThemeUi.bg(playerBar, if (dark) 0xff17191d.toInt() else 0xfff7f8fa.toInt(), 0f)
        if (::disc.isInitialized) ThemeUi.bg(disc, palette.accent, 19f)
        tint(root, palette.text)
        if (::playerBar.isInitialized) ThemeUi.applyTheme(playerBar, palette)
        // PNG 图标不经过 Button 主题回刷；播放键白色，普通控制键随主题切换。
        fun tintIcons(view: View) {
            if (view is ImageButton) view.imageTintList = ColorStateList.valueOf(
                if (view.tag == ThemeUi.ROLE_ACCENT_FILL) 0xffffffff.toInt() else palette.controlText)
            if (view is ViewGroup) for (i in 0 until view.childCount) tintIcons(view.getChildAt(i))
        }
        if (::playerBar.isInitialized) tintIcons(playerBar)
        if (::themeButton.isInitialized) themeButton.text = if (dark) "浅色" else "深色"
        window.statusBarColor = palette.background
        window.navigationBarColor = if (dark) 0xff17191d.toInt() else 0xfff7f8fa.toInt()
        tint(root, palette.text)
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is LinearLayout && child.tag == "card") {
                ThemeUi.bg(child, palette.surface, 14f)
            }
        }
        refreshControls()
        refreshAll()
        listOf(sourceDialog, infoDialog, exportDialog).forEach { dialog ->
            if (dialog?.isShowing == true) {
                val content = dialog.findViewById<ViewGroup>(android.R.id.content)
                if (content != null) ThemeUi.applyTheme(content, palette)
            }
        }
        // 状态栏/导航栏图标随背景切换，浅色的时间、电量必须保持深色可读。
        preferences.edit().putBoolean("dark", dark).apply()
    }

    private fun tint(view: View, color: Int) {
        ThemeUi.applyTheme(view, palette)
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(
                if (dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        }
    }

    private fun refreshPlayback() {
        val player = service?.player
        val radio = service?.radio
        val playing = if (isLive) radio?.isPlaying == true else player?.isPlaying == true
        if (playButton is ImageButton) {
            (playButton as ImageButton).setImageResource(
                if (playing) jiangyi.spatial8d.R.drawable.ic_pause else jiangyi.spatial8d.R.drawable.ic_play
            )
            playButton.contentDescription = if (playing) "暂停" else "播放"
        } else {
            (playButton as Button).text = if (playing) "暂停" else "播放"
        }
        visualization.playing = playing
        curve.playing = playing
        if (::lyricsView.isInitialized) lyricsView.setPosition(((if (isLive) 0.0 else player?.position ?: 0.0) * 1000).toLong())
        songTitle.text = currentItem?.name ?: "未选择歌曲"
        position.visibility = View.GONE
        currentTime.text = time(player?.position ?: 0.0)
        durationTime.text = time(player?.duration ?: 0.0)
        val station = if (isLive) RadioCatalog.filter(false).firstOrNull { it.id == currentStationId } else null
        modeTextView.text = station?.let {
            Regex("(?:FM|AM)\\s?\\d+(?:\\.\\d+)?", RegexOption.IGNORE_CASE).find("${it.name} ${it.note}")?.value
                ?: if (it.type == "网络台") "网络电台" else "电台直播"
        }.orEmpty()
        modeTextView.visibility = if (isLive) View.VISIBLE else View.GONE
        status.text = playbackStatus
        (playerBar.findViewWithTag<TextView>("playerStatus"))?.text = playbackStatus
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
        if (!seeking) seek.progress = if (player?.duration ?: 0.0 > 0)
            (player!!.position / player.duration * 1000).toInt() else 0
        durationHint.text = if (player?.duration ?: 0.0 > 0) time(player!!.duration / transform.speed) else "--:--"
        (liveTimeline.findViewWithTag<TextView>("liveStatus"))?.text = playbackStatus
        service?.updateMedia(currentItem?.name ?: "8D 空间音频",
            if (isLive) "电台直播 · 8D 实时处理" else currentItem?.artist ?: playbackStatus,
            playing, if (isLive) 0.0 else player?.position ?: 0.0, if (isLive) 0.0 else player?.duration ?: 0.0)
        disc.rotation = if (playing) (SystemClock.uptimeMillis() / 12f) % 360f else disc.rotation
        (transportRow.getChildAt(0) as? LinearLayout)?.let {
            val icon = (it.getChildAt(0) as? ImageButton) ?: return@let
            icon.setImageResource(when (currentMode()) {
                PlayMode.LOOP_ONE -> jiangyi.spatial8d.R.drawable.ic_loop1
                PlayMode.SHUFFLE -> jiangyi.spatial8d.R.drawable.ic_shuffle
                else -> jiangyi.spatial8d.R.drawable.ic_loop
            })
            icon.contentDescription = modeText()
        }
    }

    private fun applyPreset() {
        service?.player?.preset = preset
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
        service?.radio?.denoise = transform.denoiseLevel
        // 实时播放走 PlaybackParams，导出时才离线重算 PCM，因此这里立即完成。
        playbackStatus = if (transform.active) "音频已更新（${formatSpeedShort(transform.speed)}× · ${transform.semi.toInt()}半音）"
            else "已恢复原始速度"
        savePlaybackState()
        refreshPlayback()
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
        // 曲线/滑块修改后，同时同步中间下拉文本、全部展开控件与预设说明。
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
                refreshPlayback()
            }
        }.start()
    }

    private fun load(item: MusicItem) {
        load(item, true)
    }

    private fun load(item: MusicItem, autoplay: Boolean) {
        // 所有选歌、切歌和冷启动恢复共用同一条加载链路，旧代号回调全部丢弃。
        if (recordingKind.isNotEmpty()) { playbackStatus = "请先停止录音，再切换歌曲"; refreshPlayback(); return }
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
        service?.player?.onEnd = { main.post { if (generation == loadGeneration) next(auto = true) } }
        service?.player?.onError = { main.post { if (generation == loadGeneration) toast(it) } }
        currentItem = item
        loadLyrics(item)
        queue = musicStore.view(songs)
        currentIndex = queue.indexOfFirst { it.id == item.id && it.uri == item.uri }
        currentQueue = queue
        currentUri = item.uri
        songTitle.text = item.name
        playbackStatus = "正在解码 ${item.name}…"
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
                            playbackStatus = "加载 $percent% · ${item.name}"; refreshPlayback()
                        } }
                    }, { generation == loadGeneration }, onHead = { head, _ ->
                        main.post { prepareLoaded(item, head, autoplay, resumePosition, generation) }
                    }) else AudioDecoder(this).decode(musicStore.readableUri(item), { percent ->
                    main.post { if (generation == loadGeneration && service?.player?.isPlaying != true) {
                        playbackStatus = "解码 $percent% · ${item.name}"; refreshPlayback()
                    } }
                }, { generation == loadGeneration }, onHead = { head, _ ->
                    main.post { prepareLoaded(item, head, autoplay, resumePosition, generation) }
                })
                } finally { stream?.close() }
                main.post {
                    if (generation == loadGeneration) {
                        if (service?.player?.isPlaying != true) playbackStatus = "解码完成 · ${item.name}"
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

    /** 本地文件与在线音频共用首块起播，后续 PCM 继续写入同一播放缓冲。 */
    private fun prepareLoaded(item: MusicItem, head: jiangyi.spatial8d.core.PcmBuffer,
        autoplay: Boolean, resumePosition: Double, generation: Int) {
        if (generation != loadGeneration) return
        service?.player?.prepare(head, item.duration / 1000.0)
        service?.player?.preset = preset
        service?.player?.transform = transform
        service?.player?.reverse = reverse
        service?.player?.seek(resumePosition)
        playbackStatus = "已加载 ${item.name} · ${head.sampleRate} Hz"
        seekGuardUntil = SystemClock.elapsedRealtime() + 1500
        if (!item.uri.startsWith("http")) preferences.edit().putString("lastUri", item.uri).apply()
        if (autoplayRequests > 0) {
            requestPlaybackFocus()
            service?.player?.play()
        }
        refreshPlayback()
        savePlaybackState()
    }

    private fun restoreLast() {
        val uri = restoreUri ?: preferences.getString("lastUri", null) ?: return
        currentUri = uri
        currentItem = songs.firstOrNull { it.uri == uri }
        currentItem?.let { load(it, false) }
    }

    private fun restorePlaybackState() {
        val text = preferences.getString("playbackState", null) ?: return
        try {
            val state = JSONObject(text)
            preset = SpacePreset("标准绕头 · 默认", "", state.getDouble("period"),
                state.getDouble("elev"), state.getDouble("depth"), state.getDouble("phase"),
                state.getDouble("direction"), state.getDouble("width"), state.getDouble("room"),
                state.getDouble("rt60"), state.getDouble("wet"), state.getBoolean("raw"))
            val builtInName = matchPresetIn(SPACE_PRESETS, preset.period,
                preset.elev, preset.depth, preset.room, preset.wet, preset.rt60, preset.phase,
                preset.direction, preset.width, preset.raw)
            val name = if (builtInName != "自定义") builtInName else matchPresetIn(SPACE_PRESETS + customPresets, preset.period,
                preset.elev, preset.depth, preset.room, preset.wet, preset.rt60, preset.phase,
                preset.direction, preset.width, preset.raw)
            preset = preset.copy(name = name)
            reverse = state.getBoolean("reverse")
            transform = TransformParams(state.getDouble("speed"), state.getDouble("semi"),
                state.getBoolean("tape"), denoise = state.optInt("denoise", 65))
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
        }.toString()).apply()
        lastStateSavedAt = SystemClock.uptimeMillis()
    }

    override fun songs(): List<MusicItem> = songs

    override fun onPause() {
        // 页面不可见时 ticker 直接返回，曲线和小球也停止请求动画帧。
        pageActive = false
        savePlaybackState()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        pageActive = true
        tint(root, palette.text)
        refreshPlayback()
    }

    override fun onStop() {
        savePlaybackState()
        super.onStop()
    }

    private fun togglePlayback() {
        pausedByHeadset = false
        if (isLive) {
            val radio = service?.radio ?: return
            if (radio.isPlaying) { radio.pause(); playbackStatus = "电台已暂停" }
            else { requestPlaybackFocus(); if (radio.play()) playbackStatus = "电台播放中" }
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
            requestPlaybackFocus()
            if (!player.play()) {
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
            if (auto) service?.player?.replay() else load(currentItem!!)
            return
        }
        if (items.isEmpty()) {
            playbackStatus = "播放列表为空，请先添加或扫描音频"
            refreshPlayback()
            showPlaylist()
            return
        }
        if (auto && currentMode() == PlayMode.LOOP_ONE) {
            service?.player?.replay()
            return
        }
        val current = items.indexOfFirst { it.uri == currentUri }
        val nextIndex = when (currentMode()) {
            PlayMode.SHUFFLE -> if (items.size > 1) (items.indices.filter { it != current }).random() else 0
            else -> (current + 1 + items.size) % items.size
        }
        currentQueue = items
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
            { item -> currentQueue = queue; load(item) }, { queue = it }, songs)
        playlistDialog?.show()
    }

    private fun togglePlaylist() {
        if (playlistDialog?.isShowing() == true) playlistDialog?.dismiss() else showPlaylist()
    }

    override fun addAudio() = openAudio.launch(arrayOf("audio/*"))

    private fun saveCustomPreset() {
        saveCustomPreset("")
    }

    private fun saveCustomPreset(rawInput: String) {
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
    private fun requestPlaybackFocus() {
        val manager = getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.requestAudioFocus(audioFocus)
        else manager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
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
        player.export(stream, mp3 = false,
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

    private fun exportMp3(uri: Uri, onComplete: () -> Unit = {}) {
        if (exporting) { toast("已有导出任务，请等待完成"); return }
        val player = service?.player ?: return
        exporting = true
        val mp3 = java.io.File(cacheDir, "8d-export.mp3")
        playbackStatus = "正在导出 MP3…"
        val stream = contentResolver.openOutputStream(uri) ?: run {
            exporting = false
            toast("无法创建导出文件")
            return
        }
        player.export(stream, mp3 = true, mp3Path = mp3.absolutePath,
            onProgress = { percent -> main.post { playbackStatus = "导出 $percent%"; refreshPlayback() } },
            onDone = { ok, message ->
                mp3.delete()
                if (ok) {
                    try {
                        playbackStatus = message
                        onComplete()
                    } catch (e: Exception) {
                        playbackStatus = "MP3 保存失败: ${e.message}"
                        discardPending(uri)
                    }
                } else { playbackStatus = message; discardPending(uri) }
                exporting = false
                refreshPlayback()
                toast(playbackStatus)
            })
    }

    private fun exportPublic(name: String, mp3: Boolean) {
        if (exporting) { toast("已有导出任务，请等待完成"); return }
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$name-8D.${if (mp3) "mp3" else "wav"}")
            put(MediaStore.Audio.Media.MIME_TYPE, if (mp3) "audio/mpeg" else "audio/wav")
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
        if (mp3) exportMp3(uri, done) else exportWav(uri, done)
    }

    private fun discardPending(uri: Uri) {
        // 导出失败时撤销未发布条目，避免 Music 里留下 0 字节文件。
        if ("media" == uri.authority) contentResolver.delete(uri, "${MediaStore.Audio.Media.IS_PENDING}=1", null)
    }

    private fun pickOption(title: String, names: List<String>, selected: Int, action: (Int) -> Unit) {
        lateinit var dialog: Dialog
        dialog = ThemeUi.showSheet(this, palette, title, compactPicker = true) {
            names.forEachIndexed { index, name ->
                addView(ThemeUi.text(this@MainActivity, name, 13f, palette,
                    bold = index == selected, role = if (index == selected) ThemeUi.ROLE_ACCENT else ThemeUi.ROLE_TEXT).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(ThemeUi.dp(this@MainActivity, 14f), 0, ThemeUi.dp(this@MainActivity, 14f), 0)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeUi.dp(this@MainActivity, 38f))
                    background = ThemeUi.bgDrawable(this@MainActivity,
                        if (index == selected) palette.surfaceAlt else palette.surface, 8f)
                    setOnClickListener { dialog.dismiss(); action(index) }
                })
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
        PlayMode.LOOP_LIST -> "列表循环"
        PlayMode.LOOP_ONE -> "单曲循环"
        PlayMode.SHUFFLE -> "随机播放"
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
            main.postDelayed(this, 100)
        }
    }
    private fun navbar(): LinearLayout {
        val bar = ThemeUi.row(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ThemeUi.dp(this@MainActivity, 16f), 0, ThemeUi.dp(this@MainActivity, 16f), 0)
            setBackgroundColor(palette.background)
            tag = "navbar"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeUi.dp(this@MainActivity, 52f))
        }
        bar.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ThemeUi.row(this@MainActivity).apply {
                addView(ThemeUi.text(this@MainActivity, "8D 空间音频", 16f, palette, bold = true).apply {
                    setOnClickListener { unlockSource() }
                })
                addView(ThemeUi.text(this@MainActivity, "ⓘ", 18f, palette, role = ThemeUi.ROLE_SUB).apply {
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@MainActivity, 30f), ThemeUi.dp(this@MainActivity, 30f)).apply {
                        leftMargin = ThemeUi.dp(this@MainActivity, 5f)
                    }
                    setOnClickListener { showInfoSheet() }
                })
            })
            addView(ThemeUi.text(this@MainActivity, "HRTF 双耳引擎 · Android", 10f, palette, role = ThemeUi.ROLE_FAINT))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        fun navButton(label: String, action: () -> Unit) = ThemeUi.button(this, palette, label, minHeightDp = 30f).apply {
            textSize = 11f
            setOnClickListener { action() }
        }
        sourceButton = navButton("音源") { showSourceSheet() }.apply {
            visibility = if (sourceUnlocked) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(-2, ThemeUi.dp(this@MainActivity, 30f)).apply { leftMargin = ThemeUi.dp(this@MainActivity, 6f) }
        }
        bar.addView(sourceButton)
        themeButton = navButton(if (dark) "浅色" else "深色") { dark = !dark; refreshTheme() }
        themeButton.apply {
            layoutParams = LinearLayout.LayoutParams(-2, ThemeUi.dp(this@MainActivity, 30f)).apply { leftMargin = ThemeUi.dp(this@MainActivity, 6f) }
        }
        bar.addView(themeButton)
        return bar
    }

    /** 音源是页面级隐藏入口：1.2 秒内三击标题解锁，不写入持久化。 */
    private fun unlockSource() {
        if (sourceUnlocked) return
        val now = SystemClock.elapsedRealtime()
        sourceTapCount = if (now - sourceTapAt <= 1200) sourceTapCount + 1 else 1
        sourceTapAt = now
        if (sourceTapCount >= 3) {
            sourceUnlocked = true
            sourceTapCount = 0
            sourceButton.visibility = View.VISIBLE
        }
    }

    private fun card(block: LinearLayout.() -> Unit): LinearLayout = ThemeUi.card(this, palette, block)

    private fun spaceSettingsCard(): LinearLayout {
        val card = card {
            val header = FrameLayout(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeUi.dp(this@MainActivity, 32f))
            }
            header.addView(ThemeUi.text(this@MainActivity, "空间风格", 14f, palette, bold = true), FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
            presetPicker = ThemeUi.text(this@MainActivity, preset.name, 12f, palette, role = ThemeUi.ROLE_CONTROL).apply {
                ThemeUi.hideOverflow(this)
                gravity = Gravity.CENTER
                setPadding(ThemeUi.dp(this@MainActivity, 12f), 0, ThemeUi.dp(this@MainActivity, 24f), 0)
                layoutParams = FrameLayout.LayoutParams(ThemeUi.dp(this@MainActivity, 168f), ThemeUi.dp(this@MainActivity, 32f), Gravity.CENTER)
                setOnClickListener {
                    val all = SPACE_PRESETS + customPresets
                    pickOption("选择空间风格", all.map { it.name }, all.indexOf(preset)) {
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
            header.addView(ThemeUi.text(this@MainActivity, "展开 ▾", 11f, palette, role = ThemeUi.ROLE_SUB).apply {
                tag = "toggleP"
                layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL)
                setOnClickListener { toggleParameterOpen() }
            })
            addView(header)
            presetNote = ThemeUi.text(this@MainActivity, preset.note, 12f, palette, role = ThemeUi.ROLE_SUB).apply {
                setPadding(0, ThemeUi.dp(this@MainActivity, 5f), 0, ThemeUi.dp(this@MainActivity, 5f))
            }
            addView(presetNote)
            parameterPanel = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(parameterPanel)
            parameterPanel.addView(ThemeUi.divider(this@MainActivity, palette).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeUi.dp(this@MainActivity, 1f)).apply {
                    topMargin = ThemeUi.dp(this@MainActivity, 9f); bottomMargin = ThemeUi.dp(this@MainActivity, 9f)
                }
            })
            parameterPanel.addView(ThemeUi.row(this@MainActivity).apply {
                addView(ThemeUi.text(this@MainActivity, "旋转方向", 12f, palette).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(dirSegmented(), LinearLayout.LayoutParams(0, ThemeUi.dp(this@MainActivity, 32f), 2.2f))
            })
            parameterPanel.addView(ThemeUi.text(this@MainActivity, "随机模式每 2–8 秒自动切换顺/逆时针", 10f, palette, role = ThemeUi.ROLE_FAINT))
            parameterPanel.addView(ThemeUi.row(this@MainActivity).apply {
                addView(ThemeUi.text(this@MainActivity, "运动轨迹", 12f, palette, bold = true))
                addView(ThemeUi.text(this@MainActivity, "环绕速度 · 起伏 · 起手方位", 10f, palette, role = ThemeUi.ROLE_FAINT))
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
                hint = "自定义名称，留空自动编号"
                textSize = 11f
            }
            saveRow.addView(saveInput.apply {
                layoutParams = LinearLayout.LayoutParams(0, ThemeUi.dp(this@MainActivity, 36f), 1f).apply {
                    rightMargin = ThemeUi.dp(this@MainActivity, 8f)
                }
            })
            saveRow.addView(ThemeUi.button(this@MainActivity, palette, "保存风格", role = ThemeUi.ROLE_ACCENT_FILL) {
                saveCustomPreset(saveInput.text.toString())
            })
            parameterPanel.addView(saveRow)
            parameterPanel.addView(ThemeUi.divider(this@MainActivity, palette).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeUi.dp(this@MainActivity, 1f)).apply {
                    topMargin = ThemeUi.dp(this@MainActivity, 12f); bottomMargin = ThemeUi.dp(this@MainActivity, 9f)
                }
            })
            parameterPanel.addView(ThemeUi.row(this@MainActivity).apply {
                addView(ThemeUi.text(this@MainActivity, "房间声学", 12f, palette, bold = true))
                addView(ThemeUi.text(this@MainActivity, "空间大小 · 混响量感与尾音", 10f, palette, role = ThemeUi.ROLE_FAINT))
            }.apply {
                setPadding(0, ThemeUi.dp(this@MainActivity, 4f), 0, ThemeUi.dp(this@MainActivity, 4f))
            })
            parameterPanel.addView(sliderGroup(SLIDERS.filter { it.key in setOf("room", "wet", "rt60", "width") }))
            parameterPanel.visibility = View.GONE
        }
        return card
    }

    private fun sliderGroup(definitions: List<jiangyi.spatial8d.core.SliderDef>): LinearLayout {
        val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        definitions.forEach { definition ->
            val row = ThemeUi.row(this)
            row.addView(ThemeUi.text(this, definition.label, 12f, palette).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            sliderValues[definition.key] = ThemeUi.text(this, "", 12f, palette, role = ThemeUi.ROLE_ACCENT)
            row.addView(sliderValues[definition.key])
            group.addView(row)
            val slider = SeekBar(this).apply {
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
        val options = listOf("顺时针" to 1.0, "逆时针" to -1.0, "随机" to 0.0)
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
            header.addView(ThemeUi.text(this@MainActivity, "音频变换", 14f, palette, bold = true), FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
            pitchPicker = ThemeUi.text(this@MainActivity, "原声", 12f, palette, role = ThemeUi.ROLE_CONTROL).apply {
                gravity = Gravity.CENTER
                ThemeUi.hideOverflow(this)
                layoutParams = FrameLayout.LayoutParams(ThemeUi.dp(this@MainActivity, 168f), ThemeUi.dp(this@MainActivity, 32f), Gravity.CENTER)
                setOnClickListener {
                    pickOption("选择变声风格", PITCH_PRESETS.map { formatPitchLabel(it.name, it.semi) }, PITCH_PRESETS.indexOfFirst { it.semi == transform.semi }) {
                        transform = transform.copy(semi = PITCH_PRESETS[it].semi); applyTransform(); refreshAll()
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
            header.addView(ThemeUi.text(this@MainActivity, "展开 ▾", 11f, palette, role = ThemeUi.ROLE_SUB).apply {
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
            pitchRow.addView(ThemeUi.text(this@MainActivity, "变声", 12f, palette).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            pitchLabel = ThemeUi.text(this@MainActivity, "原声", 12f, palette, role = ThemeUi.ROLE_ACCENT)
            pitchRow.addView(pitchLabel)
            transformBody.addView(pitchRow)
            pitchSlider = SeekBar(this@MainActivity).apply {
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
            transformBody.addView(switchRow("倒放", "从结尾向开头播放") { reverse = it; service?.player?.reverse = it }.also {
                reverseSwitch = it.second
            }.first.apply { tag = "localOnly" })
            speedLabel = ThemeUi.text(this@MainActivity, "1.00×", 12f, palette, role = ThemeUi.ROLE_ACCENT)
            transformBody.addView(ThemeUi.row(this@MainActivity).apply {
                tag = "localOnly"
                addView(ThemeUi.text(this@MainActivity, "倍速", 12f, palette).apply {
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                })
                addView(speedLabel)
            })
            speedSlider = SeekBar(this@MainActivity).apply {
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
                addView(ThemeUi.text(this@MainActivity, "降噪", 12f, palette).apply {
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                })
                addView(denoiseLabel)
            })
            denoiseSlider = SeekBar(this@MainActivity).apply {
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
            val chips = ThemeUi.row(this@MainActivity)
            listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0).forEach { value ->
                chips.addView(ThemeUi.button(this@MainActivity, palette, "${formatSpeedShort(value)}×\n实时", role = if (transform.speed == value) ThemeUi.ROLE_ACCENT_FILL else ThemeUi.ROLE_CONTROL).apply {
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
            transformBody.addView(switchRow("磁带模式", "音高随时长一起变") { transform = transform.copy(tape = it); applyTransform() }.also {
                tapeSwitch = it.second
            }.first.apply { tag = "localOnly" })
            durationHint = ThemeUi.text(this@MainActivity, "新时长 --:--", 12f, palette, role = ThemeUi.ROLE_ACCENT)
            transformBody.addView(ThemeUi.row(this@MainActivity).apply {
                tag = "localOnly"
                background = ThemeUi.bgDrawable(this@MainActivity, palette.surfaceAlt, 8f)
                setPadding(ThemeUi.dp(this@MainActivity, 10f), ThemeUi.dp(this@MainActivity, 6f),
                    ThemeUi.dp(this@MainActivity, 10f), ThemeUi.dp(this@MainActivity, 6f))
                addView(ThemeUi.text(this@MainActivity, "新时长", 10f, palette, role = ThemeUi.ROLE_FAINT).apply {
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
            addView(tabRow(listOf("空间曲线", "同步歌词"), curvePanel) {
                curvePanel = it
                curve.visibility = if (it == 0) View.VISIBLE else View.GONE
                lyricsView.visibility = if (it == 1) View.VISIBLE else View.GONE
                curveLegend.visibility = if (it == 0) View.VISIBLE else View.GONE
            })
            curveLegend = ThemeUi.row(this@MainActivity).apply {
                listOf(
                    "左耳" to 0xff4ea1ff.toInt(),
                    "右耳" to 0xffff7a59.toInt(),
                    "仰角" to 0xff4fd396.toInt(),
                    "远近" to 0xffa98ce8.toInt()
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
            val available = (resources.displayMetrics.heightPixels / resources.displayMetrics.density - 52 - 184 - 216).coerceAtLeast(200f)
            curve = CurveView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeUi.dp(this@MainActivity, available * 0.38f))
                onSlide = { key, value -> updateParameter(key, value) }
            }
            addView(curve)
            lyricsView = LyricsView(this@MainActivity).apply {
                dark = this@MainActivity.dark
                layoutParams = curve.layoutParams
                visibility = View.GONE
            }
            addView(lyricsView)
        }
        return card
    }

    /** 文件名和时长共同参与匹配，切歌代号阻止旧请求覆盖新歌歌词。 */
    private fun loadLyrics(item: MusicItem) {
        val generation = ++lyricsGeneration
        if (::lyricsView.isInitialized) lyricsView.clear()
        val title = item.name.substringBeforeLast('.').substringBefore(" - ").substringBefore(" · ")
        lyrics.load(title, item.artist, item.duration / 1000.0) { result ->
            if (generation != lyricsGeneration || currentItem?.uri != item.uri) return@load
            lyricsView.setLyrics(result.getOrNull())
        }
    }

    private fun nowPlayingBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ThemeUi.dp(this@MainActivity, 12f), ThemeUi.dp(this@MainActivity, 10f), ThemeUi.dp(this@MainActivity, 12f), ThemeUi.dp(this@MainActivity, 12f))
            bg(this, if (dark) 0xff17191d.toInt() else 0xfff7f8fa.toInt(), 0f)
            gravity = Gravity.BOTTOM
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }
        playerBar = bar
        val meta = ThemeUi.row(this)
        disc = ImageView(this).apply {
            setImageResource(jiangyi.spatial8d.R.drawable.ic_note)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(ThemeUi.dp(this@MainActivity, 8f), ThemeUi.dp(this@MainActivity, 8f), ThemeUi.dp(this@MainActivity, 8f), ThemeUi.dp(this@MainActivity, 8f))
            bg(this, palette.accent, 19f)
            layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@MainActivity, 38f), ThemeUi.dp(this@MainActivity, 38f))
        }
        disc.setOnClickListener { showPlaylist() }
        meta.addView(disc)
        songTitle = ThemeUi.text(this, "未加载音频", 14f, palette, bold = true).apply {
            ThemeUi.hideOverflow(this)
            setOnClickListener { showPlaylist() }
        }
        val sub = ThemeUi.row(this)
        position = ThemeUi.text(this, "00:00 / 00:00", 11f, palette, role = ThemeUi.ROLE_SUB)
        position.layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this, 76f), ViewGroup.LayoutParams.WRAP_CONTENT)
        modeTextView = ThemeUi.text(this, "", 11f, palette, role = ThemeUi.ROLE_SUB)
        recordingLabel = ThemeUi.text(this, "", 10f, palette).apply {
            setTextColor(0xffff5d5d.toInt()); visibility = View.GONE
            setPadding(ThemeUi.dp(this@MainActivity, 6f), 0, 0, 0)
        }
        val statusTxt = ThemeUi.text(this, status.text.toString(), 11f, palette, role = ThemeUi.ROLE_SUB).apply {
            tag = "playerStatus"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = ThemeUi.dp(this@MainActivity, 8f)
            }
            ThemeUi.hideOverflow(this)
        }
        sub.addView(position)
        sub.addView(modeTextView.apply {
            setPadding(ThemeUi.dp(this@MainActivity, 8f), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@MainActivity, 56f), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        sub.addView(recordingLabel)
        sub.addView(statusTxt)
        meta.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(songTitle)
            addView(sub)
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
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) currentTime.text = time((service?.player?.duration ?: 0.0) * progress / 1000.0)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) { seeking = true }
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    val target = (service?.player?.duration ?: 0.0) * seekBar.progress / 1000.0
                    // 加载回灌产生的伪拖动不能跳曲；真正手势低于 0.8 秒也直接忽略。
                    if (SystemClock.elapsedRealtime() >= seekGuardUntil &&
                        kotlin.math.abs(target - (service?.player?.position ?: 0.0)) >= 0.8) service?.player?.seek(target)
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
            addView(iconControl(jiangyi.spatial8d.R.drawable.ic_loop, action = {
                val modes = PlayMode.entries
                val nextMode = modes[(modes.indexOf(currentMode()) + 1) % modes.size]
                preferences.edit().putString("mode", nextMode.name).apply()
                refreshPlayback()
            }))
        })
        controls.addView(iconControl(jiangyi.spatial8d.R.drawable.ic_prev) { previous() }.apply {
            layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@MainActivity, 38f), ThemeUi.dp(this@MainActivity, 38f))
        })
        playButton = iconControl(jiangyi.spatial8d.R.drawable.ic_play, big = true, action = { togglePlayback() }).apply {
            layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@MainActivity, 48f), ThemeUi.dp(this@MainActivity, 48f)).apply { leftMargin = ThemeUi.dp(this@MainActivity, 20f); rightMargin = ThemeUi.dp(this@MainActivity, 20f) }
        }
        controls.addView(playButton)
        controls.addView(iconControl(jiangyi.spatial8d.R.drawable.ic_next) { next() }.apply {
            layoutParams = LinearLayout.LayoutParams(ThemeUi.dp(this@MainActivity, 38f), ThemeUi.dp(this@MainActivity, 38f))
        })
        controls.addView(LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.RIGHT
            addView(iconControl(jiangyi.spatial8d.R.drawable.ic_list) { togglePlaylist() })
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
                it.text = if (parameterOpen) "收起 ▴" else "展开 ▾"
            }
        }
        if (::transformBody.isInitialized) {
            transformBody.visibility = if (transformOpen) View.VISIBLE else View.GONE
            (root.findViewWithTag<View>("toggleT") as? TextView)?.let {
                it.text = if (transformOpen) "收起 ▴" else "展开 ▾"
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
        -12.0 -> "低吼"
        -8.0 -> "恶魔"
        -4.0 -> "稳重"
        0.0 -> "原声"
        4.0 -> "卡通"
        8.0 -> "小黄人"
        12.0 -> "花栗鼠"
        else -> "${if (semi > 0) "+" else ""}${semi.toInt()}"
    }

    private fun formatPitchLabel(name: String, semi: Double): String =
        "${name.substringBeforeLast(' ')} · ${if (semi > 0) "+" else ""}${semi.toInt()}半音"

    private fun pitchPickerLabel(): String = PITCH_PRESETS.firstOrNull { it.semi == transform.semi }
        ?.let { formatPitchLabel(it.name, it.semi) }
        ?: "自定义 · ${if (transform.semi > 0) "+" else ""}${transform.semi.toInt()} 半音"

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
                    setOnClickListener { exitDialog?.dismiss(); finishAndRemoveTask() }
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
        infoDialog = ThemeUi.showSheet(this, palette, "参数说明", fullHeight = true) {
            PARAM_INFO.forEachIndexed { index, info ->
                lateinit var desc: TextView
                addView(ThemeUi.text(this@MainActivity, info.label, 13f, palette, bold = true).apply {
                    setPadding(0, ThemeUi.dp(this@MainActivity, 8f), 0, 0)
                    setOnClickListener {
                        val opening = infoExpanded != index
                        infoExpanded = if (opening) index else -1
                        desc.visibility = if (opening) View.VISIBLE else View.GONE
                    }
                })
                desc = ThemeUi.text(this@MainActivity, info.desc, 12f, palette, role = ThemeUi.ROLE_SUB).apply {
                    setPadding(0, 0, 0, ThemeUi.dp(this@MainActivity, 6f))
                    visibility = if (infoExpanded == index) View.VISIBLE else View.GONE
                }
                addView(desc)
                addView(ThemeUi.divider(this@MainActivity, palette))
            }
            addView(ThemeUi.text(this@MainActivity, "曲线图例：蓝=左耳 橙=右耳 绿=仰角 紫=远近；曲线与引擎同源。", 12f, palette, role = ThemeUi.ROLE_SUB))
        }
        infoDialog?.show()
    }

    private fun showExportSheet() {
        if (exportDialog?.isShowing == true) return
        if (exporting) { toast("正在导出，请等待完成"); return }
        if (isLive) { toast("电台直播没有完整时间轴，请先录音再导出"); return }
        if (service?.player?.duration ?: 0.0 <= 0.0) { toast("请先加载音频"); return }
        var mp3 = false
        val sheet = ThemeUi.showSheet(this, palette, "导出音频", subtitle = "选择格式与保存位置") {
            val formatRow = ThemeUi.row(this@MainActivity)
            val wav = RadioButton(this@MainActivity).apply { text = "WAV\n无损音质"; textSize = 12f; gravity = Gravity.CENTER }
            val mp3Btn = RadioButton(this@MainActivity).apply { text = "MP3\n192 kbps · 体积小"; textSize = 12f; gravity = Gravity.CENTER }
            fun setFormat(v: Boolean) {
                mp3 = v
                wav.isChecked = !v; mp3Btn.isChecked = v
                listOf(wav, mp3Btn).forEach { it.setTextColor(palette.text); it.buttonTintList = ColorStateList.valueOf(palette.accent) }
            }
            wav.setOnClickListener { setFormat(false) }
            mp3Btn.setOnClickListener { setFormat(true) }
            setFormat(false)
            formatRow.addView(wav.apply { layoutParams = LinearLayout.LayoutParams(0, ThemeUi.dp(this@MainActivity, 54f), 1f) })
            formatRow.addView(mp3Btn.apply { layoutParams = LinearLayout.LayoutParams(0, ThemeUi.dp(this@MainActivity, 54f), 1f) })
            addView(formatRow)
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
                val name = currentItem?.name?.substringBeforeLast('.') ?: "8d_export"
                exportPublic(name, mp3)
            })
            addView(destination("自选位置…", "拉起系统对话框，自选目录与文件名", "选择 ▸") {
                exportDialog?.dismiss()
                val name = currentItem?.name?.substringBeforeLast('.') ?: "8d_export"
                if (mp3) saveMp3.launch("$name-8D.mp3") else saveWav.launch("$name-8D.wav")
            })
        }
        exportDialog = sheet
        sheet.show()
    }

    private fun isPlaying(): Boolean =
        if (isLive) service?.radio?.isPlaying == true else service?.player?.isPlaying == true

    private fun showSourceSheet() {
        if (!sourceUnlocked) return
        if (sourceDialog?.isShowing == true) {
            sourceDialog?.window?.decorView?.findViewWithTag<LinearLayout>("sheetBody")
                ?.let(::populateSourceSheet)
            return
        }
        sourceTab = sourceTab.coerceIn(0, 1)
        sourceStatusView = null
        val sheet = ThemeUi.showSheet(this, palette, "音源", "多源聚合 · 自动回退 · 8D 实时处理",
            cancelLabel = "关闭", fullHeight = true, scrollable = false) {
            populateSourceSheet(this)
        }
        sourceDialog = sheet
        sourceStatusView = ThemeUi.text(this, "选择 FM 电台或免费音乐，或粘贴自己的直播流地址", 11f, palette,
            role = ThemeUi.ROLE_SUB)
        sheet.findViewById<ViewGroup>(android.R.id.content)?.let { content ->
            (content.getChildAt(0) as? LinearLayout)?.addView(sourceStatusView)
        }
        sheet.show()
    }

    private fun showSourceSheetRecreate() {
        showSourceSheet()
    }

    /** 页签和筛选只替换弹窗内容，不销毁 Dialog，避免整窗闪动。 */
    private fun populateSourceSheet(body: LinearLayout) {
        body.removeAllViews()
        body.addView(tabRow(listOf("FM 电台", "在线曲库"), sourceTab) {
            sourceTab = it
            sourceRegion = "全部"
            sourceCity = "全部"
            sourceType = "全部"
            showSourceSheetRecreate()
        })
        if (sourceTab == 0) {
            val filters = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            filters.addView(filterScroll("地区", RadioCatalog.regions, sourceRegion) {
                sourceRegion = it
                sourceCity = "全部"
                showSourceSheetRecreate()
            })
            if (sourceTab == 0 && sourceRegion != "全部" && sourceRegion != "全国") {
                    val cities = listOf("全部") + RadioCatalog.filter(false, sourceRegion)
                    .map { it.city }.distinct()
                if (cities.size > 1) filters.addView(filterScroll("城市", cities, sourceCity) {
                    sourceCity = it
                    showSourceSheetRecreate()
                })
            }
            filters.addView(filterScroll("类型", RadioCatalog.types, sourceType) {
                sourceType = it
                showSourceSheetRecreate()
            })
            body.addView(filters)
            addStationBrowser(body, filters)
        } else {
            body.addView(tabRow(listOf("聚合", "酷我", "网易云", "酷狗", "QQ", "Jamendo"), onlineProvider) {
                onlineProvider = it
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

    /** 选择器保持单行横向滚动，长目录不会挤压播放列表的可视区域。 */
    private fun tabRow(labels: List<String>, selected: Int, action: (Int) -> Unit): LinearLayout =
        ThemeUi.row(this).apply {
            setPadding(0, ThemeUi.dp(this@MainActivity, 5f), 0, ThemeUi.dp(this@MainActivity, 5f))
            background = ThemeUi.bgDrawable(this@MainActivity, palette.control, 10f)
            labels.forEachIndexed { i, label ->
                addView(ThemeUi.button(this@MainActivity, palette, label,
                    if (i == selected) ThemeUi.ROLE_SURFACE_ALT else ThemeUi.ROLE_CONTROL) { action(i) }.apply {
                    if (i == selected) setTextColor(palette.accent)
                    layoutParams = LinearLayout.LayoutParams(0, ThemeUi.dp(this@MainActivity, 36f), 1f)
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
        val stations = RadioCatalog.filter(sourceTab == 1, sourceRegion, sourceCity, sourceType)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val search = ThemeUi.input(this, palette, "搜索电台 / 省市 / 类型").apply {
            setOnEditorActionListener { _, _, _ ->
                renderStations(body, RadioCatalog.filter(sourceTab == 1, sourceRegion, sourceCity, sourceType, text.toString()))
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
                    if (seq == sourceSearchSeq && sourceDialog?.isShowing == true) {
                        renderStations(body, RadioCatalog.filter(sourceTab == 1, sourceRegion, sourceCity, sourceType, q))
                    }
                }, 160)
            }
        })
        // 电台目录占据剩余空间，自定义流与录音固定底部，不再被目录推到屏外。
        parent.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        renderStations(body, stations)
        val footer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toggle = ThemeUi.text(this, "流地址 / 麦克风录音 ▸", 11f, palette, role = ThemeUi.ROLE_SUB).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ThemeUi.dp(this@MainActivity, 6f), 0, ThemeUi.dp(this@MainActivity, 6f))
            setOnClickListener {
                val open = details.visibility != View.VISIBLE
                details.visibility = if (open) View.VISIBLE else View.GONE
                text = if (open) "流地址 / 麦克风录音 ▾" else "流地址 / 麦克风录音 ▸"
            }
        }
        val input = ThemeUi.input(this, palette, "粘贴 MP3/AAC 直播流地址").apply {
            setText(preferences.getString("customRadio", ""))
            setOnEditorActionListener { _, _, _ -> playCustom(text.toString()); true }
        }
        details.addView(ThemeUi.row(this).apply {
            addView(input, LinearLayout.LayoutParams(0, ThemeUi.dp(this@MainActivity, 42f), 1f))
            addView(ThemeUi.button(this@MainActivity, palette, "播放", ThemeUi.ROLE_ACCENT_FILL) {
                playCustom(input.text.toString())
            })
        })
        details.addView(ThemeUi.button(this, palette,
            if (recordingKind == "mic") "● 停止麦克风录音" else "● 麦克风录音") {
            if (recordingKind == "mic") sourceStatusView?.let(::finishMicRecording)
            else requestMic.launch(Manifest.permission.RECORD_AUDIO)
        })
        details.addView(ThemeUi.text(this, "电台自动尝试多路备用源；录音会保存最终 8D 输出。",
            11f, palette, role = ThemeUi.ROLE_SUB))
        footer.addView(toggle)
        footer.addView(details)
        details.visibility = View.GONE
        filters.addView(footer)
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
                addView(ThemeUi.text(this@MainActivity, if (active) "LIVE" else "播放 ▸", 12f, palette, role = ThemeUi.ROLE_ACCENT))
                setOnClickListener { playRadio(station); dismissSource() }
            })
        }
        parent.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(rows)
        }, LinearLayout.LayoutParams(-1, -1))
    }

    private fun refreshOnlineBody() {
        val body = onlineBody ?: return
        body.removeAllViews()
        body.addView(ThemeUi.row(this).apply {
            val authenticated = onlineProvider > 0 && onlineMusic.loggedIn(onlineProvider)
            addView(ThemeUi.text(this@MainActivity, if (authenticated) "已登录 · 按账号权限播放" else "未登录 · 公开曲目与免费内容",
                11f, palette, role = ThemeUi.ROLE_SUB), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ThemeUi.button(this@MainActivity, palette, when {
                onlineProvider == 5 -> "Client ID"
                authenticated -> "退出登录"
                else -> "账号认证"
            }) {
                if (onlineProvider == 5) showJamendoConfig()
                else
                if (authenticated) { onlineMusic.logout(onlineProvider); refreshOnlineBody() }
                else showOnlineAuthentication()
            })
        })
        val input = ThemeUi.input(this, palette, "搜索歌曲 / 歌手").apply { setText(onlineQuery) }
        body.addView(ThemeUi.row(this).apply {
            addView(input, LinearLayout.LayoutParams(0, ThemeUi.dp(this@MainActivity, 42f), 1f))
            addView(ThemeUi.button(this@MainActivity, palette, "搜索", ThemeUi.ROLE_ACCENT_FILL) {
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
                setPadding(0, ThemeUi.dp(this@MainActivity, 8f), 0, ThemeUi.dp(this@MainActivity, 8f))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(ThemeUi.text(this@MainActivity, track.title, 13f, palette, bold = true))
                    addView(ThemeUi.text(this@MainActivity, "${track.artist} · ${track.provider}", 11f, palette, role = ThemeUi.ROLE_SUB))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ThemeUi.text(this@MainActivity, "试听 ▸", 12f, palette, role = ThemeUi.ROLE_ACCENT))
                setOnClickListener {
                    onlineStatusView?.text = "正在获取 ${track.title} 的播放地址…"
                    onlineMusic.resolve(track) { result ->
                        result.onSuccess { playOnline(track, it) }
                            .onFailure { if (sourceDialog?.isShowing == true)
                                onlineStatusView?.text = it.message ?: "播放地址获取失败" }
                    }
                }
            })
        }
        if (onlineTracks.isNotEmpty()) tracks.addView(ThemeUi.button(this, palette, "加载更多") {
            onlinePage++
            loadOnlineTracks(true)
        })
        body.addView(ScrollView(this).apply { addView(tracks) }, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(ThemeUi.text(this, "仅试听平台允许的免费内容；QQ 音乐按当前账号权限播放，不绕过会员限制。",
            10f, palette, role = ThemeUi.ROLE_FAINT))
    }

    private fun openQqLogin() {
        onlineStatusView?.text = "首次请选择登录文件保存目录，再在微信官方页面完成授权"
        onlineMusic.login { result ->
            result.onSuccess { if (sourceDialog?.isShowing == true) { refreshOnlineBody(); loadOnlineTracks(false) } }
                .onFailure { if (sourceDialog?.isShowing == true)
                    onlineStatusView?.text = it.message ?: "登录失败" }
        }
    }

    /** 所有来源共用一个认证入口；会话按平台隔离，手机号和验证码不落盘。 */
    private fun showOnlineAuthentication() {
        val provider = onlineProvider
        if (provider == 0 || provider !in 1..5) {
            onlineStatusView?.text = "请先选择酷我、网易云、酷狗或 QQ，再管理对应账号"
            return
        }
        if (provider == 4) { openQqLogin(); return }
        if (provider != 2) {
            onlineStatusView?.text = "该平台尚未接入经验证的手机号登录，当前仅支持公开免费内容"
            return
        }
        lateinit var dialog: Dialog
        dialog = ThemeUi.showSheet(this, palette, "网易云手机号登录", "验证码由网易云官方发送；仅保存登录会话") {
            val phone = ThemeUi.input(this@MainActivity, palette, "手机号").apply {
                inputType = android.text.InputType.TYPE_CLASS_PHONE
            }
            val code = ThemeUi.input(this@MainActivity, palette, "短信验证码").apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
            addView(phone)
            addView(ThemeUi.row(this@MainActivity).apply {
                addView(code, LinearLayout.LayoutParams(0, ThemeUi.dp(this@MainActivity, 42f), 1f))
                addView(ThemeUi.button(this@MainActivity, palette, "发送验证码") {
                    onlineStatusView?.text = "正在发送验证码…"
                    onlineMusic.sendPhoneCode(provider, phone.text.toString()) { result ->
                        result.onSuccess { toast("验证码已发送") }
                            .onFailure { toast(it.message ?: "验证码发送失败") }
                    }
                })
            })
            addView(ThemeUi.button(this@MainActivity, palette, "登录", ThemeUi.ROLE_ACCENT_FILL) {
                onlineMusic.phoneLogin(provider, phone.text.toString(), code.text.toString()) { result ->
                    result.onSuccess {
                        dialog.dismiss(); refreshOnlineBody(); toast("网易云已登录")
                    }.onFailure { toast(it.message ?: "登录失败") }
                }
            })
        }
        dialog.show()
    }

    private fun showJamendoConfig() {
        lateinit var dialog: Dialog
        dialog = ThemeUi.showSheet(this, palette, "Jamendo 官方音源", "使用你自己的 client_id；不保存 client_secret") {
            val input = ThemeUi.input(this@MainActivity, palette, "client_id").apply {
                setText(onlineMusic.jamendoClientId())
            }
            addView(input)
            addView(ThemeUi.button(this@MainActivity, palette, "保存并检索", ThemeUi.ROLE_ACCENT_FILL) {
                onlineMusic.saveJamendoClientId(input.text.toString())
                dialog.dismiss(); refreshOnlineBody(); loadOnlineTracks(false)
            })
        }
        dialog.show()
    }

    private fun loadOnlineTracks(append: Boolean) {
        val seq = ++onlineSearchSeq
        onlineStatusView?.text = "正在加载…"
        if (sourceDialog?.isShowing != true) return
        val callback: (Result<List<OnlineTrack>>) -> Unit = { result ->
            if (seq == onlineSearchSeq && sourceDialog?.isShowing == true) {
                result.onSuccess {
                    onlineTracks = if (append) onlineTracks + it else it
                    refreshOnlineBody()
                    onlineStatusView?.text = if (onlineTracks.isEmpty()) "没有找到可播放歌曲" else "找到 ${onlineTracks.size} 首"
                }.onFailure {
                    onlineStatusView?.text = it.message ?: "在线曲库暂不可用"
                }
            }
        }
        if (onlineQuery.isEmpty() && !append) onlineMusic.hot(onlineProvider, callback)
        else onlineMusic.search(onlineProvider, onlineQuery, onlinePage, callback)
    }

    private fun playOnline(track: OnlineTrack, url: String) {
        val item = MusicItem((track.provider + track.id).hashCode().toLong(), "${track.title} · ${track.artist}",
            url, duration = (track.duration * 1000).toLong(), artist = track.artist)
        dismissSource()
        load(item)
    }

    private fun dismissSource() { sourceDialog?.dismiss() }

    private fun playCustom(raw: String) {
        val url = raw.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast("请输入以 http:// 或 https:// 开头的直播流地址")
            return
        }
        preferences.edit().putString("customRadio", url).apply()
        playRadio(RadioStation("custom", "自定义电台", "自定义", "自定义", "网络台", "自定义", "手动输入的公开直播流",
            listOf(jiangyi.spatial8d.core.RadioSource("自定义流", url, "自动识别"))))
        dismissSource()
    }

    private fun playRadio(station: RadioStation) {
        if (recordingKind.isNotEmpty()) { toast("请先停止当前录音，再切换音源"); return }
        ++loadGeneration
        service?.player?.stop()
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
}
