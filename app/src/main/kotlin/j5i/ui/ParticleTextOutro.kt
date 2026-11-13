package j5i.spatial8d.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.min
import kotlin.math.sin

internal class ParticleTextOutro(width: Int, height: Int, text: String, motifs: Int, private val duration: Float) {
    private data class Point(val x: Float, val y: Float)

    private val points: List<Point>
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val unit = min(width, height).toFloat()
    private val selectedMotifs = listOf(1, 2, 4, 8, 16).filter { motifs and it != 0 }.ifEmpty { listOf(4) }
    private var origins: List<Point> = emptyList()

    fun captureOrigins(bitmap: Bitmap) {
        val width = 80
        val height = 80
        val sample = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = IntArray(width * height)
        sample.getPixels(pixels, 0, width, 0, 0, width, height)
        if (sample !== bitmap) sample.recycle()
        val bright = pixels.indices.filter { index ->
            val color = pixels[index]
            Color.red(color) + Color.green(color) + Color.blue(color) > 120
        }
        origins = if (bright.isEmpty()) emptyList() else points.indices.map { index ->
            val pixel = bright[((index.toLong() * 7919 + 37) % bright.size).toInt()]
            Point((pixel % width + .5f) / width, (pixel / width + .5f) / height)
        }
    }

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
        val blend = outroFormationProgress(elapsed, duration)
        val fade = (elapsed / (duration * .25f).coerceAtLeast(.1f)).coerceIn(0f, 1f)
        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        paint.alpha = (100 * blend).toInt()
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), paint)
        val left = canvas.width * .08f
        val top = canvas.height * .39f
        val drawWidth = canvas.width * .84f
        val drawHeight = canvas.height * .22f
        val size = unit * .0105f
        points.forEachIndexed { index, point ->
            val origin = origins.getOrNull(index) ?: Point(((index * 73 % 997) / 997f), ((index * 137 % 991) / 991f))
            val x = origin.x * canvas.width * (1f - blend) + (left + point.x * drawWidth) * blend + sin(elapsed * 1.4f + index * .17f) * size * .24f
            val y = origin.y * canvas.height * (1f - blend) + (top + point.y * drawHeight) * blend + sin(elapsed * 1.1f + index * .11f) * size * .18f
            val motif = selectedMotifs[index % selectedMotifs.size]
            paint.color = when (motif) {
                1 -> 0xffdff6ff.toInt()
                2 -> 0xff91dcff.toInt()
                8 -> 0xffffb9dc.toInt()
                16 -> 0xffffc677.toInt()
                else -> 0xffffe7a6.toInt()
            }
            paint.alpha = (fade * (170 + 85 * (.5f + .5f * sin(elapsed * 2.4f + index))).toInt()).toInt().coerceIn(0, 255)
            when (motif) {
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
                4 -> {
                    paint.style = Paint.Style.FILL
                    path.rewind()
                    for (corner in 0 until 10) {
                        val angle = corner * Math.PI / 5.0 - Math.PI / 2.0
                        val radius = size * if (corner % 2 == 0) 1f else .42f
                        val px = x + kotlin.math.cos(angle).toFloat() * radius
                        val py = y + kotlin.math.sin(angle).toFloat() * radius
                        if (corner == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    path.close()
                    canvas.drawPath(path, paint)
                }
                8 -> {
                    paint.style = Paint.Style.FILL
                    canvas.drawOval(x - size * .65f, y + size * .2f, x + size * .15f, y + size * .7f, paint)
                    paint.strokeWidth = size * .16f
                    canvas.drawLine(x + size * .12f, y + size * .4f, x + size * .12f, y - size, paint)
                    canvas.drawLine(x + size * .12f, y - size, x + size * .7f, y - size * .7f, paint)
                }
                16 -> {
                    paint.style = Paint.Style.FILL
                    path.rewind()
                    path.moveTo(x - size, y + size * .5f)
                    path.quadTo(x - size * .8f, y - size, x + size, y - size * .5f)
                    path.quadTo(x + size * .8f, y + size, x - size, y + size * .5f)
                    path.close()
                    canvas.drawPath(path, paint)
                }
            }
        }
    }
}
