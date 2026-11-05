package j5i.spatial8d.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
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
    var dark = true
        set(value) { field = value; canvas.dark = value; restyle() }
    private val canvas = AudioCanvas(context, pcmClock)
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            onExpand?.invoke()
            return true
        }
    })
    private val graphView = WaveformView(context)
    private val lyricOverlay = LyricOverlay(context)
    private var exclusive = false
    private var visualMode = 0
    private val layerButtons = mutableListOf<Button>()
    private val exclusiveButtons = mutableListOf<Button>()
    private val layers = booleanArrayOf(true, true, true, true)
    private val singleModes = booleanArrayOf(false, false, false, false)
    private val layerRow = row()
    private var expandButton: Button? = null
    private val singleLayerButtons = mutableListOf<Button>()
    var showLayerOptions = false
        set(value) { field = value; singleLayerButtons.forEach { it.visibility = if (value) View.VISIBLE else View.GONE } }
    var drivingLyrics = false
        set(value) { field = value; updateMode() }
    val showsLyrics: Boolean get() = exclusive && visualMode != 1

    init {
        orientation = VERTICAL
        val stage = FrameLayout(context)
        addView(stage, LayoutParams(-1, 0, 1f))
        stage.addView(canvas, FrameLayout.LayoutParams(-1, -1))
        canvas.setOnTouchListener { _, event -> gestures.onTouchEvent(event); true }
        graphView.setOnTouchListener { _, event -> gestures.onTouchEvent(event); true }
        stage.addView(graphView, FrameLayout.LayoutParams(-1, -1))
        stage.addView(lyricOverlay, FrameLayout.LayoutParams(-1, -1))
        stage.addView(layerRow, FrameLayout.LayoutParams(-1, dp(28f)).apply {
            leftMargin = dp(4f); rightMargin = dp(4f); topMargin = dp(2f)
        })
        listOf("wave", "spectrum", "loudness", "phase").forEachIndexed { index, key ->
            layerRow.addView(button("${t(key)} ${if (layers[index]) "✓" else "○"}") {
                exclusive = false
                singleModes.fill(false)
                layers[index] = !layers[index]
                updateMode()
            }.also(singleLayerButtons::add).also { it.visibility = View.GONE })
        }
        layerRow.addView(button(t("analysis")) {
            exclusive = false
            singleModes.fill(false)
            layers.fill(true)
            updateMode()
        })
        listOf("spectrogram" to 1, "graph" to 4, "particles" to 2, "travel" to 3).forEach { (key, mode) ->
            layerRow.addView(button(t(key)) {
                exclusive = true
                visualMode = mode
                updateMode()
            }.also(exclusiveButtons::add))
        }
        expandButton = button(t("popup")) { onExpand?.invoke() }.also(layerRow::addView)
        updateMode()
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
    val singleVisualLayer: Int get() = singleModes.indexOfFirst { it }
    fun restoreVisualState(state: Pair<Boolean, Int>, single: Int = -1) {
        exclusive = state.first
        visualMode = state.second
        if (single >= 0) {
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
    fun setPosition(positionMs: Long) = lyricOverlay.setPosition(positionMs)

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
        layerButtons.forEachIndexed { i, button -> button.isSelected = layers[i] && !exclusive }
        singleLayerButtons.forEachIndexed { i, button -> button.isSelected = singleModes[i] && !exclusive }
        singleLayerButtons.forEachIndexed { i, button ->
            button.text = "${t(listOf("wave", "spectrum", "loudness", "phase")[i])} ${if (layers[i]) "✓" else "○"}"
            button.isSelected = layers[i] && !exclusive
        }
        exclusiveButtons.forEachIndexed { i, button -> button.isSelected = exclusive && visualMode == listOf(1, 4, 2, 3)[i] }
        restyle()
    }

    private fun row() = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun button(label: String, action: () -> Unit) = Button(context).apply {
        text = label
        textSize = 10f
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        includeFontPadding = false
        setPadding(dp(3f), 0, dp(3f), 0)
        background = ColorDrawable(Color.TRANSPARENT)
        stateListAnimator = null
        setOnClickListener { action() }
        gravity = Gravity.CENTER
        layoutParams = LayoutParams(0, dp(24f), 1f)
    }

    private fun restyle() {
        (layerButtons + exclusiveButtons + listOfNotNull(expandButton)).forEach {
            it.setTextColor(if (it.isSelected) 0xff70c5ff.toInt() else if (dark) 0xdde4eaf2.toInt() else 0xdd263446.toInt())
            it.background = ColorDrawable(Color.TRANSPARENT)
        }
    }

    private fun dp(value: Float) = ThemeUi.dp(context, value)

    private class AudioCanvas(context: Context, private val pcmClock: PcmVisualClock) : View(context) {
        var dark = true
        var mode = 0
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
        private var spectrumSequence = 0L
        private var spectrogramSequence = 0L
        private val particlePaths = Array(4) { Path() }
        private val travelPaths = Array(4) { Path() }
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

        private fun consumeDelayedSamples() {
            pcmClock.consume()
            if (clockGeneration != pcmClock.generation) {
                clockGeneration = pcmClock.generation
                visualQueue.clear()
                delayedSpectrum.fill(0.0)
                spectrogramRows.clear()
                spectrogramSequence = spectrumSequence
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
            delayedRms = pcmClock.rms.toFloat()
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
            spectrumSequence++
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
                val coefficient = 2 * cos(2 * PI * hz / 44100)
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
            PerfTrace.sample("visual.analyzeDelayedNs", System.nanoTime() - startedAt)
        }

        /** 计算自上次绘制以来的真实秒数（首次或长时间挂起时给一帧保守值）。 */
        private fun frameDeltaSeconds(): Float {
            val now = System.nanoTime()
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
                val omega = 2 * PI * hz / 44100
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
            // 声谱在绘制线程消费最新频谱，不在 PCM 回调里移动历史槽位。
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            drawCount++
            synchronized(analysisLock) {
                consumeDelayedSamples()
                // 背景与卡片 surface 一致，避免独立"纯黑/纯白"造成视觉割裂。
                val surface = ThemeUi.palette(dark).surface
                c.drawColor(surface)
                paint.textSize = dp(9f)
                paint.color = ThemeUi.palette(dark).sub
                when (mode) {
                    1 -> drawSpectrogram(c)
                    2 -> drawParticles(c)
                    3 -> drawTravel(c)
                    else -> drawCombined(c)
                }
            }
        }

        private fun drawCombined(c: Canvas) {
            val enabled = if (singleLayer >= 0) listOf(singleLayer) else layers.indices.filter { layers[it] }
            if (enabled.isEmpty()) {
                c.drawText(AppLanguage.text(context, "visual.empty"), dp(14f), height / 2f, paint)
                return
            }
            // 顶部留给悬浮按钮，图表标签不再与波形和按钮重叠。
            val chartTop = dp(30f)
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
            label(c, AppLanguage.text(context, "visual.spectrogram"), height.toFloat())
            val top = dp(30f)
            if (spectrumSequence != spectrogramSequence) {
                val row = IntArray(128)
                for (x in row.indices) {
                    val level = spectrum[x * 2].toFloat()
                    row[x] = Color.HSVToColor(floatArrayOf(240f - level * 240f, .85f, .12f + level * .88f))
                }
                spectrogramRows.addFirst(row)
                while (spectrogramRows.size > 96) spectrogramRows.removeLast()
                spectrogramSequence = spectrumSequence
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

        private fun beatPulse(): Float {
            val beat = pcmClock.beatAtNs
            if (beat == 0L) return 0f
            val age = (System.nanoTime() - beat) / 1_000_000_000f
            return if (age in 0f..0.3f) (1f - age / .3f) else 0f
        }

        private fun drawParticles(c: Canvas) {
            // 整体强度由响度驱动：安静时几乎静止，起伏时粒子立刻反应。
            val drive = (delayedRms * 9f + delayedPeak * 1.5f + beatPulse() * .18f).coerceIn(0f, 1f)
            phase += frameDeltaSeconds() * (.12f + drive * .9f)
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

        /** 始终由远及近穿梭，亮度沿尾迹渐变，不再随相位相关突然反向。 */
        private fun drawTravel(c: Canvas) {
            // 前进速度由响度驱动：高潮时飞驰，平缓时近乎悬停。
            val pulse = beatPulse()
            val drive = (delayedRms * 9f + delayedPeak * 1.5f).coerceIn(0f, 1f)
            phase += frameDeltaSeconds() * (.10f + drive * .65f + pulse * .30f)
            val cx = width / 2f
            val cy = height * .46f
            travelPaths.forEach(Path::rewind)
            val nowMs = SystemClock.uptimeMillis()
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
                        y + dy / length * size * 2f, max(size, dp(1f)), group, SystemClock.uptimeMillis())
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
    private class LyricOverlay(context: Context) : View(context) {
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
                lineStartedAtNs = System.nanoTime()
            }
            val line = lines[index]
            val elapsedMs = (System.nanoTime() - lineStartedAtNs) / 1_000_000
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
            paint.color = ThemeUi.palette(dark).surface
            paint.alpha = 238
            c.drawRect(0f, baseline + metrics.top - 8f * density, width.toFloat(), height.toFloat(), paint)
            paint.alpha = ((1f - fade) * 255).toInt().coerceIn(0, 255)
            paint.color = if (dark) 0xffe8f4ff.toInt() else 0xff102a43.toInt()
            c.drawText(text, width / 2f, baseline, paint)
            if ((System.nanoTime() / 300_000_000) % 2 == 0L) {
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
