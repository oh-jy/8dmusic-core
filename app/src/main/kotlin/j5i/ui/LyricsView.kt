package j5i.spatial8d.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import j5i.spatial8d.audio.LyricsResult
import kotlin.math.abs

/**
 * 可嵌入空间曲线同尺寸面板的歌词视图。进度单位是毫秒，主线程周期性调用 setPosition。
 * 用户拖动后暂停自动滚动三秒；点击当前行或松手三秒后继续跟随人声。
 */
class LyricsView(context: Context) : ScrollView(context) {
    var onSearch: ((String) -> Unit)? = null
    var onManual: (() -> Unit)? = null

    var dark = true
        set(value) { field = value; applyColors() }

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
    private var manualUntil = 0L
    private var touching = false
    private var topPadding = 0
    private var bottomPadding = 0
    private var liveCaption = ""

    init {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        clipToPadding = false
        addView(content, ViewGroup.LayoutParams(-1, -2))
        setLyrics(null)
    }

    /** null 表示暂无歌词；instrumental 和无时间轴文本不会伪造逐行高亮。 */
    fun setLyrics(value: LyricsResult?) {
        liveCaption = ""
        lyrics = value
        if (positionMs < 0) positionMs = 0
        activeIndex = -2
        manualUntil = 0
        content.removeAllViews()
        rows.clear()
        val p = palette
        when {
            value == null -> message(t("no.lyrics"))
            value.instrumental -> message(t("instrumental"), allowCorrection = true)
            value.synchronized -> value.lines.forEach { line ->
                val row = TextView(context).apply {
                    text = line.text
                    textSize = 15f
                    gravity = Gravity.CENTER
                    maxLines = 3
                    setPadding(dp(20f), dp(4f), dp(20f), dp(4f))
                    layoutParams = LinearLayout.LayoutParams(-1, -2)
                    setOnClickListener {
                        manualUntil = 0
                        setPosition(line.timeMs, true)
                    }
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
        if (force || (!touching && SystemClock.uptimeMillis() >= manualUntil)) {
            if (force) manualUntil = 0
            scrollToLine(index)
        }
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

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                manualUntil = SystemClock.uptimeMillis() + 3_000
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.onTouchEvent(event)
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
        rows.forEachIndexed { index, row ->
            val current = index == activeIndex
            val played = index < activeIndex
            row.setTextColor(when {
                current -> p.accent
                played -> p.sub
                else -> p.faint
            })
            row.typeface = if (index == activeIndex) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            row.alpha = if (index == activeIndex) 1f else if (played) 0.85f else 0.72f
        }
        if (rows.isEmpty()) {
            (content.getChildAt(0) as? TextView)?.setTextColor(p.sub)
        }
    }

    private fun updatePadding() {
        val compact = rows.isEmpty()
        // 首行需要占据第一行；后续滚动由 scrollToLine 给当前行保留上一行。
        topPadding = if (compact) dp(8f) else 0
        bottomPadding = if (compact) dp(8f) else (height - rows.last().height * 2 - dp(12f)).coerceAtLeast(dp(24f))
        content.setPadding(0, topPadding, 0, bottomPadding)
    }

    private fun scrollToLine(index: Int, animated: Boolean = true) {
        val target = if (index < 0) 0 else rows.getOrNull(index)?.let {
            val previous = rows.getOrNull(index - 1)
            // it.top 已包含 content 的顶部 padding，不能再次叠加。
            (it.top - (previous?.height ?: it.height)).coerceAtLeast(0)
        } ?: 0
        if (abs(scrollY - target) < 2) return
        if (animated) smoothScrollTo(0, target) else scrollTo(0, target)
    }

    private fun dp(value: Float) = ThemeUi.dp(context, value)
}
