package j5i.spatial8d

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.app.Dialog
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.widget.FrameLayout
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ImageButton
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import j5i.spatial8d.audio.PlaybackService
import j5i.spatial8d.audio.Lyrics
import j5i.spatial8d.audio.LyricsResult
import j5i.spatial8d.audio.OfflineAsr
import j5i.spatial8d.ui.LyricsView
import j5i.spatial8d.ui.VisualizerView
import com.j5i.spatial8d.MicWavRecorder
import j5i.spatial8d.core.DEFAULT_SPACE_PRESET
import j5i.spatial8d.core.SpacePreset
import j5i.spatial8d.core.TransformParams
import j5i.spatial8d.data.MusicItem
import j5i.spatial8d.data.MusicStore
import j5i.spatial8d.data.PresetStore
import j5i.spatial8d.ui.CurveView
import j5i.spatial8d.ui.SpatialVisualization
import j5i.spatial8d.ui.PlaylistDialog
import j5i.spatial8d.ui.PlaylistHost
import j5i.spatial8d.ui.ThemeUi
import j5i.spatial8d.ui.AppLanguage
import j5i.spatial8d.ui.DrivingView
import j5i.spatial8d.ui.PlaylistMetadataHost
import j5i.spatial8d.ui.PlaylistLibraryHost
import kotlin.reflect.KProperty0

/** 页面只持有一份空间参数和队列状态；实际解码、DSP、播放统一交给 SpatialPlayer。 */
class MainActivity : AppCompatActivity(), PlaylistHost, PlaylistMetadataHost, PlaylistLibraryHost {
    internal lateinit var musicStore: MusicStore
    internal lateinit var presetStore: PresetStore
    internal var service: PlaybackService? = null
    internal var bound = false
    internal var songs = mutableListOf<MusicItem>()
    internal var queue = listOf<MusicItem>()
    internal var currentIndex = -1
    internal var currentQueue = listOf<MusicItem>()
    /** 合集/在线结果的暂存队列，独立于本地库，切列表、重建页面后仍可上下首切换。 */
    internal var onlineQueue = listOf<MusicItem>()
    internal var onlineQueueProvider = ""
    internal var preset = DEFAULT_SPACE_PRESET
    internal var customPresets = mutableListOf<SpacePreset>()
    internal var loading = false
    internal var loadGeneration = 0
    internal var autoplayRequests = 0
    internal var currentUri: String? = null
    internal var restoreUri: String? = null
    internal var restorePosition = 0.0
    internal var currentItem: MusicItem? = null
    internal val favoriteDownloads = mutableSetOf<Long>()
    internal var pageActive = true
    internal var exporting = false
    internal var exportProgressDialog: android.app.Dialog? = null
    internal var exportProgressText: android.widget.TextView? = null
    internal var exportStartedAt = 0L
    internal var dark = true
    /** 0=深色 1=浅色 2=跟随系统。dark 字段始终是"当前生效"的结果，由 themeMode 派生。 */
    internal var themeMode = 0
    private var displayedLanguage = ""
    internal var playbackStatus = "就绪"
    internal var transform = TransformParams()
    internal var reverse = false
    internal val main = Handler(Looper.getMainLooper())
    internal val preferences by lazy { getSharedPreferences("spatial8d", Context.MODE_PRIVATE) }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))
    internal fun t(key: String): String = AppLanguage.text(this, key)

    internal lateinit var root: LinearLayout
    internal lateinit var status: TextView
    internal lateinit var songTitle: TextView
    internal lateinit var position: TextView
    internal lateinit var seek: SeekBar
    internal lateinit var visualization: SpatialVisualization
    internal lateinit var curve: CurveView
    internal lateinit var lyricsView: LyricsView
    internal lateinit var visualizerView: VisualizerView
    internal lateinit var curveLegend: LinearLayout
    internal lateinit var curveTabs: LinearLayout
    internal lateinit var curveContent: FrameLayout
    internal val lyrics by lazy { Lyrics(this) }
    internal var lyricsGeneration = 0
    internal var currentLyrics: LyricsResult? = null
    internal lateinit var presetPicker: TextView
    internal lateinit var homePresetButton: Button
    internal lateinit var homePitchButton: Button
    internal lateinit var playButton: View
    internal lateinit var parameterPanel: LinearLayout
    internal lateinit var palette: ThemeUi.Palette
    internal var parameterOpen = false
    internal var transformOpen = false
    internal lateinit var transformBody: LinearLayout
    internal lateinit var favoriteButton: ImageButton
    internal lateinit var presetNote: TextView
    internal lateinit var speedSlider: SeekBar
    internal lateinit var pitchSlider: SeekBar
    internal lateinit var speedLabel: TextView
    internal lateinit var pitchLabel: TextView
    internal lateinit var reverseSwitch: SwitchCompat
    internal lateinit var tapeSwitch: SwitchCompat
    internal lateinit var denoiseLabel: TextView
    internal lateinit var denoiseSlider: SeekBar
    internal lateinit var clarityLabel: TextView
    internal lateinit var claritySlider: SeekBar
    internal lateinit var pitchPicker: TextView
    internal var dirButtons = listOf<Button>()
    internal var speedChipButtons = listOf<Button>()
    internal var pitchTicks = listOf<Button>()
    internal val sliderValues = mutableMapOf<String, TextView>()
    internal val sliderControls = mutableMapOf<String, SeekBar>()
    internal var updatingControls = false
    internal var draggingControl = false
    internal lateinit var durationHint: TextView
    internal var infoDialog: Dialog? = null
    internal var exportDialog: Dialog? = null
    internal var visualizerDialog: Dialog? = null
    internal var lyricsFullscreenDialog: Dialog? = null
    internal var drivingDialog: Dialog? = null
    internal var drivingView: DrivingView? = null
    internal var drivingAutoDismissed = false
    internal var lastCarMode = false
    internal var popupVisualizer: j5i.spatial8d.ui.VisualizerView? = null
    internal lateinit var transportRow: LinearLayout
    internal lateinit var playerBar: LinearLayout
    internal lateinit var exportButton: ImageButton
    internal var presetSheetSave: TextView? = null
    internal var trackInfoUri: String? = null
    internal var trackTechnicalInfo = ""
    internal lateinit var disc: ImageView
    internal lateinit var modeTextView: TextView
    internal lateinit var currentTime: TextView
    internal lateinit var durationTime: TextView
    internal var seeking = false
    internal var isLive = false
    internal var stationIndex = -1
    internal var currentStationId = ""
    internal var radioSources = listOf<j5i.spatial8d.core.RadioSource>()
    internal var radioSourceIndex = -1
    internal var radioAllowHttp = false
    internal var radioGeneration = 0
    internal var radioProbes = listOf<j5i.spatial8d.audio.RadioPlayer>()
    internal var sourceRegion = "全部"
    internal var sourceCity = "全部"
    internal var sourceType = "全部"
    internal var stationListBody: LinearLayout? = null
    internal var onlineProvider = 0
    internal var curvePanel = 1
    internal var onlinePage = 1
    internal var onlineQuery = ""
    internal var onlineAutoSource: String? = null
    internal var providerSelection: String? = null
    internal var providerResults = listOf<Pair<String, j5i.spatial8d.provider.ProviderSearch.Track>>()
    internal var pluginResults = listOf<Pair<String, org.json.JSONObject>>()
    internal var providerRequest = 0
    internal var onlinePlayingUri: String? = null
    internal var onlinePlayingId: String? = null
    internal var onlineSelectedId: String? = null
    internal var onlinePlayingProvider = 0
    internal var onlinePlayingQuery = ""
    internal var onlinePlayingPage = 1
    internal var sourceSheetBody: LinearLayout? = null
    internal var onlineBody: LinearLayout? = null
    internal var onlineStatusView: TextView? = null
    internal var onlineSearchSeq = 0
    internal var sourceSearchSeq = 0
    internal var pendingDeleteResult: ((Boolean) -> Unit)? = null
    internal var recordingKind = ""
    internal var recordingPath = ""
    internal var recordingStartedAt = 0L
    internal var fmDialog: Dialog? = null
    internal var onlineDialog: Dialog? = null
    internal var providerLoginDialog: Dialog? = null
    internal var fmUnlocked = false
    internal var onlineUnlocked = false
    internal var sourceTapCount = 0
    internal var sourceTapAt = 0L
    internal var exitDialog: Dialog? = null
    internal var lyricsDialog: Dialog? = null
    internal lateinit var fmButton: Button
    internal lateinit var onlineButton: Button
    internal var sourceStatusView: TextView? = null
    internal var playlistDialog: PlaylistDialog? = null
    internal var seekGuardUntil = 0L
    internal var micRecorder: MicWavRecorder? = null
    internal val offlineAsrLazy = lazy {
        OfflineAsr(this,
            onText = { text -> main.post { asrTranscript = text; refreshPlayback() } },
            onError = { error -> main.post { offlineAsrEnabled = false; playbackStatus = "离线识别失败：$error"; refreshPlayback() } },
        )
    }
    internal val offlineAsr get() = offlineAsrLazy.value
    internal var asrTranscript = ""
    internal var offlineAsrEnabled = false

    internal fun toggleOfflineAsr() {
        if (!BuildConfig.WITH_ASR) { toast("当前安装包不含离线识别功能"); return }
        offlineAsrEnabled = !offlineAsrEnabled
        asrTranscript = ""
        if (offlineAsrEnabled) offlineAsr.start() else offlineAsr.stop()
        refreshPlayback()
        sourceStatusView?.text = if (offlineAsrEnabled) "离线识别已开启" else "离线识别已关闭"
    }
    internal lateinit var liveTimeline: LinearLayout
    internal lateinit var recordingLabel: TextView
    internal lateinit var transformCardRoot: LinearLayout
    internal var lastStateSavedAt = 0L

    // 扩展模块通过属性引用查询初始化状态，不直接访问 lateinit backing field。
    internal fun isViewReady(view: KProperty0<*>): Boolean = runCatching { view.get(); true }.getOrDefault(false)

    internal val ticker = object : Runnable {
        override fun run() {
            if (!pageActive) return
            refreshPlayback()
            val playbackPosition = (if (isLive) service?.radio?.position else service?.player?.position) ?: 0.0
            visualization.position = playbackPosition
            curve.position = playbackPosition
            if (SystemClock.uptimeMillis() - lastStateSavedAt >= 5000) savePlaybackState()
            main.postDelayed(this, 200)
        }
    }

    internal val connection = object : ServiceConnection {
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
            service?.onReverseUnavailable = { main.post {
                reverse = false
                if (::reverseSwitch.isInitialized) reverseSwitch.isChecked = false
                toast("超长歌曲暂不支持倒放")
            } }
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

    internal val openAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) addAndPlay(uri)
    }
    internal val openVisualImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                .onSuccess { visualizerView.setCreativeBackground(uri.toString()) }
                .onFailure { toast("无法保存图片访问权限，请重新选择") }
        }
    }
    internal val saveWav = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        if (uri != null) exportWav(uri)
    }
    internal val saveMp3 = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/mpeg")) { uri ->
        if (uri != null) exportMp3(uri)
    }
    internal var exportVideoLandscape = false
    internal var exportVideoResolution = 720
    internal var exportVideoStart = 0.0
    internal var exportVideoDuration = 0.0
    internal var exportVideoSmoothGifLoop = false
    internal val saveMp4 = registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        if (uri != null) exportMp4(uri, exportVideoLandscape, exportVideoResolution, exportVideoStart, exportVideoDuration)
    }
    internal val saveGif = registerForActivityResult(ActivityResultContracts.CreateDocument("image/gif")) { uri ->
        if (uri != null) exportMp4(uri, exportVideoLandscape, exportVideoResolution, exportVideoStart, exportVideoDuration,
            gif = true, smoothGifLoop = exportVideoSmoothGifLoop)
    }
    internal val requestAudio = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) scanMusic() else toast("未授予音乐库权限，仍可通过“添加音频”选择文件")
    }
    internal val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startMicRecording() else toast("没有麦克风录音权限，请到系统设置开启")
    }
    internal val providerStore by lazy { j5i.spatial8d.provider.ProviderStore(this) }
    internal val pluginStore by lazy { j5i.spatial8d.provider.ProviderPluginStore(this) }
    internal var loginPluginId = ""
    internal val openLoginFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importPluginLogin(loginPluginId, uri)
    }
    internal val openProviderFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importProviderFile(uri)
    }
    internal val scanProvider = registerForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { result ->
        result.contents?.let { importProviderQr(it) }
    }
    internal val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        showPendingFeatureGuide()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        displayedLanguage = AppLanguage.resolved(this)
        j5i.spatial8d.provider.ProviderMigration.run(this)
        musicStore = MusicStore(this)
        musicStore.readOnlineQueue()?.let { snapshot ->
            onlineQueue = snapshot.items
            onlineQueueProvider = snapshot.providerId
        }
        presetStore = PresetStore(this)
        customPresets = presetStore.load().toMutableList()
        // 首屏只读缓存；MediaStore 查询统一走后台，避免冷启动阻塞所有点击。
        songs = musicStore.cached().toMutableList()
        queue = musicStore.view(songs)
        // 主题三态：0 深色 / 1 浅色 / 2 跟随系统。首次启动沿用旧 dark 偏好迁移。
        themeMode = if (preferences.contains("themeMode")) preferences.getInt("themeMode", 0)
        else if (preferences.getBoolean("dark", true)) 0 else 1
        dark = resolveDark()
        ThemeUi.colorTheme = preferences.getInt("colorTheme", 0).coerceIn(0, 6)
        playbackStatus = t("ready")
        restorePlaybackState()
        transform = transform.copy(clarity = preferences.getInt("soundClarity", 50).coerceIn(0, 100))
        palette = ThemeUi.palette(dark)
        buildUi()
        visualization.pcmClock = visualizerView.pcmClock
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (exitDialog?.isShowing == true) { exitDialog?.dismiss(); return }
                var dismissed = false
                listOf(fmDialog, onlineDialog, infoDialog, exportDialog, visualizerDialog, lyricsFullscreenDialog, lyricsDialog).forEach { dialog ->
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
                view.setPadding(0, bars.top, 0, playerBar.height.coerceAtLeast(ThemeUi.dp(this, 176f)))
                playerBar.setPadding(ThemeUi.dp(this, 12f), ThemeUi.dp(this, 6f), ThemeUi.dp(this, 12f), ThemeUi.dp(this, 6f) + bars.bottom)
                insets
            }
        }
        startService(Intent(this, PlaybackService::class.java))
        bindService(Intent(this, PlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (hasAudioPermission()) scanMusic()
        scheduleLyricsPrefetch()
        main.post(ticker)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !preferences.getBoolean("guideConfirmed.notification", false)) {
            showUsageGuide("notification") { requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
        } else {
            showPendingFeatureGuide()
        }
    }

    override fun onDestroy() {
        lyricsFullscreenDialog?.dismiss()
        providerLoginDialog?.dismiss()
        providerLoginDialog = null
        main.removeCallbacks(prefetchLyrics)
        service?.player?.stopRecording()
        service?.radio?.stopRecording()
        micRecorder?.stop()
        micRecorder?.setListener(null)
        if (offlineAsrLazy.isInitialized()) offlineAsrLazy.value.release()
        main.removeCallbacks(ticker)
        service?.controls = null
        service?.onTrackChanged = null
        service?.onPlaybackError = null
        service?.onPlaybackChanged = null
        service?.onReverseUnavailable = null
        service?.player?.onPcm = null
        service?.radio?.onPcm = null
        radioProbes.forEach { it.stop() }
        radioProbes = emptyList()
        if (bound) unbindService(connection)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 8144) {
            pendingDeleteResult?.invoke(resultCode == RESULT_OK)
            pendingDeleteResult = null
            return
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (AppLanguage.current(this) == AppLanguage.SYSTEM && AppLanguage.resolved(this) != displayedLanguage) {
            recreate()
            return
        }
        val carMode = newConfig.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK ==
            android.content.res.Configuration.UI_MODE_TYPE_CAR
        if (carMode && !lastCarMode && !drivingAutoDismissed) showDrivingMode()
        lastCarMode = carMode
        if (themeMode == 2) {
            val newDark = resolveDark()
            if (newDark != dark) { dark = newDark; refreshTheme() }
        }
    }

    override fun isLocalMusicPlaying(): Boolean = !isLive && service?.player?.isPlaying == true

    override fun isMicRecording(): Boolean = recordingKind == "mic"

    override fun toggleMicRecording() {
        if (recordingKind == "mic") {
            micRecorder ?: return
            val statusView = android.widget.TextView(this)
            finishMicRecording(statusView)
            playlistDialog?.refreshContent()
            return
        }
        if (recordingKind.isNotEmpty()) { toast("请先停止当前录音"); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startMicRecording()
        } else {
            withUsageGuide("microphone") { requestMic.launch(Manifest.permission.RECORD_AUDIO) }
        }
    }

    override fun pruneOnlineReferences() {
        val leftovers = songs.filter { it.uri.startsWith("online://") }
        if (leftovers.isEmpty()) { toast("本地歌曲中没有在线记录"); return }
        android.app.AlertDialog.Builder(this).setTitle("清理在线记录")
            .setMessage("本地歌曲中残留 ${leftovers.size} 首在线歌曲记录，批量移出本地库？不会删除任何音频文件，歌单中的引用按歌单规则保留。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清理") { _, _ ->
                leftovers.forEach { musicStore.removePermanently(it) }
                songs = musicStore.cached().toMutableList()
                queue = musicStore.view(songs)
                currentQueue = queue
                service?.syncQueue(queue, currentUri)
                playlistDialog?.refreshContent()
                toast("已清理 ${leftovers.size} 首在线记录")
            }.show()
    }

    override fun scanMusic() {
        if (loading) return
        if (!hasAudioPermission()) {
            // 仅在用户主动扫描时申请，冷启动静默扫描不能突然弹权限框。
            withUsageGuide("audio") { requestAudio.launch(audioPermission()) }
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

    internal val prefetchLyrics = Runnable { lyrics.prefetch(songs.toList()) }

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

    internal fun togglePlayback() {
        if (isLive) {
            val radio = service?.radio ?: return
            if (radio.isPlaying) { radio.pause(); playbackStatus = "电台已暂停" }
            else if (radioSources.isNotEmpty()) {
                // FM 暂停后点播放即重新连接当前线路，兼作原来的刷新按钮。
                tryRadioSource(radioSourceIndex.coerceAtLeast(0))
                playbackStatus = "正在重新连接电台…"
            } else playbackStatus = "电台线路不可用"
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

    override fun onLibraryChanged(items: List<MusicItem>) {
        songs = items.toMutableList()
        queue = musicStore.view(songs)
        currentQueue = queue
        service?.syncQueue(queue, currentUri)
        refreshPlayback()
    }

    override fun deleteLibrarySource(item: MusicItem, onResult: (Boolean) -> Unit) {
        val uri = Uri.parse(item.uri)
        // 原文件已被外部删除时，直接允许清理失效的曲库记录。
        if (isSourceMissing(uri)) {
            onResult(true)
            return
        }
        if (uri.scheme == "file" || item.uri.startsWith("file://")) {
            onResult(runCatching { java.io.File(item.uri.removePrefix("file://")).delete() }.getOrDefault(false))
            return
        }
        if (uri.scheme != "content") {
            onResult(false)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val request = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
                pendingDeleteResult = onResult
                startIntentSenderForResult(request.intentSender, 8144, null, 0, 0, 0)
                return
            } catch (_: Exception) {
                pendingDeleteResult = null
            }
        }
        onResult(runCatching { contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false))
    }

    override fun addAudio() = openAudio.launch(arrayOf("audio/*"))

    override fun editMetadata(item: MusicItem) {
        currentItem = item
        showMetadataEditor()
    }

}
