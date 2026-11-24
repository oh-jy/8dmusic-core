package j5i.spatial8d

import android.content.Intent
import android.content.Context
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import android.widget.PopupWindow
import j5i.spatial8d.ui.ThemeUi
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal fun videoTime(ms: Int): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return if (seconds >= 3600) "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%02d:%02d".format(seconds / 60, seconds % 60)
}
private fun speedLabel(speed: Float): String = if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"
internal fun videoCacheKey(url: String): String = MessageDigest.getInstance("SHA-256")
    .digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun videoResumePosition(positionMs: Int, durationMs: Int): Int =
    positionMs.takeIf { it > 0 && (durationMs <= 0 || it < durationMs) } ?: 0

private fun videoCacheFile(context: Context, url: String): File =
    File(File(context.filesDir, "video-cache"), "${videoCacheKey(url)}.mp4")
private fun isCacheableVideo(uri: android.net.Uri, mime: String): Boolean =
    (mime.equals("video/mp4", true) || uri.lastPathSegment?.substringBefore('?')?.endsWith(".mp4", true) == true) &&
        uri.scheme in listOf("http", "https")

private fun MainActivity.videoIcon(res: Int, description: String, action: () -> Unit) = ImageButton(this).apply {
    setImageResource(res); contentDescription = description; setColorFilter(palette.text); setBackgroundColor(Color.TRANSPARENT); setOnClickListener { action() }
}

internal fun MainActivity.openExternalMedia(intent: Intent) {
    val uri = intent.data ?: return
    val live = intent.getBooleanExtra("j5i.live", false)
    val liveStreams = intent.getStringArrayListExtra("j5i.liveStreams").orEmpty().filter {
        runCatching { java.net.URI(it).let { address -> address.scheme in listOf("http", "https") && address.host != null && address.rawUserInfo == null && address.fragment == null } }.getOrDefault(false)
    }.distinct()
    var liveStreamIndex = 0
    val resumeKey = if (live) "live" else intent.getStringExtra("j5i.providerMv") ?: uri.toString()
    if (uri.scheme !in listOf("content", "file", "http", "https")) return
    val mime = intent.type ?: runCatching { contentResolver.getType(uri) }.getOrNull().orEmpty()
    externalVideoDialog?.dismiss()
    if (!mime.startsWith("video/", true)) { runCatching { addAndPlay(uri) }.onFailure { toast(t("video.audio.open_failed")) }; return }
    if (recordingKind.isNotEmpty()) { toast(t("video.stop_recording")); return }
    if (uri.scheme == "content" && intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    service?.pause()
    val player = VideoView(this); externalVideo = player
    val overlay = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
    val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(8, 8, 8, 8); setBackgroundColor(Color.TRANSPARENT) }
    val topBar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    val rightControls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    val controlTextSize = 14f
    val seek = SeekBar(this).apply {
        ThemeUi.configureSeekBar(this, palette, playback = true)
        thumbTintList = android.content.res.ColorStateList.valueOf(palette.accent)
    }
    val time = TextView(this).apply {
        setTextColor(Color.WHITE); gravity = Gravity.START or Gravity.CENTER_VERTICAL; text = "00:00 / 00:00"
        textSize = controlTextSize
        includeFontPadding = false
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    val feedback = TextView(this).apply { setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER; visibility = View.GONE; setBackgroundColor(0xaa000000.toInt()); setPadding(24, 12, 24, 12) }
    val loading = TextView(this).apply {
        setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
        setBackgroundColor(0xaa000000.toInt()); setPadding(28, 16, 28, 16)
        text = t("video.preparing")
    }
    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    var muted = false
    var speed = 1.0f; var prepared: android.media.MediaPlayer? = null; var dragging = false; var hidden = false; var completed = false
    var qualityRequest = 0
    var qualityResume: Pair<Int, Boolean>? = null
    var currentVideoUri = uri
    var currentQuality = intent.getStringExtra("j5i.providerMvQuality")
    externalVideoPreviousOrientation = requestedOrientation
    lateinit var dialog: android.app.Dialog
    lateinit var speedButton: TextView
    lateinit var modeButton: TextView
    lateinit var showControls: () -> Unit
    var scaleMode = 1
    lateinit var applyScaleMode: () -> Unit
    val centerPlay = videoIcon(R.drawable.video_pause, t("pause")) {
        if (player.isPlaying) player.pause() else { completed = false; player.start() }
        showControls()
    }.apply {
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        val padding = ThemeUi.dp(this@openExternalMedia, 16f)
        setPadding(padding, padding, padding, padding)
        setColorFilter(Color.WHITE)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xaa000000.toInt()) }
    }
    val centerButtons = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        setPadding(8, 8, 8, 8)
        setBackgroundColor(Color.TRANSPARENT)
    }
    val controlGroups = listOf(controls, topBar, rightControls, centerButtons)
    centerButtons.addView(videoIcon(R.drawable.video_rewind, t("video.rewind")) {
        player.seekTo((player.currentPosition - 10000).coerceAtLeast(0)); showControls()
    }.apply {
        val padding = ThemeUi.dp(this@openExternalMedia, 12f)
        setPadding(padding, padding, padding, padding)
        setColorFilter(Color.WHITE)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xaa000000.toInt()) }
    }, LinearLayout.LayoutParams(ThemeUi.dp(this, 48f), ThemeUi.dp(this, 48f)))
    centerButtons.addView(centerPlay, LinearLayout.LayoutParams(ThemeUi.dp(this, 64f), ThemeUi.dp(this, 64f)).apply { leftMargin = ThemeUi.dp(this@openExternalMedia, 12f); rightMargin = leftMargin })
    centerButtons.addView(videoIcon(R.drawable.video_forward, t("video.forward")) {
        player.seekTo((player.currentPosition + 10000).coerceAtMost(player.duration)); showControls()
    }.apply {
        val padding = ThemeUi.dp(this@openExternalMedia, 12f)
        setPadding(padding, padding, padding, padding)
        setColorFilter(Color.WHITE)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xaa000000.toInt()) }
    }, LinearLayout.LayoutParams(ThemeUi.dp(this, 48f), ThemeUi.dp(this, 48f)))
    val hide = Runnable {
        if (player.isPlaying) {
            controlGroups.forEach { view ->
                view.animate().alpha(0f).setDuration(250).withEndAction { view.visibility = android.view.View.INVISIBLE }.start()
            }
            hidden = true
        }
    }
    showControls = {
        hidden = false
        controlGroups.forEach { view ->
            view.animate().cancel()
            view.visibility = android.view.View.VISIBLE
            view.animate().alpha(1f).setDuration(150).start()
        }
        main.removeCallbacks(hide)
        main.postDelayed(hide, 3000)
    }
    fun showChoices(anchor: View, labels: List<String>, selected: Int, choose: (Int) -> Unit) {
        main.removeCallbacks(hide)
        val items = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeUi.bgDrawable(this@openExternalMedia, palette.surface, 8f)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(items) }
        val anchorPosition = IntArray(2)
        val overlayPosition = IntArray(2)
        anchor.getLocationOnScreen(anchorPosition)
        overlay.getLocationOnScreen(overlayPosition)
        val rowHeight = ThemeUi.dp(this, 36f)
        val height = minOf(rowHeight * labels.size, (anchorPosition[1] - overlayPosition[1] - ThemeUi.dp(this, 8f)).coerceAtLeast(rowHeight))
        val popup = PopupWindow(scroll, ThemeUi.dp(this, 120f), height, true).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            setOnDismissListener { showControls() }
        }
        labels.forEachIndexed { index, label ->
            items.addView(ThemeUi.text(this, label, 14f, palette,
                bold = index == selected, role = if (index == selected) ThemeUi.ROLE_ACCENT else ThemeUi.ROLE_TEXT).apply {
                gravity = Gravity.CENTER
                setOnClickListener { popup.dismiss(); choose(index) }
            }, LinearLayout.LayoutParams(-1, rowHeight))
        }
        popup.showAsDropDown(anchor, 0, -anchor.height - height, Gravity.END)
    }
    speedButton = TextView(this).apply {
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        textSize = controlTextSize
        includeFontPadding = false
        text = "1x"
        contentDescription = "${t("video.speed")} 1x"
        setPadding(0, 0, 0, 0)
        setOnClickListener {
            val options = listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)
            showChoices(this, options.map(::speedLabel), options.indexOf(speed)) { index ->
                speed = options[index]
                prepared?.let { media ->
                    val playing = player.isPlaying
                    media.playbackParams = media.playbackParams.setSpeed(speed)
                    if (!playing) player.pause()
                }
                speedButton.text = speedLabel(speed)
                speedButton.contentDescription = "${t("video.speed")} ${speedLabel(speed)}"
            }
        }
    }
    applyScaleMode = {
        val videoWidth = prepared?.videoWidth ?: 0
        val videoHeight = prepared?.videoHeight ?: 0
        if (videoWidth > 0 && videoHeight > 0 && overlay.width > 0 && overlay.height > 0) {
            val videoAspect = videoWidth.toFloat() / videoHeight
            val viewAspect = overlay.width.toFloat() / overlay.height
            val cover = if (videoAspect > viewAspect) videoAspect / viewAspect else viewAspect / videoAspect
            when (scaleMode) {
                0, 2 -> { player.scaleX = cover; player.scaleY = cover }
                1 -> { player.scaleX = 1f; player.scaleY = 1f }
                else -> {
                    player.scaleX = if (videoAspect > viewAspect) 1f else cover
                    player.scaleY = if (videoAspect > viewAspect) cover else 1f
                }
            }
        }
    }
    modeButton = TextView(this).apply {
        setTextColor(Color.WHITE); gravity = Gravity.CENTER; text = t("video.scale.fit"); contentDescription = "${t("video.scale")} $text"
        textSize = controlTextSize
        includeFontPadding = false
        setOnClickListener {
            scaleMode = (scaleMode + 1) % 4
            val label = t(listOf("video.scale.fill", "video.scale.fit", "video.scale.crop", "video.scale.stretch")[scaleMode])
            text = label; contentDescription = "${t("video.scale")} $label"; applyScaleMode(); showControls()
        }
    }
    val topButtonSize = ThemeUi.dp(this, 36f)
    fun ImageButton.topIcon(): ImageButton = apply {
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        val padding = ThemeUi.dp(this@openExternalMedia, 7f)
        setPadding(padding, padding, padding, padding)
        setColorFilter(Color.WHITE)
    }
    topBar.addView(videoIcon(R.drawable.video_close, t("video.close")) {
        dialog.dismiss()
        if (intent.getBooleanExtra("j5i.returnToOnline", false)) main.post { if (!isFinishing && !isDestroyed) showPlaylist(2) }
    }.topIcon(), LinearLayout.LayoutParams(topButtonSize, topButtonSize))
    rightControls.addView(time, LinearLayout.LayoutParams(0, topButtonSize, 1f))
    if (uri.scheme == "http" || uri.scheme == "https") {
        lateinit var favoriteButton: ImageButton
        fun refreshFavorite() {
            val selected = isVideoFavorite(uri.toString())
            favoriteButton.setImageDrawable(j5i.spatial8d.ui.HeartDrawable(selected, if (selected) 0xffff5c7a.toInt() else Color.WHITE))
            favoriteButton.clearColorFilter()
            favoriteButton.contentDescription = t(if (selected) "video.unfavorite" else "video.favorite")
        }
        favoriteButton = videoIcon(android.R.drawable.btn_star_big_off, t("video.favorite")) {
            toggleVideoFavorite(uri.toString()); refreshFavorite(); showControls()
        }.topIcon()
        refreshFavorite()
        rightControls.addView(favoriteButton, LinearLayout.LayoutParams(topButtonSize, topButtonSize))
    }
    lateinit var muteButton: ImageButton
    muteButton = videoIcon(R.drawable.video_volume, t("video.mute")) {
        muted = !muted
        prepared?.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
        muteButton.setImageResource(if (muted) R.drawable.video_muted else R.drawable.video_volume)
        muteButton.contentDescription = t(if (muted) "video.unmute" else "video.mute")
        showControls()
    }.topIcon()
    rightControls.addView(muteButton, LinearLayout.LayoutParams(topButtonSize, topButtonSize))
    rightControls.addView(speedButton, LinearLayout.LayoutParams(ThemeUi.dp(this, 40f), topButtonSize))
    rightControls.addView(modeButton, LinearLayout.LayoutParams(ThemeUi.dp(this, if (j5i.spatial8d.ui.AppLanguage.resolved(this) == j5i.spatial8d.ui.AppLanguage.ENGLISH) 52f else 40f), topButtonSize))
    val providerMvItem = currentItem?.takeIf { intent.hasExtra("j5i.providerMv") && it.uri.startsWith("online://plugin/") }
    if (providerMvItem != null) rightControls.addView(TextView(this).apply {
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        textSize = controlTextSize
        includeFontPadding = false
        text = t("video.quality")
        contentDescription = t("video.quality.change")
        setOnClickListener {
            val anchor = this
            val request = ++qualityRequest
            toast(t("video.quality.loading"))
            Thread {
                val result = runCatching { j5i.spatial8d.provider.ProviderTrackRef.mvOptions(this@openExternalMedia, providerMvItem) }
                main.post {
                    if (!dialog.isShowing || request != qualityRequest) return@post
                    result.onSuccess { options ->
                        showChoices(anchor, options.map { option ->
                            val size = if (option.size > 0) " %.1f MB".format(java.util.Locale.ROOT, option.size / 1048576.0) else ""
                            j5i.spatial8d.ui.AppLanguage.mvQuality(this@openExternalMedia, option.label) + size
                        }, options.indexOfFirst { it.id == currentQuality }) { index ->
                            if (options[index].id == currentQuality) return@showChoices
                            val cacheRequest = ++qualityRequest
                            loading.text = t("video.mv.caching")
                            loading.visibility = View.VISIBLE
                            Thread {
                                val media = runCatching {
                                    j5i.spatial8d.provider.ProviderMvCache.cache(this@openExternalMedia, providerMvItem, options[index],
                                        { dialog.isShowing && cacheRequest == qualityRequest }) { copied, total ->
                                        main.post {
                                            if (dialog.isShowing && cacheRequest == qualityRequest) {
                                                val percent = if (total > 0) " ${(copied * 100 / total).coerceIn(0, 100)}%" else ""
                                                loading.text = "${t("video.mv.caching")}$percent"
                                            }
                                        }
                                    }
                                }
                                main.post {
                                    if (!dialog.isShowing || cacheRequest != qualityRequest) return@post
                                    media.onSuccess {
                                        qualityResume = player.currentPosition.coerceAtLeast(0) to player.isPlaying
                                        prepared = null
                                        currentVideoUri = android.net.Uri.parse(it.uri)
                                        currentQuality = options[index].id
                                        player.setVideoURI(currentVideoUri)
                                    }.onFailure { loading.visibility = View.GONE; toast("${t("video.mv.load_failed")}: ${errorDetail(it)}") }
                                }
                            }.start()
                        }
                    }.onFailure { toast("${t("video.mv.resolve_failed")}: ${errorDetail(it)}") }
                }
            }.start()
        }
    }, LinearLayout.LayoutParams(ThemeUi.dp(this, 60f), topButtonSize))
    if (providerMvItem != null) rightControls.addView(videoIcon(R.drawable.ic_export, t("video.mv.export")) {
        exportMvAudioPublic(currentVideoUri, providerMvItem.name)
        showControls()
    }.topIcon(), LinearLayout.LayoutParams(topButtonSize, topButtonSize))
    rightControls.addView(videoIcon(R.drawable.video_rotate, t("video.rotate")) {
        requestedOrientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        overlay.post { applyScaleMode() }
        showControls()
    }.topIcon(), LinearLayout.LayoutParams(topButtonSize, topButtonSize))
    controls.addView(seek, LinearLayout.LayoutParams(-1, ThemeUi.dp(this, 40f)))
    overlay.addView(player, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
    overlay.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
        val margin = ThemeUi.dp(this@openExternalMedia, 24f)
        setMargins(margin, margin, margin, 0)
    })
    overlay.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = ThemeUi.dp(this@openExternalMedia, 24f) })
    overlay.addView(rightControls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
        leftMargin = ThemeUi.dp(this@openExternalMedia, 24f)
        rightMargin = leftMargin
        bottomMargin = ThemeUi.dp(this@openExternalMedia, 68f)
    })
    overlay.addView(centerButtons, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
    overlay.addView(feedback, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
    overlay.addView(loading, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
    overlay.setOnApplyWindowInsetsListener { _, insets ->
        val cutout = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout else null
        val margin = ThemeUi.dp(this, 24f)
        (topBar.layoutParams as FrameLayout.LayoutParams).apply {
            setMargins(margin + (cutout?.safeInsetLeft ?: 0), margin + (cutout?.safeInsetTop ?: 0), margin + (cutout?.safeInsetRight ?: 0), 0)
            topBar.layoutParams = this
        }
        (controls.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = cutout?.safeInsetLeft ?: 0
            rightMargin = cutout?.safeInsetRight ?: 0
            bottomMargin = ThemeUi.dp(this@openExternalMedia, 24f) + (cutout?.safeInsetBottom ?: 0)
            controls.layoutParams = this
        }
        (rightControls.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = ThemeUi.dp(this@openExternalMedia, 24f) + (cutout?.safeInsetLeft ?: 0)
            rightMargin = ThemeUi.dp(this@openExternalMedia, 24f) + (cutout?.safeInsetRight ?: 0)
            bottomMargin = ThemeUi.dp(this@openExternalMedia, 68f) + (cutout?.safeInsetBottom ?: 0)
            rightControls.layoutParams = this
        }
        insets
    }
    overlay.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) applyScaleMode()
    }
    fun toggleControls() {
        if (hidden) showControls() else {
            controlGroups.forEach { it.visibility = android.view.View.INVISIBLE }
            hidden = true
            main.removeCallbacks(hide)
        }
    }
    overlay.setOnClickListener { toggleControls() }
    var downX = 0f; var downY = 0f; var gesture = 0; var startVolume = 0; var startBrightness = 0f; var lastTapAt = 0L
    val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
    player.setOnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX; downY = event.rawY; gesture = 0
                startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                startBrightness = dialog.window?.attributes?.screenBrightness?.takeIf { it >= 0f }
                    ?: (Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX; val dy = event.rawY - downY
                if (gesture == 0 && maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) > touchSlop) gesture = if (kotlin.math.abs(dy) > kotlin.math.abs(dx)) 2 else 1
                if (gesture == 2 && overlay.height > 0) {
                    val change = -dy / overlay.height
                    if (downX < overlay.width / 2f) {
                        val value = (startBrightness + change).coerceIn(0.01f, 1f)
                        dialog.window?.let { window -> window.attributes = window.attributes.apply { screenBrightness = value } }
                        feedback.text = "${t("video.brightness")} ${(value * 100).toInt()}%"
                    } else {
                        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val value = (startVolume + change * maxVolume).toInt().coerceIn(0, maxVolume)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                        feedback.text = "${t("video.volume")} ${if (maxVolume > 0) value * 100 / maxVolume else 0}%"
                    }
                    feedback.visibility = View.VISIBLE
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.rawX - downX
                feedback.visibility = View.GONE
                if (gesture == 1 && player.duration > 0 && overlay.width > 0) {
                    player.seekTo((player.currentPosition + (dx / overlay.width * player.duration).toInt()).coerceIn(0, player.duration))
                    showControls()
                } else if (gesture == 0) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastTapAt <= 300L) {
                        if (player.isPlaying) player.pause() else { completed = false; player.start() }
                        showControls(); lastTapAt = 0L
                    } else {
                        lastTapAt = now; toggleControls()
                    }
                } else showControls()
                true
            }
            MotionEvent.ACTION_CANCEL -> { feedback.visibility = View.GONE; true }
            else -> true
        }
    }
    seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(bar: SeekBar) { dragging = true; showControls() }
        override fun onStopTrackingTouch(bar: SeekBar) {
            completed = false
            player.seekTo(bar.progress)
            if (!live) preferences.edit().putInt("video-position:$resumeKey", bar.progress).apply()
            dragging = false
            showControls()
        }
        override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) { if (fromUser) time.text = "${videoTime(progress)} / ${videoTime(player.duration)}" }
    })
    dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
        setContentView(overlay)
        window?.let { window ->
            if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.let { controller ->
                    controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller.hide(WindowInsets.Type.systemBars())
                }
            }
        }
    }
    externalVideoDialog = dialog
    var liveTimeout: Runnable? = null
    fun nextLiveStream(): Boolean {
        if (!dialog.isShowing || liveStreamIndex + 1 >= liveStreams.size) return false
        liveTimeout?.let(main::removeCallbacks)
        liveStreamIndex++
        loading.text = t("live.switching")
        loading.visibility = View.VISIBLE
        player.stopPlayback()
        player.setVideoURI(android.net.Uri.parse(liveStreams[liveStreamIndex]))
        liveTimeout = Runnable { if (!nextLiveStream()) { dialog.dismiss(); toast(t("video.play_failed")) } }
        main.postDelayed(liveTimeout!!, 12000)
        return true
    }
    val update = object : Runnable {
        override fun run() {
            if (!dialog.isShowing) return
            if (!dragging) {
                seek.max = player.duration.coerceAtLeast(0)
                seek.progress = player.currentPosition.coerceAtLeast(0)
                time.text = "${videoTime(player.currentPosition)} / ${videoTime(player.duration)}"
            }
            if (!live && !completed && player.duration > 0 && player.currentPosition > 0) {
                preferences.edit().putInt("video-position:$resumeKey", player.currentPosition).apply()
            }
            centerPlay.setImageResource(if (player.isPlaying) R.drawable.video_pause else R.drawable.video_play)
            centerPlay.contentDescription = t(if (player.isPlaying) "pause" else "play")
            main.postDelayed(this, 500)
        }
    }
    var cacheThread: Thread? = null
    dialog.setOnDismissListener {
        qualityRequest++
        liveTimeout?.let(main::removeCallbacks)
        main.removeCallbacks(update); main.removeCallbacks(hide); cacheThread?.interrupt()
        if (!live && !completed && player.duration > 0 && player.currentPosition > 0) preferences.edit().putInt("video-position:$resumeKey", player.currentPosition).apply()
        player.stopPlayback(); prepared = null
        externalVideoPreviousOrientation?.let { requestedOrientation = it }; externalVideoPreviousOrientation = null
        if (externalVideo === player) externalVideo = null; if (externalVideoDialog === dialog) externalVideoDialog = null
    }
    player.setOnPreparedListener {
        liveTimeout?.let(main::removeCallbacks)
        prepared = it
        if (!live) rememberVideo(uri, intent)
        it.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
        requestedOrientation = if (it.videoWidth > it.videoHeight) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (android.os.Build.VERSION.SDK_INT >= 23) it.setPlaybackParams(it.playbackParams.setSpeed(speed))
        val saved = if (live) 0 else qualityResume?.first ?: preferences.getInt("video-position:$resumeKey", 0)
        val resume = videoResumePosition(saved, it.duration)
        if (resume > 0) it.seekTo(resume)
        loading.visibility = View.GONE
        val shouldPlay = qualityResume?.second ?: true
        qualityResume = null
        if (shouldPlay) it.start() else player.pause()
        overlay.post { applyScaleMode() }; showControls()
    }
    player.setOnCompletionListener { completed = true; if (!live) preferences.edit().remove("video-position:$resumeKey").apply() }
    player.setOnInfoListener { _, what, _ ->
        if (liveStreams.isNotEmpty()) {
            if (what == android.media.MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                liveTimeout?.let(main::removeCallbacks)
                liveTimeout = Runnable { if (!nextLiveStream()) { dialog.dismiss(); toast(t("video.play_failed")) } }
                main.postDelayed(liveTimeout!!, 15000)
            } else if (what == android.media.MediaPlayer.MEDIA_INFO_BUFFERING_END) liveTimeout?.let(main::removeCallbacks)
        }
        false
    }
    player.setOnErrorListener { _, _, _ ->
        if (live && nextLiveStream()) return@setOnErrorListener true
        dialog.dismiss()
        val web = intent.getStringExtra("j5i.liveWeb")
        val fallbackWeb = intent.getStringExtra("j5i.liveFallbackWeb")?.takeIf {
            runCatching {
                val address = java.net.URI(it)
                address.scheme in listOf("http", "https") && address.host != null && address.rawUserInfo == null && address.fragment == null
            }.getOrDefault(false)
        }
        if (live && fallbackWeb != null) {
            lateinit var failure: android.app.Dialog
            failure = ThemeUi.showSheet(this, palette, t("live.line_failed")) {
                addView(ThemeUi.button(this@openExternalMedia, palette, t("live.official")) {
                    failure.dismiss(); openWebVideo(fallbackWeb, live = true)
                })
                if (web != null && android.net.Uri.parse(web).scheme in listOf("http", "https")) {
                    addView(ThemeUi.button(this@openExternalMedia, palette, t("live.web")) {
                        failure.dismiss(); openWebVideo(web, live = true)
                    })
                }
            }
            failure.show()
        } else if (live && web != null && android.net.Uri.parse(web).scheme in listOf("http", "https")) openWebVideo(web, live = true)
        else toast(t("video.play_failed"))
        true
    }
    dialog.show(); main.post(update)
    if (liveStreams.isNotEmpty()) {
        liveTimeout = Runnable { if (!nextLiveStream()) { dialog.dismiss(); toast(t("video.play_failed")) } }
        main.postDelayed(liveTimeout!!, 12000)
    }
    val cached = if (!live && isCacheableVideo(uri, mime)) videoCacheFile(this, uri.toString()).takeIf { it.isFile && it.length() > 0L } else null
    fun startVideo(source: android.net.Uri) {
        loading.visibility = View.GONE
        player.setVideoURI(source)
    }
    if (cached != null) {
        PlaybackCacheMetadata.put(this, cached, intent.getStringExtra(Intent.EXTRA_TITLE).orEmpty(), uri.host.orEmpty())
        startVideo(android.net.Uri.fromFile(cached))
    } else if (!live && isCacheableVideo(uri, mime)) {
        loading.visibility = View.VISIBLE
        val target = videoCacheFile(this, uri.toString())
        if (!PlaybackCacheMetadata.tryBegin(target)) {
            startVideo(uri)
        } else cacheThread = Thread {
            val part = File(target.parentFile, "${target.name}.part")
            try {
                target.parentFile?.mkdirs()
                val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
                client.newCall(Request.Builder().url(uri.toString()).build()).execute().use { response ->
                    check(response.isSuccessful) { "video download ${response.code}" }
                    val body = response.body ?: error("empty video body")
                    val total = body.contentLength()
                    var lastPercent = -1
                    body.byteStream().use { input -> part.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copied = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            if (Thread.currentThread().isInterrupted) throw InterruptedException()
                            output.write(buffer, 0, read); copied += read
                            if (total > 0) {
                                val percent = (copied * 100 / total).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    main.post { if (dialog.isShowing) loading.text = "${t("video.caching")} $percent%" }
                                }
                            }
                        }
                    } }
                }
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                if (!part.renameTo(target)) error("video cache rename failed")
                PlaybackCacheMetadata.put(this, target, intent.getStringExtra(Intent.EXTRA_TITLE).orEmpty(), uri.host.orEmpty())
                main.post { if (dialog.isShowing) startVideo(android.net.Uri.fromFile(target)) }
            } catch (_: InterruptedException) {
                part.delete()
            } catch (_: Exception) {
                part.delete()
                main.post { if (dialog.isShowing) { loading.text = t("video.streaming"); startVideo(uri) } }
            } finally {
                PlaybackCacheMetadata.end(target)
            }
        }.also { it.name = "video-cache-${videoCacheKey(uri.toString()).take(8)}"; it.start() }
    } else {
        startVideo(uri)
    }
}
