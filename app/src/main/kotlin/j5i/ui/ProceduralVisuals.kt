package j5i.spatial8d.ui

import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

internal data class FlowerMotion(val x: Double, val y: Double, val size: Double, val opening: Double, val phase: Double, val rotation: Double)
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
    val duration = 26.0 + flowerRandom(index, 0, 1) * 24.0
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
    return FlowerMotion((between(3) - .5) * 1.6, (between(4) - .5) * 1.6,
        .22 + between(5) * .10, opening, flowerRandom(index, 0, 6) * 2 * PI,
        (between(7) - .5) * PI * .7)
}

internal fun flowerPositions(time: Double, width: Double, height: Double): List<Pair<Double, Double>> {
    val frame = floor(time * 5.4)
    val blend = time * 5.4 - frame
    val first = flowerAvoidedPositions(frame / 4, width, height)
    val second = flowerAvoidedPositions((frame + 1) / 4, width, height)
    return first.indices.map { index ->
        first[index].first * (1 - blend) + second[index].first * blend to
            first[index].second * (1 - blend) + second[index].second * blend
    }
}

private fun flowerAvoidedPositions(time: Double, width: Double, height: Double): List<Pair<Double, Double>> {
    val unit = min(width, height)
    val limitX = (width / 2 - unit * .16).coerceAtLeast(0.0)
    val limitY = (height / 2 - unit * .16).coerceAtLeast(0.0)
    val positions = (0 until 5).map { index ->
        val motion = flowerMotion(index, time)
        doubleArrayOf(motion.x / .8 * limitX, motion.y / .8 * limitY)
    }
    repeat(12) {
        for (first in 0 until 5) for (second in first + 1 until 5) {
            val dx = positions[second][0] - positions[first][0]
            val dy = positions[second][1] - positions[first][1]
            val distance = kotlin.math.hypot(dx, dy).coerceAtLeast(.000001)
            val push = ((unit * .39 - distance) / 2).coerceAtLeast(0.0)
            val directionX = if (dx == 0.0 && dy == 0.0) 1.0 else dx / distance
            val directionY = dy / distance
            positions[first][0] = (positions[first][0] - directionX * push).coerceIn(-limitX, limitX)
            positions[first][1] = (positions[first][1] - directionY * push).coerceIn(-limitY, limitY)
            positions[second][0] = (positions[second][0] + directionX * push).coerceIn(-limitX, limitX)
            positions[second][1] = (positions[second][1] + directionY * push).coerceIn(-limitY, limitY)
        }
    }
    return positions.map { it[0] to it[1] }
}

internal data class StardustKind(val symbol: String, val simplified: String, val traditional: String, val english: String)
internal val stardustKinds = listOf(
    StardustKind("🌸", "樱花", "櫻花", "Cherry blossom"), StardustKind("💎", "钻石", "鑽石", "Diamond"),
    StardustKind("🐅", "老虎", "老虎", "Tiger"), StardustKind("🐉", "龙", "龍", "Dragon"),
    StardustKind("🦅", "雄鹰", "雄鷹", "Eagle"), StardustKind("🚀", "火箭", "火箭", "Rocket"),
    StardustKind("✈️", "飞机", "飛機", "Airplane"), StardustKind("🦋", "蝴蝶", "蝴蝶", "Butterfly"),
    StardustKind("🐋", "鲸鱼", "鯨魚", "Whale"), StardustKind("🌙", "月亮", "月亮", "Moon"),
    StardustKind("🌻", "向日葵", "向日葵", "Sunflower"), StardustKind("🪐", "行星", "行星", "Planet"),
    StardustKind("🌹", "玫瑰", "玫瑰", "Rose"), StardustKind("🌷", "郁金香", "鬱金香", "Tulip"),
    StardustKind("🌲", "松树", "松樹", "Pine tree"), StardustKind("🍁", "枫叶", "楓葉", "Maple leaf"),
    StardustKind("🐬", "海豚", "海豚", "Dolphin"), StardustKind("🐢", "海龟", "海龜", "Turtle"),
    StardustKind("🐘", "大象", "大象", "Elephant"), StardustKind("🦁", "狮子", "獅子", "Lion"),
    StardustKind("🐈", "猫", "貓", "Cat"), StardustKind("🐎", "骏马", "駿馬", "Horse"),
    StardustKind("🦉", "猫头鹰", "貓頭鷹", "Owl"), StardustKind("🦢", "天鹅", "天鵝", "Swan"),
    StardustKind("🐠", "热带鱼", "熱帶魚", "Tropical fish"), StardustKind("🐙", "章鱼", "章魚", "Octopus"),
    StardustKind("🚁", "直升机", "直升機", "Helicopter"), StardustKind("⛵", "帆船", "帆船", "Sailboat"),
    StardustKind("🏰", "城堡", "城堡", "Castle"), StardustKind("🎸", "吉他", "吉他", "Guitar"),
    StardustKind("🎵", "音符", "音符", "Music note"), StardustKind("❄️", "雪花", "雪花", "Snowflake")
)

internal fun stardustShape(epoch: Int): Int {
    val block = Math.floorDiv(epoch, stardustKinds.size)
    val order = IntArray(stardustKinds.size) { it }
    val random = java.util.Random(block.toLong())
    for (index in order.lastIndex - 1 downTo 2) {
        val other = 1 + random.nextInt(index)
        val value = order[index]
        order[index] = order[other]
        order[other] = value
    }
    return Math.floorMod(order[Math.floorMod(epoch, order.size)] + block * 3, order.size)
}

internal class StardustTimeline(private val complexity: DoubleArray) {
    private val starts = mutableListOf(0.0)

    fun duration(epoch: Int): Double = 6.0 + 4.0 * complexity[stardustShape(epoch)].coerceIn(0.0, 1.0) +
        2.0 * flowerRandom(epoch, 0, 31)

    fun position(time: Double): Pair<Int, Double> {
        val seconds = time.coerceAtLeast(0.0)
        while (starts.last() <= seconds) starts.add(starts.last() + duration(starts.lastIndex))
        val index = starts.binarySearch(seconds).let { if (it >= 0) it else -it - 2 }
        return index to ((seconds - starts[index]) / (starts[index + 1] - starts[index]))
    }
}

internal const val RING_SHAPE_COUNT = 22

internal fun ringShape(birth: Long, seed: Long): Int {
    var value = birth + seed
    value = (value xor (value ushr 30)) * -4658895280553007687L
    value = (value xor (value ushr 27)) * -7723592293110705685L
    return ((value xor (value ushr 31)) ushr 1).rem(RING_SHAPE_COUNT).toInt()
}

internal const val RING_SCENE_SECONDS = 10.0

internal fun ringScene(epoch: Long, seed: Long): Int {
    val block = Math.floorDiv(epoch, RING_SHAPE_COUNT.toLong())
    val order = IntArray(RING_SHAPE_COUNT) { it }
    val random = java.util.Random(seed xor block)
    for (index in order.lastIndex - 1 downTo 2) {
        val other = 1 + random.nextInt(index)
        val value = order[index]
        order[index] = order[other]
        order[other] = value
    }
    return Math.floorMod(order[Math.floorMod(epoch, RING_SHAPE_COUNT.toLong()).toInt()] + (block % RING_SHAPE_COUNT).toInt() * 3, RING_SHAPE_COUNT)
}

internal fun ringSceneBlend(time: Double): Double = flowerEase(((time / RING_SCENE_SECONDS).mod(1.0) - .8) / .2)

internal fun fieldTrackCount(epoch: Long, seed: Long): Int = 4 + ringShape(epoch, seed) % 9

internal fun ringPoint(shape: Int, angle: Double): Pair<Double, Double> {
    require(shape in 0 until RING_SHAPE_COUNT)
    if (shape == 19) {
        val sine = sin(angle)
        return 16 * sine * sine * sine / 18.0 to
            -(13 * cos(angle) - 5 * cos(2 * angle) - 2 * cos(3 * angle) - cos(4 * angle)) / 18.0
    }
    val radius = when (shape) {
        in 0..13 -> {
            val sides = shape + 3
            val sector = 2 * PI / sides
            cos(PI / sides) / cos((angle + PI / sides).mod(sector) - PI / sides)
        }
        in 14..18 -> {
            val points = shape - 10
            val fraction = (angle * points / (2 * PI)).mod(1.0)
            .55 + .45 * kotlin.math.abs(2 * fraction - 1)
        }
        20 -> .78 + .22 * cos(6 * angle)
        else -> 1.0
    }
    return cos(angle) * radius to sin(angle) * radius
}

internal class ProceduralVisuals {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    private val hsv = floatArrayOf(0f, .7f, 1f)
    private val ringSeed = java.util.Random().nextLong()
    private val ringShapes = Array(RING_SHAPE_COUNT) { shape ->
        Array(97) { step -> ringPoint(shape, step * 2 * PI / 96) }
    }
    private val stardustShapes by lazy {
        stardustKinds.map { kind ->
            val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 105f; textAlign = Paint.Align.CENTER; color = Color.WHITE }
            Canvas(bitmap).drawText(kind.symbol, 64f, 105f, glyphPaint)
            val pixels = IntArray(128 * 128)
            bitmap.getPixels(pixels, 0, 128, 0, 0, 128, 128)
            bitmap.recycle()
            val points = pixels.indices.filter { Color.alpha(pixels[it]) > 80 }.map { index ->
                ((index % 128 - 64) / 64f) to ((index / 128 - 64) / 64f)
            }.ifEmpty { listOf(0f to 0f) }
            val edges = (1 until 127).sumOf { y ->
                (1 until 127).count { x ->
                    val index = y * 128 + x
                    Color.alpha(pixels[index]) > 80 &&
                        (Color.alpha(pixels[index - 1]) <= 80 || Color.alpha(pixels[index + 1]) <= 80 ||
                            Color.alpha(pixels[index - 128]) <= 80 || Color.alpha(pixels[index + 128]) <= 80)
                }
            }
            points to edges
        }
    }
    private val stardustTimeline by lazy {
        val edges = stardustShapes.map { it.second }
        val minimum = edges.minOrNull() ?: 0
        val range = ((edges.maxOrNull() ?: 0) - minimum).coerceAtLeast(1)
        StardustTimeline(DoubleArray(edges.size) { (edges[it] - minimum).toDouble() / range })
    }

    fun draw(canvas: Canvas, width: Float, height: Float, mode: Int, time: Double,
        spectrum: DoubleArray, pulse: Float, density: Float, dark: Boolean, particleDensity: Float, intensity: Float,
        language: String) {
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
            38 -> {
                val shapes = stardustShapes
                val (epoch, progress) = stardustTimeline.position(time)
                val blend = flowerEase((progress - .75) / .25).toFloat()
                val current = shapes[stardustShape(epoch)].first
                val next = shapes[stardustShape(epoch + 1)].first
                val scale = unit * .33f
                val currentEffect = Math.floorMod(epoch * 7 + stardustShape(epoch), 4)
                val nextEffect = Math.floorMod((epoch + 1) * 7 + stardustShape(epoch + 1), 4)
                paint.style = Paint.Style.FILL
                for (index in 0 until 90) {
                    val starX = (flowerRandom(index, 0, 41) - .5) * width
                    val starY = (flowerRandom(index, 0, 42) - .5) * height
                    val sparkle = .5 + .5 * sin(time * (.45 + flowerRandom(index, 0, 43)) + index)
                    color(190f + (flowerRandom(index, 0, 44) * 95).toFloat(), (25 + sparkle * 65).toInt())
                    canvas.drawCircle(starX.toFloat(), starY.toFloat(), density * (.35f + sparkle.toFloat() * .45f), paint)
                }
                fun movement(shapeEpoch: Int, effect: Int, seed: Double, x: Float, y: Float): Pair<Float, Float> {
                    val wave = sin(time * .32 + seed * 6.0 + shapeEpoch)
                    val variation = when (effect) {
                        0 -> (x * wave * .012).toFloat() to (y * wave * .012).toFloat()
                        1 -> (sin(time * .27 + y * 4 + seed) * .012).toFloat() to 0f
                        2 -> 0f to (cos(time * .29 + x * 4 + seed) * .012).toFloat()
                        else -> (-y * wave * .012).toFloat() to (x * wave * .012).toFloat()
                    }
                    return variation
                }
                paint.style = Paint.Style.FILL
                for (index in 0 until (1600 * particleDensity).toInt().coerceIn(600, 2400)) {
                    val seed = flowerRandom(index, 0, 23)
                    val from = current[(seed * current.size).toInt().coerceAtMost(current.lastIndex)]
                    val to = next[(seed * next.size).toInt().coerceAtMost(next.lastIndex)]
                    val x = from.first + (to.first - from.first) * blend
                    val y = from.second + (to.second - from.second) * blend
                    val shimmer = .5 + .5 * sin(time * (1.4 + seed) + index * 2.4)
                    val fromMotion = movement(epoch, currentEffect, seed, x, y)
                    val toMotion = movement(epoch + 1, nextEffect, seed, x, y)
                    val driftX = fromMotion.first + (toMotion.first - fromMotion.first) * blend
                    val driftY = fromMotion.second + (toMotion.second - fromMotion.second) * blend
                    color(195f + (seed * 100).toFloat(), (65 + shimmer * 135 + energy * 25).toInt())
                    canvas.drawCircle((x + driftX) * scale, (y + driftY) * scale, density * (.35f + shimmer.toFloat() * .55f), paint)
                }
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = density * .8f
                canvas.save()
                canvas.rotate((8 * sin(time * .12)).toFloat())
                for (ring in 0 until 5) {
                    val radius = scale * (.78f + ring * .13f + .025f * sin(time * .3 + ring).toFloat())
                    val flattening = .27f + .035f * sin(time * .17 + ring * .5).toFloat()
                    color(205f + ring * 14f, 65 - ring * 7)
                    canvas.drawOval(-radius, -radius * flattening, radius, radius * flattening, paint)
                }
                canvas.restore()
                val kind = stardustKinds[stardustShape(if (blend < .5f) epoch else epoch + 1)]
                val label = when (language) {
                    AppLanguage.TRADITIONAL -> kind.traditional
                    AppLanguage.ENGLISH -> kind.english
                    else -> kind.simplified
                }
                paint.style = Paint.Style.FILL
                paint.textSize = density * 12f
                paint.textAlign = Paint.Align.RIGHT
                paint.color = if (dark) Color.argb(120, 230, 230, 230) else Color.argb(145, 55, 55, 55)
                canvas.drawText(label, width / 2f - density * 12f, height / 2f - density * 14f, paint)
                paint.textAlign = Paint.Align.LEFT
            }
            35 -> {
                for (index in 0 until 20) {
                    val motion = flowerMotion(index + 5, time * .8)
                    val centerX = (motion.x * width * .58).toFloat()
                    val centerY = (motion.y * height * .58).toFloat()
                    val size = unit * (.018f + flowerRandom(index, 0, 52).toFloat() * .018f)
                    val hue = (flowerRandom(index, 0, 53) * 360 + 28 * sin(time * (.05 + flowerRandom(index, 0, 54) * .05) + index)).toFloat()
                    paint.style = Paint.Style.FILL
                    for (petal in 0 until 5) {
                        val angle = petal * 2 * PI / 5 + motion.rotation
                        color(hue + petal * 3, 90)
                        canvas.drawCircle(centerX + (cos(angle) * size * .55).toFloat(),
                            centerY + (sin(angle) * size * .55).toFloat(), size * .5f, paint)
                    }
                    color(48f, 160)
                    canvas.drawCircle(centerX, centerY, size * .22f, paint)
                }
                val positions = flowerPositions(time, width.toDouble(), height.toDouble())
                for (flowerIndex in 0 until 5) {
                    val flower = flowerMotion(flowerIndex, time)
                    val position = positions[flowerIndex]
                    val centerX = position.first.toFloat()
                    val centerY = position.second.toFloat()
                    val flowerSize = (unit * (.13 + (flower.size - .22) * .2) * 1.5).toFloat()
                    val rotation = flower.phase + flower.rotation
                    val tilt = .35 + flower.opening * .75
                    val flowerHue = (flowerRandom(flowerIndex, 0, 55) * 360 +
                        45 * sin(time * (.045 + flowerRandom(flowerIndex, 0, 56) * .06) + flower.phase)).toFloat()
                    val petals = ArrayList<FlowerPetal>(24)
                    canvas.save()
                    canvas.translate(centerX, centerY)
                    paint.style = Paint.Style.FILL
                    color(flowerHue + 55f, 85 + (energy * 40).toInt())
                    canvas.drawCircle(0f, 0f, flowerSize * (.045f + energy * .008f), paint)
                    paint.style = Paint.Style.STROKE
                    for (layer in 2 downTo 0) {
                        val opening = flowerEase((flower.opening - layer * .045) / (1.0 - layer * .045))
                        val count = 7 + layer
                        val length = .70 + layer * .10
                        for (petal in 0 until count) {
                            val angle = 2 * PI * petal / count + rotation + layer * .13
                            val baseRadius = .025 + layer * .015
                            val radial = baseRadius + length * (.035 + opening * .95)
                            val tipHeight = length * (.98 - opening * .78)
                            val middleRadius = baseRadius + length * (.19 + opening * .36)
                            val middleHeight = length * (.55 - opening * .30)
                            val petalWidth = length * (.10 + opening * .16)
                            fun point(radius: Double, height: Double, sideways: Double): FlowerPoint = projectFlowerPoint(
                                cos(angle) * radius - sin(angle) * sideways, height,
                                sin(angle) * radius + cos(angle) * sideways, tilt, flowerSize)
                            val base = point(baseRadius, 0.0, 0.0)
                            val tip = point(radial, tipHeight, 0.0)
                            val left = point(middleRadius, middleHeight, -petalWidth)
                            val right = point(middleRadius, middleHeight, petalWidth)
                            petals.add(FlowerPetal(base, left, tip, right,
                                flowerHue + layer * 10f + petal * 1.3f,
                                (base.depth + tip.depth + left.depth + right.depth) / 4, layer))
                        }
                    }
                    for (petal in petals.sortedBy { it.depth }) {
                        path.rewind()
                        path.moveTo(petal.base.x, petal.base.y)
                        path.quadTo(petal.left.x, petal.left.y, petal.tip.x, petal.tip.y)
                        path.quadTo(petal.right.x, petal.right.y, petal.base.x, petal.base.y)
                        paint.style = Paint.Style.FILL
                        color(petal.hue, (85 + petal.layer * 8 + (petal.depth + .6) * 25).toInt())
                        canvas.drawPath(path, paint)
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = density * .75f
                        color(petal.hue + 18f, (100 + (petal.depth + .6) * 55 + energy * 40).toInt())
                        canvas.drawPath(path, paint)
                        if (petal.layer == 0) {
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
                val epoch = floor(time.coerceAtLeast(0.0) / RING_SCENE_SECONDS).toLong()
                val blend = ringSceneBlend(time.coerceAtLeast(0.0))
                val shape = ringShapes[ringScene(epoch, ringSeed)]
                val nextShape = ringShapes[ringScene(epoch + 1, ringSeed)]
                for (ring in 0 until 22) {
                    val progress = ring / 22.0 + time * .075
                    val depth = (progress - floor(progress)).toFloat()
                    val radius = unit * (.035f + depth * depth * .92f)
                    path.rewind()
                    for (step in 0..96) {
                        val x = ((shape[step].first * (1 - blend) + nextShape[step].first * blend) * radius).toFloat()
                        val y = ((shape[step].second * (1 - blend) + nextShape[step].second * blend) * radius).toFloat()
                        if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    color(195f + depth * 110f + (time * 5).toFloat(), (30 + depth * 150).toInt())
                    canvas.drawPath(path, paint)
                }
            }
            37 -> {
                paint.style = Paint.Style.FILL
                val clock = time.coerceAtLeast(0.0) / 16.0
                val epoch = floor(clock).toLong()
                val blend = flowerEase(((clock - floor(clock)) - .875) / .125)
                val current = fieldTrackCount(epoch, ringSeed)
                val next = fieldTrackCount(epoch + 1, ringSeed)
                val trackCount = current + (next - current) * blend
                val slots = maxOf(current, next)
                for (index in 0 until (850 * particleDensity).toInt().coerceIn(250, 1600)) {
                    val track = index % slots
                    val opacity = (trackCount - track).coerceIn(0.0, 1.0)
                    if (opacity == 0.0) continue
                    val seed = ((index / slots) * 0.61803398875) % 1.0
                    val travel = (seed + time * (.025 + (index % 7) * .002)) % 1.0
                    val angle = track * 2 * PI / trackCount + phase + travel * (3.0 + morph * 6.0)
                    val radius = unit * (.05 + travel * .67)
                    val bend = (sin(angle * 3 + phase * 2) * morph * .08 +
                        sin(angle * 5 - phase * 1.4 + travel * 8) * .025) * unit
                    val x = (cos(angle) * radius + bend).toFloat()
                    val y = (sin(angle) * radius * (.55 + .4 * sin(phase * .4) * sin(phase * .4))).toFloat()
                    color(180f + (travel * 170).toFloat() + (time * 6).toFloat(), ((45 + 185 * (1 - travel)) * opacity).toInt())
                    canvas.drawCircle(x, y, density * (.6f + (1 - travel).toFloat() * 1.5f + pulse), paint)
                    if (index % 13 == 0) {
                        color(200f + (travel * 120).toFloat(), (45 * opacity).toInt())
                        canvas.drawCircle(x, y, density * (2f + pulse * 1.5f), paint)
                    }
                }
                paint.style = Paint.Style.STROKE
            }
        }
        canvas.restore()
    }
}
