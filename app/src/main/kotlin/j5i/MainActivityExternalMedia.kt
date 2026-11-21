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
import android.widget.PopupMenu
import j5i.spatial8d.ui.ThemeUi

private fun videoTime(ms: Int): String { val s = (ms / 1000).coerceAtLeast(0); return "%02d:%02d".format(s / 60, s % 60) }
private fun speedLabel(speed: Float): String = if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"

private fun MainActivity.videoIcon(res: Int, description: String, action: () -> Unit) = ImageButton(this).apply {
    setImageResource(res); contentDescription = description; setColorFilter(palette.text); setBackgroundColor(Color.TRANSPARENT); setOnClickListener { action() }
}

internal fun MainActivity.openExternalMedia(intent: Intent) {
    val uri = intent.data ?: return
    if (uri.scheme !in listOf("content", "file", "http", "https")) return
    val mime = intent.type ?: runCatching { contentResolver.getType(uri) }.getOrNull().orEmpty()
    externalVideoDialog?.dismiss()
    if (!mime.startsWith("video/", true)) { runCatching { addAndPlay(uri) }.onFailure { toast("无法打开音频文件") }; return }
    if (recordingKind.isNotEmpty()) { toast("请先停止录音，再播放视频"); return }
    service?.pause()
    val player = VideoView(this); externalVideo = player
    val overlay = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
    val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(8, 8, 8, 8); setBackgroundColor(Color.TRANSPARENT) }
    val topBar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    val seek = SeekBar(this).apply {
        ThemeUi.configureSeekBar(this, palette, playback = true)
        thumbTintList = android.content.res.ColorStateList.valueOf(palette.accent)
    }
    val time = TextView(this).apply { setTextColor(Color.WHITE); gravity = Gravity.CENTER; text = "00:00 / 00:00" }
    val feedback = TextView(this).apply { setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER; visibility = View.GONE; setBackgroundColor(0xaa000000.toInt()); setPadding(24, 12, 24, 12) }
    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    var muted = false
    var speed = 1.0f; var prepared: android.media.MediaPlayer? = null; var dragging = false; var hidden = false
    externalVideoPreviousOrientation = requestedOrientation
    lateinit var dialog: android.app.Dialog
    lateinit var speedButton: TextView
    lateinit var modeButton: TextView
    lateinit var showControls: () -> Unit
    var scaleMode = 1
    lateinit var applyScaleMode: () -> Unit
    val centerPlay = videoIcon(android.R.drawable.ic_media_pause, "暂停") {
        if (player.isPlaying) player.pause() else player.start()
        showControls()
    }.apply {
        scaleType = android.widget.ImageView.ScaleType.CENTER
        setPadding(24, 24, 24, 24)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xaa000000.toInt()) }
    }
    val centerButtons = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        setPadding(8, 8, 8, 8)
        setBackgroundColor(Color.TRANSPARENT)
    }
    centerButtons.addView(videoIcon(android.R.drawable.ic_media_rew, "后退 10 秒") {
        player.seekTo((player.currentPosition - 10000).coerceAtLeast(0)); showControls()
    }.apply {
        setPadding(20, 20, 20, 20)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xaa000000.toInt()) }
    }, LinearLayout.LayoutParams(ThemeUi.dp(this, 48f), ThemeUi.dp(this, 48f)))
    centerButtons.addView(centerPlay, LinearLayout.LayoutParams(ThemeUi.dp(this, 64f), ThemeUi.dp(this, 64f)).apply { leftMargin = ThemeUi.dp(this@openExternalMedia, 12f); rightMargin = leftMargin })
    centerButtons.addView(videoIcon(android.R.drawable.ic_media_ff, "前进 10 秒") {
        player.seekTo((player.currentPosition + 10000).coerceAtMost(player.duration)); showControls()
    }.apply {
        setPadding(20, 20, 20, 20)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xaa000000.toInt()) }
    }, LinearLayout.LayoutParams(ThemeUi.dp(this, 48f), ThemeUi.dp(this, 48f)))
    val hide = Runnable {
        if (player.isPlaying) {
            listOf(controls, topBar, centerButtons).forEach { view ->
                view.animate().alpha(0f).setDuration(250).withEndAction { view.visibility = android.view.View.INVISIBLE }.start()
            }
            hidden = true
        }
    }
    showControls = {
        hidden = false
        listOf(controls, topBar, centerButtons).forEach { view ->
            view.animate().cancel()
            view.visibility = android.view.View.VISIBLE
            view.animate().alpha(1f).setDuration(150).start()
        }
        main.removeCallbacks(hide)
        main.postDelayed(hide, 3000)
    }
    speedButton = TextView(this).apply {
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        text = "1x"
        contentDescription = "播放速度 1 倍"
        setPadding(0, 0, 0, 0)
        setOnClickListener {
            val options = listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)
            PopupMenu(this@openExternalMedia, this).apply {
                options.forEachIndexed { index, value ->
                    menu.add(0, index, index, speedLabel(value)).apply { isCheckable = true; isChecked = value == speed }
                }
                setOnMenuItemClickListener { item ->
                    speed = options[item.itemId]
                    if (android.os.Build.VERSION.SDK_INT >= 23) prepared?.let { it.setPlaybackParams(it.playbackParams.setSpeed(speed)) }
                    text = speedLabel(speed); contentDescription = "播放速度 ${speedLabel(speed)}"; showControls(); true
                }
                show()
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
        setTextColor(Color.WHITE); gravity = Gravity.CENTER; text = "适应"; contentDescription = "画面比例 适应"
        setOnClickListener {
            scaleMode = (scaleMode + 1) % 4
            val label = listOf("铺满", "适应", "裁剪", "拉伸")[scaleMode]
            text = label; contentDescription = "画面比例 $label"; applyScaleMode(); showControls()
        }
    }
    val topButtonSize = ThemeUi.dp(this, 48f)
    topBar.addView(videoIcon(android.R.drawable.ic_menu_close_clear_cancel, "关闭视频") { dialog.dismiss() }, LinearLayout.LayoutParams(topButtonSize, topButtonSize))
    topBar.addView(android.view.View(this), LinearLayout.LayoutParams(0, topButtonSize, 1f))
    if (uri.scheme == "http" || uri.scheme == "https") {
        lateinit var favoriteButton: ImageButton
        fun refreshFavorite() {
            val selected = isVideoFavorite(uri.toString())
            favoriteButton.setImageDrawable(j5i.spatial8d.ui.HeartDrawable(selected, if (selected) 0xffff5c7a.toInt() else Color.WHITE))
            favoriteButton.contentDescription = if (selected) "取消视频收藏" else "收藏视频"
        }
        favoriteButton = videoIcon(android.R.drawable.btn_star_big_off, "收藏视频") {
            toggleVideoFavorite(uri.toString()); refreshFavorite(); showControls()
        }
        refreshFavorite()
        topBar.addView(favoriteButton, LinearLayout.LayoutParams(topButtonSize, topButtonSize))
    }
    lateinit var muteButton: ImageButton
    muteButton = videoIcon(android.R.drawable.ic_lock_silent_mode_off, "静音") {
        muted = !muted
        prepared?.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
        muteButton.setImageResource(if (muted) android.R.drawable.ic_lock_silent_mode else android.R.drawable.ic_lock_silent_mode_off)
        muteButton.contentDescription = if (muted) "取消静音" else "静音"
        showControls()
    }
    topBar.addView(muteButton, LinearLayout.LayoutParams(topButtonSize, topButtonSize))
    topBar.addView(speedButton, LinearLayout.LayoutParams(ThemeUi.dp(this, 56f), topButtonSize))
    topBar.addView(modeButton, LinearLayout.LayoutParams(ThemeUi.dp(this, 56f), topButtonSize))
    topBar.addView(videoIcon(android.R.drawable.ic_menu_rotate, "切换横竖屏") {
        requestedOrientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        overlay.post { applyScaleMode() }
        showControls()
    }, LinearLayout.LayoutParams(topButtonSize, topButtonSize))
    controls.addView(seek, LinearLayout.LayoutParams(-1, ThemeUi.dp(this, 40f)))
    controls.addView(time, LinearLayout.LayoutParams(-1, -2))
    overlay.addView(player, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
    overlay.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
        val margin = ThemeUi.dp(this@openExternalMedia, 16f)
        setMargins(margin, margin, margin, 0)
    })
    overlay.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = ThemeUi.dp(this@openExternalMedia, 24f) })
    overlay.addView(centerButtons, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
    overlay.addView(feedback, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
    overlay.setOnApplyWindowInsetsListener { _, insets ->
        val cutout = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout else null
        val margin = ThemeUi.dp(this, 16f)
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
        insets
    }
    overlay.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) applyScaleMode()
    }
    fun toggleControls() {
        if (hidden) showControls() else {
            controls.visibility = android.view.View.INVISIBLE
            topBar.visibility = android.view.View.INVISIBLE
            centerButtons.visibility = android.view.View.INVISIBLE
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
                        feedback.text = "亮度 ${(value * 100).toInt()}%"
                    } else {
                        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val value = (startVolume + change * maxVolume).toInt().coerceIn(0, maxVolume)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                        feedback.text = "音量 ${if (maxVolume > 0) value * 100 / maxVolume else 0}%"
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
                        if (player.isPlaying) player.pause() else player.start()
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
        override fun onStopTrackingTouch(bar: SeekBar) { player.seekTo(bar.progress); dragging = false; showControls() }
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
    val update = object : Runnable { override fun run() { if (!dialog.isShowing) return; if (!dragging) { seek.max = player.duration.coerceAtLeast(0); seek.progress = player.currentPosition.coerceAtLeast(0); time.text = "${videoTime(player.currentPosition)} / ${videoTime(player.duration)}" }; centerPlay.setImageResource(if (player.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play); main.postDelayed(this, 500) } }
    dialog.setOnDismissListener {
        main.removeCallbacks(update); main.removeCallbacks(hide); player.stopPlayback(); prepared = null
        externalVideoPreviousOrientation?.let { requestedOrientation = it }; externalVideoPreviousOrientation = null
        if (externalVideo === player) externalVideo = null; if (externalVideoDialog === dialog) externalVideoDialog = null
    }
    player.setOnPreparedListener {
        prepared = it
        it.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
        requestedOrientation = if (it.videoWidth > it.videoHeight) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (android.os.Build.VERSION.SDK_INT >= 23) it.setPlaybackParams(it.playbackParams.setSpeed(speed))
        it.start(); overlay.post { applyScaleMode() }; showControls()
    }
    player.setOnErrorListener { _, _, _ -> toast("无法播放此视频"); dialog.dismiss(); true }
    dialog.show(); player.setVideoURI(uri); main.post(update)
}
