package jiangyi.spatial8d.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.MotionEvent
import jiangyi.spatial8d.core.clampSlider
import jiangyi.spatial8d.core.SpacePreset
import jiangyi.spatial8d.core.azimuthAt
import jiangyi.spatial8d.core.earGains
import jiangyi.spatial8d.core.farAt
import jiangyi.spatial8d.core.frameAt
import kotlin.math.cos
import kotlin.math.sin

class SpatialVisualization(context: Context) : View(context) {
    var preset: SpacePreset? = null
    var position: Double = 0.0
        set(value) {
            field = value
            invalidate()
        }
    var dark = true
        set(value) {
            field = value
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val p = preset ?: return
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) * 0.28f / (1f + (p.depth / 100f).toFloat())
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = if (dark) 0xff34495e.toInt() else 0xffd4dbe3.toInt()
        canvas.drawCircle(cx, cy, radius, paint)
        paint.color = if (dark) 0xff9fb4c8.toInt() else 0xff425466.toInt()
        paint.style = Paint.Style.FILL
        canvas.drawOval(RectF(cx - 30f, cy - 40f, cx + 30f, cy + 40f), paint)
        canvas.drawOval(RectF(cx - 49f, cy - 18f, cx - 25f, cy + 18f), paint)
        canvas.drawOval(RectF(cx + 25f, cy - 18f, cx + 49f, cy + 18f), paint)
        paint.strokeWidth = 3f
        paint.color = 0xff4ea1ff.toInt()
        path.reset()
        for (i in 0 until 100) {
            val t = position + i * p.period / 100.0
            val frame = frameAt(t, p.period, p.phase, p.direction, p.elev, p.depth / 100.0)
            val x = cx + frame.x.toFloat() * radius
            val y = cy - frame.y.toFloat() * radius
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        paint.style = Paint.Style.STROKE
        canvas.drawPath(path, paint)
        val current = frameAt(position, p.period, p.phase, p.direction, p.elev, p.depth / 100.0)
        val x = cx + current.x.toFloat() * radius
        val y = cy - current.y.toFloat() * radius
        paint.style = Paint.Style.FILL
        paint.color = 0xffff7a59.toInt()
        canvas.drawCircle(x, y, 14f, paint)
    }
}

class CurveView(context: Context) : View(context) {
    var onSlide: ((String, Double) -> Unit)? = null
    private var dragStartY = 0f
    private var dragStartX = 0f
    private var startPhase = 0.0
    private var startValue = 0.0
    private var dragKey = ""

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val p = preset ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragStartY = event.y; dragStartX = event.x
                startPhase = p.phase
                val span = p.period * 2
                val t = event.x / width * span
                val elevation = frameAt(t, p.period, p.phase, p.direction, p.elev, p.depth / 100.0).elev
                val elevY = height - (elevation / (p.elev.coerceAtLeast(1.0) * Math.PI / 180.0) * 0.5 + 0.5).toFloat() * (height - 16f) - 8f
                val depthY = height - farAt(t, p.period).toFloat() * (height - 16f) - 8f
                dragKey = if (kotlin.math.abs(event.y - elevY) < kotlin.math.abs(event.y - depthY)) "elev" else "depth"
                startValue = if (dragKey == "elev") p.elev else p.depth
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val phase = (startPhase - p.direction * (event.x - dragStartX) / width * 720.0).mod(360.0)
                onSlide?.invoke("phase", clampSlider("phase", phase))
                val change = (dragStartY - event.y) * (if (dragKey == "elev") 40.0 else 100.0) / (height * 0.85f)
                onSlide?.invoke(dragKey, clampSlider(dragKey, startValue + change))
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    var preset: SpacePreset? = null
    var position: Double = 0.0
        set(value) {
            field = value
            invalidate()
        }
    var dark = true
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    override fun onDraw(canvas: Canvas) {
        val p = preset ?: return
        val span = p.period * 2.0
        val w = width.toFloat()
        val h = height.toFloat()
        paint.color = if (dark) 0xff34495e.toInt() else 0xffd4dbe3.toInt()
        paint.strokeWidth = 1f
        canvas.drawLine(0f, h / 2, w, h / 2, paint)
        paint.strokeWidth = 3f
        drawSeries(canvas, w, h, 0xff4ea1ff.toInt()) { t ->
            val s = sin(azimuthAt(t, p.period, p.phase, p.direction))
            earGains(s)[0]
        }
        drawSeries(canvas, w, h, 0xffff7a59.toInt()) { t ->
            val s = sin(azimuthAt(t, p.period, p.phase, p.direction))
            earGains(s)[1]
        }
        drawSeries(canvas, w, h, 0xff4fd396.toInt()) { t ->
            val frame = frameAt(t, p.period, p.phase, p.direction, p.elev, p.depth / 100.0)
            frame.elev / (p.elev.coerceAtLeast(1.0) * Math.PI / 180.0) * 0.5 + 0.5
        }
        drawSeries(canvas, w, h, 0xffa98ce8.toInt()) { t -> farAt(t, p.period) }
        paint.color = if (dark) Color.WHITE else Color.BLACK
        paint.strokeWidth = 2f
        val cursor = ((position % span) / span * w).toFloat()
        canvas.drawLine(cursor, 0f, cursor, h, paint)
    }

    private fun drawSeries(
        canvas: Canvas,
        width: Float,
        height: Float,
        color: Int,
        sample: (Double) -> Double
    ) {
        val path = Path()
        val p = preset ?: return
        val span = p.period * 2.0
        for (i in 0..160) {
            val value = sample(i / 160.0 * span).toFloat().coerceIn(0f, 1f)
            val x = i / 160f * width
            val y = height - value * (height - 16f) - 8f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        paint.color = color
        canvas.drawPath(path, paint)
    }
}
