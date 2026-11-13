package j5i.spatial8d.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

internal data class FlowerMotion(val x: Double, val y: Double, val size: Double, val opening: Double, val phase: Double)
internal data class FlowerPoint(val x: Float, val y: Float, val depth: Double)
private data class FlowerPetal(val base: FlowerPoint, val left: FlowerPoint, val tip: FlowerPoint,
    val right: FlowerPoint, val hue: Float, val depth: Double, val layer: Int)

internal fun projectFlowerPoint(x: Double, height: Double, z: Double, tilt: Double, size: Float): FlowerPoint {
    val vertical = height * cos(tilt) - z * sin(tilt)
    val depth = z * cos(tilt) + height * sin(tilt)
    val perspective = 2.8 / (2.8 - depth.coerceIn(-1.0, 1.0) * .65)
    return FlowerPoint((x * size * perspective).toFloat(), (-vertical * size * perspective).toFloat(), depth)
}

private fun flowerRandom(index: Int, cycle: Long, salt: Int): Double {
    val value = sin(index * 91.73 + cycle * 37.19 + salt * 53.13) * 43758.5453
    return value - floor(value)
}

private fun flowerEase(value: Double): Double {
    val progress = value.coerceIn(0.0, 1.0)
    return progress * progress * (3.0 - 2.0 * progress)
}

internal fun flowerMotion(index: Int, time: Double): FlowerMotion {
    val duration = 13.0 + flowerRandom(index, 0, 1) * 17.0
    val clock = time / duration + flowerRandom(index, 0, 2)
    val cycle = floor(clock).toLong()
    val phase = clock - floor(clock)
    val blend = flowerEase(phase)
    fun between(salt: Int): Double = flowerRandom(index, cycle, salt) * (1 - blend) + flowerRandom(index, cycle + 1, salt) * blend
    val opening = when {
        phase < .15 -> .12 * flowerEase(phase / .15)
        phase < .55 -> .12 + .88 * flowerEase((phase - .15) / .40)
        phase < .72 -> 1.0
        else -> 1.0 - flowerEase((phase - .72) / .28)
    }
    return FlowerMotion((between(3) - .5) * .85, (between(4) - .5) * .75,
        .36 + between(5) * .47, opening, flowerRandom(index, 0, 6) * 2 * PI)
}

internal fun flowerPosition(index: Int, time: Double): Pair<Double, Double> {
    val motion = flowerMotion(index, time)
    val rotation = (time * 8.5 + sin(time * .23) * 15.0) * PI / 180.0
    val angle = index * 2.0 * PI / 5.0 + rotation + motion.x * .35
    val radius = .72 + motion.y * .25 + sin(time * .10 + motion.phase) * .06
    return cos(angle) * radius to sin(angle) * radius
}

internal class ProceduralVisuals {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    private val hsv = floatArrayOf(0f, .7f, 1f)

    fun draw(canvas: Canvas, width: Float, height: Float, mode: Int, time: Double,
        spectrum: DoubleArray, pulse: Float, density: Float, dark: Boolean, particleDensity: Float, intensity: Float) {
        val unit = min(width, height)
        val bands = min(48, spectrum.size)
        var sum = 0.0
        for (index in 0 until bands) sum += spectrum[index]
        val energy = (if (bands > 0) sum / bands else 0.0).toFloat().times(intensity).coerceIn(0f, 1f)
        val phase = time * .19
        val morph = (.5 + .5 * sin(time * .17)).toFloat()
        fun color(hue: Float, alpha: Int) {
            hsv[0] = (hue % 360f + 360f) % 360f
            hsv[1] = if (dark) .65f else .8f
            hsv[2] = if (dark) 1f else .55f
            paint.color = Color.HSVToColor(alpha.coerceIn(0, 255), hsv)
        }
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        paint.strokeWidth = density * .8f
        when (mode) {
            35 -> {
                val globalSpin = (time * 8.5 + sin(time * .23) * 15.0).toFloat()
                for (index in 0 until 28) {
                    val drift = time * (.008 + flowerRandom(index, 0, 8) * .008)
                    val rawX = flowerRandom(index, 0, 9) + drift
                    val rawY = flowerRandom(index, 0, 10) + drift * .6
                    val x = ((rawX - floor(rawX) - .5) * width).toFloat()
                    val y = ((rawY - floor(rawY) - .5) * height).toFloat()
                    val petalSize = unit * (.018f + flowerRandom(index, 0, 11).toFloat() * .032f)
                    canvas.save()
                    canvas.translate(x, y)
                    canvas.rotate((time * (5 + index % 4) + index * 43).toFloat())
                    path.rewind()
                    path.moveTo(0f, -petalSize)
                    path.quadTo(petalSize * .75f, 0f, 0f, petalSize)
                    path.quadTo(-petalSize * .75f, 0f, 0f, -petalSize)
                    paint.style = Paint.Style.FILL
                    color(195f + index * 4f + (time * 3).toFloat(), 38 + (energy * 25).toInt())
                    canvas.drawPath(path, paint)
                    paint.style = Paint.Style.STROKE
                    canvas.restore()
                }
                for (flowerIndex in 0 until 5) {
                    val flower = flowerMotion(flowerIndex, time)
                    val position = flowerPosition(flowerIndex, time)
                    val centerX = (position.first * width * .46).toFloat()
                    val centerY = (position.second * height * .46).toFloat()
                    val flowerSize = (unit * flower.size * (1.12f + energy * .10f + pulse * .02f)).toFloat()
                    val rotation = time * (.09 + flowerIndex * .018) + flower.phase + globalSpin * PI / 180.0
                    val tilt = .78 + .22 * sin(time * .15 + flower.phase)
                    val petals = ArrayList<FlowerPetal>(110)
                    canvas.save()
                    canvas.translate(centerX, centerY)
                    paint.style = Paint.Style.FILL
                    color(42f + flowerIndex * 18f + (time * 6).toFloat(), 85 + (energy * 40).toInt())
                    canvas.drawCircle(0f, 0f, flowerSize * (.045f + energy * .008f), paint)
                    paint.style = Paint.Style.STROKE
                    for (layer in 6 downTo 0) {
                        val opening = flowerEase((flower.opening - layer * .035) / (1.0 - layer * .035))
                        val count = 12 + ((layer + flowerIndex) % 4) * 2
                        val length = (.65 + layer * .038) * (1 - layer * .025)
                        for (petal in 0 until count) {
                            val angle = 2 * PI * petal / count + rotation + layer * .13
                            val petalWave = sin(time * (.52 + layer * .025) + petal * 1.71 + flower.phase)
                            val baseRadius = .045 + layer * .017
                            val radial = baseRadius + length * (.13 + opening * .98)
                            val tipHeight = length * (.96 - opening * .78) + petalWave * .025
                            val middleRadius = baseRadius + length * (.09 + opening * .52)
                            val middleHeight = length * (.56 - opening * .25)
                            val petalWidth = length * (.14 + opening * .26)
                            fun point(radius: Double, height: Double, sideways: Double): FlowerPoint = projectFlowerPoint(
                                cos(angle) * radius - sin(angle) * sideways, height,
                                sin(angle) * radius + cos(angle) * sideways, tilt, flowerSize)
                            val base = point(baseRadius, 0.0, 0.0)
                            val tip = point(radial, tipHeight, 0.0)
                            val left = point(middleRadius, middleHeight, -petalWidth)
                            val right = point(middleRadius, middleHeight, petalWidth)
                            petals.add(FlowerPetal(base, left, tip, right,
                                185f + layer * 10f + petal * 1.3f + (time * 4).toFloat(),
                                (base.depth + tip.depth + left.depth + right.depth) / 4, layer))
                        }
                    }
                    for (petal in petals.sortedBy { it.depth }) {
                        path.rewind()
                        path.moveTo(petal.base.x, petal.base.y)
                        path.quadTo(petal.left.x, petal.left.y, petal.tip.x, petal.tip.y)
                        path.quadTo(petal.right.x, petal.right.y, petal.base.x, petal.base.y)
                        paint.style = Paint.Style.FILL
                        color(petal.hue, (35 + petal.layer * 5 + (petal.depth + .6) * 30).toInt())
                        canvas.drawPath(path, paint)
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = density * .75f
                        color(petal.hue + 18f, (100 + (petal.depth + .6) * 55 + energy * 40).toInt())
                        canvas.drawPath(path, paint)
                        if (petal.layer < 4) {
                            path.rewind()
                            path.moveTo(petal.base.x, petal.base.y)
                            path.lineTo(petal.tip.x, petal.tip.y)
                            color(petal.hue + 30f, 55 + (energy * 35).toInt())
                            paint.strokeWidth = density * .5f
                            canvas.drawPath(path, paint)
                        }
                    }
                    canvas.restore()
                }
            }
            36 -> {
                for (ring in 0 until 22) {
                    val depth = ((ring / 22.0 + time * .075) % 1.0).toFloat()
                    val radius = unit * (.035f + depth * depth * .92f)
                    path.rewind()
                    for (step in 0..96) {
                        val angle = step * 2 * PI / 96
                        val shape = 1 + morph * .16 * cos(angle * 4 + phase * 3) + energy * .1 * sin(angle * 8 - phase * 4)
                        val x = (cos(angle + phase * .2) * radius * shape + sin(phase + depth * 4) * unit * .07 * (1 - depth)).toFloat()
                        val y = (sin(angle + phase * .2) * radius * shape + cos(phase * .7 + depth * 3) * unit * .06 * (1 - depth)).toFloat()
                        if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    color(195f + depth * 110f + (time * 5).toFloat(), (30 + depth * 150).toInt())
                    canvas.drawPath(path, paint)
                }
            }
            37 -> {
                paint.style = Paint.Style.FILL
                for (index in 0 until (520 * particleDensity).toInt().coerceIn(130, 1040)) {
                    val seed = (index * 0.61803398875) % 1.0
                    val travel = (seed + time * (.025 + (index % 7) * .002)) % 1.0
                    val angle = index * 2.399963 + phase + travel * (3.0 + morph * 6.0)
                    val radius = unit * (.05 + travel * .67)
                    val bend = sin(angle * 3 + phase * 2) * morph * unit * .08
                    val x = (cos(angle) * radius + bend).toFloat()
                    val y = (sin(angle) * radius * (.55 + .4 * sin(phase * .4) * sin(phase * .4))).toFloat()
                    color(180f + (travel * 170).toFloat() + (time * 6).toFloat(), (45 + 185 * (1 - travel)).toInt())
                    canvas.drawCircle(x, y, density * (.6f + (1 - travel).toFloat() * 1.5f + pulse), paint)
                }
                paint.style = Paint.Style.STROKE
            }
        }
        canvas.restore()
    }
}
