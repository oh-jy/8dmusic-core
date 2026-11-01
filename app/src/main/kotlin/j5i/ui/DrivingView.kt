package j5i.spatial8d.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import j5i.spatial8d.audio.LyricsResult
import j5i.spatial8d.data.MusicItem
import android.util.Log
import android.os.SystemClock
import kotlin.math.abs

/** 驾驶模式复用当前可视化，播放器仍由原服务控制。 */
class DrivingView(context: Context, visualizer: VisualizerView) : FrameLayout(context) {
    var onClose: (() -> Unit)? = null
    var onPlayPause: (() -> Unit)? = null
    var onPrevious: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    private val title = ThemeUi.text(context, "", 20f, ThemeUi.palette(true), bold = true)
    private val play = ThemeUi.button(context, ThemeUi.palette(true), "▶", ThemeUi.ROLE_SURFACE_ALT, minHeightDp = 68f)
    private val lines = Array(3) { index ->
        ThemeUi.text(context, "", 18f, ThemeUi.palette(true), bold = index == 1,
            role = if (index == 1) ThemeUi.ROLE_ACCENT else ThemeUi.ROLE_SUB).apply {
            gravity = Gravity.CENTER
            maxLines = 1
        }
    }
    private var lyrics: LyricsResult? = null
    private var positionMs = 0L
    private var lastLoggedIndex = -2
    private var lastPlaying = false
    private var clockBaseMs = 0L
    private var clockAtMs = 0L
    private val ticker = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            val currentPosition = if (lastPlaying) clockBaseMs + (SystemClock.elapsedRealtime() - clockAtMs) else clockBaseMs
            renderLyrics(currentPosition)
            postDelayed(this, 200)
        }
    }
    private var downX = 0f

    init {
        setBackgroundColor(ThemeUi.palette(true).background)
        addView(visualizer, LayoutParams(-1, -1))
        val shade = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setBackgroundColor(0x990b0c0e.toInt())
            setPadding(dp(24), dp(14), dp(24), dp(20))
        }
        shade.addView(title.apply { gravity = Gravity.CENTER; maxLines = 1 })
        lines.forEach { shade.addView(it, LinearLayout.LayoutParams(-1, dp(34))) }
        shade.addView(ThemeUi.text(context, AppLanguage.text(context, "driving.hint"), 11f,
            ThemeUi.palette(true), role = ThemeUi.ROLE_SUB).apply { gravity = Gravity.CENTER })
        shade.addView(ThemeUi.row(context).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
            addView(bigButton("⏮") { onPrevious?.invoke() }.apply {
                contentDescription = AppLanguage.text(context, "previous")
            }, LinearLayout.LayoutParams(0, dp(68), 1f))
            addView(play.apply { textSize = 28f; setOnClickListener { onPlayPause?.invoke() } }, LinearLayout.LayoutParams(0, dp(68), 1f).apply {
                leftMargin = dp(18); rightMargin = dp(18)
            })
            addView(bigButton("⏭") { onNext?.invoke() }.apply {
                contentDescription = AppLanguage.text(context, "next")
            }, LinearLayout.LayoutParams(0, dp(68), 1f))
        })
        addView(shade, LayoutParams(-1, -2, Gravity.BOTTOM))
        addView(ThemeUi.button(context, ThemeUi.palette(true), AppLanguage.text(context, "driving.close"),
            ThemeUi.ROLE_ACCENT_FILL, minHeightDp = 48f).apply {
            setOnClickListener { onClose?.invoke() }
        }, LayoutParams(-2, dp(48), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(34); rightMargin = dp(36)
        })
    }

    private fun bigButton(label: String, action: () -> Unit) =
        ThemeUi.button(context, ThemeUi.palette(true), label, ThemeUi.ROLE_SURFACE_ALT, minHeightDp = 68f).apply {
            textSize = 28f
            setOnClickListener { action() }
        }

    fun update(item: MusicItem?, result: LyricsResult?, position: Long, playing: Boolean) {
        title.text = item?.name?.substringBeforeLast('.') ?: AppLanguage.text(context, "no.song")
        play.text = if (playing) "⏸" else "▶"
        play.contentDescription = AppLanguage.text(context, if (playing) "pause" else "play")
        lyrics = result
        positionMs = position
        if (lastPlaying != playing || kotlin.math.abs(positionMs - position) > 800) {
            clockBaseMs = positionMs
            clockAtMs = SystemClock.elapsedRealtime()
        }
        lastPlaying = playing
        renderLyrics(positionMs)
    }

    private fun renderLyrics(position: Long) {
        val result = lyrics
        val plainLines = result?.plainLyrics?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList().orEmpty()
        val current = if (result?.lines?.isNotEmpty() == true) result.lines.indexOfLast { it.timeMs <= position }
        else if (plainLines.isNotEmpty()) ((position / 4000L).toInt() % plainLines.size) else -1
        if (current != lastLoggedIndex) {
            lastLoggedIndex = current
            Log.i("8DDriveLyrics", "index=$current positionMs=$position lines=${result?.lines?.size ?: plainLines.size}")
        }
        lines.forEachIndexed { index, view ->
            view.text = if (result?.lines?.isNotEmpty() == true)
                result.lines.getOrNull(current - 1 + index)?.text.orEmpty()
            else plainLines.getOrNull(current - 1 + index).orEmpty()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(ticker)
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> downX = event.x
            MotionEvent.ACTION_UP -> if (abs(event.x - downX) > dp(80)) {
                visualizerChild?.let { it.restoreVisualState(true to nextMode(it.visualState.second, event.x < downX)) }
            }
        }
        return true
    }

    private val visualizerChild: VisualizerView? get() = (getChildAt(0) as? VisualizerView)
    private fun nextMode(current: Int, forward: Boolean): Int {
        val modes = listOf(3, 2, 1, 4)
        val index = modes.indexOf(current).coerceAtLeast(0)
        return modes[(index + if (forward) 1 else -1).mod(modes.size)]
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) downX = event.x
        return event.actionMasked == MotionEvent.ACTION_MOVE && abs(event.x - downX) > dp(24)
    }

    private fun dp(value: Int) = ThemeUi.dp(context, value.toFloat())
}
