package j5i.spatial8d.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import kotlin.math.min

/** 矢量爱心：空心描边与实心填充共用同一路径，不依赖系统表情字体。 */
class HeartDrawable(private val filled: Boolean, private val color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    init {
        paint.color = color
    }

    override fun draw(canvas: Canvas) {
        val r = bounds
        val size = min(r.width(), r.height()).toFloat()
        if (size <= 0f) return
        val left = r.left + (r.width() - size) / 2f
        val top = r.top + (r.height() - size) / 2f
        val path = Path().apply {
            moveTo(left + size * 0.5f, top + size * 0.92f)
            cubicTo(left + size * 0.42f, top + size * 0.84f,
                left + size * 0.02f, top + size * 0.56f,
                left + size * 0.02f, top + size * 0.30f)
            cubicTo(left + size * 0.02f, top + size * 0.05f,
                left + size * 0.38f, top + size * 0.05f,
                left + size * 0.50f, top + size * 0.24f)
            cubicTo(left + size * 0.62f, top + size * 0.05f,
                left + size * 0.98f, top + size * 0.05f,
                left + size * 0.98f, top + size * 0.30f)
            cubicTo(left + size * 0.98f, top + size * 0.56f,
                left + size * 0.58f, top + size * 0.84f,
                left + size * 0.50f, top + size * 0.92f)
            close()
        }
        paint.strokeWidth = size * 0.085f
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
