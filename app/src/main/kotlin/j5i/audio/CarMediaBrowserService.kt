package j5i.spatial8d.audio

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.media.MediaBrowserService
import android.media.browse.MediaBrowser
import j5i.spatial8d.data.MusicItem
import j5i.spatial8d.data.MusicStore

class CarMediaBrowserService : MediaBrowserService() {
    private lateinit var session: MediaSession
    private var playback: PlaybackService? = null
    private val store by lazy { MusicStore(this) }
    private val main = Handler(Looper.getMainLooper())
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            playback = (binder as? PlaybackService.Binder)?.service
            playback?.appInForeground = false
            updateSession()
            main.removeCallbacks(ticker)
            main.post(ticker)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            playback = null
        }
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (playback == null) return
            updateSession()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        session = MediaSession(this, "8DCarMedia").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { playback?.play() }
                override fun onPause() { playback?.pause() }
                override fun onSkipToPrevious() { playback?.previous() }
                override fun onSkipToNext() { playback?.next() }
                override fun onSeekTo(position: Long) {
                    playback?.player?.seek(position / 1000.0, userInitiated = true)
                }
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    val item = mediaId?.let(::findItem) ?: return
                    val service = playback ?: return
                    service.load(item, service.queueSnapshot.ifEmpty { store.cached() })
                }
                override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                    val normalized = query?.trim()?.lowercase().orEmpty()
                    if (normalized.isEmpty()) return
                    val item = store.cached().firstOrNull {
                        it.name.lowercase().contains(normalized) || it.artist.lowercase().contains(normalized)
                    } ?: return
                    val service = playback ?: return
                    service.load(item, service.queueSnapshot.ifEmpty { store.cached() })
                }
            })
            isActive = true
        }
        sessionToken = session.sessionToken
        bindService(Intent(this, PlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)
        main.post(ticker)
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        if (!isTrustedBrowser(clientPackageName, clientUid)) return null
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(parentId: String, result: Result<List<MediaBrowser.MediaItem>>) {
        result.sendResult(when (parentId) {
            ROOT_ID -> listOf(MediaBrowser.MediaItem(
                MediaDescription.Builder().setMediaId(LIBRARY_ID).setTitle("本地音乐").build(),
                MediaBrowser.MediaItem.FLAG_BROWSABLE))
            LIBRARY_ID -> mediaItems()
            else -> emptyList()
        })
    }

    override fun onDestroy() {
        main.removeCallbacks(ticker)
        runCatching { unbindService(connection) }
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    private fun mediaItems(): List<MediaBrowser.MediaItem> =
        (playback?.queueSnapshot?.takeIf { it.isNotEmpty() } ?: store.cached()).map { item ->
            val title = item.name.substringBeforeLast('.')
            MediaBrowser.MediaItem(
                MediaDescription.Builder()
                    .setMediaId(item.uri)
                    .setTitle(title)
                    .setSubtitle(item.artist)
                    .setMediaUri(Uri.parse(item.uri))
                    .build(),
                MediaBrowser.MediaItem.FLAG_PLAYABLE)
        }

    private fun findItem(mediaId: String): MusicItem? =
        (playback?.queueSnapshot.orEmpty() + store.cached()).firstOrNull { it.uri == mediaId }

    private fun updateSession() {
        val service = playback ?: return
        val item = service.currentTrack
        val playing = service.player.isPlaying || service.radio.isPlaying
        val durationMs = (service.player.duration * 1000.0).toLong().coerceAtLeast(0L)
        val positionMs = (service.player.position * 1000.0).toLong().coerceAtLeast(0L)
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, item?.name?.substringBeforeLast('.') ?: "8D 空间音频")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, item?.artist.orEmpty())
            .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
            .build())
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SEEK_TO)
            .setState(
                if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                if (durationMs > 0) positionMs else PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                if (playing) 1f else 0f)
            .build())
    }

    private fun isTrustedBrowser(packageName: String, uid: Int): Boolean {
        val packages = packageManager.getPackagesForUid(uid) ?: return false
        if (packageName !in packages) return false
        val info = runCatching { packageManager.getApplicationInfo(packageName, 0) }.getOrNull() ?: return false
        val systemApp = info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        val mediaControl = packageManager.checkPermission(Manifest.permission.MEDIA_CONTENT_CONTROL, packageName) ==
            PackageManager.PERMISSION_GRANTED
        return systemApp || mediaControl
    }

    companion object {
        private const val ROOT_ID = "8dmusic-root"
        private const val LIBRARY_ID = "8dmusic-library"
    }
}
