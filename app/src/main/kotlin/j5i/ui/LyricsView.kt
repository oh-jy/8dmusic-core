package j5i.spatial8d.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import j5i.spatial8d.audio.LyricsResult
import kotlin.math.abs

/**
 * 可嵌入空间曲线同尺寸面板的歌词视图。进度单位是毫秒，主线程周期性调用 setPosition。
 * 用户拖动时预览居中歌词时间，点击时间跳播；停止操作三秒后继续跟随人声。
 */
class LyricsView(context: Context) : FrameLayout(context) {
    var onSearch: ((String) -> Unit)? = null
    var onManual: (() -> Unit)? = null
    var onSeek: ((Long) -> Unit)? = null
    var onExpand: (() -> Unit)? = null

    var dark = true
        set(value) { field = value; applyColors() }
    var fullScreen = false
        set(value) {
            field = value
            rows.forEach { it.textSize = if (value) 22f else 15f }
            resetPreview()
            applyColors()
        }
    var seekEnabled = true
        set(value) {
            field = value
            if (!value) resetPreview()
        }

    private val palette get() = ThemeUi.palette(dark)
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private fun t(key: String) = AppLanguage.text(context, key)
    private val rows = mutableListOf<TextView>()
    private var lyrics: LyricsResult? = null
    private var positionMs = 0L
    private var activeIndex = -2
    private var touching = false
    private var liveCaption = ""
    private var previewIndex = -1
    private var browsing = false
    private var touchStartY = 0f
    private var doubleTapped = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val resumeFollowing = Runnable {
        resetPreview()
        if (!touching) scrollToLine(activeIndex)
    }
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            doubleTapped = true
            post { onExpand?.invoke() }
            return true
        }
    })
    private val scroller = object : ScrollView(context) {
        override fun onScrollChanged(left: Int, top: Int, oldLeft: Int, oldTop: Int) {
            super.onScrollChanged(left, top, oldLeft, oldTop)
            if (browsing) {
                updatePreview()
                if (!touching) scheduleFollowing()
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touching = true
                    doubleTapped = false
                    touchStartY = event.y
                    this@LyricsView.removeCallbacks(resumeFollowing)
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (seekEnabled && liveCaption.isEmpty() && rows.isNotEmpty() &&
                        abs(event.y - touchStartY) > touchSlop) {
                        browsing = true
                        updatePreview()
                    }
                }
            }
            gestures.onTouchEvent(event)
            val handled = if (doubleTapped) {
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
                true
            } else super.dispatchTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL || doubleTapped) {
                touching = false
                parent?.requestDisallowInterceptTouchEvent(false)
                scheduleFollowing()
            }
            return handled
        }
    }.apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        clipToPadding = false
        addView(content, ViewGroup.LayoutParams(-1, -2))
    }
    private val timeLabel = TextView(context).apply {
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(12f), 0, dp(12f), 0)
        visibility = GONE
        setOnClickListener {
            lyrics?.lines?.getOrNull(previewIndex)?.timeMs?.let { target ->
                if (seekEnabled) {
                    resetPreview()
                    onSeek?.invoke(target)
                }
            }
        }
    }

    init {
        contentDescription = t("lyrics.gestures")
        addView(scroller, LayoutParams(-1, -1))
        addView(timeLabel, LayoutParams(-2, dp(48f), Gravity.CENTER_VERTICAL or Gravity.END).apply {
            rightMargin = dp(8f)
        })
        content.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updatePadding()
            if (!browsing && !touching) scrollToLine(activeIndex, false)
        }
        setLyrics(null)
        applyColors()
    }

    /** null 表示暂无歌词；instrumental 和无时间轴文本不会伪造逐行高亮。 */
    fun setLyrics(value: LyricsResult?) {
        liveCaption = ""
        lyrics = value
        if (positionMs < 0) positionMs = 0
        activeIndex = -2
        resetPreview()
        content.removeAllViews()
        rows.clear()
        when {
            value == null -> message(t("no.lyrics"))
            value.instrumental -> message(t("instrumental"), allowCorrection = true)
            value.synchronized -> value.lines.forEach { line ->
                val row = TextView(context).apply {
                    text = line.text
                    textSize = if (fullScreen) 22f else 15f
                    gravity = Gravity.CENTER
                    maxLines = 3
                    setPadding(dp(20f), dp(4f), dp(20f), dp(4f))
                    layoutParams = LinearLayout.LayoutParams(-1, -2)
                    setOnLongClickListener {
                        onManual?.invoke()
                        true
                    }
                }
                rows += row
                content.addView(row)
            }
            value.plainLyrics.isNotBlank() -> message(value.plainLyrics, allowCorrection = true)
            else -> message(t("song.no.lyrics"))
        }
        updatePadding()
        setPosition(positionMs, true)
        content.post {
            updatePadding()
            scrollToLine(activeIndex, false)
        }
    }

    /** Shows the current FM transcript using the same centered, highlighted lyric row. */
    fun setLiveCaption(text: String) {
        if (liveCaption == text) return
        if (text.isBlank()) {
            setLyrics(null)
        } else {
            setLyrics(LyricsResult("FM", "", listOf(j5i.spatial8d.audio.LyricLine(0, text))))
        }
        liveCaption = text
    }

    /** 外部播放器仍是唯一进度来源；force 仅用于切歌或用户返回当前行。 */
    fun setPosition(positionMs: Long, force: Boolean = false) {
        this.positionMs = positionMs.coerceAtLeast(0)
        val lines = lyrics?.lines ?: return
        val index = lines.indexOfLast { it.timeMs <= this.positionMs }
        if (index != activeIndex) {
            activeIndex = index
            applyColors()
        }
        if (force || (!touching && !browsing)) {
            if (force) resetPreview()
            scrollToLine(index)
        }
    }

    private fun updatePreview() {
        val center = scroller.scrollY + scroller.height / 2
        previewIndex = rows.indices.minByOrNull { index ->
            abs(rows[index].top + rows[index].height / 2 - center)
        } ?: -1
        val target = lyrics?.lines?.getOrNull(previewIndex)?.timeMs ?: return
        val label = formatTime(target)
        val wasHidden = timeLabel.visibility != VISIBLE
        timeLabel.text = "▶ $label"
        timeLabel.contentDescription = t("lyrics.seek.time").format(label)
        timeLabel.visibility = VISIBLE
        if (wasHidden) {
            removeCallbacks(resumeFollowing)
            postDelayed(resumeFollowing, 3_000L)
        }
        applyColors()
    }

    private fun resetPreview() {
        removeCallbacks(resumeFollowing)
        browsing = false
        previewIndex = -1
        timeLabel.visibility = GONE
        applyColors()
    }

    private fun scheduleFollowing() {
        removeCallbacks(resumeFollowing)
        postDelayed(resumeFollowing, 3_000)
    }

    /** 清除旧曲目的歌词和进度，避免异步加载返回前显示上一首歌。 */
    fun clear() {
        positionMs = -1
        setLyrics(null)
        content.removeAllViews()
        message(t("lyrics.matching"))
    }

    fun showError(fileName: String, timedOut: Boolean = false) {
        setLyrics(null)
        content.removeAllViews()
        val p = palette
        // 与"长按歌词 → 修正"走同一套候选/预览流程，避免另一套单输入框的简陋 UI。
        message(if (timedOut) t("lyrics.timeout") else t("lyrics.not.found") + fileName)
        content.addView(ThemeUi.button(context, p, t("lyrics.edit")) {
            onManual?.invoke()
        }, LinearLayout.LayoutParams(-2, dp(36f)))
        ThemeUi.applyTheme(content, p)
        updatePadding()
        content.setPadding(0, dp(8f), 0, dp(8f))
    }

    fun showProgress(stage: String) {
        if (rows.isNotEmpty()) return
        lyrics = null
        content.removeAllViews()
        message(stage)
        content.addView(ThemeUi.button(context, palette, t("lyrics.edit")) {
            onManual?.invoke()
        }, LinearLayout.LayoutParams(-2, dp(48f)))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updatePadding()
        post {
            updatePadding()
            scrollToLine(activeIndex, false)
        }
    }

    override fun onDetachedFromWindow() {
        touching = false
        resetPreview()
        super.onDetachedFromWindow()
    }

    private fun formatTime(timeMs: Long): String {
        val seconds = (timeMs / 1000).coerceAtLeast(0)
        return String.format(java.util.Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
    }

    private fun message(text: String, allowCorrection: Boolean = false) {
        content.addView(TextView(context).apply {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(palette.sub)
            setPadding(dp(24f), dp(12f), dp(24f), dp(12f))
            if (allowCorrection) setOnLongClickListener {
                onManual?.invoke()
                true
            }
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun applyColors() {
        val p = palette
        setBackgroundColor(p.surface)
        timeLabel.setTextColor(p.darkInk)
        timeLabel.background = ThemeUi.bgDrawable(context, p.accent, 12f)
        rows.forEachIndexed { index, row ->
            val current = index == activeIndex
            val selected = browsing && index == previewIndex
            val played = index < activeIndex
            row.setTextColor(when {
                current || selected -> p.accent
                played -> p.sub
                else -> p.faint
            })
            row.typeface = if (current || selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            row.alpha = if (current || selected) 1f else if (played) 0.85f else 0.72f
        }
        if (rows.isEmpty()) {
            (content.getChildAt(0) as? TextView)?.setTextColor(p.sub)
        }
    }

    private fun updatePadding() {
        val compact = rows.isEmpty()
        val topPadding = if (compact) dp(8f) else ((height - rows.first().height) / 2).coerceAtLeast(0)
        val bottomPadding = if (compact) dp(8f) else ((height - rows.last().height) / 2).coerceAtLeast(0)
        content.setPadding(0, topPadding, 0, bottomPadding)
    }

    private fun scrollToLine(index: Int, animated: Boolean = true) {
        val target = if (index < 0) 0 else rows.getOrNull(index)?.let {
            (it.top + it.height / 2 - scroller.height / 2).coerceAtLeast(0)
        } ?: 0
        if (abs(scroller.scrollY - target) < 2) return
        if (animated) scroller.smoothScrollTo(0, target) else scroller.scrollTo(0, target)
    }

    private fun dp(value: Float) = ThemeUi.dp(context, value)
}
