package j5i.spatial8d.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Shader
import android.content.Context
import j5i.spatial8d.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sin

internal class AdditionalVisuals(context: Context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val texture by lazy { BitmapFactory.decodeResource(context.resources, R.drawable.visual_nature_atlas) }
    private val flame by lazy {
        texture?.let { Bitmap.createBitmap(it, 0, 0, it.width / 2, it.height / 2) }
    }
    private val flameMesh = FloatArray((24 + 1) * (16 + 1) * 2)
    private val branches = Path()
    private val softTextureFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(.7f) })
    private var maskHeight = 0f
    private var maskSurface = 0
    private var mask: LinearGradient? = null
    private val source = Rect()
    private val target = RectF()
    private val hsv = floatArrayOf(0f, .7f, .95f)
    private val particleRandom = java.util.Random(810819L)
    private val particleX = DoubleArray(105) { particleRandom.nextDouble() }
    private val particleY = DoubleArray(105) { particleRandom.nextDouble() }
    private val particleDepth = FloatArray(105) { .35f + particleRandom.nextFloat() * .6f }
    private val particleSpeed = DoubleArray(105) { .75 + particleRandom.nextDouble() * .5 }
    private val rippleTimes = DoubleArray(24) { -100.0 }
    private var rippleIndex = 0
    private var lastBeat = 0L
    private var flashTime = -100.0
    private var nextDrop = 0.0
    private var noteDensity = 22f

    fun reset() {
        rippleTimes.fill(-100.0)
        rippleIndex = 0
        lastBeat = 0L
        flashTime = -100.0
        nextDrop = 0.0
        noteDensity = 22f
    }

    fun draw(canvas: Canvas, width: Float, height: Float, mode: Int, time: Double, spectrum: DoubleArray, rms: Float,
        pulse: Float, beat: Long, density: Float, dark: Boolean, surface: Int, particleDensity: Float = 1f) {
        val unit = min(width, height)
        val energy = (rms * 6f).coerceIn(0f, 1f)
        if (beat != 0L && beat != lastBeat) {
            lastBeat = beat
            rippleTimes[rippleIndex++ % rippleTimes.size] = time
            if (time - flashTime > 1.2) flashTime = time
        }
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.strokeWidth = density
        paint.textAlign = Paint.Align.CENTER
        when (mode) {
            18, 19, 30 -> {
                val targetNotes = 28f + energy * 52f
                noteDensity += (targetNotes - noteDensity) * .04f
                val count = ((if (mode == 19) 105 else if (mode == 18) 45 else noteDensity.toInt()) * particleDensity).toInt().coerceIn(1, particleX.size)
                for (index in 0 until count) {
                    val depth = if (mode == 30) .35f + (index % 7) * .1f else particleDepth[index]
                    val fall = if (mode == 30) -.045 else (.035 + depth * .055) * particleSpeed[index]
                    val startY = if (mode == 30) index * .618034 else particleY[index]
                    val y = ((startY + time * fall) % 1.0 + 1.0) % 1.0
                    val drift = if (mode == 18) time * (.025 + depth * .018) else 0.0
                    val sway = if (mode == 19) 0.0 else if (mode == 18) sin(time * .65 + index) * .025 else sin(time * .35 + index) * (.035 + energy * .06)
                    val startX = if (mode == 30) index * .414214 else particleX[index]
                    val x = (((startX + drift + sway) % 1.0 + 1.0) % 1.0).toFloat() * width
                    val cy = y.toFloat() * height
                    paint.alpha = (90 + depth * 120).toInt().coerceAtMost(230)
                    if (mode == 19) {
                        paint.color = if (dark) Color.WHITE else 0xff4286b8.toInt()
                        val radius = density * (.8f + depth * 2.1f)
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = density * .55f
                        for (arm in 0 until 3) {
                            val angle = arm * PI / 3.0 + time * .1
                            val dx = (cos(angle) * radius).toFloat()
                            val dy = (sin(angle) * radius).toFloat()
                            canvas.drawLine(x - dx, cy - dy, x + dx, cy + dy, paint)
                        }
                        paint.style = Paint.Style.FILL
                    } else if (mode == 18) {
                        paint.color = color(25f + index % 4 * 22f, dark)
                        canvas.save()
                        canvas.rotate((28 + sin(time * .7 + index) * 18 + index % 4 * 8).toFloat(), x, cy)
                        canvas.drawOval(x - density * 2.2f * depth, cy - density * 5.5f * depth,
                            x + density * 2.2f * depth, cy + density * 5.5f * depth, paint)
                        canvas.restore()
                    } else {
                        paint.color = color(180f + index * 13f, dark)
                        paint.textSize = density * (10f + depth * 10f)
                        canvas.drawText(if (index % 2 == 0) "♪" else "♫", x, cy, paint)
                    }
                }
            }
            20 -> {
                drawFlame(canvas, width, height, time, energy)
                drawTextureMask(canvas, width, height, surface)
                for (spark in 0 until 35) {
                    val y = ((spark * .618034 - time * (.08 + spark % 4 * .025)) % 1.0 + 1.0) % 1.0
                    val x = ((spark * .414214 + sin(time * .8 + spark) * .025 + 1) % 1.0).toFloat() * width
                    paint.color = 0xffffc36a.toInt()
                    paint.alpha = (60 + energy * 130 + pulse * 50).toInt().coerceAtMost(220)
                    canvas.drawCircle(x, y.toFloat() * height, density * (.4f + spark % 3 * .3f), paint)
                }
            }
            21 -> {
                drawTexture(canvas, 1, width, height, time, 255)
                drawTextureMask(canvas, width, height, surface)
                val age = time - flashTime
                if (age in 0.0..0.28) {
                    val fade = (1.0 - age / .28).toFloat()
                    paint.style = Paint.Style.STROKE
                    paint.strokeCap = Paint.Cap.ROUND
                    paint.strokeJoin = Paint.Join.ROUND
                    path.rewind()
                    branches.rewind()
                    var x = width * (.35f + .3f * ((sin(flashTime * 3.7) + 1.0) / 2.0).toFloat())
                    for (step in 0..18) {
                        val y = height * (.08f + step * .047f)
                        if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        if (step in 3..14 && step % 3 == 0) {
                            var bx = x
                            var by = y
                            branches.moveTo(bx, by)
                            val direction = if (step % 2 == 0) 1f else -1f
                            for (part in 1..5) {
                                bx += width * (direction * .025f + sin(flashTime * 11 + step * 7 + part * 4).toFloat() * .012f)
                                by += height * (.025f + (part % 2) * .006f)
                                branches.lineTo(bx, by)
                            }
                        }
                        x += width * (sin(flashTime * 13 + step * 17.3).toFloat() * .025f + sin(step * 2.1 + flashTime).toFloat() * .008f)
                    }
                    paint.color = 0xff83aaff.toInt()
                    paint.alpha = (fade * 55).toInt()
                    paint.strokeWidth = density * 5f
                    canvas.drawPath(path, paint)
                    paint.color = 0xffeaf4ff.toInt()
                    paint.alpha = (fade * 200).toInt()
                    paint.strokeWidth = density * .9f
                    canvas.drawPath(path, paint)
                    paint.alpha = (fade * 145).toInt()
                    paint.strokeWidth = density * .45f
                    canvas.drawPath(branches, paint)
                    paint.strokeCap = Paint.Cap.BUTT
                }
            }
            22 -> {
                if (time >= nextDrop && energy > .015f) {
                    rippleTimes[rippleIndex++ % rippleTimes.size] = time
                    nextDrop = time + .25 + (1f - energy) * .45
                }
                paint.style = Paint.Style.STROKE
                for (index in rippleTimes.indices) {
                    val age = time - rippleTimes[index]
                    if (age !in 0.0..4.0) continue
                    val x = width * (.12f + ((index * 37) % 77) / 100f)
                    val y = height * (.15f + ((index * 53) % 70) / 100f)
                    if (age < .28) {
                        paint.style = Paint.Style.FILL
                        paint.color = 0xffb8edff.toInt()
                        paint.alpha = 210
                        val dropY = y - height * .35f * (1f - (age / .28).toFloat())
                        canvas.drawOval(x - density, dropY - density * 3f, x + density, dropY + density, paint)
                        paint.style = Paint.Style.STROKE
                    }
                    paint.color = color(180f + index * 7f, dark)
                    paint.alpha = ((1.0 - age / 4.0) * 180).toInt()
                    paint.strokeWidth = density * 1.1f
                    for (ring in 0..2) {
                        val radius = (age * unit * .12 - ring * density * 5).toFloat()
                        if (radius > 0) canvas.drawOval(x - radius, y - radius * .55f, x + radius, y + radius * .55f, paint)
                    }
                }
            }
            23, 25 -> {
                drawTexture(canvas, if (mode == 23) 2 else 3, width, height, time, 255)
                drawTextureMask(canvas, width, height, surface)
            }
            26, 31 -> {
                paint.style = Paint.Style.STROKE
                for (line in 0 until 28) {
                    val depth = ((line / 28.0 + time * (.045 + energy * .09)) % 1.0).toFloat()
                    val level = spectrum[line * 255 / 27].toFloat()
                    paint.color = color(180f + line * 5f, dark)
                    paint.alpha = (30 + depth * 150 + level * 50).toInt().coerceAtMost(220)
                    paint.strokeWidth = density * (.5f + depth)
                    if (mode == 26) {
                        val rx = width * depth * .52f
                        val ry = height * depth * .52f
                        canvas.drawOval(width / 2 - rx, height / 2 - ry, width / 2 + rx, height / 2 + ry, paint)
                    } else {
                        path.rewind()
                        for (step in 0..48) {
                            val x = width * step / 48f
                            val y = height * (.15f + depth * .8f + sin(step * .35 + time * 1.4 + line * .3).toFloat() * level * .10f)
                            if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        canvas.drawPath(path, paint)
                    }
                }
            }
            27 -> {
                paint.style = Paint.Style.STROKE
                for (beam in 0 until 18) {
                    val level = spectrum[beam * 255 / 17].toFloat()
                    paint.color = color(beam * 19f, dark)
                    paint.alpha = (70 + level * 160).toInt()
                    paint.strokeWidth = density * (1f + level * 2f)
                    canvas.drawLine(width * .5f, height * .5f, width, height * (.05f + beam * .05f), paint)
                }
                paint.color = color(190f, dark)
                paint.alpha = 180
                path.rewind(); path.moveTo(width * .47f, height * .32f)
                path.lineTo(width * .37f, height * .65f); path.lineTo(width * .6f, height * .65f); path.close()
                canvas.drawPath(path, paint)
                canvas.drawLine(0f, height * .5f, width * .42f, height * .5f, paint)
            }
            28, 29, 33 -> {
                paint.style = Paint.Style.STROKE
                for (line in 0 until if (mode == 33) 12 else 20) {
                    val level = spectrum[line * 255 / (if (mode == 33) 11 else 19)].toFloat()
                    paint.color = color(180f + line * 15f + energy * 40f, dark)
                    paint.alpha = (45 + level * 150).toInt()
                    paint.strokeWidth = density * (.6f + level * 1.4f)
                    path.rewind()
                    for (step in 0..80) {
                        val u = step / 80.0
                        val angle = if (mode == 33) line * PI / 6 + u * 2.0 else u * 8.0 + line * .6 + time * .3
                        val radius = if (mode == 29) unit * (.04 + u * .45) else unit * (.08 + .36 * u + level * .06)
                        val x = if (mode == 28) width * u + sin(angle + time * .2) * width * .06 else width / 2 + cos(angle) * radius
                        val y = if (mode == 28) height * (.5 + sin(angle + line) * (.12 + u * .3)) else height / 2 + sin(angle) * radius
                        if (step == 0) path.moveTo(x.toFloat(), y.toFloat()) else path.lineTo(x.toFloat(), y.toFloat())
                    }
                    canvas.drawPath(path, paint)
                }
            }
            32 -> {
                for (layer in 0 until 8) {
                    path.rewind()
                    for (step in 0..120) {
                        val angle = step * 2.0 * PI / 120
                        val level = spectrum[step * 255 / 120].toFloat()
                        val radius = unit * (.17 + layer * .018 + sin(angle * 5 + time * .5 + layer) * .025 + level * .05)
                        val x = width / 2 + cos(angle) * radius
                        val y = height / 2 + sin(angle) * radius
                        if (step == 0) path.moveTo(x.toFloat(), y.toFloat()) else path.lineTo(x.toFloat(), y.toFloat())
                    }
                    path.close()
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = density * (1f + layer * .3f)
                    paint.color = color(185f + layer * 16f, dark)
                    paint.alpha = 60 + layer * 18
                    canvas.drawPath(path, paint)
                }
            }
        }
        paint.alpha = 255
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawTexture(canvas: Canvas, quadrant: Int, width: Float, height: Float, time: Double, alpha: Int) {
        val bitmap = texture ?: return
        val halfWidth = bitmap.width / 2
        val halfHeight = bitmap.height / 2
        source.set((quadrant % 2) * halfWidth, (quadrant / 2) * halfHeight,
            (quadrant % 2 + 1) * halfWidth, (quadrant / 2 + 1) * halfHeight)
        val scale = max(width / halfWidth, height / halfHeight) * 1.18f
        val drawWidth = halfWidth * scale
        val drawHeight = halfHeight * scale
        val speed = if (quadrant == 2) .38 else .09
        val offset = (sin(time * speed) * (drawWidth - width) * .42).toFloat()
        target.set((width - drawWidth) / 2f + offset, (height - drawHeight) / 2f,
            (width + drawWidth) / 2f + offset, (height + drawHeight) / 2f)
        paint.style = Paint.Style.FILL
        paint.alpha = alpha * 210 / 255
        paint.colorFilter = softTextureFilter
        paint.isFilterBitmap = true
        canvas.save()
        canvas.clipRect(0f, 0f, width, height)
        canvas.drawBitmap(bitmap, source, target, paint)
        canvas.restore()
        paint.colorFilter = null
    }

    private fun drawFlame(canvas: Canvas, width: Float, height: Float, time: Double, energy: Float) {
        val bitmap = flame ?: return
        val scale = max(width / bitmap.width, height / bitmap.height) * 1.25f
        val speed = time * 2.8
        for (row in 0..16) {
            val vertical = row / 16f
            for (column in 0..24) {
                val horizontal = column / 24f
                val index = (row * 25 + column) * 2
                val movement = (1f - vertical) * (1f - vertical)
                flameMesh[index] = bitmap.width * (horizontal + movement * (.035f + energy * .012f) *
                    sin(speed + vertical * 7 + horizontal * 9).toFloat())
                flameMesh[index + 1] = bitmap.height * (vertical + movement * .025f *
                    sin(speed * 1.3 + vertical * 9 - horizontal * 6).toFloat())
            }
        }
        paint.style = Paint.Style.FILL
        paint.alpha = 210
        paint.colorFilter = softTextureFilter
        paint.isFilterBitmap = true
        canvas.save()
        canvas.clipRect(0f, 0f, width, height)
        canvas.translate((width - bitmap.width * scale) / 2f, (height - bitmap.height * scale) / 2f)
        canvas.scale(scale, scale)
        canvas.drawBitmapMesh(bitmap, 24, 16, flameMesh, 0, null, 0, paint)
        canvas.restore()
        paint.colorFilter = null
    }

    private fun drawTextureMask(canvas: Canvas, width: Float, height: Float, surface: Int) {
        if (height <= 0f) return
        if (mask == null || maskHeight != height || maskSurface != surface) {
            maskHeight = height
            maskSurface = surface
            val rgb = surface and 0x00ffffff
            mask = LinearGradient(0f, 0f, 0f, height,
                intArrayOf(rgb or 0x66000000, rgb or 0x18000000, rgb or 0x50000000),
                null, Shader.TileMode.CLAMP)
        }
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.shader = mask
        canvas.drawRect(0f, 0f, width, height, paint)
        paint.shader = null
    }

    private fun color(hue: Float, dark: Boolean): Int {
        hsv[0] = hue.mod(360f)
        hsv[2] = if (dark) .95f else .58f
        return Color.HSVToColor(hsv)
    }
}
