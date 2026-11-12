package j5i.spatial8d.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.LinearGradient
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.graphics.drawable.ColorDrawable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import j5i.spatial8d.audio.LyricsResult
import j5i.spatial8d.audio.LyricLine
import j5i.spatial8d.visual.PerfTrace
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** 本地 PCM 可视化，不增加原生依赖；组合图表与独占动效共用同一份输出样本。 */
class VisualizerView(context: Context) : LinearLayout(context) {
    val pcmClock = PcmVisualClock()
    private fun t(key: String) = AppLanguage.text(context, key)
    var onExpand: (() -> Unit)? = null
    var onChooseEffect: (() -> Unit)? = null
    var dark = true
        set(value) { field = value; canvas.dark = value; restyle() }
    private val canvas = AudioCanvas(context, pcmClock)
    private val creativePreferences = context.getSharedPreferences("visual_creative", Context.MODE_PRIVATE)
    private var creativeBackground = creativePreferences.getString("background", null)
    internal var creative = runCatching { VisualCreative.decode(creativePreferences.getString("settings", "") ?: "") }.getOrDefault(VisualCreative())
        private set
    internal fun setCreative(value: VisualCreative) {
        creative = value
        creativePreferences.edit().putString("settings", value.encode()).apply()
        canvas.creative = value
        canvas.invalidate()
    }
    internal fun setCreativeBackground(uri: String?) {
        creativeBackground = uri
        creativePreferences.edit().putString("background", uri).apply()
        if (uri == null) { canvas.creativeBackground = null; canvas.creativeBackgroundBlurred = null; canvas.invalidate(); return }
        Thread {
            val image = loadCreativeBackground(context, uri)
            canvas.post {
                if (creativeBackground == uri) {
                    canvas.creativeBackground = image
                    canvas.creativeBackgroundBlurred = image?.let(::softBlur)
                    canvas.invalidate()
                }
            }
        }.start()
    }

    private fun loadCreativeBackground(context: Context, uri: String): Bitmap? = runCatching {
        val parsed = android.net.Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        context.contentResolver.openInputStream(parsed)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()
    private fun softBlur(bitmap: Bitmap): Bitmap {
        val reduced = Bitmap.createScaledBitmap(bitmap, (bitmap.width / 7).coerceAtLeast(1),
            (bitmap.height / 7).coerceAtLeast(1), true)
        val blurred = Bitmap.createScaledBitmap(reduced, bitmap.width, bitmap.height, true)
        if (reduced !== bitmap && reduced !== blurred) reduced.recycle()
        return blurred
    }
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            onExpand?.invoke()
            return true
        }

    })
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var swipeHandled = false
    private var gestureTouchStart = 0
    private var lastTouchAt = 0L
    private var dragRecorded = false
    private val touches = ArrayList<VisualTouch>()
    private var visualPositionMs = 0L
    private var visualTrackKey: String? = null
    fun setVisualTrack(key: String?) {
        if (visualTrackKey == key) return
        visualTrackKey = key
        touches.clear()
        canvas.visualTouches = emptyList()
    }
    private fun handleCanvasTouch(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                swipeHandled = false
                dragRecorded = false
                gestureTouchStart = touches.size
                lastTouchAt = 0L
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - touchStartX
                val dy = event.y - touchStartY
                if (!swipeHandled && isHorizontalVisualSwipe(dx, dy, dp(48f).toFloat())) {
                    val next = if (drivingLyrics) true to nextVisualMode(visualMode, dx < 0f)
                    else nextVisualizerState(exclusive, visualMode, dx < 0f)
                    exclusive = next.first
                    visualMode = next.second
                    singleModes.fill(false)
                    if (!exclusive) layers.fill(true)
                    updateMode()
                    swipeHandled = true
                    while (touches.size > gestureTouchStart) touches.removeAt(touches.lastIndex)
                    canvas.visualTouches = touches.toList()
                } else if (!swipeHandled && abs(dy) > dp(12f) && abs(dy) > abs(dx) * 1.25f) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                if (!swipeHandled && (abs(dx) > dp(10f) || abs(dy) > dp(10f)) &&
                    (lastTouchAt == 0L || event.eventTime - lastTouchAt >= 45L) && canvas.width > 0 && canvas.height > 0) {
                    if (touches.size >= 500) touches.removeAt(0)
                    touches.add(VisualTouch((event.x / canvas.width).coerceIn(0f, 1f),
                        (event.y / canvas.height).coerceIn(0f, 1f), visualPositionMs, drag = true))
                    canvas.visualTouches = touches.toList()
                    dragRecorded = true
                    lastTouchAt = event.eventTime
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!swipeHandled && !dragRecorded && abs(event.x - touchStartX) < dp(12f) && abs(event.y - touchStartY) < dp(12f) && canvas.width > 0 && canvas.height > 0) {
                    if (touches.size >= 500) touches.removeAt(0)
                    touches.add(VisualTouch((event.x / canvas.width).coerceIn(0f, 1f), (event.y / canvas.height).coerceIn(0f, 1f), visualPositionMs))
                    canvas.visualTouches = touches.toList()
                    canvas.invalidate()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
    private val graphView = WaveformView(context)
    private val lyricOverlay = LyricOverlay(context, pcmClock)
    private var exclusive = false
    private var visualMode = 0
    private val layers = booleanArrayOf(true, true, true, true)
    private val singleModes = booleanArrayOf(false, false, false, false)
    private val effectButton = button(t("visual.choose")) { onChooseEffect?.invoke() }
    private val hideControls = Runnable {
        effectButton.animate().alpha(0f).setDuration(600).withEndAction {
            effectButton.visibility = View.INVISIBLE
        }.start()
    }
    var fullScreen = false
        set(value) {
            field = value
            removeCallbacks(hideControls)
            effectButton.animate().cancel()
            effectButton.alpha = 1f
            canvas.immersive = value
            canvas.invalidate()
            effectButton.visibility = if (value) View.VISIBLE else View.INVISIBLE
            if (value) postDelayed(hideControls, 3500L)
        }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (fullScreen && event.actionMasked == MotionEvent.ACTION_DOWN) {
            effectButton.animate().cancel()
            effectButton.alpha = 1f
            effectButton.visibility = View.VISIBLE
            removeCallbacks(hideControls)
            postDelayed(hideControls, 3500L)
        }
        return super.dispatchTouchEvent(event)
    }
    var drivingLyrics = false
        set(value) { field = value; updateMode() }
    val showsLyrics: Boolean get() = exclusive && visualMode != 1

    init {
        orientation = VERTICAL
        val stage = FrameLayout(context)
        addView(stage, LayoutParams(-1, 0, 1f))
        stage.addView(canvas, FrameLayout.LayoutParams(-1, -1))
        canvas.setOnTouchListener { _, event -> handleCanvasTouch(event) }
        graphView.setOnTouchListener { _, event -> handleCanvasTouch(event) }
        stage.addView(graphView, FrameLayout.LayoutParams(-1, -1))
        stage.addView(lyricOverlay, FrameLayout.LayoutParams(-1, -1))
        stage.addView(effectButton, FrameLayout.LayoutParams(-2, dp(44f), Gravity.TOP or Gravity.END).apply {
            rightMargin = dp(4f); topMargin = dp(2f)
        })
        updateMode()
        canvas.creative = creative
        creativeBackground?.let { setCreativeBackground(it) }
        effectButton.visibility = View.INVISIBLE
    }

    fun accept(data: ByteArray, bytes: Int) {
        // 输出线程只生成不可变 PCM 快照；主线程永远只消费最新一包，不排队积压。
        val chunk = data.copyOf(bytes)
        pendingPcm.set(chunk)
        receivedPcm++
        schedulePcm()
    }
    private val pendingPcm = java.util.concurrent.atomic.AtomicReference<ByteArray?>(null)
    private val renderPosted = java.util.concurrent.atomic.AtomicBoolean(false)
    private var receivedPcm = 0L
    private var consumedPcm = 0L
    private var lastDiagnosticAt = 0L
    val visualState: Pair<Boolean, Int> get() = exclusive to visualMode
    val analysisLayers: BooleanArray get() = layers.copyOf()
    fun toggleAnalysisLayer(index: Int) {
        if (index !in layers.indices) return
        layers[index] = !layers[index]
        exclusive = false
        singleModes.fill(false)
        updateMode()
    }
    val singleVisualLayer: Int get() = singleModes.indexOfFirst { it }
    internal data class ExportState(
        val exclusive: Boolean, val mode: Int, val layers: BooleanArray,
        val single: Int, val dark: Boolean, val palette: ThemeUi.Palette, val creative: VisualCreative, val touches: List<VisualTouch>, val background: String?
    )
    internal fun exportState() = ExportState(exclusive, visualMode, layers.copyOf(), singleVisualLayer,
        dark, ThemeUi.palette(dark), creative, touches.toList(), creativeBackground)

    internal fun prepareExport(state: ExportState, lyrics: LyricsResult?, width: Int, height: Int, sampleRate: Int) {
        dark = state.dark
        state.layers.copyInto(layers)
        restoreVisualState(state.exclusive to state.mode, state.single)
        canvas.exportPalette = state.palette
        canvas.creative = state.creative
        canvas.visualTouches = state.touches
        creativeBackground = state.background
        canvas.creativeBackground = state.background?.let { loadCreativeBackground(context, it) }
        canvas.creativeBackgroundBlurred = canvas.creativeBackground?.let(::softBlur)
        canvas.exporting = true
        canvas.immersive = true
        canvas.sampleRate = sampleRate
        effectButton.visibility = View.GONE
        setLyrics(lyrics)
        measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        layout(0, 0, width, height)
    }

    internal fun drawExportFrame(target: Canvas, data: ByteArray, bytes: Int, timeUs: Long, lyricPositionMs: Long) {
        pcmClock.acceptOffline(data, bytes, timeUs)
        canvas.acceptOffline(data, bytes)
        if (graphView.visibility == View.VISIBLE) graphView.accept(data, bytes)
        lyricOverlay.setPosition(lyricPositionMs)
        canvas.visualPositionMs = lyricPositionMs
        target.drawColor((canvas.exportPalette ?: ThemeUi.palette(dark)).surface)
        draw(target)
    }
    fun restoreVisualState(state: Pair<Boolean, Int>, single: Int = -1) {
        exclusive = state.first
        visualMode = state.second.takeIf { mode -> exclusiveVisualModes.any { it.second == mode } } ?: 3
        if (single in singleModes.indices) {
            singleModes.fill(false)
            singleModes[single] = true
        }
        updateMode()
    }
    private val consumePcm = object : Runnable {
        override fun run() {
            renderPosted.set(false)
            val latest = pendingPcm.getAndSet(null)
            if (latest != null && isAttachedToWindow) {
                canvas.accept(latest, latest.size)
                graphView.accept(latest, latest.size)
                consumedPcm++
            }
            diagnoseRender()
            // 消费期间到达的新包必须重新排队，不能因旧标记而永久滞留。
            if (pendingPcm.get() != null) schedulePcm()
        }
    }

    private fun schedulePcm() {
        if (!isAttachedToWindow || renderPosted.getAndSet(true)) return
        post(consumePcm)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        schedulePcm()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideControls)
        removeCallbacks(consumePcm)
        renderPosted.set(false)
        pendingPcm.set(null)
        super.onDetachedFromWindow()
    }

    private fun diagnoseRender() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDiagnosticAt < 3_000) return
        lastDiagnosticAt = now
        Log.i("8DVisual", "view=$width x $height shown=$isShown attached=$isAttachedToWindow " +
            "canvas=${canvas.visibility}/${canvas.width}x${canvas.height} draws=${canvas.drawCount} " +
            "graph=${graphView.visibility}/${graphView.width}x${graphView.height} pcm=$receivedPcm/$consumedPcm")
    }

    /** 当前歌曲歌词与播放位置，用于在穿梭模式中叠加逐行歌词字幕。 */
    fun setLyrics(result: LyricsResult?) {
        lyricOverlay.setLyrics(result)
        updateMode()
    }
    fun setPosition(positionMs: Long) {
        visualPositionMs = positionMs
        canvas.visualPositionMs = positionMs
        lyricOverlay.setPosition(positionMs)
    }

    private fun updateMode() {
        canvas.mode = if (!exclusive) 0 else visualMode
        canvas.layers = layers.copyOf()
        canvas.singleLayer = singleVisualLayer
        graphView.dark = dark
        graphView.visibility = if (exclusive && visualMode == 4) View.VISIBLE else View.GONE
        canvas.visibility = if (graphView.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        lyricOverlay.dark = dark
        lyricOverlay.visibility = if (showsLyrics && !drivingLyrics) View.VISIBLE else View.GONE
        if (showsLyrics) lyricOverlay.bringToFront()
        canvas.invalidate()
        effectButton.bringToFront()
        restyle()
    }

    private fun button(label: String, action: () -> Unit) = Button(context).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        maxLines = 1
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        includeFontPadding = false
        setPadding(dp(8f), 0, dp(8f), 0)
        background = ColorDrawable(Color.TRANSPARENT)
        stateListAnimator = null
        setOnClickListener { action() }
        gravity = Gravity.CENTER
        layoutParams = LayoutParams(-2, dp(44f))
    }

    private fun restyle() {
        val palette = ThemeUi.palette(dark)
        effectButton.setTextColor(palette.text)
        ThemeUi.bg(effectButton, palette.surface, 8f)
    }

    private fun dp(value: Float) = ThemeUi.dp(context, value)

    private class AudioCanvas(context: Context, private val pcmClock: PcmVisualClock) : View(context) {
        var dark = true
        var mode = 0
        var sampleRate = 44100
        var immersive = false
        var exportPalette: ThemeUi.Palette? = null
        var creative = VisualCreative()
        var visualTouches: List<VisualTouch> = emptyList()
        var visualPositionMs = 0L
        var creativeBackground: Bitmap? = null
        var creativeBackgroundBlurred: Bitmap? = null
        var exporting = false
        private val creativePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        var layers = BooleanArray(4) { true }
        var singleLayer = -1
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val history = ShortArray(2048 * 2)
        private var total = 0L
        private var rms = .0
        private var peak = .0
        private var correlation = .0
        private var phase = 0f
        private val spectrum = DoubleArray(256)
        private val spectrogramRows = ArrayDeque<IntArray>()
        private var spectrogramGeneration = pcmClock.generation
        private var lastSpectrogramAt = 0L
        private val particlePaths = Array(4) { Path() }
        private val travelPaths = Array(4) { Path() }
        private val dreamPaths = Array(6) { Path() }
        private val dreamStreaks = Path()
        private val dreamRandom = java.util.Random(970813L)
        private val dreamAngle = FloatArray(1200) { dreamRandom.nextFloat() * 2f * PI.toFloat() }
        private val dreamDepth = FloatArray(1200) { dreamRandom.nextFloat() }
        private val dreamOffset = FloatArray(1200) { dreamRandom.nextFloat() * 2f - 1f }
        private val dreamPointA = FloatArray(2)
        private val dreamPointB = FloatArray(2)
        private val terrainPaths = Array(15) { Path() }
        private val terrainCrossPath = Path()
        private val terrainHistory = SpectrumTerrainHistory()
        private val waterPaths = Array(4) { Path() }
        private var waterShader: Shader? = null
        private var waterShaderWidth = 0
        private val stringPaths = Array(18) { Path() }
        private val referencePath = Path()
        private val additionalVisuals = AdditionalVisuals(context)
        private val ambientRandom = java.util.Random(8108L)
        private val galaxyX = FloatArray(900) { ambientRandom.nextFloat() }
        private val galaxySpread = FloatArray(900) { (ambientRandom.nextGaussian() * .085).toFloat() }
        private val galaxyPhase = FloatArray(900) { ambientRandom.nextFloat() * 6.28f }
        private val starX = FloatArray(140) { ambientRandom.nextFloat() }
        private val starY = FloatArray(140) { ambientRandom.nextFloat() }
        private val starPhase = FloatArray(140) { ambientRandom.nextFloat() * 6.28f }
        private val constellationX = FloatArray(36)
        private val constellationY = FloatArray(36)
        private val rainX = FloatArray(150) { (it % 50 + .5f) / 50f }
        private val rainOffset = FloatArray(150) { ambientRandom.nextFloat() }
        private val rainSpeed = FloatArray(150) { .12f + ambientRandom.nextFloat() * .16f }
        private val splashAge = FloatArray(150) { 1f }
        private val effectBounds = RectF()
        private val visibleBounds = Rect()
        private val effectHsv = FloatArray(3)
        private var effectTime = 0.0
        private var mobiusTravel = 0.0
        private var mobiusSpeed = .18f
        private var seasonEnergy = 0f
        private val seasonGrid = Array(64) { FloatArray(97) }
        private var frameSeconds = 0f
        private data class TravelBurst(val x: Float, val y: Float, val radius: Float, val group: Int, val startedAt: Long)
        private val travelBursts = mutableListOf<TravelBurst>()
        private val travelRandom = java.util.Random()
        private val travelPrevious = FloatArray(180) { -1f }
        private val travelExploded = BooleanArray(180)
        private val travelThreshold = FloatArray(180) { .35f + travelRandom.nextFloat() * .45f }
        private var lastTravelBeat = 0L
        private var lastBeatBurstMs = 0L
        private var clockGeneration = pcmClock.generation
        private val sphereBounds = RectF()
        // PCM 写出比实际听感提前约 80ms；每帧只消费已到时间的样本，不能反复读取队首。
        private val visualQueue = ArrayDeque<Pair<Long, ByteArray>>()
        private val delayedSpectrum = DoubleArray(256)
        private var delayedRms = 0f
        private var delayedPeak = 0f
        // 平滑响度（RMS 滑动平均）和基于真实时间的帧推进，避免动画被解码 buffer 节奏拉扯。
        private var smoothRms = 0f
        private var smoothPeak = 0f
        private var lastDrawNs = 0L
        private val analysisLock = Any()
        var drawCount = 0L
            private set

        fun accept(data: ByteArray, bytes: Int) {
            synchronized(analysisLock) {
                visualQueue.addLast(System.nanoTime() + VISUAL_DELAY_NS to data.copyOf(bytes))
                val targetFrames = 44100 * 80 / 1000
                while (visualQueue.size > 1 &&
                    visualQueue.sumOf { it.second.size / 4 } > targetFrames * 2) {
                    visualQueue.removeFirst()
                }
                analyzeImmediate(data, bytes)
            }
        }

        fun acceptOffline(data: ByteArray, bytes: Int) {
            synchronized(analysisLock) {
                analyzeImmediate(data, bytes)
                analyzeDelayed(data.copyOf(bytes))
            }
        }

        private fun consumeDelayedSamples() {
            pcmClock.consume()
            if (clockGeneration != pcmClock.generation) {
                clockGeneration = pcmClock.generation
                visualQueue.clear()
                delayedSpectrum.fill(0.0)
                terrainHistory.clear()
                effectTime = 0.0
                additionalVisuals.reset()
                mobiusTravel = 0.0
                mobiusSpeed = .18f
                seasonEnergy = 0f
                splashAge.fill(1f)
                travelBursts.clear()
                travelPrevious.fill(-1f)
                travelExploded.fill(false)
                lastTravelBeat = 0L
                lastBeatBurstMs = 0L
            }
            val now = System.nanoTime()
            var latest: ByteArray? = null
            while (visualQueue.firstOrNull()?.first?.let { it <= now } == true) {
                latest = visualQueue.removeFirst().second
            }
            latest?.let(::analyzeDelayed)
            delayedRms = pcmClock.rms.toFloat() * creative.intensity / 100f
            delayedPeak = pcmClock.peak.toFloat()
        }

        private fun analyzeImmediate(data: ByteArray, bytes: Int) {
            val startedAt = System.nanoTime()
            val input = ByteBuffer.wrap(data, 0, bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            var energy = .0
            var loudest = .0
            var lr = .0
            var ll = .0
            var rr = .0
            var count = 0
            while (input.remaining() >= 2) {
                val left = input.get().toInt()
                val right = input.get().toInt()
                history[(total % history.size).toInt()] = left.toShort()
                history[((total + 1) % history.size).toInt()] = right.toShort()
                total += 2
                val l = left / 32768.0
                val r = right / 32768.0
                energy += (l * l + r * r) * .5
                loudest = max(loudest, max(abs(l), abs(r)))
                lr += l * r
                ll += l * l
                rr += r * r
                count++
            }
            if (count > 0) {
                rms = sqrt(energy / count)
                peak = loudest
                correlation = (lr / sqrt(max(1e-12, ll * rr))).coerceIn(-1.0, 1.0)
                // 单次 PCM 包约几十毫秒，用一次一阶低通平滑，保留节奏但去掉爆点。
                val alpha = 0.18f
                smoothRms = smoothRms * (1 - alpha) + rms.toFloat() * alpha
                smoothPeak = maxOf(smoothPeak * 0.90f, peak.toFloat())
            }
            analyze()
            if (spectrogramGeneration != pcmClock.generation) {
                spectrogramGeneration = pcmClock.generation
                spectrogramRows.clear()
                lastSpectrogramAt = 0L
            }
            val sampleTime = pcmClock.nowNs()
            if (sampleTime - lastSpectrogramAt >= 100_000_000L) {
                val row = IntArray(128)
                for (x in row.indices) {
                    val level = spectrum[x * 2].toFloat()
                    row[x] = Color.HSVToColor(floatArrayOf(240f - level * 240f, .85f, .12f + level * .88f))
                }
                spectrogramRows.addFirst(row)
                while (spectrogramRows.size > 96) spectrogramRows.removeLast()
                lastSpectrogramAt = sampleTime
            }
            postInvalidateOnAnimation()
            PerfTrace.sample("visual.analyzeImmediateNs", System.nanoTime() - startedAt)
        }

        private fun analyzeDelayed(data: ByteArray) {
            val startedAt = System.nanoTime()
            val input = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val samples = ShortArray(input.remaining())
            input.get(samples)
            val frames = min(samples.size / 2, 1024)
            if (frames == 0) return
            val start = samples.size / 2 - frames
            for (band in delayedSpectrum.indices) {
                val hz = 40.0 * Math.pow(500.0, band / 255.0)
                val coefficient = 2 * cos(2 * PI * hz / sampleRate)
                var s1 = .0
                var s2 = .0
                for (i in 0 until frames) {
                    val s0 = samples[(start + i) * 2] / 32768.0 + coefficient * s1 - s2
                    s2 = s1
                    s1 = s0
                }
                val power = max(1e-18, (s1 * s1 + s2 * s2 - coefficient * s1 * s2) /
                    (frames.toDouble() * frames))
                val db = (10 * ln(power) / ln(10.0)).coerceIn(-100.0, 0.0)
                delayedSpectrum[band] = sqrt(((db + 70) / 70).coerceIn(0.0, 1.0))
            }
            terrainHistory.accept(delayedSpectrum, pcmClock.nowNs())
            PerfTrace.sample("visual.analyzeDelayedNs", System.nanoTime() - startedAt)
        }

        /** 计算自上次绘制以来的真实秒数（首次或长时间挂起时给一帧保守值）。 */
        private fun frameDeltaSeconds(): Float {
            val now = pcmClock.nowNs()
            val dt = if (lastDrawNs == 0L) 1f / 60f else ((now - lastDrawNs) / 1_000_000_000.0).toFloat()
            lastDrawNs = now
            return dt.coerceIn(1f / 240f, 1f / 15f)
        }

        /** Goertzel 对数频点足够驱动图表，避免为显示引入整套 FFT 库。 */
        private fun analyze() {
            val available = min(total / 2, 1024).toInt()
            if (available == 0) return
            val start = ((total - available * 2) % history.size + history.size).toInt() % history.size
            for (band in spectrum.indices) {
                val hz = 40.0 * Math.pow(500.0, band / 255.0)
                val omega = 2 * PI * hz / sampleRate
                val coefficient = 2 * cos(omega)
                var s1 = .0
                var s2 = .0
                for (i in 0 until available) {
                    val sample = history[(start + i * 2) % history.size] / 32768.0
                    val s0 = sample + coefficient * s1 - s2
                    s2 = s1
                    s1 = s0
                }
                // 除以窗长平方做能量归一，否则低频/高频在固定采样数下量级差距悬殊。
                val norm = available.toDouble() * available
                val power = max(1e-18, (s1 * s1 + s2 * s2 - coefficient * s1 * s2) / norm)
                val db = (10 * ln(power) / ln(10.0)).coerceIn(-100.0, 0.0)
                // 用更窄动态范围 + sqrt 拉伸，把低频细节抬起来，避免左侧柱子"看起来没变化"。
                val normalized = ((db + 70) / 70).coerceIn(0.0, 1.0)
                spectrum[band] = sqrt(normalized)
            }
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            drawCount++
            synchronized(analysisLock) {
                consumeDelayedSamples()
                frameSeconds = frameDeltaSeconds()
                if (pcmClock.active) effectTime += frameSeconds
                // 背景与卡片 surface 一致，避免独立"纯黑/纯白"造成视觉割裂。
                val surface = (exportPalette ?: ThemeUi.palette(dark)).surface
                c.drawColor(surface)
                creativeBackground?.let { bitmap ->
                    val scale = max(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
                    val imageWidth = bitmap.width * scale
                    val imageHeight = bitmap.height * scale
                    effectBounds.set((width - imageWidth) / 2f, (height - imageHeight) / 2f,
                        (width + imageWidth) / 2f, (height + imageHeight) / 2f)
                    paint.isFilterBitmap = true
                    val blurAlpha = creative.backgroundBlur * 255 / 24
                    paint.alpha = creative.backgroundAlpha * (255 - blurAlpha) / 100
                    c.drawBitmap(bitmap, null, effectBounds, paint)
                    if (blurAlpha > 0) {
                        creativeBackgroundBlurred?.let { blurred ->
                            paint.alpha = creative.backgroundAlpha * blurAlpha / 100
                            c.drawBitmap(blurred, null, effectBounds, paint)
                        }
                    }
                    if (creative.backgroundMask) {
                        paint.alpha = 255
                        paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(),
                            intArrayOf(0x70000000, 0x00000000, 0x00000000, 0x70000000),
                            floatArrayOf(0f, .22f, .78f, 1f), Shader.TileMode.CLAMP)
                        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                        paint.shader = null
                    }
                }
                paint.style = Paint.Style.FILL
                paint.shader = null
                paint.alpha = 255
                paint.textSize = dp(9f)
                paint.color = (exportPalette ?: ThemeUi.palette(dark)).sub
                val layer = if (creative.hue != 0) {
                    val matrix = ColorMatrix().apply { setRotate(0, creative.hue.toFloat()); postConcat(ColorMatrix().apply { setRotate(1, creative.hue.toFloat()) }); postConcat(ColorMatrix().apply { setRotate(2, creative.hue.toFloat()) }) }
                    creativePaint.colorFilter = ColorMatrixColorFilter(matrix)
                    c.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), creativePaint)
                } else -1
                when (mode) {
                    1 -> drawSpectrogram(c)
                    2 -> drawParticles(c)
                    3 -> drawTravel(c)
                    34 -> drawDream(c)
                    5 -> drawSpectrumTerrain(c)
                    6 -> drawWater(c)
                    7 -> drawStrings(c)
                    8 -> drawStarfield(c)
                    10 -> drawConstellation(c)
                    11 -> drawRadial(c)
                    12 -> drawRibbon(c)
                    13 -> drawRain(c)
                    14 -> drawStarburst(c)
                    15 -> drawFilaments(c)
                    16 -> drawOrbits(c)
                    17 -> drawSeasons(c)
                    in 18..33 -> additionalVisuals.draw(c, width.toFloat(), height.toFloat(), mode, effectTime, delayedSpectrum,
                        delayedRms, beatPulse(), pcmClock.beatAtNs, resources.displayMetrics.density, dark, surface, creative.density / 100f)
                    else -> drawCombined(c)
                }
                if (creative.overlay != 0 && creative.overlay != mode) {
                    additionalVisuals.draw(c, width.toFloat(), height.toFloat(), creative.overlay, effectTime, delayedSpectrum,
                        delayedRms, beatPulse(), pcmClock.beatAtNs, resources.displayMetrics.density, dark, surface, creative.density / 100f)
                }
                if (layer >= 0) c.restoreToCount(layer)
                if (creative.brightness < 100) c.drawColor(Color.argb((100 - creative.brightness) * 255 / 100, 0, 0, 0))
                if (creative.brightness > 100) c.drawColor(Color.argb((creative.brightness - 100) * 128 / 100, 255, 255, 255))
                paint.shader = null
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(1.2f)
                for (touch in visualTouches) {
                    val age = (visualPositionMs - touch.positionMs) / 1200f
                    if (age !in 0f..1f) continue
                    paint.color = effectColor(190f + age * 80f, .5f)
                    paint.alpha = ((1f - age) * 200).toInt()
                    c.drawCircle(touch.x * width, touch.y * height, dp(4f) + age * min(width, height) * .13f, paint)
                }
                visualTouches.filter { it.drag }.takeLast(48).forEach { touch ->
                    val age = (visualPositionMs - touch.positionMs) / 1000f
                    if (age in 0f..1f) {
                        paint.style = Paint.Style.FILL
                        paint.color = effectColor(205f + age * 45f, .5f)
                        paint.alpha = ((1f - age) * 165).toInt()
                        val radius = dp(1.2f + (1f - age) * 1.2f)
                        c.drawCircle(touch.x * width, touch.y * height, radius, paint)
                    }
                }
                paint.style = Paint.Style.FILL
                paint.alpha = 255
                if (exporting && creative.watermark) {
                    paint.shader = null; paint.style = Paint.Style.FILL; paint.alpha = 180
                    paint.color = (exportPalette ?: ThemeUi.palette(dark)).sub
                    val right = creative.watermarkPosition % 2 == 1
                    val bottom = creative.watermarkPosition >= 2
                    paint.textAlign = if (right) Paint.Align.RIGHT else Paint.Align.LEFT; paint.textSize = dp(10f)
                    c.drawText(creative.watermarkText, if (right) width - dp(10f) else dp(10f), if (bottom) height - dp(10f) else dp(36f), paint)
                    paint.textAlign = Paint.Align.LEFT
                }
                val title = when (mode) {
                    1 -> "visual.spectrogram"
                    2 -> "visual.particles"
                    3 -> "visual.travel"
                    34 -> "visual.dream"
                    5 -> "visual.3d.title"
                    6 -> "visual.water.title"
                    7 -> "visual.strings.title"
                    8 -> "visual.stars.title"
                    10 -> "visual.constellation.title"
                    11 -> "visual.radial.title"
                    12 -> "visual.ribbon.title"
                    13 -> "visual.rain.title"
                    14 -> "visual.starburst.title"
                    15 -> "visual.filaments.title"
                    16 -> "visual.orbits.title"
                    17 -> "visual.seasons.title"
                    in 18..33 -> exclusiveVisualModes.first { it.second == mode }.first
                    else -> "analysis"
                }
                drawEffectTitle(c, title)
                if (mode >= 2 && mode != 4 && pcmClock.active && isShown &&
                    getGlobalVisibleRect(visibleBounds)) postInvalidateDelayed(33L)
            }
        }

        private fun drawCombined(c: Canvas) {
            val enabled = if (singleLayer >= 0) listOf(singleLayer) else layers.indices.filter { layers[it] }
            if (enabled.isEmpty()) {
                c.drawText(AppLanguage.text(context, "visual.empty"), dp(14f), height / 2f, paint)
                return
            }
            val chartTop = 0f
            val chartHeight = height - chartTop
            enabled.forEachIndexed { index, layer ->
                val top = chartTop + chartHeight * index / enabled.size
                val bottom = chartTop + chartHeight * (index + 1) / enabled.size
                when (layer) {
                    0 -> drawWave(c, top, bottom)
                    1 -> drawSpectrum(c, top, bottom)
                    2 -> drawLoudness(c, top, bottom)
                    else -> drawPhase(c, top, bottom)
                }
            }
        }

        private fun label(c: Canvas, text: String, bottom: Float) {
            val oldAlign = paint.textAlign
            paint.textAlign = Paint.Align.LEFT
            c.drawText(text, dp(6f), bottom - dp(4f), paint)
            paint.textAlign = oldAlign
        }

        private fun drawWave(c: Canvas, top: Float, bottom: Float) {
            label(c, AppLanguage.text(context, "visual.wave"), bottom)
            val available = min(total / 2, 1024).toInt()
            if (available == 0) return
            // 底部保留说明文字行，波形振幅不侵入标签区域。
            val center = top + (bottom - top - dp(14f)) / 2
            val amplitude = (bottom - top - dp(18f)) * .48f
            // 用近期 peak 做归一化，让弱信号也能撑满波形区；用最小值兜底避免静音时除零。
            val gain = 1f / max(0.15f, smoothPeak * 1.05f)
            for (channel in 0..1) {
                val path = Path()
                paint.color = if (channel == 0) 0xff54a8ff.toInt() else 0xffff916d.toInt()
                paint.strokeWidth = dp(1f)
                paint.style = Paint.Style.STROKE
                for (x in 0 until width) {
                    val frame = (total - available * 2 + x.toLong() * available * 2 / width + channel).toInt()
                    val value = history[(frame % history.size + history.size) % history.size] / 32768f
                    val boosted = (value * gain).coerceIn(-1f, 1f)
                    val y = center - boosted * amplitude
                    if (x == 0) path.moveTo(x.toFloat(), y) else path.lineTo(x.toFloat(), y)
                }
                c.drawPath(path, paint)
            }
            paint.style = Paint.Style.FILL
        }

        private fun drawSpectrum(c: Canvas, top: Float, bottom: Float) {
            label(c, AppLanguage.text(context, "visual.spectrum"), bottom)
            val baseline = bottom - dp(15f)
            val bar = width / 64f
            for (i in 0 until 64) {
                val level = spectrum[i * 4].toFloat()
                paint.color = Color.HSVToColor(floatArrayOf(215f - level * 150f, .75f, .95f))
                c.drawRect(i * bar + 1, baseline - level * (bottom - top - dp(28f)), (i + 1) * bar - 1, baseline, paint)
            }
        }

        private fun drawLoudness(c: Canvas, top: Float, bottom: Float) {
            val db = if (rms > 0) 20 * ln(rms) / ln(10.0) else -80.0
            label(c, String.format(AppLanguage.text(context, "visual.loudness"), db, if (peak > 0) 20 * ln(peak) / ln(10.0) else -80.0), bottom)
            val left = 0f
            val right = width.toFloat()
            val y = top + (bottom - top - dp(18f)) / 2
            paint.color = if (dark) 0xff334455.toInt() else 0xffdbe3eb.toInt()
            c.drawRoundRect(RectF(left, y, right, y + dp(10f)), dp(5f), dp(5f), paint)
            paint.color = if (db > -3) 0xffff665c.toInt() else 0xff50cf91.toInt()
            c.drawRoundRect(RectF(left, y, left + (right - left) * ((db + 60) / 60).coerceIn(0.0, 1.0).toFloat(), y + dp(10f)), dp(5f), dp(5f), paint)
        }

        private fun drawPhase(c: Canvas, top: Float, bottom: Float) {
            label(c, String.format(AppLanguage.text(context, "visual.phase"), correlation), bottom)
            val center = width / 2f
            val y = top + (bottom - top - dp(18f)) / 2
            paint.strokeWidth = dp(2f)
            paint.color = if (dark) 0xff435468.toInt() else 0xffcbd5df.toInt()
            c.drawLine(0f, y, width.toFloat(), y, paint)
            paint.color = if (correlation < 0) 0xffff775f.toInt() else 0xff68c7ff.toInt()
            val x = center + correlation.toFloat() * width / 2f
            c.drawCircle(x, y, dp(6f), paint)
        }

        private fun drawSpectrogram(c: Canvas) {
            val top = 0f
            if (spectrogramGeneration != pcmClock.generation) {
                spectrogramGeneration = pcmClock.generation
                spectrogramRows.clear()
                lastSpectrogramAt = 0L
            }
            val cellW = width / 128f
            val cellH = (height - top) / 96f
            spectrogramRows.forEachIndexed { y, source ->
                for (x in 0 until 128) {
                    paint.color = source[x]
                    c.drawRect(x * cellW, top + y * cellH, (x + 1) * cellW + 1,
                        top + (y + 1) * cellH + 1, paint)
                }
            }
        }

        private fun drawEffectTitle(c: Canvas, key: String) {
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.alpha = 255
            paint.textSize = dp(9f)
            paint.color = (exportPalette ?: ThemeUi.palette(dark)).sub
            paint.textAlign = Paint.Align.LEFT
            paint.setShadowLayer(dp(2f), 0f, 0f, if (dark) Color.BLACK else Color.WHITE)
            c.drawText(AppLanguage.text(context, key), dp(6f), dp(14f), paint)
            paint.clearShadowLayer()
        }

        private fun contentTop(normalDp: Float): Float = 0f

        private fun effectColor(hue: Float, saturation: Float = .65f): Int {
            effectHsv[0] = hue.mod(360f)
            effectHsv[1] = if (dark) saturation else (saturation + .2f).coerceAtMost(1f)
            effectHsv[2] = if (dark) .96f else .66f
            return Color.HSVToColor(effectHsv)
        }

        private fun terrainX(column: Int, age: Int): Float {
            return width / 2f + (column / (terrainHistory.columns - 1f) - .5f) * width
        }

        private fun terrainY(column: Int, age: Int): Float {
            val depth = age / (terrainHistory.rows - 1f)
            val usable = (height - contentTop(60f) - dp(26f)).coerceAtLeast(1f)
            return height - dp(26f) - depth * usable * .72f -
                terrainHistory[age, column] * usable * .24f
        }

        private fun drawSpectrumTerrain(c: Canvas) {
            for (row in terrainHistory.size - 1 downTo 0) {
                val path = terrainPaths[row].apply { rewind() }
                for (column in 0 until terrainHistory.columns) {
                    val x = terrainX(column, row)
                    val y = terrainY(column, row)
                    if (column == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(1.3f - row * .04f)
                paint.color = effectColor(175f + row * 7f)
                paint.alpha = 255 - row * 9
                c.drawPath(path, paint)
            }
            terrainCrossPath.rewind()
            for (column in 0 until terrainHistory.columns step 3) {
                for (row in 0 until terrainHistory.size) {
                    val x = terrainX(column, row)
                    val y = terrainY(column, row)
                    if (row == 0) terrainCrossPath.moveTo(x, y) else terrainCrossPath.lineTo(x, y)
                }
            }
            paint.color = effectColor(198f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(.65f)
            paint.alpha = 95
            c.drawPath(terrainCrossPath, paint)
            paint.style = Paint.Style.FILL
            paint.alpha = 255
        }

        private fun drawStarburst(c: Canvas) {
            val centerX = width * .5f
            val top = contentTop(60f)
            val usable = (height - top).coerceAtLeast(1f)
            val centerY = top + usable * .5f
            val pulse = beatPulse()
            paint.style = Paint.Style.STROKE
            for (index in 0 until 120) {
                val angle = index * 2.0 * PI / 120.0
                val level = delayedSpectrum[index * 255 / 119].toFloat()
                val start = .04f + level * .08f
                val end = (.32f + level * .56f + pulse * .12f).coerceAtMost(1f)
                val dx = cos(angle).toFloat() * width * .53f
                val dy = sin(angle).toFloat() * usable * .53f
                paint.color = effectColor(185f + index % 45 * 2f, .7f)
                paint.alpha = (65 + level * 150 + pulse * 40).toInt().coerceIn(0, 255)
                paint.strokeWidth = dp(.5f + level * 1.4f)
                c.drawLine(centerX + dx * start, centerY + dy * start, centerX + dx * end, centerY + dy * end, paint)
                val particle = ((effectTime * (.13 + index % 5 * .018) + index * .137) % 1.0).toFloat()
                paint.style = Paint.Style.FILL
                c.drawCircle(centerX + dx * particle, centerY + dy * particle, dp(.7f + level * 1.5f), paint)
                paint.style = Paint.Style.STROKE
            }
            paint.style = Paint.Style.FILL
            paint.color = effectColor(195f, .25f)
            paint.alpha = 220
            c.drawCircle(centerX, centerY, dp(3f + pulse * 5f), paint)
            paint.alpha = 255
        }

        private fun drawFilaments(c: Canvas) {
            val top = contentTop(60f)
            val usable = (height - top).coerceAtLeast(1f)
            val pulse = beatPulse()
            for (line in 0 until 24) {
                val level = delayedSpectrum[line * 255 / 23].toFloat()
                val path = referencePath.apply { rewind() }
                val phase = effectTime * (.30 + line % 5 * .07) + line * .7
                val convergence = .15f + .7f * (.5f + .5f * sin(effectTime * .16 + line * .41).toFloat())
                val node = ((effectTime * (.12 + line % 3 * .02) + line * .17) % 1.0).toFloat()
                var nodeX = 0f
                var nodeY = 0f
                for (step in 0..80) {
                    val u = step / 80f
                    val envelope = .35f + abs(u - convergence) * (.7f + line % 3 * .16f)
                    val drift = sin(u * 4.0 - effectTime * .21 + line * .31) * .09
                    val offset = (line - 11.5f) * .012f + sin(effectTime * .17 + line) * .035
                    val wave = sin(u * (6.0 + line % 4) + phase) +
                        .34 * sin(u * (13.0 + line % 3) - phase * .67 + line)
                    val y = (top + usable * (.48 + offset + drift) +
                        wave * usable * envelope * (.08f + line % 6 * .026f + level * .08f)).toFloat()
                    val x = u * width
                    if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    if (step == (node * 80).toInt()) { nodeX = x; nodeY = y }
                }
                paint.color = effectColor(175f + line * 5f, .7f)
                paint.alpha = (45 + level * 110 + pulse * 30).toInt().coerceIn(0, 210)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(.6f + level)
                c.drawPath(path, paint)
                paint.style = Paint.Style.FILL
                paint.alpha = (130 + level * 125).toInt().coerceAtMost(255)
                c.drawCircle(nodeX, nodeY, dp(1f + level * 1.8f), paint)
            }
            paint.alpha = 255
        }

        private fun drawOrbits(c: Canvas) {
            val top = contentTop(60f)
            val usable = (height - top).coerceAtLeast(1f)
            val centerX = width * .5f
            val centerY = top + usable * .5f
            val radius = min(width * .43f, usable * .43f)
            for (orbit in 0 until 16) {
                val level = delayedSpectrum[orbit * 255 / 15].toFloat()
                val tilt = orbit * PI / 16.0 + effectTime * .08
                val path = referencePath.apply { rewind() }
                val node = ((effectTime * (.12 + orbit % 4 * .02) + orbit * .13) % 1.0).toFloat()
                var nodeX = centerX
                var nodeY = centerY
                for (step in 0..96) {
                    val angle = step * 2.0 * PI / 96.0
                    val horizontal = cos(angle) * radius
                    val vertical = sin(angle) * radius * (.18 + orbit % 4 * .07)
                    val x = (centerX + horizontal * cos(tilt) - vertical * sin(tilt)).toFloat()
                    val y = (centerY + horizontal * sin(tilt) + vertical * cos(tilt)).toFloat()
                    if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    if (step == (node * 96).toInt()) { nodeX = x; nodeY = y }
                }
                paint.color = effectColor(180f + orbit * 7f, .65f)
                paint.alpha = (55 + level * 110).toInt().coerceAtMost(200)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(.7f + level)
                c.drawPath(path, paint)
                paint.style = Paint.Style.FILL
                paint.alpha = 220
                c.drawCircle(nodeX, nodeY, dp(1.3f + level * 2f), paint)
            }
            paint.alpha = 255
        }

        private fun drawSeasons(c: Canvas) {
            val top = contentTop(60f)
            val usable = (height - top).coerceAtLeast(1f)
            val targetEnergy = (delayedRms * 5f).coerceIn(0f, 1f)
            seasonEnergy += (targetEnergy - seasonEnergy) * (1f - kotlin.math.exp(-frameSeconds * 2f))
            val season = (effectTime / 20.0) % 4.0
            val hue = when {
                season < 1 -> 150f + season.toFloat() * 50f
                season < 2 -> 200f - (season - 1).toFloat() * 160f
                season < 3 -> 40f + (season - 2).toFloat() * 170f
                else -> 210f - (season - 3).toFloat() * 60f
            }
            fun terrainY(u: Float, depth: Float): Float = top + usable *
                (.98f - depth * .61f - seasonHeight(u, depth, effectTime, seasonEnergy) * .72f)
            for (row in seasonGrid.indices) {
                val depth = row / 63f
                for (column in seasonGrid[row].indices) {
                    seasonGrid[row][column] = terrainY(column / 96f, depth)
                }
            }
            val silhouette = referencePath.apply { rewind() }
            silhouette.moveTo(0f, height.toFloat())
            for (step in 0..96) {
                val u = step / 96f
                silhouette.lineTo(u * width, seasonGrid[63][step])
            }
            silhouette.lineTo(width.toFloat(), height.toFloat())
            silhouette.close()
            paint.style = Paint.Style.FILL
            paint.color = effectColor(hue + 20f, .62f)
            paint.alpha = if (dark) 35 else 22
            c.drawPath(silhouette, paint)
            paint.style = Paint.Style.STROKE
            for (line in seasonGrid.indices) {
                val depth = line / 63f
                val path = referencePath.apply { rewind() }
                for (step in 0..96) {
                    val u = step / 96f
                    val y = seasonGrid[line][step]
                    if (step == 0) path.moveTo(u * width, y) else path.lineTo(u * width, y)
                }
                paint.color = effectColor(hue + depth * 55f + 18f * sin(effectTime * .12 + depth * 4).toFloat(), .67f)
                paint.alpha = (45 + depth * 90).toInt()
                paint.strokeWidth = dp(.5f + (1f - depth) * .3f)
                c.drawPath(path, paint)
            }
            for (line in 0 until 48) {
                val column = line * 96 / 47
                val u = column / 96f
                val path = referencePath.apply { rewind() }
                for (step in seasonGrid.indices) {
                    val y = seasonGrid[step][column]
                    if (step == 0) path.moveTo(u * width, y) else path.lineTo(u * width, y)
                }
                paint.color = effectColor(hue + 20f + line * .6f, .6f)
                paint.alpha = 38
                paint.strokeWidth = dp(.45f)
                c.drawPath(path, paint)
            }
            paint.style = Paint.Style.FILL
            paint.alpha = 255
        }

        private fun drawWater(c: Canvas) {
            if (waterShader == null || waterShaderWidth != width) {
                waterShaderWidth = width
                waterShader = LinearGradient(0f, 0f, width.toFloat().coerceAtLeast(1f), 0f,
                    intArrayOf(0xff8654e8.toInt(), 0xff388fff.toInt(), 0xff34e9dc.toInt(),
                        0xffff86c8.toInt(), 0xffffc45c.toInt()), null, Shader.TileMode.CLAMP)
            }
            val base = height * .50f
            val time = effectTime
            val pulse = beatPulse()
            val energy = (delayedRms * 6f + delayedPeak * .45f + pulse * .3f).coerceIn(0f, 1f)
            val amplitude = height * (.025f + energy * .24f)
            for (layer in 0 until 4) {
                val path = waterPaths[layer].apply { rewind() }
                val phase = time * (.8 + layer * .13) + layer * 1.5
                for (step in 0..192) {
                    val returning = step > 96
                    val u = (if (returning) 192 - step else step) / 96f
                    val shifted = u - time * (.10 + layer * .015)
                    val envelope = (.52 + .30 * sin(shifted * 5.0 + layer * .7) +
                        .18 * sin(shifted * 9.0 - layer)).toFloat().coerceIn(.08f, 1f)
                    val shape = (.55 + .27 * sin(shifted * (9.0 + layer) + phase * .22) +
                        .18 * sin(shifted * 17.0 + layer)).toFloat().coerceAtLeast(.05f)
                    val direction = if (returning) 1f else -1f
                    val y = base + direction * amplitude * envelope * shape * (1f + layer * .12f)
                    if (step == 0) path.moveTo(u * width, y) else path.lineTo(u * width, y)
                }
                path.close()
                paint.style = Paint.Style.FILL
                paint.shader = waterShader
                paint.alpha = if (dark) 38 + layer * 9 else 46 + layer * 8
                c.drawPath(path, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(.65f + layer * .2f)
                paint.strokeCap = Paint.Cap.ROUND
                paint.alpha = (80 + layer * 15 + pulse * 45).toInt().coerceAtMost(220)
                c.drawPath(path, paint)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1f + pulse)
            paint.alpha = (120 + energy * 100).toInt().coerceAtMost(240)
            c.drawLine(0f, base, width.toFloat(), base, paint)
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.strokeCap = Paint.Cap.BUTT
            paint.alpha = 255
        }

        private fun drawStrings(c: Canvas) {
            val count = 18
            val left = 0f
            val right = width.toFloat()
            val top = contentTop(70f)
            val bottom = height.toFloat()
            val time = effectTime
            val pulse = beatPulse()
            for (string in 0 until count) {
                val x = left + (right - left) * string / (count - 1f)
                val band = string * 255 / (count - 1)
                val energy = delayedSpectrum[band].toFloat()
                val strength = (energy * .9f + delayedRms * 2f + pulse * .55f).coerceIn(0f, 1f)
                val recentTouch = visualTouches.lastOrNull { it.drag && visualPositionMs - it.positionMs in 0L..650L }
                val touchInfluence = recentTouch?.let {
                    val distance = abs(it.x * width - x)
                    (1f - distance / (width / 7f)).coerceIn(0f, 1f) *
                        (1f - (visualPositionMs - it.positionMs) / 650f)
                } ?: 0f
                val path = stringPaths[string].apply { rewind() }
                for (step in 0..36) {
                    val fraction = step / 36f
                    val baseDisplacement = stringDisplacement(fraction, time, strength, 1 + string % 4) *
                        min(dp(3f), (right - left) / (count - 1f) * .22f)
                    val localPull = recentTouch?.let { touch ->
                        val verticalFalloff = (1f - abs(fraction - touch.y) * 5f).coerceIn(0f, 1f)
                        val direction = if (touch.x * width >= x) 1f else -1f
                        direction * touchInfluence * verticalFalloff * dp(5f)
                    } ?: 0f
                    val displacement = baseDisplacement + localPull
                    val y = top + fraction * (bottom - top)
                    if (step == 0) path.moveTo(x + displacement, y) else path.lineTo(x + displacement, y)
                }
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(.7f + energy * .8f + pulse * .3f)
                paint.color = effectColor(197f + string * 7f)
                paint.alpha = (100 + strength * 155).toInt().coerceIn(0, 255)
                c.drawPath(path, paint)
                if (strength > .08f) {
                    paint.style = Paint.Style.FILL
                    paint.alpha = (55 + strength * 145).toInt().coerceIn(0, 255)
                    c.drawCircle(x, top + (bottom - top) * (.18f + energy * .6f), dp(1.2f + strength * 2f), paint)
                }
            }
            paint.style = Paint.Style.FILL
            paint.alpha = 255
        }

        private fun drawStarfield(c: Canvas) {
            val time = effectTime
            val drive = (delayedRms * 8f + beatPulse() * .35f).coerceIn(0f, 1f)
            for (index in galaxyX.indices) {
                val x = ((galaxyX[index] + time * .025) % 1.0).toFloat()
                val band = (.72f - x * .43f + galaxySpread[index] + 1f) % 1f
                val twinkle = (.5 + .5 * sin(time * .35 + galaxyPhase[index])).toFloat()
                paint.color = effectColor(if (index % 7 == 0) 36f else 205f + index % 4 * 13f, .28f)
                paint.alpha = (50 + twinkle * 105 + drive * 75).toInt().coerceIn(0, 225)
                c.drawCircle(x * width, band * height, dp(.35f + twinkle * .8f), paint)
            }
            for (i in starX.indices) {
                val twinkle = (.5 + .5 * sin(time * (.55f + i % 5 * .12f) + starPhase[i])).toFloat()
                val depth = .35f + (i % 7) / 9f
                val x = (((starX[i] + time * .012 * depth) % 1.0) * width).toFloat()
                val y = contentTop(68f) + ((starY[i] + sin(time * .06 + starPhase[i]) * .008) *
                    height.coerceAtLeast(1)).toFloat()
                paint.color = effectColor(if (i % 8 == 0) 38f else 200f + i % 4 * 12f, .20f)
                paint.alpha = (35 + twinkle * 85f + drive * 100f).toInt().coerceIn(0, 255)
                val radius = dp(.35f + depth * (.65f + twinkle * .65f + drive * .5f))
                c.drawCircle(x, y, radius, paint)
                if (i % 23 == 0) {
                    paint.alpha /= 3
                    c.drawCircle(x, y, radius * 2.8f, paint)
                    paint.alpha = (100 + drive * 120).toInt().coerceAtMost(255)
                    paint.strokeWidth = dp(.7f)
                    c.drawLine(x - radius * 2f, y, x + radius * 2f, y, paint)
                    c.drawLine(x, y - radius * 2f, x, y + radius * 2f, paint)
                }
            }
            paint.alpha = 255
        }

        private fun drawConstellation(c: Canvas) {
            for (index in constellationX.indices) {
                val source = index * 3 % starX.size
                constellationX[index] = (starX[source] +
                    sin(effectTime * .15 + starPhase[source]).toFloat() * .018f) * width
                constellationY[index] = (starY[source] +
                    sin(effectTime * .13 + starPhase[source]).toFloat() * .018f) * height
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(.65f)
            for (index in constellationX.indices) {
                val level = delayedSpectrum[(index * 7) % delayedSpectrum.size].toFloat()
                for (other in index + 1 until constellationX.size) {
                    val dx = (constellationX[index] - constellationX[other]) / width.coerceAtLeast(1)
                    val dy = (constellationY[index] - constellationY[other]) / height.coerceAtLeast(1)
                    val distance = dx * dx + dy * dy
                    val cycle = effectTime * .22 + index * 1.73 + other * 2.19
                    val visibility = ((sin(cycle) + .1) / 1.1).toFloat().coerceIn(0f, 1f)
                    if (distance < .045f && visibility > .02f) {
                        paint.color = effectColor(205f + index * 3f, .42f)
                        paint.alpha = ((45 + level * 150 + beatPulse() * 30) * visibility).toInt().coerceIn(0, 220)
                        c.drawLine(constellationX[index], constellationY[index], constellationX[other], constellationY[other], paint)
                        if ((index * 37 + other) % 9 == 0) {
                            val pulseProgress = ((effectTime * .45 + index * .17 + other * .11) % 1.0).toFloat()
                            val x = constellationX[index] + (constellationX[other] - constellationX[index]) * pulseProgress
                            val y = constellationY[index] + (constellationY[other] - constellationY[index]) * pulseProgress
                            paint.strokeWidth = dp(1.8f)
                            paint.alpha = (240 * visibility).toInt()
                            c.drawLine(constellationX[index], constellationY[index], x, y, paint)
                            paint.style = Paint.Style.FILL
                            c.drawCircle(x, y, dp(2.4f), paint)
                            paint.style = Paint.Style.STROKE
                            paint.strokeWidth = dp(.65f)
                        }
                    }
                }
            }
            paint.style = Paint.Style.FILL
            for (index in constellationX.indices) {
                val level = delayedSpectrum[(index * 7) % delayedSpectrum.size].toFloat()
                paint.color = effectColor(190f + index * 4f, .32f)
                paint.alpha = (80 + level * 175).toInt().coerceIn(0, 255)
                c.drawCircle(constellationX[index], constellationY[index], dp(1f + level * 2.4f), paint)
                paint.alpha /= 4
                c.drawCircle(constellationX[index], constellationY[index], dp(3f + level * 3f), paint)
            }
            paint.alpha = 255
        }

        private fun drawRadial(c: Canvas) {
            val centerX = width / 2f
            val usable = (height - contentTop(66f) - dp(24f)).coerceAtLeast(1f)
            val centerY = contentTop(66f) + usable * .5f
            val pulse = beatPulse()
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            for (index in 0 until 96) {
                val angle = index * 2f * PI.toFloat() / 96f - PI.toFloat() / 2f
                val level = delayedSpectrum[index * 255 / 95].toFloat()
                val inner = .12f + pulse * .02f
                val outer = .58f + level * .42f
                val radiusX = width * .47f
                val radiusY = usable * .47f
                paint.strokeWidth = dp(1f + level * 2f)
                paint.color = effectColor(185f + index * 1.7f, .7f)
                paint.alpha = (80 + level * 175).toInt().coerceIn(0, 255)
                c.drawLine(centerX + cos(angle) * radiusX * inner, centerY + sin(angle) * radiusY * inner,
                    centerX + cos(angle) * radiusX * outer, centerY + sin(angle) * radiusY * outer, paint)
            }
            paint.strokeCap = Paint.Cap.BUTT
            paint.style = Paint.Style.FILL
            paint.alpha = 255
        }

        private fun drawRibbon(c: Canvas) {
            val usable = (height - contentTop(66f) - dp(24f)).coerceAtLeast(1f)
            val radius = min(width * .40f, usable * .49f)
            val rotation = effectTime * .22
            val drive = (delayedRms * 5f + beatPulse() * .3f).coerceIn(0f, 1f)
            for (stripe in 0..12) {
                val offset = (stripe / 12f - .5f) * (.62f + drive * .12f)
                val path = referencePath.apply { rewind() }
                for (step in 0..160) {
                    val angle = step * 2.0 * PI / 160.0
                    val radial = 1.0 + offset * cos(angle / 2.0)
                    val px = radial * cos(angle)
                    val py = radial * sin(angle)
                    val pz = offset * sin(angle / 2.0)
                    val rotatedX = px * cos(rotation) + pz * sin(rotation)
                    val rotatedZ = -px * sin(rotation) + pz * cos(rotation)
                    val x = (width * .5 + radius * rotatedX).toFloat()
                    val y = (contentTop(66f) + usable * .5 + radius * (py * .55 + rotatedZ * .55)).toFloat()
                    if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(if (stripe == 0 || stripe == 12) 1.8f else .8f)
                paint.color = effectColor(175f + stripe * 10f + drive * 25f)
                paint.alpha = (100 + drive * 100).toInt().coerceIn(0, 255)
                c.drawPath(path, paint)
            }
            paint.style = Paint.Style.FILL
            val targetSpeed = .14f + (delayedRms * 3.5f).coerceIn(0f, .45f) + beatPulse() * .45f
            mobiusSpeed += (targetSpeed - mobiusSpeed) * (1f - kotlin.math.exp(-frameSeconds * 4f))
            if (pcmClock.active) mobiusTravel += frameSeconds * mobiusSpeed
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = dp(11f + drive * 4f)
            for (note in 0 until 3) {
                val angle = (mobiusTravel + note / 3.0) * 2.0 * PI
                val px = cos(angle)
                val py = sin(angle)
                val rotatedX = px * cos(rotation)
                val rotatedZ = -px * sin(rotation)
                val x = (width * .5 + radius * rotatedX).toFloat()
                val y = (contentTop(66f) + usable * .5 + radius * (py * .55 + rotatedZ * .55)).toFloat()
                paint.color = effectColor(175f + note * 18f, .35f)
                paint.alpha = (170 + drive * 85).toInt().coerceIn(0, 255)
                c.drawText(if (note % 2 == 0) "♪" else "♫", x, y, paint)
            }
            paint.textAlign = Paint.Align.LEFT
            paint.alpha = 255
        }

        private fun drawRain(c: Canvas) {
            val drive = (delayedRms * 9f + beatPulse() * .5f).coerceIn(0f, 1f)
            val top = contentTop(76f)
            val floor = height.toFloat()
            val fallHeight = (floor - top).coerceAtLeast(1f)
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = ThemeUi.palette(dark).sub
            paint.alpha = 120
            paint.strokeWidth = dp(1f)
            c.drawLine(0f, top, width.toFloat(), top, paint)
            for (drop in rainX.indices) {
                val energy = delayedSpectrum[(rainX[drop] * 255).toInt()].toFloat()
                if (pcmClock.active) {
                    splashAge[drop] += frameSeconds
                    rainOffset[drop] += frameSeconds * rainSpeed[drop] * (.55f + delayedRms * 1.4f)
                    if (rainOffset[drop] >= 1f) {
                        rainOffset[drop] %= 1f
                        splashAge[drop] = 0f
                    }
                }
                val phase = rainOffset[drop]
                val y = top + phase * phase * fallHeight
                val length = min(y - top, dp(3f + phase * (8f + energy * 4f)))
                val x = rainX[drop] * width
                paint.strokeWidth = dp(.55f + rainSpeed[drop] * .7f)
                paint.color = effectColor(198f + (drop % 4) * 12f, .42f)
                paint.alpha = (45 + energy * 100 + drive * 95).toInt().coerceIn(0, 230)
                c.drawLine(x, y - length, x, y, paint)
                paint.style = Paint.Style.FILL
                c.drawCircle(x, y, dp(.65f + phase * .55f), paint)
                paint.style = Paint.Style.STROKE
                val progress = splashAge[drop] / .65f
                if (progress < 1f) {
                    val radius = dp(2f + progress * (10f + drive * 10f))
                    paint.alpha = ((1f - progress) * (100 + energy * 130)).toInt().coerceIn(0, 230)
                    effectBounds.set(x - radius, floor - radius * .22f, x + radius, floor + radius * .22f)
                    c.drawOval(effectBounds, paint)
                }
            }
            paint.strokeCap = Paint.Cap.BUTT
            paint.style = Paint.Style.FILL
            paint.alpha = 255
        }

        private fun beatPulse(): Float {
            val beat = pcmClock.beatAtNs
            if (beat == 0L) return 0f
            val age = (pcmClock.nowNs() - beat) / 1_000_000_000f
            return if (age in 0f..0.3f) (1f - age / .3f) else 0f
        }

        private fun drawParticles(c: Canvas) {
            // 整体强度由响度驱动：安静时几乎静止，起伏时粒子立刻反应。
            val drive = (delayedRms * 9f + delayedPeak * 1.5f + beatPulse() * .18f).coerceIn(0f, 1f)
            phase += frameSeconds * (.12f + drive * .9f)
            val cx = width / 2f
            val cy = height / 2f
            val radiusUnit = min(width, height) / 220f
            particlePaths.forEach(Path::rewind)
            for (i in 0 until 320) {
                val angle = i * 2.399963f + phase
                val band = sqrt(delayedSpectrum[i % delayedSpectrum.size].toFloat())
                val baseRadius = (12 + i % 100) * radiusUnit
                // 半径随频带能量扩张，叠加整体响度驱动；静默时收缩到内核。
                val radius = baseRadius * (.35f + drive * .45f + band * (0.6f + drive * 1.3f))
                val x = cx + cos(angle) * radius
                val jump = sin(phase * (2.2f + i % 5 * .12f) + i * .71f) * radiusUnit * (1f + band * (8f + drive * 36f))
                val y = cy + sin(angle) * radius * .65f + jump
                val group = i % particlePaths.size
                val size = dp(.2f + band * (1f + drive * 2.5f))
                particlePaths[group].addCircle(x, y, size, Path.Direction.CW)
            }
            particlePaths.indices.forEach { group ->
                paint.color = Color.HSVToColor(floatArrayOf((group * 70f + phase * 25f) % 360, .72f, .95f))
                paint.alpha = (60 + (drive * 195).toInt()).coerceIn(60, 255)
                paint.style = Paint.Style.FILL
                c.drawPath(particlePaths[group], paint)
            }
        }

        private fun drawDream(c: Canvas) {
            val time = effectTime
            val epoch = (time / DREAM_SCENE_SECONDS).toInt()
            val blend = dreamBlend(time)
            val first = dreamScene(epoch)
            val next = dreamScene(epoch + 1)
            val drive = (delayedRms * 7f + beatPulse() * .3f).coerceIn(0f, 1f)
            val unit = min(width, height).toFloat()
            val centerX = width * (.5f + .08f * sin(time * .11).toFloat())
            val centerY = height * (.5f + .07f * cos(time * .09).toFloat())
            dreamStreaks.rewind()
            for (index in 0 until 300) {
                val progress = ((dreamDepth[index] + time * (.12 + index % 5 * .007)) % 1.0).toFloat()
                val depth = .15f + .85f * progress * progress
                val tail = (progress - .025f - depth * .10f).coerceAtLeast(0f)
                val rayX = cos(dreamAngle[index]) * width * .75f
                val rayY = sin(dreamAngle[index]) * height * .75f
                val tailDepth = .15f + .85f * tail * tail
                dreamStreaks.moveTo(centerX + rayX * tailDepth, centerY + rayY * tailDepth)
                dreamStreaks.lineTo(centerX + rayX * depth, centerY + rayY * depth)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(.7f + drive * .5f)
            paint.color = effectColor((205f + (time * 5).toFloat()).mod(360f), .45f)
            paint.alpha = (100 + drive * 80).toInt()
            c.drawPath(dreamStreaks, paint)
            for (line in 0 until 16) {
                val strand = referencePath.apply { rewind() }
                val strandDepth = line / 15f
                for (step in 0..96) {
                    val progress = step / 96f
                    dreamLinePosition(first, line, progress, time, drive, dreamPointA)
                    dreamLinePosition(next, line, progress, time, drive, dreamPointB)
                    val x = centerX + unit * (dreamPointA[0] + (dreamPointB[0] - dreamPointA[0]) * blend)
                    val y = centerY + unit * (dreamPointA[1] + (dreamPointB[1] - dreamPointA[1]) * blend)
                    if (step == 0) strand.moveTo(x, y) else strand.lineTo(x, y)
                }
                paint.color = effectColor((35f + line * 15f + (time * 7).toFloat()).mod(360f), .75f)
                paint.alpha = (35 + strandDepth * 85 + drive * 45).toInt().coerceAtMost(190)
                paint.strokeWidth = dp(.45f + drive * .55f)
                c.drawPath(strand, paint)
            }
            dreamPaths.forEach(Path::rewind)
            for (index in dreamAngle.indices) {
                val angle = dreamAngle[index] + time * (.12 + dreamDepth[index] * .12)
                val depth = ((dreamDepth[index] + time * (.035 + index % 5 * .002)) % 1.0).toFloat()
                val offset = dreamOffset[index]
                dreamPosition(first, angle, depth, offset, time, dreamPointA)
                dreamPosition(next, angle, depth, offset, time, dreamPointB)
                val x = centerX + unit * (dreamPointA[0] + (dreamPointB[0] - dreamPointA[0]) * blend)
                val y = centerY + unit * (dreamPointA[1] + (dreamPointB[1] - dreamPointA[1]) * blend)
                val band = delayedSpectrum[index * 255 / dreamAngle.size].toFloat()
                val radius = dp(.35f + depth * .9f + band * .65f + drive * .3f)
                dreamPaths[index % dreamPaths.size].addCircle(x, y, radius, Path.Direction.CW)
            }
            paint.style = Paint.Style.FILL
            for (group in dreamPaths.indices) {
                val hue = (38f + group * 48f + (time * 4.0).toFloat()).mod(360f)
                paint.color = effectColor(hue, .72f)
                paint.alpha = (85 + group * 13 + drive * 55).toInt().coerceAtMost(230)
                c.drawPath(dreamPaths[group], paint)
            }
            paint.alpha = 255
        }

        private fun dreamLinePosition(scene: Int, line: Int, progress: Float, time: Double, drive: Float, point: FloatArray) {
            val depth = line / 15f
            val angle = progress * 2.0 * PI + time * .18 + line * .08
            val ripple = sin(angle * (3 + line % 4) + time * .8 + line) * (.012 + drive * .025)
            when (scene) {
                0 -> {
                    val direction = line * 2.0 * PI / 16.0 + time * .12
                    val radius = .18 + progress * .65 + ripple
                    point[0] = (cos(direction + sin(progress * 8 + time + line) * .06) * radius).toFloat()
                    point[1] = (sin(direction + sin(progress * 8 + time + line) * .06) * radius * .8).toFloat()
                }
                1 -> {
                    val radius = .20 + depth * .32 + ripple
                    point[0] = (cos(angle) * radius).toFloat()
                    point[1] = (sin(angle) * radius * (.4 + .18 * sin(time * .22 + line * .15)) +
                        sin(angle * 2 + time * .4 + line * .3) * .06).toFloat()
                }
                2 -> {
                    val spiral = angle * 2.5 + line * .16
                    val radius = .18 + progress * .38 + depth * .12 + ripple
                    point[0] = (cos(spiral) * radius).toFloat()
                    point[1] = (sin(spiral) * radius * .72).toFloat()
                }
                else -> {
                    point[0] = (progress - .5f) * 1.4f
                    point[1] = ((depth - .5f) * .8 + sin(progress * 8 + time * .55 + line * .24) * .12 + ripple).toFloat()
                }
            }
        }

        private fun dreamPosition(scene: Int, angle: Double, depth: Float, offset: Float, time: Double, point: FloatArray) {
            val radius = when (scene) {
                0 -> .05f + depth * .82f
                1 -> .17f + depth * .30f + offset * .025f
                2 -> .09f + depth * .58f
                else -> .13f + depth * .55f
            }
            val twist = when (scene) {
                0 -> angle + depth * 4.0
                1 -> angle
                2 -> angle + depth * 8.0 + sin(time * .16) * .6
                else -> angle + sin(depth * 9.0 + time * .45) * .5
            }
            point[0] = (cos(twist) * radius).toFloat()
            point[1] = when (scene) {
                0 -> sin(twist) * radius * .65
                1 -> sin(twist) * radius * .5 + sin(twist * 2 + time * .25) * .08 * depth
                2 -> sin(twist) * radius * .55 + offset * .08
                else -> sin(twist) * radius * .8 + sin(angle * 3 + time * .6) * .08
            }.toFloat()
        }

        /** 始终由远及近穿梭，亮度沿尾迹渐变，不再随相位相关突然反向。 */
        private fun drawTravel(c: Canvas) {
            // 前进速度由响度驱动：高潮时飞驰，平缓时近乎悬停。
            val pulse = beatPulse()
            val drive = (delayedRms * 9f + delayedPeak * 1.5f).coerceIn(0f, 1f)
            phase += frameSeconds * (.10f + drive * .65f + pulse * .30f)
            val cx = width / 2f
            val cy = height * .46f
            travelPaths.forEach(Path::rewind)
            val nowMs = pcmClock.nowNs() / 1_000_000L
            travelBursts.removeAll { it.startedAt + 650L <= nowMs }
            val beatAt = pcmClock.beatAtNs
            val newBeat = beatAt != 0L && beatAt != lastTravelBeat && pulse > 0f
            if (newBeat) lastTravelBeat = beatAt
            var beatBurstPending = newBeat && nowMs - lastBeatBurstMs >= 600L && travelBursts.size < 12
            val offset = if (beatBurstPending) travelRandom.nextInt(180) else 0
            for (step in 0 until 180) {
                val i = (step + offset) % 180
                val progress = (i * .6180339f + phase * .24f) % 1f
                val previous = travelPrevious[i]
                if (previous >= 0f && progress < previous) {
                    travelExploded[i] = false
                    travelThreshold[i] = .35f + travelRandom.nextFloat() * .45f
                }
                travelPrevious[i] = progress
                val z = progress
                val depth = .04f + z * z * .96f
                val energy = delayedSpectrum[i % delayedSpectrum.size].toFloat()
                // 保留原始透视穿梭射线：小球从远处迎面飞来，不做环形旋转。
                val rayX = sin(i * 12.37f) * width * .58f
                val rayY = cos(i * 8.73f) * height * .55f
                val x = cx + rayX * depth
                val y = cy + rayY * depth
                // 频带能量越大尾迹越长、粒子越大，直接反映音频起伏。
                val tailStretch = .015f + depth * (.045f + energy * .28f + drive * .10f + pulse * .08f)
                val tailDepth = (depth - tailStretch).coerceAtLeast(.015f)
                val tailX = cx + rayX * tailDepth
                val tailY = cy + rayY * tailDepth
                val size = dp(.9f * (.2f + depth * (.5f + energy * (1.5f + drive * 4f))))
                val dx = x - tailX
                val dy = y - tailY
                val length = max(dp(.01f), sqrt(dx * dx + dy * dy))
                val nx = -dy / length * size
                val ny = dx / length * size
                val group = (depth * 3.99f).toInt().coerceIn(0, 3)
                // 非单调包络：宽头后先收成细腰、再小幅鼓起、再收细，避免看起来只是一个锥形。
                val tail = Path()
                val widthProfile = floatArrayOf(0.92f, 0.42f, 0.70f, 0.28f, 0.34f, 0f)
                tail.moveTo(x + nx, y + ny)
                for (segment in 1..widthProfile.lastIndex) {
                    val fraction = segment.toFloat() / widthProfile.lastIndex
                    val segmentDepth = depth + (tailDepth - depth) * fraction
                    val segmentX = cx + rayX * segmentDepth
                    val segmentY = cy + rayY * segmentDepth
                    val half = size * widthProfile[segment]
                    tail.lineTo(segmentX - dy / length * half, segmentY + dx / length * half)
                }
                tail.lineTo(tailX, tailY)
                for (segment in widthProfile.lastIndex - 1 downTo 0) {
                    val fraction = segment.toFloat() / widthProfile.lastIndex
                    val segmentDepth = depth + (tailDepth - depth) * fraction
                    val segmentX = cx + rayX * segmentDepth
                    val segmentY = cy + rayY * segmentDepth
                    val half = size * widthProfile[segment]
                    tail.lineTo(segmentX + dy / length * half, segmentY - dx / length * half)
                }
                tail.close()
                val beatBurst = beatBurstPending && previous >= 0f && progress in .35f..0.8f && !travelExploded[i]
                val randomBurst = previous >= 0f && previous < travelThreshold[i] && progress >= travelThreshold[i] &&
                    !travelExploded[i] && travelRandom.nextFloat() < .25f
                if ((beatBurst || randomBurst) && travelBursts.size < 12) {
                    // The explosion is anchored ahead of the moving head, not at the end of its path.
                    travelBursts += TravelBurst(x + dx / length * size * 2f,
                        y + dy / length * size * 2f, max(size, dp(1f)), group, nowMs)
                    travelExploded[i] = true
                    if (beatBurst) {
                        beatBurstPending = false
                        lastBeatBurstMs = nowMs
                    }
                }
                if (!travelExploded[i]) {
                    travelPaths[group].addPath(tail)
                    drawSphere(c, x, y, size, group, drive)
                }
            }
            travelPaths.indices.forEach { group ->
                // 在原有蓝绿黄基础上补充橙、淡紫、淡红和淡青，保持柔和不发白。
                val colors = listOf(205f to .68f, 145f to .58f, 48f to .62f,
                    27f to .68f, 270f to .38f, 350f to .42f, 178f to .48f)
                val colorIndex = (group + (phase * .35f).toInt() % colors.size + colors.size) % colors.size
                val (hue, saturation) = colors[colorIndex]
                paint.alpha = (10 + group * 8 + (drive * 40 + pulse * 65).toInt()).coerceIn(0, 255)
                paint.color = Color.HSVToColor(floatArrayOf(hue, saturation, .95f))
                c.drawPath(travelPaths[group], paint)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(.7f)
            paint.strokeCap = Paint.Cap.ROUND
            travelBursts.forEach { burst ->
                val progress = ((nowMs - burst.startedAt) / 650f).coerceIn(0f, 1f)
                paint.color = Color.HSVToColor(floatArrayOf((205f + burst.group * 55f) % 360f, .65f, 1f))
                paint.alpha = ((1f - progress) * 230).toInt()
                for (ray in 0 until 8) {
                    val angle = ray * PI.toFloat() / 4f + burst.group * .37f
                    val inner = burst.radius * (1f + progress * 2f)
                    val outer = burst.radius * (2f + progress * 9f)
                    c.drawLine(burst.x + cos(angle) * inner, burst.y + sin(angle) * inner,
                        burst.x + cos(angle) * outer, burst.y + sin(angle) * outer, paint)
                }
            }
            paint.strokeCap = Paint.Cap.BUTT
            paint.style = Paint.Style.FILL
            paint.shader = null
            paint.alpha = 255
        }

        private fun drawSphere(c: Canvas, x: Float, y: Float, radius: Float, group: Int, drive: Float) {
            if (radius < dp(.25f)) return
            val hue = (205f + group * 35f + phase * 10f) % 360f
            val color = Color.HSVToColor(floatArrayOf(hue, .48f, .98f))
            // 左上方高光、右下方暗部形成球面明暗，不使用资源贴图或逐帧对象集合。
            paint.shader = RadialGradient(x - radius * .35f, y - radius * .4f, radius * 1.45f,
                intArrayOf(Color.WHITE, color, Color.rgb(24, 43, 68)), floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP)
            paint.alpha = (65 + group * 25 + drive * 145f).toInt().coerceIn(0, 255)
            paint.style = Paint.Style.FILL
            sphereBounds.set(x - radius, y - radius, x + radius, y + radius)
            c.drawOval(sphereBounds, paint)
            paint.shader = null
            if (radius > dp(1f)) {
                paint.color = Color.WHITE
                paint.alpha = (90 + drive * 100f).toInt().coerceIn(0, 255)
                c.drawCircle(x - radius * .33f, y - radius * .36f, radius * .14f, paint)
            }
        }

        private fun dp(value: Float) = value * resources.displayMetrics.density

        private companion object {
            const val VISUAL_DELAY_NS = 80_000_000L
        }
    }

    /** 所有独占模式共用的歌词顶层，独立背景确保粒子、图谱与彗尾不会遮住文字。 */
    private class LyricOverlay(context: Context, private val pcmClock: PcmVisualClock) : View(context) {
        var dark = true
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var lines: List<LyricLine> = emptyList()
        private var positionMs = 0L
        private var activeIndex = -1
        private var lineStartedAtNs = 0L

        fun setLyrics(result: LyricsResult?) {
            lines = result?.lines ?: emptyList()
            activeIndex = -1
            lineStartedAtNs = 0L
            invalidate()
        }

        fun setPosition(value: Long) {
            positionMs = value.coerceAtLeast(0)
            postInvalidateOnAnimation()
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            if (lines.isEmpty()) return
            val index = lines.indexOfLast { it.timeMs <= positionMs }
            if (index < 0) return
            if (index != activeIndex) {
                activeIndex = index
                lineStartedAtNs = pcmClock.nowNs()
            }
            val line = lines[index]
            val elapsedMs = (pcmClock.nowNs() - lineStartedAtNs) / 1_000_000
            val typeDuration = 900L
            val fadeDuration = 500L
            val remainingMs = lines.getOrNull(index + 1)?.let { it.timeMs - positionMs } ?: Long.MAX_VALUE
            val fade = if (remainingMs < fadeDuration) 1f - remainingMs.toFloat() / fadeDuration else 0f
            val visibleChars = if (elapsedMs >= typeDuration) line.text.length
            else (line.text.length * elapsedMs.toFloat() / typeDuration).toInt().coerceAtLeast(0)
            val text = line.text.take(visibleChars)
            if (text.isEmpty()) return
            val density = resources.displayMetrics.density
            paint.textSize = 16f * density
            paint.textAlign = Paint.Align.CENTER
            paint.style = Paint.Style.FILL
            paint.typeface = android.graphics.Typeface.DEFAULT
            val baseline = height - 10f * density
            val metrics = paint.fontMetrics
            paint.color = if (dark) 0xffe8f4ff.toInt() else 0xff102a43.toInt()
            paint.alpha = ((1f - fade) * 255).toInt().coerceIn(0, 255)
            paint.setShadowLayer(3f * density, 0f, 0f, if (dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            c.drawText(text, width / 2f, baseline, paint)
            paint.clearShadowLayer()
            if ((pcmClock.nowNs() / 300_000_000) % 2 == 0L) {
                val cursorX = width / 2f + paint.measureText(text) / 2 + 2f * density
                // 输入法式光标略高于文字上下两端。
                c.drawRect(cursorX, baseline + metrics.top - 3f * density,
                    cursorX + 2f * density, baseline + metrics.bottom + 3f * density, paint)
            }
            paint.alpha = 255
            postInvalidateOnAnimation()
        }
    }
}
