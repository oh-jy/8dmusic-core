package j5i.spatial8d.audio

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import j5i.spatial8d.data.MusicItem
import j5i.spatial8d.data.isOnlineAudio
import j5i.spatial8d.provider.ProviderCredentials
import j5i.spatial8d.data.MusicStore
import j5i.spatial8d.data.PlayMode
import com.j5i.spatial8d.HttpMediaDataSource
import com.j5i.spatial8d.TsAdtsMediaDataSource
import java.util.concurrent.Executors
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import j5i.spatial8d.ui.AppLanguage

/** 音频链路的唯一持有者，同时把真实播放状态同步给锁屏与通知栏。 */
class PlaybackService : Service() {
    private fun t(key: String) = AppLanguage.text(this, key)
    inner class Binder : android.os.Binder() {
        val service: PlaybackService get() = this@PlaybackService
    }

    interface Controls {
        fun play()
        fun pause()
        fun previous()
        fun next()
    }

    val player = SpatialPlayer(this)
    val radio = RadioPlayer()
    var controls: Controls? = null
    var onTrackChanged: ((MusicItem) -> Unit)? = null
    var onPlaybackError: ((String) -> Unit)? = null
    var onPlaybackChanged: (() -> Unit)? = null
    var onReverseUnavailable: (() -> Unit)? = null
    val currentTrack: MusicItem? get() = currentItem
    val queueSnapshot: List<MusicItem> get() = queue
    private val binder = Binder()
    private val store by lazy { MusicStore(this) }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val decodeExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var queue = listOf<MusicItem>()
    @Volatile private var currentItem: MusicItem? = null
    @Volatile private var loadGeneration = 0
    private var foreground = false
    private var lastAppInForeground = true
    /** Activity 是否在前台。前台播放时不发通知，后台播放才显示以保持服务存活。 */
    @Volatile var appInForeground = true
    private var transitioning = false
    private var autoplayFailures = 0
    private var pausedByHeadset = false
    private var pausedByFocus = false
    private lateinit var session: MediaSession
    private var title = "8D 空间音频"
    private var subtitle = ""
    private var playing = false
    private var positionMs = 0L
    private var durationMs = 0L
    private var mediaInitialized = false
    private var lastPlaybackStateUpdateMs = 0L
    private var lastSnapshotAtMs = 0L
    private var desiredPlaying = false
    private val mediaTicker = object : Runnable {
        override fun run() {
            val item = currentItem
            val active = player.isPlaying || radio.isPlaying
            if (item != null && active) updateMedia(item.name, item.artist, true, player.position, player.duration)
            if (active) main.postDelayed(this, 1000)
        }
    }
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            main.post {
                player.pause()
                radio.pause()
                pausedByFocus = !transitioning
                updateMedia(title, subtitle, false, player.position, player.duration)
                onPlaybackChanged?.invoke()
            }
        }
    }
    private val focusRequest by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) createFocusRequest()
        else error("AudioFocusRequest requires Android 8 or later")
    }
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createFocusRequest(): AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener(focusListener, main).build()
    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val unplugged = intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY ||
                (intent?.action == Intent.ACTION_HEADSET_PLUG && intent.getIntExtra("state", 0) == 0)
            val plugged = intent?.action == Intent.ACTION_HEADSET_PLUG && intent.getIntExtra("state", 0) == 1
            if (unplugged && (player.isPlaying || radio.isPlaying)) {
                pausedByHeadset = true
                pause()
            } else if (plugged && pausedByHeadset) {
                pausedByHeadset = false
                play()
            }
        }
    }

    override fun onCreate() {
        j5i.spatial8d.provider.ProviderMigration.run(this)
        super.onCreate()
        // 上次进程异常退出可能留下整轨 PCM；本进程尚未创建新缓冲。
        File(cacheDir, "decoded-pcm").listFiles()?.forEach { it.delete() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "播放控制", NotificationManager.IMPORTANCE_LOW)
            )
        }
        session = MediaSession(this, "Spatial8D").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { play() }
                override fun onPause() { pause() }
                override fun onSkipToPrevious() { previous() }
                override fun onSkipToNext() { next() }
                override fun onSeekTo(position: Long) { player.seek(position / 1000.0, userInitiated = true) }
            })
            isActive = true
        }
        player.onEnd = { if (!pausedByFocus) continueQueue() }
        player.onError = { message -> main.post { onPlaybackError?.invoke(message) } }
        player.onReverseUnavailable = {
            controlsReverse = false
            onReverseUnavailable?.invoke()
        }
        player.onSeekOutsideWindow = { position ->
            main.post {
                val item = currentItem ?: return@post
                load(item, queue, autoplay = desiredPlaying || player.isPlaying, resumePosition = position)
            }
        }
        ContextCompat.registerReceiver(this, headsetReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** 解码、队列和播放都在服务；页面销毁不影响自然续播。 */
    fun load(item: MusicItem, queue: List<MusicItem> = this.queue, autoplay: Boolean = true,
        resumePosition: Double = 0.0) {
        if (j5i.spatial8d.provider.ProviderTrackRef.isOnline(item)) {
            decodeExecutor.execute {
                val resolved = runCatching { j5i.spatial8d.provider.ProviderTrackRef.resolve(this, item) }
                main.post {
                    resolved.onSuccess { load(it, queue, autoplay, resumePosition) }
                        .onFailure { onPlaybackError?.invoke("在线歌曲解析失败：${it.message}") }
                }
            }
            return
        }
        if (item.album.startsWith("plugin:https:")) {
            val id = item.album.substringAfter("plugin:https:")
            val active = runCatching { j5i.spatial8d.provider.ProviderPluginStore(this).list().any { it.enabled && it.descriptor.id == id } }.getOrDefault(false)
            if (!active) { onPlaybackError?.invoke("插件来源不存在或已停用"); return }
        }
        if (item.album.startsWith("provider:")) {
            val id = item.album.substringAfter(':').substringAfter(':', "")
            val active = runCatching { j5i.spatial8d.provider.ProviderStore(this).list().any { it.enabled && it.definition.id == id } }.getOrDefault(false)
            if (!active) { onPlaybackError?.invoke("来源不存在或已停用"); return }
        }
        val own = ++loadGeneration
        desiredPlaying = autoplay
        pausedByFocus = false
        if (!autoplay || currentItem?.uri != item.uri) autoplayFailures = 0
        val keepForeground = transitioning
        transitioning = false
        val previous = currentItem
        if (autoplay && previous != null && previous.isOnlineAudio() != item.isOnlineAudio()) {
            controlsPreset = if (item.isOnlineAudio()) j5i.spatial8d.core.SPACE_PRESETS[0] else j5i.spatial8d.core.DEFAULT_SPACE_PRESET
        }
        this.queue = queue.ifEmpty { listOf(item) }
        currentItem = item
        player.stop()
        player.seekRestartSupported = !item.uri.startsWith("http://", ignoreCase = true) &&
            !item.uri.startsWith("https://", ignoreCase = true)
        updateMedia(item.name, item.artist, keepForeground, duration = item.duration / 1000.0)
        decodeExecutor.execute {
            var stream: android.media.MediaDataSource? = null
            try {
            val remote = item.uri.startsWith("https://") || item.uri.startsWith("http://")
            stream = if (remote) {
                val safe = item.album.startsWith("provider:http:") || item.album.startsWith("provider:https:") || item.album.startsWith("plugin:https:")
                val plugin = if (item.album.startsWith("plugin:https:"))
                    j5i.spatial8d.provider.ProviderPluginStore(this).list().first { it.enabled && it.descriptor.id == item.album.substringAfter("plugin:https:") } else null
                val allowHttp = item.album.startsWith("provider:http:") || plugin != null
                if (item.uri.substringBefore('?').endsWith(".m3u8", ignoreCase = true))
                    TsAdtsMediaDataSource(item.uri, safe, allowHttp) else if (item.album.startsWith("plugin:https:")) {
                    val id = item.album.substringAfter("plugin:https:")
                    val entry = requireNotNull(plugin)
                    val referer = if ("referer" in entry.descriptor.capabilities) "https://www.bilibili.com/" else ""
                    val cookie = ProviderCredentials(this).get(id)
                    HttpMediaDataSource(item.uri, true, allowHttp, referer, cookie)
                } else HttpMediaDataSource(item.uri, safe, allowHttp)
            } else null
                val decoder = AudioDecoder(this)
                val source = if (stream != null) null else store.readableUri(item)
                val onHead: (j5i.spatial8d.core.PcmBuffer, Long) -> Unit = { head, _ ->
                    main.post {
                        if (own != loadGeneration) return@post
                        player.prepare(head, item.duration / 1000.0)
                        player.preset = controlsPreset ?: player.preset
                        controlsTransform?.let { player.transform = it }
                        controlsReverse?.let { player.reverse = it }
                        if (resumePosition > 0.0) player.seek(resumePosition)
                        if (autoplay && requestPlaybackFocus()) player.play()
                        transitioning = false
                        savePlaybackSnapshot(force = true)
                        onTrackChanged?.invoke(item)
                        updateMedia(item.name, item.artist, player.isPlaying, duration = item.duration / 1000.0)
                    }
                }
                if (stream != null) decoder.decode(stream, {}, { own == loadGeneration }, onHead = onHead)
                else decoder.decode(source!!, {}, { own == loadGeneration }, onHead = onHead,
                    startPositionSec = resumePosition)
            } catch (e: Exception) {
                main.post {
                    if (own == loadGeneration) {
                        Log.w("8DQueue", "load failed: ${item.name}", e)
                        onPlaybackError?.invoke(e.message ?: "加载失败")
                        if (autoplay && queue.isNotEmpty() && ++autoplayFailures <= 3) continueQueue()
                        else transitioning = false
                    }
                }
            } finally { stream?.close() }
        }
    }

    fun play(): Boolean {
        pausedByHeadset = false
        pausedByFocus = false
        if (!requestPlaybackFocus()) return false
        val resumed = if (currentItem == null) radio.play() else player.play()
        if (currentItem != null) desiredPlaying = resumed
        updateMedia(title, subtitle, resumed, player.position, player.duration)
        return resumed
    }

    fun pause() {
        transitioning = false
        desiredPlaying = false
        pausedByHeadset = false
        player.pause()
        radio.pause()
        updateMedia(title, subtitle, false, player.position, player.duration)
    }

    fun next() = continueQueue(auto = false)

    fun previous() {
        val item = currentItem ?: return
        PlaybackQueue(queue, playbackMode()).previous(item.uri, item.id)?.let { load(it) }
    }

    /** 切到电台也使尚未完成的本地解码失效，避免后台迟到回调重新起播。 */
    fun stopLocal() {
        loadGeneration++
        transitioning = false
        player.stop()
        currentItem = null
        desiredPlaying = false
    }

    @Suppress("DEPRECATION")
    fun requestPlaybackFocus(): Boolean {
        val manager = getSystemService(AudioManager::class.java)
        val result = if (Build.VERSION.SDK_INT >= 26) manager.requestAudioFocus(focusRequest)
        else manager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun syncQueue(items: List<MusicItem>, currentUri: String?) {
        queue = items
        store.cached().firstOrNull { it.uri == currentUri }?.let { currentItem = it }
    }

    // 播放参数仍由页面统一编辑，服务在续播时沿用最后一套参数。
    var controlsPreset: j5i.spatial8d.core.SpacePreset? = null
    var controlsTransform: j5i.spatial8d.core.TransformParams? = null
    var controlsReverse: Boolean? = null

    private fun continueQueue(auto: Boolean = true) {
        val item = currentItem ?: return
        val mode = playbackMode()
        if (auto && mode == PlayMode.LOOP_ONE) {
            ensureContinuousForeground(item)
            player.replay(forcePlay = true)
            return
        }
        val next = PlaybackQueue(queue, mode).next(item.uri, auto, item.id)
        if (next == null) {
            if (auto && queue.isNotEmpty()) {
                ensureContinuousForeground(item)
                player.replay(forcePlay = true)
            }
            return
        }
        Log.i("8DQueue", "service auto next: ${next.name}")
        ensureContinuousForeground(item)
        load(next)
    }

    private fun ensureContinuousForeground(item: MusicItem) {
        if (!autoContinuationAllowed()) return
        // 曲尾到下一首 PCM 到达前也保持媒体前台，厂商不能把这个间隙当成可冻结后台。
        transitioning = true
        updateMedia(item.name, item.artist, true, player.position, player.duration)
    }

    private fun autoContinuationAllowed() = desiredPlaying || player.isPlaying || transitioning

    private fun playbackMode() = try {
        PlayMode.valueOf(getSharedPreferences("spatial8d", MODE_PRIVATE)
            .getString("mode", PlayMode.LOOP_LIST.name) ?: PlayMode.LOOP_LIST.name)
    } catch (_: Exception) { PlayMode.LOOP_LIST }

    /** 每次页面刷新都复用此方法，确保电台与本地歌曲使用同一套系统控制。 */
    fun updateMedia(title: String, subtitle: String, playing: Boolean, position: Double = 0.0, duration: Double = 0.0) {
        val newTitle = title.ifBlank { "8D 空间音频" }
        val newPositionMs = (position * 1000).toLong().coerceAtLeast(0)
        val newDurationMs = (duration * 1000).toLong().coerceAtLeast(0)
        val mediaChanged = !mediaInitialized || newTitle != this.title || subtitle != this.subtitle ||
            playing != this.playing || newDurationMs != durationMs || appInForeground != lastAppInForeground
        lastAppInForeground = appInForeground
        val now = SystemClock.elapsedRealtime()
        val positionChanged = newDurationMs > 0 && now - lastPlaybackStateUpdateMs >= 1000 &&
            abs(newPositionMs - positionMs) >= 500
        if (!mediaChanged && !positionChanged) return

        this.title = newTitle
        this.subtitle = subtitle
        this.playing = playing
        positionMs = newPositionMs
        durationMs = newDurationMs
        mediaInitialized = true
        if (mediaChanged || playing && now - lastSnapshotAtMs >= 5000) savePlaybackSnapshot(force = true)
        if (mediaChanged) {
            session.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, this.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs).build())
        }
        val state = if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        session.setPlaybackState(PlaybackState.Builder().setActions(
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SEEK_TO
        ).setState(state, if (durationMs > 0) positionMs else PlaybackState.PLAYBACK_POSITION_UNKNOWN,
            if (playing) 1f else 0f).build())
        lastPlaybackStateUpdateMs = now
        if (mediaChanged) {
            val notification = buildNotification()
            val notificationManager = getSystemService(NotificationManager::class.java)
            if (playing) {
                if (!appInForeground) {
                    // 只在后台播放时进入前台服务并展示通知，避免前台时打扰。
                    if (Build.VERSION.SDK_INT >= 29) {
                        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                    } else startForeground(NOTIFICATION_ID, notification)
                    foreground = true
                }
            } else {
                if (foreground) stopForeground(true)
                foreground = false
                // 前台暂停也不弹通知；后台暂停保留现有通知以便用户回到播放。
                if (!appInForeground) notificationManager.notify(NOTIFICATION_ID, notification)
            }
        }
        if (playing) {
            main.removeCallbacks(mediaTicker)
            main.postDelayed(mediaTicker, 1000)
        } else main.removeCallbacks(mediaTicker)
    }

    private fun buildNotification(): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val content = PendingIntent.getActivity(this, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val style = Notification.MediaStyle().setMediaSession(session.sessionToken)
        style.setShowActionsInCompactView(0, 1, 2)
        // Notification channels are only available on Android 8 and later.
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title).setContentText(subtitle).setContentIntent(content)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setOnlyAlertOnce(true).setOngoing(playing)
            .setShowWhen(false)
            .addAction(android.R.drawable.ic_media_previous, t("previous"), action("previous"))
            .addAction(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) t("pause") else t("play"), action(if (playing) "pause" else "play"))
            .addAction(android.R.drawable.ic_media_next, t("next"), action("next"))
            .setStyle(style).build()
    }

    private fun action(command: String): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).setAction(command)
        return if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(this, command.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        else PendingIntent.getService(this, command.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (currentItem == null && (intent == null || intent.action == "play")) restorePlayback()
        dispatchControl(intent?.action)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 滑出任务不等于暂停；播放时保留媒体前台服务，系统回收后允许重建服务。
        if (!playing && !player.isPlaying && !radio.isPlaying) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun dispatchControl(action: String?) {
        when (action) {
            "play" -> controls?.play() ?: play()
            "pause" -> controls?.pause() ?: pause()
            "previous" -> controls?.previous() ?: previous()
            "next" -> controls?.next() ?: next()
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    /** START_STICKY 重建的不应只是空服务；系统或通知恢复播放时继续原有队列。 */
    private fun restorePlayback() {
        if (currentItem != null || player.isPrepared) return
        val preferences = getSharedPreferences("spatial8d", MODE_PRIVATE)
        val state = try { JSONObject(preferences.getString("servicePlayback", null) ?: return) }
        catch (_: Exception) { return }
        if (!state.optBoolean("playing")) return
        val items = MusicStore.jsonSongs(state.optJSONArray("queue")?.toString())
        val uri = state.optString("uri")
        val item = items.firstOrNull { it.uri == uri } ?: store.cached().firstOrNull { it.uri == uri } ?: return
        load(item, items.ifEmpty { store.cached().ifEmpty { listOf(item) } },
            resumePosition = state.optDouble("position"))
    }

    private fun savePlaybackSnapshot(force: Boolean = false) {
        val item = currentItem ?: return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastSnapshotAtMs < 5000) return
        lastSnapshotAtMs = now
        getSharedPreferences("spatial8d", MODE_PRIVATE).edit()
            .putString("servicePlayback", JSONObject().apply {
                put("uri", item.uri)
                put("position", player.position)
                put("playing", desiredPlaying && (player.isPlaying || transitioning))
                put("queue", JSONArray().apply { queue.forEach { put(MusicStore.songJson(it)) } })
            }.toString()).apply()
    }

    override fun onDestroy() {
        stopForeground(true)
        main.removeCallbacks(mediaTicker)
        try { unregisterReceiver(headsetReceiver) } catch (_: Exception) {}
        session.isActive = false
        session.release()
        decodeExecutor.shutdownNow()
        player.close()
        radio.stop()
        radio.stopRecording()
        val manager = getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.abandonAudioFocusRequest(focusRequest)
        else @Suppress("DEPRECATION") manager.abandonAudioFocus(focusListener)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
    }
}
