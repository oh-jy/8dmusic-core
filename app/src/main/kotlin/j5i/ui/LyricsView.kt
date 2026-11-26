package j5i.spatial8d.ui

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.icu.text.BreakIterator
import android.text.Spannable
import android.text.style.ForegroundColorSpan
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import j5i.spatial8d.audio.LyricsResult
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.cos
import kotlin.math.sin

internal fun lyricLineProgress(startMs: Long, endMs: Long?, positionMs: Long): Float? {
    if (endMs == null || endMs <= startMs) return null
    return ((positionMs - startMs).toDouble() / (endMs - startMs)).coerceIn(0.0, 1.0).toFloat()
}

internal fun lyricRipplePhase(progress: Float, count: Int): Float =
    if (count <= 0) 0f else (progress.coerceIn(0f, 0.999999f) * count).let { it - floor(it) }

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
        set(value) { field = value; applyColors(); secondary?.dark = value }
    var fullScreen = false
        set(value) {
            field = value
            applyTextSize()
            secondary?.fullScreen = value
            resetPreview()
            applyColors()
        }
    var seekEnabled = true
        set(value) {
            field = value
            secondary?.seekEnabled = value
            if (!value) resetPreview()
        }

    private val palette get() = ThemeUi.palette(dark)
    private val fontPreferences = context.getSharedPreferences("spatial8d", Context.MODE_PRIVATE)
    private var fontScale = fontPreferences.getFloat("lyrics_font_scale", 0.82f).coerceIn(0.65f, 1.35f)
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
    }
    private fun t(key: String) = AppLanguage.text(context, key)
    private val rows = mutableListOf<TextView>()
    private var secondary: LyricsView? = null
    private var lyrics: LyricsResult? = null
    private var positionMs = 0L
    private var focusPositionMs = 0L
    private var focusAnimator: ValueAnimator? = null
    private var activeIndex = -2
    private var touching = false
    private var liveCaption = ""
    private var previewIndex = -1
    private var browsing = false
    private var touchStartY = 0f
    private var doubleTapped = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val scaleGesture = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            fontScale = (fontScale * detector.scaleFactor).coerceIn(0.65f, 1.35f)
            fontPreferences.edit().putFloat("lyrics_font_scale", fontScale).apply()
            applyTextSize()
            return true
        }
    })
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
            scaleGesture.onTouchEvent(event)
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
        clipChildren = false
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        clipToPadding = false
        addView(content, ViewGroup.LayoutParams(-1, -2))
    }
    private val timeLabel = TextView(context).apply {
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(16f), 0, dp(16f), 0)
        minWidth = dp(80f)
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
        focusAnimator?.cancel()
        focusPositionMs = positionMs
        activeIndex = -2
        resetPreview()
        content.removeAllViews()
        rows.clear()
        when {
            value == null -> message(t("no.lyrics"))
            value.instrumental -> message(t("instrumental"), allowCorrection = true)
            value.synchronized -> value.lines.forEach { line ->
                val row = RippleLyricText(context).apply {
                    text = line.text
                    textSize = baseTextSize()
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

    private fun baseTextSize() = 15f * fontScale

    private fun applyTextSize() {
        rows.forEach { it.textSize = baseTextSize() }
        secondary?.applyTextSize()
        content.requestLayout()
    }

    fun setDualLyrics(left: LyricsResult?, right: LyricsResult?, leftEdit: () -> Unit, rightEdit: () -> Unit) {
        onManual = leftEdit
        setLyrics(left)
        val other = secondary ?: LyricsView(context).also {
            secondary = it
            addView(it, LayoutParams(0, -1, Gravity.END))
        }
        other.dark = dark
        other.fullScreen = fullScreen
        other.seekEnabled = seekEnabled
        other.onManual = rightEdit
        other.onSeek = { onSeek?.invoke(it) }
        other.onExpand = { onExpand?.invoke() }
        other.setLyrics(right)
        other.setPosition(positionMs, true)
        updateColumns(width)
    }

    fun clearDualLyrics() {
        secondary?.let { removeView(it) }
        secondary = null
        scroller.layoutParams = (scroller.layoutParams as LayoutParams).apply { width = -1 }
        timeLabel.layoutParams = (timeLabel.layoutParams as LayoutParams).apply { rightMargin = dp(8f) }
    }

    private fun updateColumns(width: Int) {
        if (secondary == null || width <= 0) return
        scroller.layoutParams = (scroller.layoutParams as LayoutParams).apply { this.width = width / 2 }
        secondary?.layoutParams = (secondary?.layoutParams as LayoutParams).apply { this.width = width - width / 2 }
        timeLabel.layoutParams = (timeLabel.layoutParams as LayoutParams).apply { rightMargin = width - width / 2 + dp(8f) }
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
        secondary?.setPosition(positionMs, force)
        val previous = this.positionMs
        this.positionMs = positionMs.coerceAtLeast(0)
        val lines = lyrics?.lines ?: return
        val index = lines.indexOfLast { it.timeMs <= this.positionMs }
        if (index != activeIndex) {
            activeIndex = index
            applyColors()
        }
        focusAnimator?.cancel()
        if (!force && this.positionMs > previous && this.positionMs - previous <= 500) {
            focusAnimator = ValueAnimator.ofFloat(focusPositionMs.toFloat(), this.positionMs.toFloat()).apply {
                duration = 200
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener {
                    focusPositionMs = (it.animatedValue as Float).toLong()
                    updateFocus()
                }
                start()
            }
        } else {
            focusPositionMs = this.positionMs
            updateFocus()
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
        updateColumns(w)
        updatePadding()
        post {
            updatePadding()
            scrollToLine(activeIndex, false)
        }
    }

    override fun onDetachedFromWindow() {
        focusAnimator?.cancel()
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
        timeLabel.background = null
        timeLabel.setPadding(dp(16f), 0, dp(16f), 0)
        timeLabel.setTextColor(p.accent)
        timeLabel.setShadowLayer(dp(3f).toFloat(), 0f, 0f, p.surface)
        rows.forEachIndexed { index, row ->
            (row as? RippleLyricText)?.clearRipple()
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
            (row.text as? Spannable)?.let { text ->
                text.getSpans(0, text.length, ForegroundColorSpan::class.java).forEach(text::removeSpan)
                text.getSpans(0, text.length, LyricGlowSpan::class.java).forEach(text::removeSpan)
            }
        }
        if (rows.isEmpty()) {
            (content.getChildAt(0) as? TextView)?.setTextColor(p.sub)
        }
    }

    private fun updateFocus() {
        val lines = lyrics?.lines ?: return
        val row = rows.getOrNull(activeIndex) ?: return
        val line = lines.getOrNull(activeIndex) ?: return
        val progress = lyricLineProgress(line.timeMs, lines.getOrNull(activeIndex + 1)?.timeMs, focusPositionMs) ?: return
        if (liveCaption.isNotEmpty() || line.text.isEmpty()) return
        if (row.text !is Spannable) row.setText(line.text, TextView.BufferType.SPANNABLE)
        val text = row.text as Spannable
        text.getSpans(0, text.length, ForegroundColorSpan::class.java).forEach(text::removeSpan)
        text.getSpans(0, text.length, LyricGlowSpan::class.java).forEach(text::removeSpan)
        val iterator = BreakIterator.getCharacterInstance().apply { setText(line.text) }
        val ranges = mutableListOf<Pair<Int, Int>>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            ranges += start to end
            start = end
            end = iterator.next()
        }
        val center = progress * ranges.size
        val focused = floor(center).toInt().coerceIn(0, ranges.lastIndex)
        (row as? RippleLyricText)?.showRipple(ranges, focused, lyricRipplePhase(progress, ranges.size), palette.accent)
        ranges.forEachIndexed { index, (from, to) ->
            val distance = abs(index + 0.5f - center)
            val amount = (1f - distance / 1.5f).coerceIn(0f, 1f)
            val base = if (index + 0.5f < center) palette.accent else ColorUtils.blendARGB(palette.accent, palette.sub, 0.55f)
            text.setSpan(ForegroundColorSpan(ColorUtils.blendARGB(base, palette.text, amount)), from, to, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (amount > 0f) text.setSpan(LyricGlowSpan(palette.accent, dp(5f) * amount), from, to, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        row.invalidate()
    }

    private fun updatePadding() {
        val compact = rows.isEmpty()
        val topPadding = if (compact) dp(8f) else ((height - rows.first().height) / 2).coerceAtLeast(0)
        val bottomPadding = if (compact) dp(8f) else dp(24f)
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

private class LyricGlowSpan(private val color: Int, private val radius: Float) : CharacterStyle() {
    override fun updateDrawState(paint: TextPaint) {
        paint.setShadowLayer(radius.coerceAtLeast(0.01f), 0f, 0f, color)
    }
}

private class RippleLyricText(context: Context) : TextView(context) {
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var ranges: List<Pair<Int, Int>> = emptyList()
    private var focused = -1
    private var ripplePhase = 0f
    private var rippleColor = Color.TRANSPARENT

    fun showRipple(ranges: List<Pair<Int, Int>>, focused: Int, phase: Float, color: Int) {
        this.ranges = ranges
        this.focused = focused
        ripplePhase = phase
        rippleColor = color
        invalidate()
    }

    fun clearRipple() {
        focused = -1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val textLayout = layout
        if (focused >= 0 && textLayout != null) {
            for (character in (focused - 1).coerceAtLeast(0)..focused) {
                val (start, end) = ranges.getOrNull(character) ?: continue
                if (end > text.length) continue
                val line = textLayout.getLineForOffset(start)
                if (line >= maxLines || textLayout.getLineForOffset((end - 1).coerceAtLeast(start)) != line) continue
                val x = compoundPaddingLeft + (textLayout.getPrimaryHorizontal(start) + textLayout.getPrimaryHorizontal(end)) / 2f
                val y = extendedPaddingTop + (textLayout.getLineTop(line) + textLayout.getLineBottom(line)) / 2f
                val density = resources.displayMetrics.density
                val age = ((focused - character + ripplePhase) / 1.5f).coerceIn(0f, 1f)
                val radius = (4f + 16f * age) * density
                ripplePaint.color = rippleColor
                ripplePaint.style = Paint.Style.STROKE
                ripplePaint.strokeWidth = (1.5f - age * 0.6f) * density
                ripplePaint.alpha = ((1f - age) * 135).toInt()
                canvas.drawCircle(x, y, radius * 0.75f, ripplePaint)
                ripplePaint.style = Paint.Style.FILL
                repeat(12) { index ->
                    val seed = (character + 1) * 31 + index * 71
                    val angle = seed * 2.3999632297
                    val distance = radius * (0.45f + (seed % 11) / 12f)
                    val size = (1f + (seed % 7) * 0.32f) * (1f - age) * density
                    ripplePaint.alpha = ((1f - age) * (140 + seed % 100)).toInt().coerceIn(0, 255)
                    canvas.drawCircle(x + cos(angle).toFloat() * distance,
                        y + sin(angle).toFloat() * distance, size, ripplePaint)
                }
            }
        }
        super.onDraw(canvas)
    }
}
