package j5i.spatial8d.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.sin
import kotlin.math.PI

/** 显示实际写入 AudioTrack 的 PCM；峰值和相邻样本突变用于定位爆音。 */
class WaveformView(context: Context) : View(context) {
    var style = 0
        set(value) { field = value.coerceIn(graphStyleKeys.indices); invalidate() }
    var dark = true
        set(value) { field = value; invalidate() }
    private val samples = ShortArray(4096 * 2)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var count = 0
    private var peak = 0
    private var jump = 0
    private var rms = 0.0
    private var spectrum = DoubleArray(256)

    fun setSpectrum(value: DoubleArray) {
        synchronized(lock) { spectrum = value.copyOf() }
        invalidate()
    }

    fun accept(data: ByteArray, bytes: Int) {
        val input = ByteBuffer.wrap(data, 0, bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val nextSamples = samples.clone()
        var nextCount = count
        var nextPeak = peak
        while (input.hasRemaining()) {
            val value = input.get()
            nextSamples[nextCount % nextSamples.size] = value
            nextCount++
            nextPeak = maxOf(nextPeak, abs(value.toInt()))
        }
        var energy = 0.0
        var largest = 0
        val visible = minOf(nextCount, nextSamples.size)
        for (i in 1 until visible) {
            val at = (nextCount - visible + i) % nextSamples.size
            val before = (at + nextSamples.size - 1) % nextSamples.size
            val value = nextSamples[at].toInt()
            energy += value.toDouble() * value
            largest = maxOf(largest, abs(value - nextSamples[before]))
        }
        synchronized(lock) {
            System.arraycopy(nextSamples, 0, samples, 0, samples.size)
            count = nextCount
            peak = nextPeak
            jump = largest
            rms = if (visible > 0) sqrt(energy / visible) else 0.0
        }
        postInvalidateOnAnimation()
    }
    private val lock = Any()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val (snapshotSamples, snapshotCount, snapshotPeak, snapshotJump, snapshotRms) = synchronized(lock) {
            Snapshot(samples.clone(), count, peak, jump, rms)
        }
        val h = height.toFloat()
        val visible = minOf(snapshotCount, snapshotSamples.size)
        val density = resources.displayMetrics.density
        val horizontalPadding = 6f * density
        paint.color = if (dark) 0xff91b9e8.toInt() else 0xff315f91.toInt()
        paint.strokeWidth = 1f
        val contentTop = 0f
        val contentBottom = h
        val contentHeight = (contentBottom - contentTop).coerceAtLeast(24f * density)
        val middle = contentTop + contentHeight * 0.5f
        if (style == 0) {
            for (x in 0 until width) {
                val index = (snapshotCount - visible + x * visible / width) % snapshotSamples.size
                val y = middle - snapshotSamples[index] / 32768f * contentHeight * 0.42f
                canvas.drawLine(x.toFloat(), middle, x.toFloat(), y, paint)
            }
        } else drawStyled(canvas, snapshotSamples, snapshotCount, visible, contentHeight, density)
        val labels = listOf(
            AppLanguage.text(context, "graph"),
            "${AppLanguage.text(context, "graph.window")} ${visible / 2} ${AppLanguage.text(context, "graph.frames")}",
            "${AppLanguage.text(context, "graph.peak")} $snapshotPeak",
            "RMS ${snapshotRms.toInt()}",
            "${AppLanguage.text(context, "graph.jump")} $snapshotJump"
        )
        val oldAlign = paint.textAlign
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 9f * density
        paint.color = ThemeUi.palette(dark).sub
        paint.setShadowLayer(2f * density, 0f, 0f, if (dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        var x = horizontalPadding
        var y = 14f * density
        labels.forEachIndexed { index, label ->
            val text = if (index == 0 || x == horizontalPadding) label else "  ·  $label"
            val textWidth = paint.measureText(text)
            if (x > horizontalPadding && x + textWidth > width - horizontalPadding) {
                x = horizontalPadding
                y += 12f * density
                canvas.drawText(label, x, y, paint)
                x += paint.measureText(label)
            } else {
                canvas.drawText(text, x, y, paint)
                x += textWidth
            }
        }
        paint.clearShadowLayer()
        paint.textAlign = oldAlign
    }

    private fun drawStyled(canvas: Canvas, data: ShortArray, count: Int, visible: Int, height: Float, density: Float) {
        if (width <= 0 || visible < 2) return
        val middle = height * .5f
        val amplitude = height * .48f
        val frames = visible / 2
        val bands = synchronized(lock) { spectrum.copyOf() }
        paint.color = if (dark) 0xff48e1dc.toInt() else 0xff007e80.toInt()
        paint.style = Paint.Style.FILL
        val columns = when (style) { in 5..8 -> 64; 2 -> 80; else -> 160 }
        val cell = width.toFloat() / columns
        fun sample(frame: Int): Float {
            val offset = (count - visible + frame.coerceIn(0, frames - 1) * 2) % data.size
            return (data[offset].toInt() + data[(offset + 1) % data.size].toInt()) / 65536f
        }
        fun level(column: Int): Float {
            val first = column * frames / columns
            val last = ((column + 1) * frames / columns).coerceAtLeast(first + 1).coerceAtMost(frames)
            var peak = 0f
            for (frame in first until last) peak = maxOf(peak, abs(sample(frame)))
            return peak
        }
        if (style in 5..8) {
            for (column in 0 until columns) {
                val value = bands[column * bands.size / columns].toFloat().coerceIn(0f, 1f)
                val x = (column + .5f) * cell
                if (style == 5 || style == 8) {
                    val extent = value * amplitude
                    canvas.drawRect(x - cell * .34f, if (style == 8) middle - extent else middle + amplitude - extent * 2,
                        x + cell * .34f, if (style == 8) middle + extent else middle + amplitude, paint)
                } else {
                    for (step in 0 until (value * 20).toInt()) {
                        val y = middle + amplitude - step * amplitude * 2 / 20
                        if (style == 6) canvas.drawRect(x - cell * .34f, y - density, x + cell * .34f, y + density, paint)
                        else canvas.drawCircle(x, y, minOf(cell * .22f, density * 1.4f), paint)
                    }
                }
            }
        } else if (style in 9..12) {
            val path = Path()
            val lines = when (style) { 9 -> 5; 10 -> 12; 11 -> 7; else -> 2 }
            for (line in 0 until lines) {
                path.rewind()
                paint.style = if (style == 9) Paint.Style.FILL else Paint.Style.STROKE
                paint.strokeWidth = density * if (style == 12) 1.5f else .8f
                paint.alpha = if (style == 9) 45 else (95 + line * 12).coerceAtMost(230)
                for (column in 0 until columns) {
                    val value = level(column)
                    val u = column.toFloat() / (columns - 1)
                    val direction = if (style == 12 && line == 1) -1f else 1f
                    val shape = if (style == 12) value else value * sin(u * PI * (3 + line % 3) + line * .4).toFloat()
                    val y = (middle - direction * shape * amplitude * (1f + line * .08f)).coerceIn(0f, height)
                    val x = u * width
                    if (style == 11) canvas.drawCircle(x, y, density, paint)
                    else if (column == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                if (style == 9) { path.lineTo(width.toFloat(), middle + amplitude * .15f); path.lineTo(0f, middle + amplitude * .15f); path.close() }
                if (style != 11) canvas.drawPath(path, paint)
            }
        } else {
            paint.strokeWidth = if (style == 1) maxOf(1f, cell) else density
            for (column in 0 until columns) {
                val value = level(column)
                val x = (column + .5f) * cell
                val extent = value * amplitude
                when (style) {
                    1, 2 -> canvas.drawLine(x, middle - extent, x, middle + extent, paint)
                    3 -> canvas.drawLine(x, middle - extent, x, middle + extent * .35f, paint)
                    4 -> { canvas.drawCircle(x, middle - extent, density * 1.2f, paint); canvas.drawCircle(x, middle + extent, density * 1.2f, paint) }
                }
            }
        }
        paint.alpha = 255
        paint.style = Paint.Style.FILL
    }
    private data class Snapshot(val samples: ShortArray, val count: Int, val peak: Int, val jump: Int, val rms: Double)
}
