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
        listOf("声谱图" to 1, "粒子" to 2, "穿梭" to 3).forEach { (label, mode) ->
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

        fun accept(data: ByteArray, bytes: Int) {
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
            }
            analyze()
            postInvalidateOnAnimation()
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
                val power = max(1e-12, s1 * s1 + s2 * s2 - coefficient * s1 * s2)
                val db = (10 * ln(power) / ln(10.0)).coerceIn(-90.0, 0.0)
                spectrum[band] = ((db + 90) / 90).coerceIn(0.0, 1.0)
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
            c.drawColor(if (dark) 0xff111820.toInt() else 0xfff6f8fb.toInt())
            paint.textSize = dp(9f)
            paint.color = if (dark) 0xffa9bbce.toInt() else 0xff52677d.toInt()
            if (mode >= 2) c.drawColor(if (dark) 0xff080d16.toInt() else 0xff101827.toInt())
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
            paint.textAlign = Paint.Align.RIGHT
            c.drawText(text, width - dp(6f), bottom - dp(4f), paint)
            paint.textAlign = oldAlign
        }

        private fun drawWave(c: Canvas, top: Float, bottom: Float) {
            label(c, "波形  L / R", bottom)
            val available = min(total / 2, 1024).toInt()
            if (available == 0) return
            // 底部保留说明文字行，波形振幅不侵入标签区域。
            val center = top + (bottom - top - dp(14f)) / 2
            val amplitude = (bottom - top - dp(18f)) * .40f
            for (channel in 0..1) {
                val path = Path()
                paint.color = if (channel == 0) 0xff54a8ff.toInt() else 0xffff916d.toInt()
                paint.strokeWidth = dp(1f)
                paint.style = Paint.Style.STROKE
                for (x in 0 until width) {
                    val frame = (total - available * 2 + x.toLong() * available * 2 / width + channel).toInt()
                    val value = history[(frame % history.size + history.size) % history.size] / 32768f
                    val y = center - value * amplitude
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
            label(c, "声谱图  时间向下 · 频率向右", height.toFloat())
            val top = dp(30f)
            val cellW = width / 128f
            val cellH = (height - top) / 64f
            for (y in 0 until 64) for (x in 0 until 128) {
                paint.color = spectrogram[((spectrogramRow + y) % 64) * 128 + x]
                c.drawRect(x * cellW, top + y * cellH, (x + 1) * cellW + 1, top + (y + 1) * cellH + 1, paint)
            }
        }

        private fun drawParticles(c: Canvas) {
            phase += .009f + rms.toFloat() * .075f
            val cx = width / 2f
            val cy = height / 2f
            val radiusUnit = min(width, height) / 220f
            particlePaths.forEach(Path::rewind)
            for (i in 0 until 320) {
                val angle = i * 2.399963f + phase
                val band = sqrt(spectrum[i % spectrum.size].toFloat())
                val baseRadius = (12 + i % 100) * radiusUnit
                val radius = baseRadius * (.72f + band * 1.05f)
                val x = cx + cos(angle) * radius
                val jump = sin(phase * (2.2f + i % 5 * .12f) + i * .71f) * radiusUnit * (5f + band * 20f)
                val y = cy + sin(angle) * radius * .65f + jump
                val group = i % particlePaths.size
                val size = dp(.35f + band * 1.5f)
                particlePaths[group].addCircle(x, y, size, Path.Direction.CW)
            }
            particlePaths.indices.forEach { group ->
                paint.color = Color.HSVToColor(floatArrayOf((group * 70f + phase * 25f) % 360, .72f, .95f))
                paint.alpha = 255
                paint.style = Paint.Style.FILL
                c.drawPath(particlePaths[group], paint)
            }
        }

        /** 始终由远及近穿梭，亮度沿尾迹渐变，不再随相位相关突然反向。 */
        private fun drawTravel(c: Canvas) {
            phase += .012f + rms.toFloat() * .09f
            val cx = width / 2f
            val cy = height * .46f
            travelPaths.forEach(Path::rewind)
            travelHeads.forEach(Path::rewind)
            for (i in 0 until 180) {
                val progress = (i * .6180339f + phase * .24f) % 1f
                val z = progress
                val depth = .04f + z * z * .96f
                val energy = spectrum[i % spectrum.size].toFloat()
                val rayX = sin(i * 12.37f) * width * .58f
                val rayY = cos(i * 8.73f) * height * .55f
                val x = cx + rayX * depth
                val y = cy + rayY * depth
                val tailDepth = (depth - (.025f + depth * .16f)).coerceAtLeast(.015f)
                val tailX = cx + rayX * tailDepth
                val tailY = cy + rayY * tailDepth
                val size = dp(.4f + depth * (1f + energy.toFloat() * 2.5f))
                val dx = x - tailX
                val dy = y - tailY
                val length = max(dp(.01f), sqrt(dx * dx + dy * dy))
                val nx = -dy / length * size
                val ny = dx / length * size
                val group = (depth * 3.99f).toInt().coerceIn(0, 3)
                // 三角形尾迹由尖端渐细到零，四组路径合批，避免逐粒子 draw call。
                travelPaths[group].moveTo(x + nx, y + ny)
                travelPaths[group].lineTo(tailX, tailY)
                travelPaths[group].lineTo(x - nx, y - ny)
                travelPaths[group].close()
                travelHeads[group].addCircle(x, y, size, Path.Direction.CW)
            }
            travelPaths.indices.forEach { group ->
                // 与界面一致的蓝青主题，不再混入紫色。
                paint.color = Color.HSVToColor(floatArrayOf(198f + group * 5f, .65f, .95f))
                paint.alpha = 18 + group * 10
                c.drawPath(travelPaths[group], paint)
                paint.alpha = 140 + group * 35
                c.drawPath(travelHeads[group], paint)
            }
            paint.alpha = 255
        }

        private fun dp(value: Float) = value * resources.displayMetrics.density
    }
}
