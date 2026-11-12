package j5i.spatial8d.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.min
import kotlin.math.sin

internal class ParticleTextOutro(width: Int, height: Int, text: String, private val motif: Int) {
    private data class Point(val x: Float, val y: Float)

    private val points: List<Point>
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val unit = min(width, height).toFloat()

    init {
        val mask = Bitmap.createBitmap(720, 220, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(mask)
        val measuredWidth = Paint().apply {
            textSize = 128f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }.measureText(text).coerceAtLeast(1f)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            textSize = min(128f, 640f / measuredWidth * 128f)
        }
        canvas.drawText(text, 360f, 110f - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        val pixels = IntArray(mask.width * mask.height)
        mask.getPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
        val sampled = ArrayList<Point>()
        for (y in 3 until mask.height step 5) for (x in 3 until mask.width step 5) {
            if (Color.alpha(pixels[y * mask.width + x]) > 96) sampled.add(Point(x / mask.width.toFloat(), y / mask.height.toFloat()))
        }
        points = sampled
        mask.recycle()
    }

    fun draw(canvas: Canvas, elapsed: Float) {
        val fade = (elapsed / .65f).coerceIn(0f, 1f)
        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        paint.alpha = (155 * fade).toInt()
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), paint)
        val left = canvas.width * .08f
        val top = canvas.height * .39f
        val drawWidth = canvas.width * .84f
        val drawHeight = canvas.height * .22f
        val size = unit * .0105f
        points.forEachIndexed { index, point ->
            val x = left + point.x * drawWidth + sin(elapsed * 1.4f + index * .17f) * size * .24f
            val y = top + point.y * drawHeight + sin(elapsed * 1.1f + index * .11f) * size * .18f
            paint.color = when (motif) {
                1 -> 0xffdff6ff.toInt()
                2 -> 0xff91dcff.toInt()
                3 -> when (index % 3) { 0 -> 0xffe4f7ff.toInt(); 1 -> 0xff91dcff.toInt(); else -> 0xffffe7a6.toInt() }
                else -> 0xffffe7a6.toInt()
            }
            paint.alpha = (fade * (170 + 85 * (.5f + .5f * sin(elapsed * 2.4f + index))).toInt()).toInt().coerceIn(0, 255)
            when (if (motif == 3) index % 3 + 1 else motif) {
                1 -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = size * .18f
                    for (arm in 0..2) {
                        val angle = arm * Math.PI.toFloat() / 3f
                        val dx = kotlin.math.cos(angle.toDouble()).toFloat() * size
                        val dy = kotlin.math.sin(angle.toDouble()).toFloat() * size
                        canvas.drawLine(x - dx, y - dy, x + dx, y + dy, paint)
                    }
                }
                2 -> {
                    paint.style = Paint.Style.FILL
                    path.rewind()
                    path.moveTo(x, y - size)
                    path.cubicTo(x - size * .4f, y - size * .2f, x - size * .65f, y + size * .25f, x, y + size * .5f)
                    path.cubicTo(x + size * .65f, y + size * .25f, x + size * .4f, y - size * .2f, x, y - size)
                    canvas.drawPath(path, paint)
                }
                else -> {
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(x, y, size * (.35f + .18f * (.5f + .5f * sin(elapsed * 2f + index))), paint)
                }
            }
        }
    }
}
