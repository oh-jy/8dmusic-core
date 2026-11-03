package jiangyi.spatial8d.ui

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
        while (input.hasRemaining()) {
            val value = input.get()
            samples[count % samples.size] = value
            count++
            peak = maxOf(peak, abs(value.toInt()))
        }
        var energy = 0.0
        var largest = 0
        val visible = minOf(count, samples.size)
        for (i in 1 until visible) {
            val at = (count - visible + i) % samples.size
            val before = (at + samples.size - 1) % samples.size
            val value = samples[at].toInt()
            energy += value.toDouble() * value
            largest = maxOf(largest, abs(value - samples[before]))
        }
        jump = largest
        rms = if (visible > 0) sqrt(energy / visible) else 0.0
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val visible = minOf(count, samples.size)
        paint.textSize = 11f * resources.displayMetrics.density
        paint.color = if (dark) 0xffd8dee8.toInt() else 0xff263446.toInt()
        canvas.drawText("AudioTrack PCM", 16f, paint.textSize + 8f, paint)
        paint.color = if (dark) 0xff91b9e8.toInt() else 0xff315f91.toInt()
        paint.strokeWidth = 1f
        val middle = h * 0.42f
        for (x in 0 until width) {
            val index = (count - visible + x * visible / width) % samples.size
            val y = middle - samples[index] / 32768f * h * 0.30f
            canvas.drawLine(x.toFloat(), middle, x.toFloat(), y, paint)
        }
        paint.color = if (dark) 0xffd8dee8.toInt() else 0xff263446.toInt()
        // 各段固定起点，避免数字位数变化推动后续文本左右抖动。
        val labels = listOf(
            "峰值 $peak",
            "最大突变 $jump",
            "RMS ${rms.toInt()}",
            "采样窗 ${visible / 2} 帧"
        )
        // 四段均分，按列中心对齐绘制；超长时按列宽截断，避免相邻段相互挤占。
        val padding = 16f
        val columnWidth = (w - padding * 2) / labels.size
        val oldAlign = paint.textAlign
        paint.textAlign = Paint.Align.CENTER
        labels.forEachIndexed { index, label ->
            val centerX = padding + index * columnWidth + columnWidth / 2
            var text = label
            // 文本超过列宽时逐字截断并加省略号，保证不侵入下一列。
            if (paint.measureText(text) > columnWidth - 8) {
                while (text.isNotEmpty() && paint.measureText("$text…") > columnWidth - 8) {
                    text = text.dropLast(1)
                }
                text = "$text…"
            }
            canvas.drawText(text, centerX, h - 8f, paint)
        }
        paint.textAlign = oldAlign
    }
}
