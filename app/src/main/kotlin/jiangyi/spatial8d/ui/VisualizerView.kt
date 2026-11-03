package jiangyi.spatial8d.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.graphics.drawable.ColorDrawable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import jiangyi.spatial8d.audio.LyricsResult
import jiangyi.spatial8d.audio.LyricLine
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
    var onExpand: (() -> Unit)? = null
    var dark = true
        set(value) { field = value; canvas.dark = value; restyle() }
    private val canvas = AudioCanvas(context)
    private var exclusive = false
    private var visualMode = 0
    private val layerButtons = mutableListOf<Button>()
    private val exclusiveButtons = mutableListOf<Button>()
    private val layers = booleanArrayOf(true, true, true, true)
    private val layerRow = row()
    private var expandButton: Button? = null

    init {
        orientation = VERTICAL
        val stage = FrameLayout(context)
        addView(stage, LayoutParams(-1, 0, 1f))
        stage.addView(canvas, FrameLayout.LayoutParams(-1, -1))
        stage.addView(layerRow, FrameLayout.LayoutParams(-1, dp(28f)).apply {
            leftMargin = dp(4f); rightMargin = dp(4f); topMargin = dp(2f)
        })
        listOf("波形", "频谱", "响度", "相位").forEachIndexed { index, label ->
            layerRow.addView(button(label) {
                exclusive = false
                layers[index] = !layers[index]
                updateMode()
            }.also(layerButtons::add))
        }
        listOf("声谱" to 1, "粒子" to 2, "穿梭" to 3).forEach { (label, mode) ->
            layerRow.addView(button(label) {
                exclusive = true
                visualMode = mode
                updateMode()
            }.also(exclusiveButtons::add))
        }
        expandButton = button("弹窗") { onExpand?.invoke() }.also(layerRow::addView)
        updateMode()
    }

    fun accept(data: ByteArray, bytes: Int) = canvas.accept(data, bytes)

    /** 当前歌曲歌词与播放位置，用于在穿梭模式中叠加逐行歌词字幕。 */
    fun setLyrics(result: LyricsResult?) = canvas.setLyrics(result)
    fun setPosition(positionMs: Long) = canvas.setPosition(positionMs)

    private fun updateMode() {
        canvas.mode = if (!exclusive) 0 else visualMode
        canvas.layers = layers.copyOf()
        layerButtons.forEachIndexed { i, button -> button.isSelected = layers[i] && !exclusive }
        exclusiveButtons.forEachIndexed { i, button -> button.isSelected = exclusive && visualMode == i + 1 }
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

    private class AudioCanvas(context: Context) : View(context) {
        var dark = true
        var mode = 0
        var layers = BooleanArray(4) { true }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val history = ShortArray(2048 * 2)
        private var total = 0L
        private var rms = .0
        private var peak = .0
        private var correlation = .0
        private var phase = 0f
        private val spectrum = DoubleArray(256)
        private val spectrogram = IntArray(128 * 64)
        private var spectrogramRow = 0
        private val particlePaths = Array(4) { Path() }
        private val travelPaths = Array(4) { Path() }
        private val travelHeads = Array(4) { Path() }
        // PCM 到达比实际听感提前约 80ms；按绘制时间消费延迟样本，避免缓冲越积越多。
        private val visualQueue = ArrayDeque<ByteArray>()
        private var delayedSamplesRead = false
        private val delayedSpectrum = DoubleArray(256)
        private var delayedRms = 0f
        private var delayedPeak = 0f
        // 平滑响度（RMS 滑动平均）和基于真实时间的帧推进，避免动画被解码 buffer 节奏拉扯。
        private var smoothRms = 0f
        private var smoothPeak = 0f
        private var lastDrawNs = 0L
        // 歌词字幕状态：当前行索引、键入开始时间（纳秒），用于逐字键入 + 淡出动画。
        private var lyricLines: List<LyricLine> = emptyList()
        private var lyricPositionMs = 0L
        private var activeLyricIndex = -1
        private var lyricLineStartedAtNs = 0L

        fun setLyrics(result: LyricsResult?) {
            lyricLines = result?.lines ?: emptyList()
            activeLyricIndex = -1
            lyricLineStartedAtNs = 0L
        }

        fun setPosition(positionMs: Long) {
            lyricPositionMs = positionMs.coerceAtLeast(0)
        }

        fun accept(data: ByteArray, bytes: Int) {
            val chunk = data.copyOf(bytes)
            visualQueue.addLast(chunk)
            val targetFrames = 44100 * 80 / 1000
            while (visualQueue.size > 1 &&
                visualQueue.sumOf { it.size / 4 } - visualQueue.first().size / 4 > targetFrames) {
                visualQueue.removeFirst()
                delayedSamplesRead = false
            }
            consumeDelayedSamples()
            analyzeImmediate(data, bytes)
        }

        private fun consumeDelayedSamples() {
            // 80ms 窗口里的首个完整 PCM 包直接用于粒子与穿梭；即时图表仍走原数据。
            if (!delayedSamplesRead) visualQueue.firstOrNull()?.let {
                analyzeDelayed(it)
                delayedSamplesRead = true
            }
        }

        private fun analyzeImmediate(data: ByteArray, bytes: Int) {
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
            postInvalidateOnAnimation()
        }

        private fun analyzeDelayed(data: ByteArray) {
            val input = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val samples = ShortArray(input.remaining())
            input.get(samples)
            if (samples.isEmpty()) return
            for (band in delayedSpectrum.indices) {
                val hz = 40.0 * Math.pow(500.0, band / 255.0)
                val coefficient = 2 * cos(2 * PI * hz / 44100)
                var s1 = .0
                var s2 = .0
                for (i in samples.indices) {
                    val s0 = samples[i] / 32768.0 + coefficient * s1 - s2
                    s2 = s1
                    s1 = s0
                }
                val power = max(1e-18, (s1 * s1 + s2 * s2 - coefficient * s1 * s2) /
                    (samples.size.toDouble() * samples.size))
                val db = (10 * ln(power) / ln(10.0)).coerceIn(-100.0, 0.0)
                delayedSpectrum[band] = sqrt(((db + 70) / 70).coerceIn(0.0, 1.0))
            }
            delayedRms = sqrt(samples.sumOf { val v = it / 32768.0; v * v } / samples.size).toFloat()
            delayedPeak = samples.maxOf { abs(it.toInt()) } / 32768f
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
            val row = spectrogramRow * 128
            for (x in 0 until 128) {
                val level = spectrum[x * 2].toFloat()
                spectrogram[row + x] = Color.HSVToColor(floatArrayOf((240f - level * 240f), .85f, .12f + level * .88f))
            }
            spectrogramRow = (spectrogramRow + 1) % 64
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
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

        private fun drawCombined(c: Canvas) {
            val enabled = layers.indices.filter { layers[it] }
            if (enabled.isEmpty()) {
                c.drawText("请至少开启一个图表", dp(14f), height / 2f, paint)
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
            label(c, "波形  L / R", bottom)
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
            label(c, "频谱  40 Hz - 20 kHz", bottom)
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
            label(c, String.format("响度  RMS %.1f dBFS   峰值 %.1f dBFS", db, if (peak > 0) 20 * ln(peak) / ln(10.0) else -80.0), bottom)
            val left = 0f
            val right = width.toFloat()
            val y = top + (bottom - top - dp(18f)) / 2
            paint.color = if (dark) 0xff334455.toInt() else 0xffdbe3eb.toInt()
            c.drawRoundRect(RectF(left, y, right, y + dp(10f)), dp(5f), dp(5f), paint)
            paint.color = if (db > -3) 0xffff665c.toInt() else 0xff50cf91.toInt()
            c.drawRoundRect(RectF(left, y, left + (right - left) * ((db + 60) / 60).coerceIn(0.0, 1.0).toFloat(), y + dp(10f)), dp(5f), dp(5f), paint)
        }

        private fun drawPhase(c: Canvas, top: Float, bottom: Float) {
            label(c, String.format("相位相关  %+.2f   -1 反相 / +1 同相", correlation), bottom)
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
            label(c, "声谱  时间向下 · 频率向右", height.toFloat())
            val top = dp(30f)
            val cellW = width / 128f
            val cellH = (height - top) / 64f
            for (y in 0 until 64) for (x in 0 until 128) {
                paint.color = spectrogram[((spectrogramRow + y) % 64) * 128 + x]
                c.drawRect(x * cellW, top + y * cellH, (x + 1) * cellW + 1, top + (y + 1) * cellH + 1, paint)
            }
        }

        private fun drawParticles(c: Canvas) {
            // 整体强度由响度驱动：安静时几乎静止，起伏时粒子立刻反应。
            val drive = (delayedRms * 9f + delayedPeak * 1.5f).coerceIn(0f, 1f)
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
            // 用 RMS 滑动平均 + 真实帧间隔，节奏感保留但速度上限被钳制，避免"爆点一来就加速"的卡顿感。
            val drive = (delayedRms * 9f + delayedPeak * 1.5f).coerceIn(0f, 1f)
            phase += frameDeltaSeconds() * (.10f + drive * .65f)
            val cx = width / 2f
            val cy = height * .46f
            travelPaths.forEach(Path::rewind)
            travelHeads.forEach(Path::rewind)
            for (i in 0 until 180) {
                val progress = (i * .6180339f + phase * .24f) % 1f
                val z = progress
                val depth = .04f + z * z * .96f
                val energy = delayedSpectrum[i % delayedSpectrum.size].toFloat()
                val rayX = sin(i * 12.37f) * width * .58f
                val rayY = cos(i * 8.73f) * height * .55f
                val x = cx + rayX * depth
                val y = cy + rayY * depth
                // 频带能量越大尾迹越长、粒子越大，直接反映音频起伏。
                // 更短的彗尾：随能量伸长，但始终比旧版收窄。
                val tailStretch = .015f + depth * (.045f + energy * .28f + drive * .10f)
                val tailDepth = (depth - tailStretch).coerceAtLeast(.015f)
                val tailX = cx + rayX * tailDepth
                val tailY = cy + rayY * tailDepth
                val size = dp(.2f + depth * (.5f + energy * (1.5f + drive * 4f)))
                val dx = x - tailX
                val dy = y - tailY
                val length = max(dp(.01f), sqrt(dx * dx + dy * dy))
                val nx = -dy / length * size
                val ny = dx / length * size
                val group = (depth * 3.99f).toInt().coerceIn(0, 3)
                // 单个三角形从头部直接收窄到零，不出现中间再次变宽的折点。
                travelPaths[group].moveTo(x + nx, y + ny)
                travelPaths[group].lineTo(tailX, tailY)
                travelPaths[group].lineTo(x - nx, y - ny)
                travelPaths[group].close()
                travelHeads[group].addCircle(x, y, size, Path.Direction.CW)
            }
            travelPaths.indices.forEach { group ->
                // 频段能量驱动蓝→青→橙渐变；深度只负责层次，不再固定每组颜色。
                val energy = delayedSpectrum[group * 64].toFloat()
                val hue = 210f - energy * 180f
                paint.color = Color.HSVToColor(floatArrayOf(hue % 360f, .68f, .95f))
                paint.alpha = (10 + group * 8 + (drive * 40).toInt()).coerceIn(0, 255)
                c.drawPath(travelPaths[group], paint)
                paint.alpha = (60 + group * 25 + (drive * 150).toInt()).coerceIn(0, 255)
                c.drawPath(travelHeads[group], paint)
            }
            paint.alpha = 255
            // 穿梭模式叠加逐行歌词：键入 → 停驻 → 淡出，随播放位置切换。
            drawLyricLine(c)
        }

        /** 逐字键入 + 停驻 + 淡出的歌词字幕，叠加在穿梭视图下半区。 */
        private fun drawLyricLine(c: Canvas) {
            if (lyricLines.isEmpty()) return
            val index = lyricLines.indexOfLast { it.timeMs <= lyricPositionMs }
            if (index < 0) return
            if (index != activeLyricIndex) {
                activeLyricIndex = index
                lyricLineStartedAtNs = System.nanoTime()
            }
            val line = lyricLines[index]
            val elapsedMs = (System.nanoTime() - lyricLineStartedAtNs) / 1_000_000
            val typeDuration = 900L
            val fadeDuration = 500L
            // 当前行剩余时长（到下一行的时间），末尾段触发淡出。
            val remainingMs = lyricLines.getOrNull(index + 1)?.let { it.timeMs - lyricPositionMs } ?: Long.MAX_VALUE
            val fadeProgress = if (remainingMs < fadeDuration) 1f - remainingMs.toFloat() / fadeDuration else 0f
            val visibleChars = if (elapsedMs >= typeDuration) line.text.length
            else (line.text.length * (elapsedMs.toFloat() / typeDuration)).toInt().coerceAtLeast(0)
            val text = line.text.take(visibleChars)
            if (text.isEmpty()) return
            val oldSize = paint.textSize
            val oldAlign = paint.textAlign
            val oldAlpha = paint.alpha
            paint.textSize = dp(16f)
            paint.textAlign = Paint.Align.CENTER
            paint.alpha = ((1f - fadeProgress) * 255).toInt().coerceIn(0, 255)
            paint.color = if (dark) 0xffe8f4ff.toInt() else 0xff102a43.toInt()
            paint.style = Paint.Style.FILL
            val y = height - dp(28f)
            c.drawText(text, width / 2f, y, paint)
            // 键入光标：仅在键入阶段显示在末尾。
            if (visibleChars < line.text.length && (System.nanoTime() / 300_000_000) % 2 == 0L) {
                val cursorX = width / 2f + paint.measureText(text) / 2 + dp(2f)
                c.drawRect(cursorX, y - dp(14f), cursorX + dp(2f), y, paint)
            }
            paint.textSize = oldSize
            paint.textAlign = oldAlign
            paint.alpha = oldAlpha
        }

        private fun dp(value: Float) = value * resources.displayMetrics.density
    }
}
