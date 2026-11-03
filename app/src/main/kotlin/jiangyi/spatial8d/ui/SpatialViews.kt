package jiangyi.spatial8d.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Rect
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import jiangyi.spatial8d.core.SpaceFrame
import jiangyi.spatial8d.core.SpacePreset
import jiangyi.spatial8d.core.earGains
import jiangyi.spatial8d.core.earRange
import jiangyi.spatial8d.core.frameAt
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val SIN_T = 0.4067f
private const val COS_T = 0.9135f
private const val FRAME_DELAY_MS = 33L

abstract class AnimatedCanvasView internal constructor(context: Context) : View(context) {
    private var animationScheduled = false
    private val visibleBounds = Rect()
    private val frameCallback = Choreographer.FrameCallback {
        animationScheduled = false
        if (canAnimate()) {
            invalidate()
            scheduleAnimation()
        }
    }

    abstract var playing: Boolean

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateAnimation()
    }

    protected fun updateAnimation() {
        if (canAnimate()) scheduleAnimation() else stopAnimation()
    }

    // isShown 不会排除 ScrollView 已滚出屏幕的子 View，离屏曲线不能继续耗帧。
    private fun canAnimate() = playing && isAttachedToWindow && isShown &&
        getGlobalVisibleRect(visibleBounds) && !visibleBounds.isEmpty

    private fun scheduleAnimation() {
        if (animationScheduled) return
        animationScheduled = true
        Choreographer.getInstance().postFrameCallbackDelayed(frameCallback, FRAME_DELAY_MS)
    }

    private fun stopAnimation() {
        if (!animationScheduled) return
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        animationScheduled = false
    }
}

private class Clock {
    var playing = false
    var seconds = 0.0
    private var base = 0.0
    private var at = 0L
    fun sync(value: Double) {
        if (!playing || abs(now() - value) > 0.6) { base = value; at = SystemClock.uptimeMillis() }
        seconds = value
    }
    fun now() = if (playing) base + (SystemClock.uptimeMillis() - at) / 1000.0 else seconds
}

/** 原生 Canvas 版空间舞台，轨迹直接复用 core 的 frameAt，不另写一套运动公式。 */
class SpatialVisualization(context: Context) : AnimatedCanvasView(context) {
    var preset: SpacePreset? = null
        set(value) { field = value; clock.sync(clock.seconds); invalidate() }
    var position: Double = 0.0
        set(value) { field = value; clock.sync(value); if (playing) updateAnimation() else invalidate() }
    override var playing: Boolean = false
        set(value) {
            if (field == value) return
            field = value; clock.playing = value; clock.sync(position); invalidate(); updateAnimation()
        }
    var dark = true
        set(value) { field = value; invalidate() }
    private val clock = Clock()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val p = preset ?: return
        val w = width / d
        val h = height / d
        canvas.save()
        canvas.scale(d, d)
        // 页面侧按真实播放头同步，组件内部用单调时钟插值，避免小球跳帧。
        val t = clock.now()
        val now = SystemClock.uptimeMillis() / 1000.0
        val cx = w / 2f
        val cy = h * 0.50f
        val elevMax = min(80.0, max(0.0, p.elev)) * Math.PI / 180.0
        val vSpan = (SIN_T + COS_T * sin(elevMax)).toFloat()
        val r = min(w * 0.5f - 3f, (h * 0.5f - 3f) / max(0.45f, vSpan)) * 1.12f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f
        paint.color = if (dark) 0xb8becfe4.toInt() else 0xc7445871.toInt()
        paint.pathEffect = DashPathEffect(floatArrayOf(4f, 5f), 0f)
        canvas.drawOval(RectF(cx - r, cy - r * SIN_T, cx + r, cy + r * SIN_T), paint)
        paint.pathEffect = null
        paint.style = Paint.Style.FILL
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.textSize = 12f
        paint.textAlign = Paint.Align.CENTER
        paint.color = if (dark) 0xe0c6d2e2.toInt() else 0xeb374558.toInt()
        canvas.drawText("前", cx, 12f, paint)
        canvas.drawText("后", cx, h - 2f, paint)
        canvas.drawText("左", 7f, cy + 4f, paint)
        canvas.drawText("右", w - 7f, cy + 4f, paint)
        val f = frameAt(t, p.period, p.phase, p.direction, p.elev, p.depth / 100.0)
        // 彩虹球及初始外圈再次缩小三分之一，人头像保持原尺寸。
        val sourceR = r / (1.0 + p.depth.coerceIn(0.0, 100.0) / 100.0).toFloat() * 0.45f
        // 远近最靠近时也要与人头像保留距离，避免球体进入头部轮廓。
        val minScale = (28f / sourceR).coerceAtMost(1f)
        fun clearOfHead(f: SpaceFrame): SpaceFrame {
            val radius = f.radius.coerceAtLeast(minScale.toDouble())
            return if (radius == f.radius) f else f.copy(radius = radius,
                x = sin(f.azimuth) * radius, y = cos(f.azimuth) * radius, z = sin(f.elev) * radius)
        }
        val light = project(clearOfHead(f), sourceR, cx, cy)
        paint.color = if (dark) 0x4d4ea1ff.toInt() else 0x3d2e7fe6.toInt()
        canvas.drawCircle(light[0], light[1], 3.2f, paint)
        // 前后景遮挡与 UTS 一致，位于前方的小球先画，人头像最后覆盖。
        if (clearOfHead(f).y < 0) {
            drawAvatar(canvas, cx, cy, now, light)
            drawPlanet(canvas, t, sourceR, cx, cy, now, p, ::clearOfHead)
        } else {
            drawPlanet(canvas, t, sourceR, cx, cy, now, p, ::clearOfHead)
            drawAvatar(canvas, cx, cy, now, light)
        }
        canvas.restore()
    }

    private fun project(f: SpaceFrame, r: Float, cx: Float, cy: Float): FloatArray {
        val perspective = (1f - 0.10f * f.y.toFloat()) * (1f - 0.14f * (f.radius.toFloat() - 1f))
        return floatArrayOf(cx + f.x.toFloat() * r, cy - f.y.toFloat() * SIN_T * r - f.z.toFloat() * COS_T * r, perspective)
    }

    private fun drawAvatar(c: Canvas, cx: Float, cy: Float, now: Double, light: FloatArray) {
        val breath = if (playing) 0.5f + 0.5f * sin(now * 1.9).toFloat() else 0f
        val dx = light[0] - cx
        val dy = light[1] - cy
        val len = max(1f, sqrt(dx * dx + dy * dy))
        val lx = dx / len
        val ly = dy / len
        paint.style = Paint.Style.FILL
        paint.color = if (dark) 0x57699ce8.toInt() else 0x3d182230.toInt()
        c.drawOval(RectF(cx - lx * 3.5f - 17f, cy + 42f - ly * 2.2f - 4.4f, cx - lx * 3.5f + 17f, cy + 42f - ly * 2.2f + 4.4f), paint)
        paint.shader = RadialGradient(cx, cy + 2, 44f, if (dark) 0xff4ea1ff.toInt() else 0xff2e7fe6.toInt(), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        paint.alpha = ((0.10f + 0.10f * breath) * 255).toInt()
        c.drawCircle(cx, cy + 2, 44f, paint)
        paint.shader = null
        paint.alpha = 255
        paint.color = if (dark) 0xff8b9bb0.toInt() else 0xff697d95.toInt()
        c.drawArc(RectF(cx - 26, cy + 21, cx + 26, cy + 47), 180f, 180f, true, paint)
        paint.shader = LinearGradient(cx, cy - 14, cx, cy + 16, if (dark) 0xffa7b8cc.toInt() else 0xffc3d0df.toInt(), if (dark) 0xff657a93.toInt() else 0xff7b91aa.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy + 2, 19f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = if (dark) 0xe6d5e2f2.toInt() else 0xd9304359.toInt()
        c.drawCircle(cx, cy + 2, 19f, paint)
        paint.color = if (dark) 0xff4ea1ff.toInt() else 0xff2e7fe6.toInt()
        paint.strokeWidth = 4.5f
        paint.strokeCap = Paint.Cap.ROUND
        c.drawArc(RectF(cx - 23, cy - 21, cx + 23, cy + 25), 194.4f, 151.2f, false, paint)
        drawEar(c, cx - 22, cy + 4, now, false)
        drawEar(c, cx + 22, cy + 4, now, true)
        paint.strokeCap = Paint.Cap.BUTT
        paint.alpha = if (dark) 51 else 71
        paint.color = Color.WHITE
        paint.strokeWidth = 1.3f
        val angle = atan2(ly, lx) * 180f / Math.PI.toFloat()
        c.drawArc(RectF(cx - 19, cy - 17, cx + 19, cy + 21), angle - 49f, 98f, false, paint)
        paint.alpha = 255
    }

    private fun drawEar(c: Canvas, x: Float, y: Float, now: Double, mirror: Boolean) {
        val phase = (now * 96.0) % 360.0 + if (mirror) 72.0 else 0.0
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f
        for (s in 0 until 6) { paint.color = hue(phase + s * 60.0); c.drawCircle(x, y, 6f - s - 0.5f, paint) }
    }

    private fun drawPlanet(c: Canvas, t: Double, r: Float, cx: Float, cy: Float, now: Double, p: SpacePreset,
        clearOfHead: (SpaceFrame) -> SpaceFrame) {
        val span = min(2.6, max(0.6, p.period * 0.55))
        paint.style = Paint.Style.FILL
        for (k in 42 downTo 1) {
            val u = k / 42.0
            val age = u * (0.35 + 0.65 * u)
            val f = clearOfHead(frameAt(t - span * age, p.period, p.phase, p.direction, p.elev, p.depth / 100.0))
            val pt = project(f, r, cx, cy)
            val taper = ((1 - u) * (1 - u)).toFloat()
            val dx = pt[0] - cx
            val dy = pt[1] - cy
            val len = max(1f, sqrt(dx * dx + dy * dy))
            val wobble = sin(u * 9.0 + now * 1.7).toFloat() * 2.2f * u.toFloat()
            paint.color = planetHue(f.azimuth * 180.0 / Math.PI)
            paint.alpha = (173f * taper).toInt()
            c.drawCircle(pt[0] - dy / len * wobble, pt[1] + dx / len * wobble, max(0.9f, 12f * pt[2] * (0.18f + 0.82f * taper)), paint)
        }
        paint.alpha = 255
        val f = clearOfHead(frameAt(t, p.period, p.phase, p.direction, p.elev, p.depth / 100.0))
        val pt = project(f, r, cx, cy)
        val pr = 5.36f * pt[2] * if (playing) 1f + 0.05f * sin(now * 4.2).toFloat() else 1f
        if (playing) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.4f
            for (k in 0 until 2) {
                val ph = ((now / 1.4 + k * 0.5) % 1.0).toFloat()
                paint.color = hue(40.0 + k * 160.0)
                paint.alpha = ((1 - ph) * 107).toInt()
                c.drawCircle(pt[0], pt[1], pr + ph * 22, paint)
            }
        }
        paint.style = Paint.Style.STROKE
        paint.alpha = 242
        // 保留原来的同心渐变；完整 360° 均匀分配到 42 个色环，不截断紫色段。
        for (s in 0 until 42) {
            val rw = max(1f, pr / 42)
            paint.strokeWidth = rw + 0.18f
            paint.color = planetHue(s * (360.0 / 42.0) - now * 96.0)
            val rr = pr - (s + 0.5f) * rw
            if (rr > 0) c.drawCircle(pt[0], pt[1], rr, paint)
        }
        paint.alpha = 255
    }

    /** 球本体的橙黄占约四成时间，红蓝各约一成；耳环仍保持标准彩虹色。 */
    private fun planetHue(value: Double): Int {
        val phase = (value % 360 + 360) % 360
        val mapped = if (phase < 36.0) phase * (60.0 / 36.0)
        else if (phase < 180.0) 60.0 + (phase - 36.0) * (20.0 / 144.0)
        else if (phase < 252.0) 80.0 + (phase - 180.0) * (120.0 / 72.0)
        else if (phase < 288.0) 200.0 + (phase - 252.0)
        else 300.0 + (phase - 288.0) * (60.0 / 72.0)
        return Color.HSVToColor(floatArrayOf(mapped.toFloat(), 1f, 1f))
    }
    private fun hue(value: Double): Int =
        Color.HSVToColor(floatArrayOf(((value % 360 + 360) % 360).toFloat(), 1f, 1f))
}

class CurveView(context: Context) : AnimatedCanvasView(context) {
    var preset: SpacePreset? = null
        set(value) { field = value; clock.sync(clock.seconds); invalidate() }
    var position: Double = 0.0
        set(value) { field = value; clock.sync(value); if (playing) updateAnimation() else invalidate() }
    override var playing: Boolean = false
        set(value) {
            if (field == value) return
            field = value; clock.playing = value; clock.sync(position); invalidate(); updateAnimation()
        }
    var dark = true
        set(value) { field = value; invalidate() }
    private val clock = Clock()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density

    private fun ys(t: Double, p: SpacePreset, h: Float): FloatArray {
        val range = earRange()
        val center = (range[0] + range[1]) / 2.0
        val half = max(0.0001, (range[1] - range[0]) / 2.0)
        val angle = p.direction * 2.0 * Math.PI * t / max(1.0, p.period) + p.phase * Math.PI / 180.0
        val gain = earGains(sin(angle))
        val f = frameAt(t, p.period, p.phase, p.direction, p.elev, p.depth / 100.0)
        val mid = h / 2f
        return floatArrayOf(
            mid - ((gain[0] - center) / half * (h - 26f) * 0.45f).toFloat(),
            mid - ((gain[1] - center) / half * (h - 26f) * 0.45f).toFloat(),
            mid - p.elev.toFloat() * cos(angle).toFloat() / 40f * (h - 26f) * 0.34f,
            mid - (f.far.toFloat() - 0.5f) * 2f * (p.depth.toFloat() / 100f) * (h - 26f) * 0.30f
        )
    }

    override fun onDraw(raw: Canvas) {
        val p = preset ?: return
        raw.save()
        raw.scale(d, d)
        val w = width / d
        val h = height / d
        val usable = w - 6f
        val span = max(2.0, p.period) * 2.0
        val colors = if (dark) intArrayOf(0xff4ea1ff.toInt(), 0xffff7a59.toInt(), 0xff4fd396.toInt(), 0xffa98ce8.toInt()) else intArrayOf(0xff1f6feb.toInt(), 0xffd9501e.toInt(), 0xff0f9d58.toInt(), 0xff7c5cd6.toInt())
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = if (dark) 0x38788496.toInt() else 0x42788496.toInt()
        raw.drawLine(0f, h / 2, w, h / 2, paint)
        for (index in listOf(3, 2, 0, 1)) {
            val path = Path()
            for (i in 0..120) {
                val x = usable * i / 120f
                val y = ys(span * i / 120.0, p, h)[index]
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            paint.color = colors[index]
            paint.strokeWidth = if (index <= 1) 2f else 1.2f
            paint.pathEffect = if (index >= 2) DashPathEffect(if (index == 2) floatArrayOf(4f, 3f) else floatArrayOf(2f, 3f), 0f) else null
            raw.drawPath(path, paint)
        }
        paint.pathEffect = null
        paint.style = Paint.Style.FILL
        paint.textSize = 9f
        paint.color = if (dark) 0xb8becada.toInt() else 0xc64c5869.toInt()
        paint.textAlign = Paint.Align.LEFT
        raw.drawText("0s", 1f, h - 2f, paint)
        paint.textAlign = Paint.Align.CENTER
        raw.drawText("${(span / 2).toInt()}s", usable / 2, h - 2f, paint)
        paint.textAlign = Paint.Align.RIGHT
        raw.drawText("${span.toInt()}s", usable, h - 2f, paint)
        val t = clock.now() % span
        val x = usable * (t / span).toFloat()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = if (dark) 0xccebf0f8.toInt() else 0xcc374150.toInt()
        paint.pathEffect = null
        raw.drawLine(x, 4f, x, h - 14f, paint)
        paint.pathEffect = null
        paint.style = Paint.Style.FILL
        val yy = ys(t, p, h)
        for (i in 0 until 3) { paint.color = colors[i]; raw.drawCircle(x, yy[i], 3f, paint) }
        raw.restore()
    }
}
