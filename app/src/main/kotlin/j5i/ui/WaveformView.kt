package j5i.spatial8d.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** 显示实际写入 AudioTrack 的 PCM；峰值和相邻样本突变用于定位爆音。 */
class WaveformView(context: Context) : View(context) {
    var dark = true
        set(value) { field = value; invalidate() }
    private val samples = ShortArray(4096 * 2)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var count = 0
    private var peak = 0
    private var jump = 0
    private var rms = 0.0

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
        for (x in 0 until width) {
            val index = (snapshotCount - visible + x * visible / width) % snapshotSamples.size
            val y = middle - snapshotSamples[index] / 32768f * contentHeight * 0.42f
            canvas.drawLine(x.toFloat(), middle, x.toFloat(), y, paint)
        }
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
    private data class Snapshot(val samples: ShortArray, val count: Int, val peak: Int, val jump: Int, val rms: Double)
}
