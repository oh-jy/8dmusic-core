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
        val w = width.toFloat()
        val h = height.toFloat()
        val visible = minOf(snapshotCount, snapshotSamples.size)
        paint.textSize = 11f * resources.displayMetrics.density
        paint.color = if (dark) 0xffd8dee8.toInt() else 0xff263446.toInt()
        val density = resources.displayMetrics.density
        val horizontalPadding = 6f * density
        paint.color = if (dark) 0xff91b9e8.toInt() else 0xff315f91.toInt()
        paint.strokeWidth = 1f
        val contentTop = 50f * density
        val contentBottom = h - 52f * density
        val contentHeight = (contentBottom - contentTop).coerceAtLeast(24f * density)
        val middle = contentTop + contentHeight * 0.5f
        for (x in 0 until width) {
            val index = (snapshotCount - visible + x * visible / width) % snapshotSamples.size
            val y = middle - snapshotSamples[index] / 32768f * contentHeight * 0.42f
            canvas.drawLine(x.toFloat(), middle, x.toFloat(), y, paint)
        }
        paint.color = if (dark) 0xffd8dee8.toInt() else 0xff263446.toInt()
        // 各段固定起点，避免数字位数变化推动后续文本左右抖动。
        val labels = listOf(
            "峰值 $snapshotPeak",
            "最大突变 $snapshotJump",
            "RMS ${snapshotRms.toInt()}",
            "采样窗 ${visible / 2} 帧"
        )
        // 四段均分，按列中心对齐绘制；超长时按列宽截断，避免相邻段相互挤占。
        val padding = horizontalPadding
        val columnWidth = (w - padding * 2) / labels.size
        val oldAlign = paint.textAlign
        paint.textAlign = Paint.Align.CENTER
        labels.forEachIndexed { index, label ->
            val centerX = padding + index * columnWidth + columnWidth / 2
            // 四列本身已有足够空间，不主动截断文本。
            canvas.drawText(label, centerX, 30f * density + paint.textSize, paint)
        }
        paint.textAlign = oldAlign
    }
    private data class Snapshot(val samples: ShortArray, val count: Int, val peak: Int, val jump: Int, val rms: Double)
}
