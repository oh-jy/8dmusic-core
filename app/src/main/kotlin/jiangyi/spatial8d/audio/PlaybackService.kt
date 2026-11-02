package jiangyi.spatial8d.audio

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import kotlin.math.abs

/** 音频链路的唯一持有者，同时把真实播放状态同步给锁屏与通知栏。 */
class PlaybackService : Service() {
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
    private val binder = Binder()
    private lateinit var session: MediaSession
    private var title = "8D 空间音频"
    private var subtitle = ""
    private var playing = false
    private var positionMs = 0L
    private var durationMs = 0L
    private var mediaInitialized = false
    private var lastPlaybackStateUpdateMs = 0L
    private val playbackWakeLock by lazy {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "spatial8d:PlaybackService")
            .apply { setReferenceCounted(false) }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "播放控制", NotificationManager.IMPORTANCE_LOW)
        )
        session = MediaSession(this, "Spatial8D").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = controls?.play() ?: Unit
                override fun onPause() = controls?.pause() ?: Unit
                override fun onSkipToPrevious() = controls?.previous() ?: Unit
                override fun onSkipToNext() = controls?.next() ?: Unit
                override fun onSeekTo(position: Long) { player.seek(position / 1000.0, userInitiated = true) }
            })
            isActive = true
        }
    }

    /** 每次页面刷新都复用此方法，确保电台与本地歌曲使用同一套系统控制。 */
    fun updateMedia(title: String, subtitle: String, playing: Boolean, position: Double = 0.0, duration: Double = 0.0) {
        val newTitle = title.ifBlank { "8D 空间音频" }
        val newPositionMs = (position * 1000).toLong().coerceAtLeast(0)
        val newDurationMs = (duration * 1000).toLong().coerceAtLeast(0)
        val mediaChanged = !mediaInitialized || newTitle != this.title || subtitle != this.subtitle ||
            playing != this.playing || newDurationMs != durationMs
        val now = SystemClock.elapsedRealtime()
        val positionChanged = newDurationMs > 0 && now - lastPlaybackStateUpdateMs >= 1000 &&
            abs(newPositionMs - positionMs) >= 500
        if (!mediaChanged && !positionChanged) return

        val wasPlaying = this.playing
        this.title = newTitle
        this.subtitle = subtitle
        this.playing = playing
        syncPlaybackWakeLock()
        positionMs = newPositionMs
        durationMs = newDurationMs
        mediaInitialized = true
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
            if (playing && !wasPlaying) {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } else startForeground(NOTIFICATION_ID, notification)
            } else {
                if (!playing && wasPlaying) stopForeground(false)
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun syncPlaybackWakeLock() {
        // 曲目结束到下一首解码完成之间也要保持 CPU 可运行；页面在后台时没有 ticker 兜底。
        if (playing && !playbackWakeLock.isHeld) playbackWakeLock.acquire()
        else if (!playing && playbackWakeLock.isHeld) playbackWakeLock.release()
    }

    private fun buildNotification(): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val content = PendingIntent.getActivity(this, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val style = Notification.MediaStyle().setMediaSession(session.sessionToken)
        style.setShowActionsInCompactView(0, 1, 2)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title).setContentText(subtitle).setContentIntent(content)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setOnlyAlertOnce(true).setOngoing(playing)
            .setShowWhen(false)
            .addAction(android.R.drawable.ic_media_previous, "上一首", action("previous"))
            .addAction(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "暂停" else "播放", action(if (playing) "pause" else "play"))
            .addAction(android.R.drawable.ic_media_next, "下一首", action("next"))
            .setStyle(style).build()
    }

    private fun action(command: String): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).setAction(command)
        return PendingIntent.getService(this, command.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知栏可在页面已销毁时唤醒服务，先建立绑定再投递播放动作。
        if (controls == null && intent?.action != null) {
            startActivity(packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                dispatchControl(intent.action)
            }, 500)
        } else dispatchControl(intent?.action)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 滑出任务不等于暂停；播放时保留媒体前台服务，系统回收后允许重建服务。
        if (!playing && !player.isPlaying && !radio.isPlaying) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun dispatchControl(action: String?) {
        when (action) {
            "play" -> controls?.play()
            "pause" -> controls?.pause()
            "previous" -> controls?.previous()
            "next" -> controls?.next()
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        stopForeground(true)
        if (playbackWakeLock.isHeld) playbackWakeLock.release()
        session.isActive = false
        session.release()
        player.close()
        radio.stop()
        radio.stopRecording()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
    }
}
