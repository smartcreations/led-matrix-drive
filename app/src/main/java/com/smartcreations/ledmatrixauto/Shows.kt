package com.smartcreations.ledmatrixauto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Something to play on the LEDs that the app makes itself, with no server or AI. */
abstract class Show(val title: String, val game: Boolean = false) {
    /** The car's speed, for shows that react to it. */
    @Volatile var metersPerSecond = 0f

    /** Fills [px], a [w] x [h] grid of LEDs where 0 is off, for the clock time [t] in ms. */
    abstract fun render(px: IntArray, w: Int, h: Int, t: Long)

    /** Changes whenever the next frame would look different, so unchanged frames aren't drawn again. */
    open fun frameKey(t: Long): Long = t / 50

    /** A tap at ([x], [y]), both 0 to 1. */
    open fun tap(x: Float, y: Float) {}
}

/** A show drawn with Canvas on a 64 x 32 layout, so finer LEDs simply get smoother shapes. */
abstract class CanvasShow(title: String, game: Boolean = false) : Show(title, game) {
    private var bitmap: Bitmap? = null
    private var canvas = Canvas()
    protected val paint = Paint().apply { isAntiAlias = false }
    protected val letters = Paint().apply {
        isAntiAlias = false
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    abstract fun draw(c: Canvas, t: Long)

    final override fun render(px: IntArray, w: Int, h: Int, t: Long) {
        val b = bitmap?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            bitmap = it
            canvas = Canvas(it)
            canvas.scale(w / 64f, h / 32f)
        }
        canvas.drawColor(Color.BLACK)
        draw(canvas, t)
        b.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) if (px[i] and 0xffffff == 0) px[i] = 0
    }

    protected fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int) {
        letters.textSize = size
        letters.color = color
        c.drawText(s, x, y, letters)
    }

    protected fun rect(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        paint.color = color
        paint.style = Paint.Style.FILL
        c.drawRect(l, t, r, b, paint)
    }

    protected fun circle(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        paint.color = color
        paint.style = Paint.Style.FILL
        c.drawCircle(x, y, r, paint)
    }
}

class ClockShow : CanvasShow("Clock") {
    private val hours = SimpleDateFormat("h", Locale.getDefault())
    private val minutes = SimpleDateFormat("mm", Locale.getDefault())
    private val day = SimpleDateFormat("EEE MMM d", Locale.getDefault())

    override fun frameKey(t: Long) = t / 250

    override fun draw(c: Canvas, t: Long) {
        val now = Date()
        val color = Color.HSVToColor(floatArrayOf((t / 200f) % 360f, 0.7f, 1f))
        val colon = if (now.time % 1000 < 500) ":" else " "
        text(c, "${hours.format(now)}$colon${minutes.format(now)}", 32f, 19f, 19f, color)
        text(c, day.format(now).uppercase(), 32f, 29f, 8f, Color.LTGRAY)
        rect(c, 0f, 0f, (now.time % 60000) / 60000f * 64f, 1f, color)
    }
}

class CaliforniaShow : CanvasShow("California") {
    private val path = Path()

    override fun draw(c: Canvas, t: Long) {
        for (y in 0 until 32) {
            val k = (y / 22f).coerceAtMost(1f)
            rect(c, 0f, y.toFloat(), 64f, y + 1f, Color.rgb((70 + 185 * k).toInt(), (150 + 20 * k).toInt(), (235 - 125 * k).toInt()))
        }
        val spin = t / 3000f
        for (i in 0 until 8) {
            val a = spin + i * PI.toFloat() / 4
            paint.color = SUN_RAY
            paint.strokeWidth = 0.7f
            c.drawLine(44 + 5 * cos(a), 9 + 5 * sin(a), 44 + 7.5f * cos(a), 9 + 7.5f * sin(a), paint)
        }
        circle(c, 44f, 9f, 3.8f, SUN_RAY)
        circle(c, 44f, 9f, 2.8f, SUN)
        val drift = (t / 400f) % 90f
        for (x in floatArrayOf(10f, 55f)) {
            val cx = (x + drift) % 90f - 12f
            rect(c, cx, 5f, cx + 10f, 6.5f, Color.WHITE)
            rect(c, cx + 2f, 4f, cx + 7f, 5f, Color.WHITE)
        }
        ridge(c, 21f, 4.5f, 0.27f, MOUNTAIN_FAR)
        ridge(c, 24f, 3.5f, 0.41f, MOUNTAIN_NEAR)
        rect(c, 0f, 26f, 64f, 32f, SAND)
        palm(c, 12f, t)
        palm(c, 53f, t + 900)
    }

    private fun ridge(c: Canvas, base: Float, height: Float, width: Float, color: Int) {
        path.reset()
        path.moveTo(0f, 32f)
        for (x in 0..64 step 2) path.lineTo(x.toFloat(), base - height * abs(sin(x * width)) - height * 0.4f * abs(sin(x * width * 2.3f)))
        path.lineTo(64f, 32f)
        path.close()
        paint.color = color
        paint.style = Paint.Style.FILL
        c.drawPath(path, paint)
    }

    private fun palm(c: Canvas, x: Float, t: Long) {
        val sway = sin(t / 1100f) * 1.2f
        paint.color = TRUNK
        paint.strokeWidth = 1f
        c.drawLine(x, 30f, x + sway, 11f, paint)
        paint.color = FROND
        for (i in 0 until 6) {
            val a = PI.toFloat() * (i + 0.5f) / 6
            c.drawLine(x + sway, 11f, x + sway + 6 * cos(a) * 1.3f, 11f - 4 * sin(a) + 3f + sway * 0.4f, paint)
        }
    }

    private companion object {
        val SUN = Color.rgb(255, 225, 90)
        val SUN_RAY = Color.rgb(255, 165, 40)
        val MOUNTAIN_FAR = Color.rgb(120, 86, 130)
        val MOUNTAIN_NEAR = Color.rgb(112, 74, 62)
        val SAND = Color.rgb(205, 165, 105)
        val TRUNK = Color.rgb(120, 82, 46)
        val FROND = Color.rgb(40, 190, 80)
    }
}

class FireworksShow : Show("Fireworks") {
    override fun render(px: IntArray, w: Int, h: Int, t: Long) {
        px.fill(0)
        val newest = t / GAP
        for (burst in (newest - 3)..newest) {
            if (burst < 0) continue
            val r = Random(burst * 7919)
            val cx = (0.15f + 0.7f * r.nextFloat()) * w
            val cy = (0.2f + 0.4f * r.nextFloat()) * h
            val hue = r.nextFloat() * 360f
            val age = (t - burst * GAP) / 1000f
            if (age < RISE) {
                val y = h - (h - cy) * (age / RISE)
                dot(px, w, h, cx, y, Color.WHITE)
                continue
            }
            val blast = age - RISE
            val fade = 1f - blast / LIFE
            if (fade <= 0f) continue
            val color = Color.HSVToColor(floatArrayOf(hue, 0.75f, fade))
            repeat(46) {
                val a = r.nextFloat() * 2 * PI.toFloat()
                val v = (0.35f + 0.65f * r.nextFloat()) * h * 0.55f
                val x = cx + cos(a) * v * blast
                val y = cy + sin(a) * v * blast + 0.35f * h * blast * blast
                dot(px, w, h, x, y, color)
            }
        }
    }

    private fun dot(px: IntArray, w: Int, h: Int, x: Float, y: Float, color: Int) {
        val i = x.toInt()
        val j = y.toInt()
        if (i in 0 until w && j in 0 until h) px[j * w + i] = color
    }

    private companion object {
        const val GAP = 650L
        const val RISE = 0.5f
        const val LIFE = 1.3f
    }
}

class FlagsShow : Show("Flags") {
    private val flags: List<(Float, Float) -> Int> = listOf(::usa, ::france, ::mexico, ::japan, ::uk, ::brazil, ::canada, ::italy)
    private var skipped = 0

    override fun tap(x: Float, y: Float) {
        skipped++
    }

    override fun render(px: IntArray, w: Int, h: Int, t: Long) {
        val flag = flags[((t / HOLD_MS + skipped) % flags.size).toInt()]
        for (y in 0 until h) for (x in 0 until w) {
            val u = x / w.toFloat()
            val wave = sin(u * 9f - t / 260f)
            val v = (y / h.toFloat() + wave * 0.035f).coerceIn(0f, 0.999f)
            val c = flag(u, v)
            val light = 0.86f + 0.14f * wave
            px[y * w + x] = Color.rgb((Color.red(c) * light).toInt(), (Color.green(c) * light).toInt(), (Color.blue(c) * light).toInt())
        }
    }

    private fun usa(u: Float, v: Float): Int {
        if (u < 0.42f && v < 7 / 13f) {
            val row = (v * 22).toInt()
            val star = row % 2 == 1 && ((u * 36).toInt() + row / 2 * 2) % 4 == 1
            return if (star) WHITE else NAVY
        }
        return if ((v * 13).toInt() % 2 == 0) RED else WHITE
    }

    private fun france(u: Float, v: Float) = if (u < 1 / 3f) NAVY else if (u < 2 / 3f) WHITE else RED

    private fun italy(u: Float, v: Float) = if (u < 1 / 3f) GREEN else if (u < 2 / 3f) WHITE else RED

    private fun mexico(u: Float, v: Float): Int {
        if (u < 1 / 3f) return GREEN
        if (u >= 2 / 3f) return RED
        val d = sqrt(sq((u - 0.5f) * 2) + sq(v - 0.5f))
        return if (d < 0.14f) Color.rgb(150, 100, 40) else if (d < 0.19f) GREEN else WHITE
    }

    private fun japan(u: Float, v: Float) = if (sqrt(sq((u - 0.5f) * 2) + sq(v - 0.5f)) < 0.3f) RED else WHITE

    private fun uk(u: Float, v: Float): Int {
        val a = abs(u - 0.5f) * 2
        val b = abs(v - 0.5f) * 2
        if (a < 0.08f || b < 0.14f) return RED
        if (a < 0.16f || b < 0.26f) return WHITE
        return when {
            abs(a - b) < 0.09f -> RED
            abs(a - b) < 0.2f -> WHITE
            else -> NAVY
        }
    }

    private fun brazil(u: Float, v: Float): Int {
        val a = abs(u - 0.5f) * 2
        val b = abs(v - 0.5f) * 2
        if (sqrt(sq((u - 0.5f) * 2) + sq(v - 0.5f)) < 0.24f) return NAVY
        return if (a / 0.88f + b / 0.8f < 1f) YELLOW else GREEN
    }

    private fun canada(u: Float, v: Float): Int {
        if (u < 0.25f || u >= 0.75f) return RED
        val a = abs(u - 0.5f) * 4
        val y = v - 0.5f
        val leaf = (y > -0.34f && y < 0.2f && a < 0.62f - abs(sin(y * 15f)) * 0.3f) || (y >= 0.2f && y < 0.34f && a < 0.08f)
        return if (leaf) RED else WHITE
    }

    private fun sq(x: Float) = x * x

    private companion object {
        const val HOLD_MS = 5000L
        val RED = Color.rgb(205, 30, 45)
        val WHITE = Color.rgb(245, 245, 245)
        val NAVY = Color.rgb(20, 40, 120)
        val GREEN = Color.rgb(0, 130, 70)
        val YELLOW = Color.rgb(250, 210, 30)
    }
}

class FartShow(private val context: Context) : CanvasShow("Fart") {
    private var pressedAt = -10_000L
    private var clock = 0L
    private var presses = 0
    private var seed = 0

    override fun tap(x: Float, y: Float) {
        pressedAt = clock
        presses++
        seed = Random.nextInt()
        Sounds.fart(context)
    }

    override fun draw(c: Canvas, t: Long) {
        clock = t
        val age = (t - pressedAt) / 1000f
        val down = if (age < 0.18f) 1.5f else 0f
        circle(c, 32f, 17f, 13f, DARK_RED)
        circle(c, 32f, 15f + down, 13f, RED)
        circle(c, 28f, 10f + down, 3f, SHINE)
        text(c, "PRESS", 32f, 19f + down, 9f, Color.WHITE)
        if (presses > 0) text(c, "$presses", 56f, 30f, 8f, Color.LTGRAY)
        if (age < 1.2f) {
            val r = Random(seed)
            repeat(14) {
                val a = r.nextFloat() * 2 * PI.toFloat()
                val v = 10f + 18f * r.nextFloat()
                val x = 32 + cos(a) * v * age
                val y = 16 + sin(a) * v * age * 0.6f - 4 * age
                val size = (2.6f + r.nextFloat() * 2.4f) * (1f - age / 1.2f)
                circle(c, x, y, size, if (it % 3 == 0) CLOUD_BROWN else CLOUD_GREEN)
            }
        }
    }

    private companion object {
        val RED = Color.rgb(220, 40, 40)
        val DARK_RED = Color.rgb(110, 16, 20)
        val SHINE = Color.rgb(255, 130, 120)
        val CLOUD_GREEN = Color.rgb(130, 190, 60)
        val CLOUD_BROWN = Color.rgb(150, 120, 50)
    }
}

/** Faster stars the faster the car goes. */
class StarfieldShow : Show("Warp") {
    private val stars = List(90) { Triple(Random.nextFloat() * 2 - 1, Random.nextFloat() * 2 - 1, Random.nextFloat()) }
    private var travelled = 0f
    private var last = 0L

    override fun render(px: IntArray, w: Int, h: Int, t: Long) {
        val seconds = ((t - last) / 1000f).coerceIn(0f, 0.2f)
        last = t
        val speed = 0.12f + metersPerSecond * 0.06f
        travelled += seconds * speed
        px.fill(0)
        val trail = min(6, 1 + (metersPerSecond / 8).toInt())
        for ((x, y, z0) in stars) {
            val z = 1f - ((z0 + travelled) % 1f)
            for (i in 0 until trail) {
                val depth = (z + i * speed * 0.05f).coerceAtMost(1f) * 0.9f + 0.05f
                val sx = (w / 2f + x * w * 0.25f / depth).toInt()
                val sy = (h / 2f + y * h * 0.25f / depth).toInt()
                if (sx in 0 until w && sy in 0 until h) {
                    val v = ((1f - depth) * 255 / (i + 1)).toInt().coerceIn(40, 255)
                    px[sy * w + sx] = Color.rgb(v, v, min(255, v + 40))
                }
            }
        }
    }
}

class AquariumShow : CanvasShow("Aquarium") {
    override fun draw(c: Canvas, t: Long) {
        for (y in 0 until 32) rect(c, 0f, y.toFloat(), 64f, y + 1f, Color.rgb(4, 30 + y * 2, 90 + y * 3))
        rect(c, 0f, 29f, 64f, 32f, Color.rgb(190, 165, 100))
        paint.strokeWidth = 1f
        for (x in floatArrayOf(8f, 14f, 47f, 58f)) {
            paint.color = Color.rgb(30, 160, 80)
            var px = x
            var py = 30f
            for (i in 1..6) {
                val nx = x + sin(t / 700f + i * 0.9f + x) * 1.6f
                val ny = 30f - i * 2.6f
                c.drawLine(px, py, nx, ny, paint)
                px = nx
                py = ny
            }
        }
        for (i in 0 until 5) {
            val speed = 5f + i * 2f
            val right = i % 2 == 0
            val span = 80f
            val travel = (t / 1000f * speed + i * 17f) % span
            val x = if (right) travel - 8f else 72f - travel
            val y = 6f + i * 4.2f + sin(t / 600f + i) * 1.2f
            fish(c, x, y, if (right) 1f else -1f, Color.HSVToColor(floatArrayOf(20f + i * 55f, 0.8f, 1f)))
        }
        for (i in 0 until 8) {
            val x = 6f + i * 8f + sin(t / 500f + i) * 0.8f
            val y = 30f - ((t / 90f + i * 11f) % 32f)
            rect(c, x, y, x + 0.8f, y + 0.8f, Color.rgb(200, 235, 255))
        }
    }

    private fun fish(c: Canvas, x: Float, y: Float, dir: Float, color: Int) {
        paint.color = color
        paint.style = Paint.Style.FILL
        c.drawOval(x - 3f, y - 1.6f, x + 3f, y + 1.6f, paint)
        val tail = Path().apply {
            moveTo(x - 2.5f * dir, y)
            lineTo(x - 5f * dir, y - 1.8f)
            lineTo(x - 5f * dir, y + 1.8f)
            close()
        }
        c.drawPath(tail, paint)
        rect(c, x + 1.5f * dir, y - 0.7f, x + 2.2f * dir, y, Color.BLACK)
    }
}

class LavaShow : Show("Lava") {
    override fun render(px: IntArray, w: Int, h: Int, t: Long) {
        val s = t / 1400f
        val blobs = FloatArray(10) { i -> if (i % 2 == 0) 32f + 26f * sin(s * (0.7f + i * 0.08f) + i) else 16f + 13f * cos(s * (0.9f - i * 0.05f) + i * 2) }
        for (y in 0 until h) for (x in 0 until w) {
            val ux = x * 64f / w
            val uy = y * 32f / h
            var field = 0f
            for (i in 0 until 5) {
                val dx = ux - blobs[i * 2]
                val dy = (uy - blobs[i * 2 + 1]) * 1.3f
                field += 42f / (dx * dx + dy * dy + 6f)
            }
            px[y * w + x] = when {
                field > 1.5f -> Color.rgb(255, 225, 90)
                field > 0.9f -> Color.rgb(255, 120, 30)
                field > 0.55f -> Color.rgb(205, 30, 80)
                else -> Color.rgb(45, 8, 70)
            }
        }
    }
}

class CityShow : CanvasShow("Night City") {
    private val buildings = List(11) { i -> 6 + (Random(i * 31).nextInt(16)) }

    override fun draw(c: Canvas, t: Long) {
        circle(c, 52f, 6f, 3.4f, Color.rgb(235, 230, 190))
        circle(c, 50.6f, 5.2f, 3f, Color.BLACK)
        buildings.forEachIndexed { i, height ->
            val left = i * 6f
            val top = 32f - height - 2
            rect(c, left, top, left + 5.5f, 32f, Color.rgb(16, 18, 38))
            var y = top + 1
            while (y < 31) {
                for (col in 0..1) {
                    val on = Random((i * 100 + y.toInt() * 10 + col) + (t / 1600).toInt() * (if ((i + col) % 3 == 0) 1 else 0)).nextFloat() > 0.45f
                    if (on) rect(c, left + 1 + col * 2.2f, y, left + 2 + col * 2.2f, y + 1, Color.rgb(250, 200, 90))
                }
                y += 2f
            }
        }
        paint.color = Color.rgb(110, 140, 200)
        paint.strokeWidth = 0.5f
        for (i in 0 until 26) {
            val r = Random(i)
            val x = (r.nextFloat() * 74f - t / 60f * 0.5f).mod(74f) - 5f
            val y = (r.nextFloat() * 36f + t / 30f).mod(36f) - 3f
            c.drawLine(x, y, x - 0.8f, y + 2f, paint)
        }
    }
}

class WhackShow : CanvasShow("Whack", game = true) {
    private var clock = 0L
    private var score = 0
    private var startedAt = -1L
    private var targetAt = 0L
    private var cell = 0
    private var hitAt = -10_000L
    private var best = 0

    private val roundOver get() = startedAt >= 0 && clock - startedAt > ROUND_MS

    override fun tap(x: Float, y: Float) {
        if (startedAt < 0 || roundOver) {
            startedAt = clock
            score = 0
            nextTarget()
            return
        }
        val tapped = (x * COLUMNS).toInt().coerceAtMost(COLUMNS - 1) + (y * ROWS).toInt().coerceAtMost(ROWS - 1) * COLUMNS
        if (tapped == cell) {
            score++
            best = max(best, score)
            hitAt = clock
            nextTarget()
        }
    }

    private fun nextTarget() {
        cell = Random.nextInt(COLUMNS * ROWS)
        targetAt = clock
    }

    override fun draw(c: Canvas, t: Long) {
        clock = t
        if (startedAt < 0 || roundOver) {
            if (startedAt < 0) {
                text(c, "TAP THE", 32f, 10f, 8f, Color.WHITE)
                text(c, "GREEN BOX", 32f, 19f, 8f, Color.WHITE)
                rect(c, 24f, 22f, 40f, 30f, TARGET)
            } else {
                text(c, "SCORE $score", 32f, 12f, 9f, Color.rgb(255, 200, 60))
                text(c, "BEST $best", 32f, 21f, 7f, Color.LTGRAY)
                text(c, "TAP TO PLAY", 32f, 29f, 6.5f, Color.WHITE)
            }
            return
        }
        if (t - targetAt > max(450L, 1100L - score * 30)) nextTarget()
        val cw = 64f / COLUMNS
        val ch = 32f / ROWS
        for (i in 0 until COLUMNS * ROWS) {
            val x = (i % COLUMNS) * cw
            val y = (i / COLUMNS) * ch
            rect(c, x + 2f, y + 1.5f, x + cw - 2f, y + ch - 1.5f, if (i == cell) TARGET else Color.rgb(28, 32, 48))
        }
        if (t - hitAt < 150) rect(c, 0f, 0f, 64f, 1f, Color.WHITE)
        text(c, "$score", 32f, 19f, 8f, Color.WHITE)
        val left = (ROUND_MS - (t - startedAt)) / ROUND_MS.toFloat()
        rect(c, 0f, 31f, 64f * left, 32f, Color.rgb(255, 200, 60))
    }

    private companion object {
        const val COLUMNS = 4
        const val ROWS = 3
        const val ROUND_MS = 30_000L
        val TARGET = Color.rgb(60, 240, 120)
    }
}

class SnakeShow : CanvasShow("Snake", game = true) {
    private val body = ArrayDeque<Pair<Int, Int>>()
    private var dx = 1
    private var dy = 0
    private var wantX = 1
    private var wantY = 0
    private var food = 20 to 8
    private var steppedAt = 0L
    private var alive = false
    private var played = false

    private fun reset() {
        body.clear()
        repeat(4) { body.addLast(8 - it to 8) }
        dx = 1
        dy = 0
        wantX = 1
        wantY = 0
        alive = true
        played = true
        placeFood()
    }

    private fun placeFood() {
        do food = Random.nextInt(CELLS_W) to Random.nextInt(CELLS_H) while (food in body)
    }

    /** Turns toward whichever side of the screen was tapped. */
    override fun tap(x: Float, y: Float) {
        if (!alive) return reset()
        val across = x - 0.5f
        val down = (y - 0.5f)
        if (abs(across) > abs(down)) {
            if (dx == 0) { wantX = if (across > 0) 1 else -1; wantY = 0 }
        } else if (dy == 0) {
            wantY = if (down > 0) 1 else -1
            wantX = 0
        }
    }

    override fun draw(c: Canvas, t: Long) {
        if (!alive) {
            text(c, if (played) "SCORE ${body.size - 4}" else "TAP A SIDE", 32f, 10f, 8f, Color.rgb(60, 240, 120))
            text(c, "TO TURN", 32f, 19f, 8f, Color.WHITE)
            arrows(c, Color.rgb(255, 200, 60))
            text(c, "TAP TO PLAY", 32f, 29f, 6.5f, Color.LTGRAY)
            return
        }
        if (t - steppedAt > max(90L, 190L - body.size * 3)) {
            steppedAt = t
            step()
        }
        arrows(c, Color.rgb(50, 56, 80))
        rect(c, food.first * 2f, food.second * 2f, food.first * 2f + 2, food.second * 2f + 2, Color.rgb(255, 70, 60))
        body.forEachIndexed { i, (x, y) ->
            rect(c, x * 2f, y * 2f, x * 2f + 2, y * 2f + 2, if (i == 0) Color.rgb(170, 255, 170) else Color.rgb(50, 210, 100))
        }
    }

    /** The four places to tap, one arrow on each side of the screen. */
    private fun arrows(c: Canvas, color: Int) {
        paint.color = color
        c.drawPath(Path().apply { moveTo(1f, 16f); lineTo(5f, 12f); lineTo(5f, 20f); close() }, paint)
        c.drawPath(Path().apply { moveTo(63f, 16f); lineTo(59f, 12f); lineTo(59f, 20f); close() }, paint)
        c.drawPath(Path().apply { moveTo(32f, 1f); lineTo(28f, 5f); lineTo(36f, 5f); close() }, paint)
        c.drawPath(Path().apply { moveTo(32f, 31f); lineTo(28f, 27f); lineTo(36f, 27f); close() }, paint)
    }

    private fun step() {
        dx = wantX
        dy = wantY
        val (hx, hy) = body.first()
        val next = (hx + dx).mod(CELLS_W) to (hy + dy).mod(CELLS_H)
        if (next in body) {
            alive = false
            return
        }
        body.addFirst(next)
        if (next == food) placeFood() else body.removeLast()
    }

    private companion object {
        const val CELLS_W = 32
        const val CELLS_H = 16
    }
}

/** Glowing logs with flames rising off them, with a heat map carried from frame to frame. */
class FireplaceShow : Show("Fireplace") {
    /** Flames read fine at 15 frames a second, and it's the heaviest show to draw. */
    override fun frameKey(t: Long) = t / 66

    private var heat = FloatArray(0)
    private var fireW = 0
    private var fireH = 0

    override fun render(px: IntArray, w: Int, h: Int, t: Long) {
        if (fireW != w || fireH != h) {
            fireW = w
            fireH = h
            heat = FloatArray(w * h)
            repeat(h * 2) { burn(w, h) }
        }
        burn(w, h)
        val logTop = h * 26 / 32
        for (y in 0 until h) for (x in 0 until w) {
            val v = heat[y * w + x]
            px[y * w + x] = if (y >= logTop) log(x, y, w, h, t) else flame(v)
        }
    }

    private fun burn(w: Int, h: Int) {
        for (x in 0 until w) heat[(h - 1) * w + x] = if (Random.nextFloat() < 0.6f) 1f else 0.35f
        for (y in 0 until h - 1) for (x in 0 until w) {
            val below = heat[(y + 1) * w + x]
            val left = heat[(y + 1) * w + (x + w - 1) % w]
            val right = heat[(y + 1) * w + (x + 1) % w]
            val under = heat[min(h - 1, y + 2) * w + x]
            heat[y * w + x] = max(0f, (below + left + right + under) / 4f - 2.4f / h * Random.nextFloat())
        }
    }

    private fun flame(v: Float): Int = when {
        v > 0.72f -> Color.rgb(255, 240, 150)
        v > 0.5f -> Color.rgb(255, 170, 30)
        v > 0.3f -> Color.rgb(235, 80, 15)
        v > 0.14f -> Color.rgb(140, 24, 8)
        else -> 0
    }

    private fun log(x: Int, y: Int, w: Int, h: Int, t: Long): Int {
        val band = (y * 32 / h + (x * 64 / w) / 11) % 3
        val ember = sin(x * 1.7f + y * 2.1f + t / 240f) > 0.72f
        return when {
            ember -> Color.rgb(255, 120, 30)
            band == 0 -> Color.rgb(90, 50, 22)
            else -> Color.rgb(58, 32, 16)
        }
    }
}

class PumpkinShow : CanvasShow("Pumpkin") {
    override fun draw(c: Canvas, t: Long) {
        circle(c, 52f, 6f, 3.5f, Color.rgb(235, 230, 190))
        val orange = Color.rgb(240, 120, 20)
        for (dx in intArrayOf(-11, -6, 0, 6, 11)) {
            paint.color = if (dx == 0 || abs(dx) == 11) orange else Color.rgb(205, 95, 15)
            c.drawOval(30f + dx - 7f, 6f, 30f + dx + 7f, 30f, paint)
        }
        rect(c, 28.5f, 2f, 31.5f, 7f, Color.rgb(50, 120, 40))
        val flicker = (200 + 55 * abs(sin(t / 130f))).toInt()
        val glow = Color.rgb(flicker, flicker - 40, 40)
        triangle(c, 21f, 15f, 18f, 19f, 24f, 19f, glow)
        triangle(c, 39f, 15f, 36f, 19f, 42f, 19f, glow)
        rect(c, 19f, 23f, 41f, 26f, glow)
        rect(c, 25f, 23f, 27f, 24f, orange)
        rect(c, 33f, 25f, 35f, 26f, orange)
    }

    private fun triangle(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, color: Int) {
        paint.color = color
        c.drawPath(Path().apply { moveTo(x1, y1); lineTo(x2, y2); lineTo(x3, y3); close() }, paint)
    }
}

class SnowCabinShow : CanvasShow("Snow Cabin") {
    override fun draw(c: Canvas, t: Long) {
        rect(c, 0f, 0f, 64f, 24f, Color.rgb(10, 16, 44))
        rect(c, 0f, 24f, 64f, 32f, Color.rgb(225, 232, 245))
        rect(c, 20f, 15f, 42f, 25f, Color.rgb(100, 60, 34))
        paint.color = Color.rgb(235, 240, 250)
        c.drawPath(Path().apply { moveTo(17f, 16f); lineTo(31f, 7f); lineTo(45f, 16f); close() }, paint)
        val lit = (205 + 50 * sin(t / 300f)).toInt()
        rect(c, 24f, 18f, 28f, 22f, Color.rgb(lit, lit - 50, 60))
        rect(c, 34f, 18f, 38f, 22f, Color.rgb(lit, lit - 50, 60))
        for (i in 0 until 3) {
            val y = 5f - ((t / 70f + i * 22f) % 22f) * 0.25f
            circle(c, 38f + y * 0.1f + i, y + 3f, 0.7f, Color.rgb(120, 125, 140))
        }
        for (i in 0 until 34) {
            val r = Random(i)
            val x = (r.nextFloat() * 64f + sin(t / 900f + i) * 2f).mod(64f)
            val y = (r.nextFloat() * 32f + t / (45f + i)).mod(32f)
            rect(c, x, y, x + 0.8f, y + 0.8f, Color.WHITE)
        }
    }
}

class AuroraShow : Show("Aurora") {
    override fun render(px: IntArray, w: Int, h: Int, t: Long) {
        val s = t / 1800f
        for (x in 0 until w) {
            val u = x * 64f / w
            val top = 9f + 5f * sin(u * 0.13f + s) + 3f * sin(u * 0.31f - s * 1.4f)
            val hue = 130f + 60f * sin(u * 0.07f + s * 0.6f)
            for (y in 0 until h) {
                val v = y * 32f / h
                val light = ((v - top) / 13f).let { if (it < 0f || it > 1f) 0f else (1f - it) * (0.7f + 0.3f * sin(u * 0.9f + s * 3f)) }
                px[y * w + x] = when {
                    v > 26f -> Color.rgb(8, 14, 20)
                    light > 0.05f -> Color.HSVToColor(floatArrayOf(hue, 0.8f, light))
                    (x * 7 + y * 13) % 97 == 0 -> Color.rgb(190, 200, 220)
                    else -> Color.rgb(2, 4, 16)
                }
            }
        }
    }
}

class BeachShow : Show("Beach Sunset") {
    override fun render(px: IntArray, w: Int, h: Int, t: Long) {
        val s = t / 900f
        for (y in 0 until h) for (x in 0 until w) {
            val u = x * 64f / w
            val v = y * 32f / h
            px[y * w + x] = when {
                sqrt(sq(u - 32f) + sq(v - 15f)) < 7f && v < 17f -> Color.rgb(255, 215, 90)
                v < 17f -> Color.rgb(255 - (17 - v.toInt()) * 7, 110 + v.toInt() * 3, 120 - v.toInt() * 3)
                v > 28f + sin(u * 0.2f + s) * 1.2f -> Color.rgb(205, 165, 105)
                abs(u - 32f) < (v - 15f) * 1.1f && sin(v * 3f + s * 2) + sin(u * 1.1f - s) > 0.4f -> Color.rgb(255, 180, 90)
                sin(v * 1.6f + sin(u * 0.15f + s)) > 0.8f -> Color.rgb(60, 110, 160)
                else -> Color.rgb(25, 62, 120)
            }
        }
    }

    private fun sq(x: Float) = x * x
}

/** Falling columns of little glyphs. */
class CodeRainShow : Show("Code Rain") {
    override fun render(px: IntArray, w: Int, h: Int, t: Long) {
        px.fill(0)
        val cell = max(1, w / 64)
        for (column in 0 until w / cell) {
            if (column % 2 == 1) continue
            val r = Random(column * 37)
            val speed = 12f + r.nextFloat() * 22f
            val length = 8 + r.nextInt(14)
            val head = ((t / 1000f * speed + r.nextFloat() * 60f) % (h / cell + length + 8)).toInt()
            for (i in 0 until length) {
                val y = head - i
                if (y !in 0 until h / cell) continue
                if ((column * 31 + y * 17 + (t / 300).toInt()) % 5 == 0) continue
                val v = 255 - i * 200 / length
                val c = if (i == 0) Color.rgb(210, 255, 220) else Color.rgb(0, v, v / 4)
                for (dy in 0 until cell) for (dx in 0 until cell) px[(y * cell + dy) * w + column * cell + dx] = c
            }
        }
    }
}

class HelixShow : CanvasShow("Helix") {
    override fun draw(c: Canvas, t: Long) {
        val s = t / 500f
        for (x in 0..64 step 2) {
            val a = x * 0.22f + s
            val y1 = 16f + sin(a) * 11f
            val y2 = 16f - sin(a) * 11f
            if (x % 6 == 0) {
                paint.color = Color.rgb(90, 100, 130)
                paint.strokeWidth = 0.7f
                c.drawLine(x.toFloat(), y1, x.toFloat(), y2, paint)
            }
            val near = cos(a) > 0
            circle(c, x.toFloat(), y1, if (near) 1.6f else 1f, Color.rgb(60, 220, 240))
            circle(c, x.toFloat(), y2, if (near) 1f else 1.6f, Color.rgb(250, 90, 170))
        }
    }
}

/** A neon sun and a grid road that rushes faster with the car. */
class SynthRoadShow : CanvasShow("Neon Road") {
    private var travelled = 0f
    private var last = 0L

    override fun draw(c: Canvas, t: Long) {
        val seconds = ((t - last) / 1000f).coerceIn(0f, 0.2f)
        last = t
        travelled += seconds * (0.5f + metersPerSecond * 0.09f)
        for (y in 0 until 16) rect(c, 0f, y.toFloat(), 64f, y + 1f, Color.rgb(30 + y * 6, 8, 70 - y * 2))
        for (i in 0 until 8) {
            val y = 3f + i * 1.7f
            if (i % 2 == 0 || y < 8f) rect(c, 32f - sqrt(max(0f, 81f - sq(y - 11f))), y, 32f + sqrt(max(0f, 81f - sq(y - 11f))), y + 1.6f, Color.rgb(255, 210 - i * 22, 90))
        }
        rect(c, 0f, 16f, 64f, 32f, Color.rgb(12, 4, 30))
        paint.color = Color.rgb(240, 50, 200)
        paint.strokeWidth = 0.5f
        for (i in -8..8) c.drawLine(32f + i * 2.5f, 16f, 32f + i * 12f, 32f, paint)
        for (i in 0 until 6) {
            val z = ((i + travelled) % 6f) / 6f
            val y = 16f + 16f * z * z
            c.drawLine(0f, y, 64f, y, paint)
        }
    }

    private fun sq(x: Float) = x * x
}

class StormShow : CanvasShow("Lightning") {
    override fun draw(c: Canvas, t: Long) {
        val slot = t / 2600
        val r = Random(slot)
        val age = t % 2600
        val flash = age < 170 && r.nextFloat() < 0.8f
        val sky = if (flash) Color.rgb(70, 76, 120) else Color.rgb(16, 18, 36)
        rect(c, 0f, 0f, 64f, 32f, sky)
        for (i in 0 until 7) {
            val x = i * 11f - (t / 200f) % 11f
            circle(c, x, 3f + i % 2 * 2f, 6f, Color.rgb(44, 46, 64))
        }
        if (flash) {
            paint.color = Color.rgb(250, 250, 200)
            paint.strokeWidth = 0.9f
            var x = 12f + r.nextFloat() * 40f
            var y = 6f
            while (y < 30f) {
                val nx = x + (r.nextFloat() - 0.5f) * 8f
                val ny = y + 3f + r.nextFloat() * 3f
                c.drawLine(x, y, nx, ny, paint)
                x = nx
                y = ny
            }
        }
        paint.color = Color.rgb(90, 120, 180)
        paint.strokeWidth = 0.5f
        for (i in 0 until 22) {
            val q = Random(i)
            val x = (q.nextFloat() * 70f - t / 90f).mod(70f)
            val y = (q.nextFloat() * 34f + t / 26f).mod(34f)
            c.drawLine(x, y, x - 0.8f, y + 2f, paint)
        }
    }
}

class BalloonsShow : CanvasShow("Balloons") {
    override fun draw(c: Canvas, t: Long) {
        for (y in 0 until 32) rect(c, 0f, y.toFloat(), 64f, y + 1f, Color.rgb(110 + y * 3, 180 + y, 240 - y))
        rect(c, 0f, 27f, 64f, 32f, Color.rgb(90, 160, 80))
        for (i in 0 until 4) {
            val x = (9f + i * 17f + t / (900f + i * 250f)).mod(76f) - 6f
            val y = 11f + i % 2 * 5f + sin(t / 1300f + i) * 2f
            val hue = i * 75f + 10f
            circle(c, x, y, 5f, Color.HSVToColor(floatArrayOf(hue, 0.85f, 1f)))
            rect(c, x - 1.2f, y - 5f, x + 1.2f, y + 5f, Color.HSVToColor(floatArrayOf(hue + 40f, 0.7f, 1f)))
            rect(c, x - 1.3f, y + 6.5f, x + 1.3f, y + 8.5f, Color.rgb(120, 76, 40))
        }
    }
}

class CatShow : CanvasShow("Napping Cat") {
    override fun draw(c: Canvas, t: Long) {
        rect(c, 0f, 0f, 64f, 32f, Color.rgb(44, 34, 60))
        rect(c, 0f, 26f, 64f, 32f, Color.rgb(110, 60, 70))
        val breath = sin(t / 900f) * 0.8f
        val orange = Color.rgb(240, 150, 60)
        paint.color = orange
        c.drawOval(14f, 13f - breath, 44f, 27f, paint)
        circle(c, 44f, 18f, 6.5f, orange)
        paint.color = orange
        c.drawPath(Path().apply { moveTo(39f, 15f); lineTo(40f, 9f); lineTo(44f, 13f); close() }, paint)
        c.drawPath(Path().apply { moveTo(44f, 13f); lineTo(49f, 9f); lineTo(50f, 15f); close() }, paint)
        rect(c, 40f, 18f, 43f, 18.8f, Color.rgb(60, 30, 20))
        rect(c, 46f, 18f, 49f, 18.8f, Color.rgb(60, 30, 20))
        for (i in 0 until 3) rect(c, 22f + i * 7f, 14f - breath, 24f + i * 7f, 20f, Color.rgb(200, 110, 40))
        val rise = (t / 90f) % 18f
        text(c, "Z", 52f + rise * 0.3f, 14f - rise * 0.6f, 6f + rise * 0.1f, Color.rgb(200, 210, 255))
    }
}

class PlanetsShow : CanvasShow("Planets") {
    override fun draw(c: Canvas, t: Long) {
        for (i in 0 until 30) {
            val r = Random(i * 5)
            val x = r.nextFloat() * 64f
            val y = r.nextFloat() * 32f
            rect(c, x, y, x + 0.6f, y + 0.6f, Color.rgb(170, 170, 190))
        }
        circle(c, 32f, 16f, 4.5f, Color.rgb(255, 205, 70))
        val orbits = listOf(Triple(10f, 1.2f, Color.rgb(200, 140, 90)), Triple(16f, 1.8f, Color.rgb(70, 150, 240)), Triple(22f, 1.5f, Color.rgb(230, 80, 60)), Triple(28f, 2.4f, Color.rgb(230, 190, 120)))
        orbits.forEachIndexed { i, (radius, size, color) ->
            val a = t / (900f + i * 700f) + i * 1.4f
            circle(c, 32f + cos(a) * radius, 16f + sin(a) * radius * 0.42f, size, color)
        }
    }
}

/** One of Tronbyt's community apps, rendered ahead of time by Pixlet at both LED sizes. */
class ClipShow(private val context: Context, private val name: String) : Show(name) {
    private val cache = HashMap<String, Animation>()

    private fun clip(size: String) = cache.getOrPut(size) {
        Animation.fromLmx(context.assets.open("tronbyt/$name-$size.lmx").use { it.readBytes() }, name, "")
    }

    override fun frameKey(t: Long) = clip("1x").frameIndexAt(t).toLong()

    override fun render(px: IntArray, w: Int, h: Int, t: Long) {
        val a = clip(if (w >= 128) "2x" else "1x")
        if (a.width == w && a.height == h) a.frames[a.frameIndexAt(t)].copyInto(px)
    }
}

/**
 * A scene that moves with the car, like the Car tab. Parked, the camera sits close on the subject at
 * ([focusX], [focusY]). Moving, it pulls back over the same 1.5 s, the world goes by at the car's
 * speed, and the speed shows in the top right corner.
 */
abstract class SpeedShow(
    title: String,
    private val parkedZoom: Float,
    private val focusX: Float,
    private val focusY: Float,
) : CanvasShow(title) {
    /** The car's display unit. */
    @Volatile var unit = "mph"

    /** How far it's gone, in units of the 64 x 32 layout. */
    protected var travelled = 0f
        private set
    protected var mph = 0f
        private set

    /** 0 parked and close, 1 moving and pulled back. */
    protected var camera = 0f
        private set

    private var smoothed = 0f
    private var last = 0L
    private var moving = false
    private var pullBack = 0f

    override fun frameKey(t: Long) = if (pullBack == 0f && smoothed < 0.01f && metersPerSecond <= 0f) t / 250 else t / 33

    /** Draws everything in the 64 x 32 layout; the camera is already applied. */
    abstract fun scene(c: Canvas, t: Long)

    final override fun draw(c: Canvas, t: Long) {
        val seconds = ((t - last) / 1000f).coerceIn(0f, 0.2f)
        last = t
        smoothed += (metersPerSecond - smoothed) * min(1f, seconds * SMOOTHING)
        travelled += smoothed * UNITS_PER_METER * seconds
        mph = smoothed * MPH
        if (mph > START_MPH) moving = true else if (mph < STOP_MPH) moving = false
        pullBack = (pullBack + (if (moving) seconds else -seconds) / CAMERA_SECONDS).coerceIn(0f, 1f)
        camera = pullBack * pullBack * (3 - 2 * pullBack)

        val zoom = parkedZoom + (1f - parkedZoom) * camera
        c.save()
        c.translate(32f, 16f)
        c.scale(zoom, zoom)
        c.translate(-(focusX + (32f - focusX) * camera), -(focusY + (16f - focusY) * camera))
        scene(c, t)
        c.restore()

        if (camera > 0f && mph >= 0.5f) {
            val alpha = (camera * 255).toInt()
            val shown = (smoothed * (if (unit == "mph") MPH else KMH)).roundToInt().toString()
            letters.textAlign = Paint.Align.RIGHT
            letters.textSize = 7.5f
            val digitsWidth = letters.measureText(shown)
            letters.textSize = 5f
            val width = max(digitsWidth, letters.measureText(unit.uppercase()))
            paint.color = Color.argb(alpha * 2 / 3, 8, 10, 20)
            paint.style = Paint.Style.FILL
            c.drawRoundRect(62f - width - 2f, 0.6f, 63.6f, 13.6f, 3.2f, 3.2f, paint)
            text(c, shown, 62f, 7f, 7.5f, Color.argb(alpha, 255, 255, 255))
            text(c, unit.uppercase(), 62f, 12.6f, 5f, Color.argb(alpha, 205, 210, 220))
            letters.textAlign = Paint.Align.CENTER
        }
    }

    protected fun path(c: Canvas, color: Int, vararg points: Float) {
        paint.color = color
        paint.style = Paint.Style.FILL
        val shape = Path()
        shape.moveTo(points[0], points[1])
        for (i in 2 until points.size step 2) shape.lineTo(points[i], points[i + 1])
        shape.close()
        c.drawPath(shape, paint)
    }

    /** [x] moved along by [offset] and wrapped to stay on screen across a [span]. */
    protected fun wrap(x: Float, offset: Float, span: Float) = (x - offset).mod(span) - (span - 64f) / 2

    private companion object {
        const val MPH = 2.23694f
        const val KMH = 3.6f
        const val SMOOTHING = 4f
        const val UNITS_PER_METER = 1.4f
        const val START_MPH = 3f
        const val STOP_MPH = 1f
        const val CAMERA_SECONDS = 1.5f
    }
}

/** Lifts off from a pad and climbs through stars and past planets. */
class RocketShow : SpeedShow("Rocket", parkedZoom = 1.9f, focusX = 32f, focusY = 15f) {
    override fun scene(c: Canvas, t: Long) {
        val space = min(1f, travelled / 60f)
        for (y in -32 until 64) rect(c, -64f, y.toFloat(), 128f, y + 1f, sky(space, y.coerceIn(0, 31)))
        for (i in 0 until 36) star(c, i)
        planets(c)
        ground(c)
        rocket(c, t)
    }

    private fun sky(space: Float, y: Int): Int {
        val k = 1f - space
        return Color.rgb(((30 + y * 3) * k).toInt(), ((90 + y * 4) * k).toInt(), ((170 + y * 2) * k).toInt())
    }

    /** Three depths of star falling past, streaking at speed. */
    private fun star(c: Canvas, i: Int) {
        val height = min(1f, travelled / 30f)
        if (height <= 0f) return
        val r = Random(i * 13)
        val depth = 0.3f + (i % 3) * 0.35f
        val x = r.nextFloat() * 64f
        val y = (r.nextFloat() * 40f + travelled * 2.2f * depth).mod(40f) - 4f
        val v = (255 * height * depth).toInt().coerceAtMost(255)
        rect(c, x, y - min(7f, mph / 14f) * depth, x + 0.7f, y + 0.7f, Color.rgb(v, v, min(255, v + 30)))
    }

    private fun planets(c: Canvas) {
        if (travelled < 40f) return
        val y1 = ((travelled - 40f) * 0.5f).mod(130f) - 30f
        circle(c, 12f, y1, 6f, Color.rgb(200, 110, 70))
        circle(c, 10f, y1 - 1.5f, 1.4f, Color.rgb(160, 80, 50))
        val y2 = ((travelled - 40f) * 0.35f + 60f).mod(130f) - 30f
        circle(c, 52f, y2, 4f, Color.rgb(226, 190, 120))
        rect(c, 45f, y2 - 0.3f, 59f, y2 + 0.5f, Color.rgb(170, 150, 200))
        val y3 = ((travelled - 40f) * 0.8f + 100f).mod(130f) - 30f
        circle(c, 46f, y3, 1.8f, Color.rgb(205, 205, 215))
    }

    /** The pad and tower, which drop out of sight on the way up. */
    private fun ground(c: Canvas) {
        val drop = travelled * 3f
        if (drop > 60f) return
        rect(c, -80f, 28f + drop, 144f, 60f + drop, Color.rgb(50, 96, 58))
        rect(c, 22f, 26f + drop, 42f, 28f + drop, Color.rgb(120, 120, 130))
        rect(c, 22f, 9f + drop, 24f, 26f + drop, Color.rgb(170, 60, 50))
        for (i in 0 until 4) rect(c, 24f, 11f + i * 4f + drop, 27f, 11.6f + i * 4f + drop, Color.rgb(200, 90, 70))
    }

    private fun rocket(c: Canvas, t: Long) {
        val x = 32f + if (mph > 3f) sin(t / 28f) * min(0.5f, mph / 120f) else 0f
        if (mph > 0.5f) {
            val length = 3f + min(11f, mph / 7f) + sin(t / 40f)
            path(c, Color.rgb(255, 120, 30), x - 2.4f, 26f, x + 2.4f, 26f, x, 26f + length)
            path(c, Color.rgb(255, 235, 140), x - 1.4f, 26f, x + 1.4f, 26f, x, 26f + length * 0.6f)
        }
        path(c, Color.rgb(190, 40, 40), x - 3f, 23f, x - 6f, 27f, x - 3f, 26f)
        path(c, Color.rgb(190, 40, 40), x + 3f, 23f, x + 6f, 27f, x + 3f, 26f)
        path(c, Color.rgb(235, 238, 245), x, 6f, x + 2.6f, 9f, x + 3f, 13f, x + 3f, 26f, x - 3f, 26f, x - 3f, 13f, x - 2.6f, 9f)
        path(c, Color.rgb(205, 45, 45), x, 6f, x + 2.6f, 9f, x + 3f, 11f, x - 3f, 11f, x - 2.6f, 9f)
        circle(c, x, 15f, 1.6f, Color.rgb(50, 66, 110))
        circle(c, x, 15f, 1.1f, Color.rgb(120, 200, 240))
        rect(c, x - 3f, 22f, x + 3f, 23f, Color.rgb(34, 62, 130))
    }
}

/** A speedboat at a dock, then planing across the water with its wake growing behind. */
class BoatShow : SpeedShow("Speedboat", parkedZoom = 2.3f, focusX = 26f, focusY = 18f) {
    override fun scene(c: Canvas, t: Long) {
        for (y in -20 until 15) {
            val k = ((y + 8) / 22f).coerceIn(0f, 1f)
            rect(c, -64f, y.toFloat(), 128f, y + 1f, Color.rgb((95 + 150 * k).toInt(), (170 + 30 * k).toInt(), (235 - 60 * k).toInt()))
        }
        circle(c, 50f, 6f, 3f, Color.rgb(255, 225, 110))
        for (i in 0 until 3) {
            val x = wrap(i * 30f, travelled * 0.08f, 90f)
            val h = 3f + i
            path(c, Color.rgb(96, 110, 96), x - 11f, 15f, x - 3f, 15f - h, x + 3f, 14f - h * 0.6f, x + 11f, 15f)
        }
        for (y in 15 until 52) rect(c, -64f, y.toFloat(), 128f, y + 1f, Color.rgb(16, 72 + y, 130 + y))
        for (i in 0 until 16) {
            val r = Random(i * 7)
            val y = 16f + r.nextFloat() * 15f
            val near = (y - 15f) / 15f
            val x = wrap(r.nextFloat() * 90f, travelled * (0.25f + near), 90f) + sin(t / 500f + i) * 0.8f
            rect(c, x, y, x + 2f + near * 3f, y + 0.6f, Color.rgb(120, 190, 225))
        }
        for (i in 0 until 2) {
            val x = wrap(i * 50f + 20f, travelled * 0.7f, 110f)
            val bob = sin(t / 400f + i) * 0.5f
            rect(c, x, 22f + bob, x + 1.5f, 25f + bob, Color.rgb(240, 70, 50))
            circle(c, x + 0.75f, 21.5f + bob, 0.9f, Color.rgb(255, 225, 90))
        }
        for (i in 0 until 3) {
            val x = wrap(i * 33f, travelled * 0.4f + t / 90f, 100f)
            val y = 5f + i * 3f
            val flap = if ((t / 180 + i) % 2 == 0L) 1f else -0.4f
            paint.color = Color.WHITE
            paint.strokeWidth = 0.5f
            c.drawLine(x - 1.6f, y - flap, x, y, paint)
            c.drawLine(x, y, x + 1.6f, y - flap, paint)
        }
        boat(c, t)
    }

    private fun boat(c: Canvas, t: Long) {
        val speed = min(1f, mph / 70f)
        val bob = sin(t / 450f) * (0.4f - 0.2f * speed)
        val lift = 2.6f * speed
        val y = 21f + bob
        if (mph > 3f) {
            val wake = 6f + speed * 28f
            // Foam: lines of fine dots fanning out behind, thinning with distance.
            for (i in 0 until (10 + speed * 50).toInt()) {
                val r = Random(i * 3L + t / 70)
                val along = r.nextFloat()
                val x = 18f - along * wake
                val spread = (y + 2.6f) + (r.nextFloat() - 0.5f) * (1f + along * (2f + speed * 3f))
                val size = 0.8f - along * 0.4f
                rect(c, x, spread, x + size * 1.6f, spread + size, if (along < 0.5f) Color.WHITE else Color.rgb(185, 225, 240))
            }
            for (i in 0 until (2 + speed * 8).toInt()) {
                val r = Random(i * 3L + t / 60)
                val x = 34f + r.nextFloat() * 3f
                val top = y + 1.6f - lift - r.nextFloat() * 2f * speed
                rect(c, x, top, x + 0.7f, top + 0.7f, Color.WHITE)
            }
        }
        path(c, Color.rgb(226, 58, 50), 18f, y - 0.5f, 18f, y + 3f, 28f, y + 3f - lift * 0.3f, 38f, y - 1f - lift)
        path(c, Color.rgb(240, 242, 248), 18f, y - 1f, 18f, y + 0.6f, 38f, y - 1f - lift, 32f, y - 1.8f - lift * 0.7f)
        path(c, Color.WHITE, 18f, y + 1.5f, 18f, y + 2f, 33f, y + 0.6f - lift * 0.65f, 33f, y + 0.1f - lift * 0.65f)
        path(c, Color.rgb(120, 190, 235), 23f, y - 1.2f, 27f, y - 4f - lift * 0.3f, 29f, y - 1.6f - lift * 0.5f)
        rect(c, 18.6f, y - 3f, 20f, y - 0.5f, Color.rgb(50, 50, 60))
        circle(c, 24f, y - 2.2f, 0.8f, Color.rgb(230, 190, 150))
    }
}

/** A small prop plane waiting on a runway, then climbing among clouds and birds. */
class PlaneShow : SpeedShow("Plane", parkedZoom = 2.4f, focusX = 26f, focusY = 21f) {
    override fun scene(c: Canvas, t: Long) {
        for (y in -20 until 54) {
            val k = ((y + 4) / 30f).coerceIn(0f, 1f)
            rect(c, -64f, y.toFloat(), 128f, y + 1f, Color.rgb((34 + 76 * k).toInt(), (96 + 66 * k).toInt(), (205 + 25 * k).toInt()))
        }
        clouds(c, 0.12f, 7, 4f, 0.65f)
        ground(c)
        clouds(c, 0.45f, 4, 7f, 1f)
        for (i in 0 until 4) {
            val x = wrap(i * 27f, travelled * 0.8f + t / 120f, 100f)
            val y = 6f + i * 4.5f + sin(t / 700f + i) * 1.2f
            val flap = if ((t / 160 + i) % 2 == 0L) 1.1f else -0.5f
            paint.color = Color.rgb(30, 30, 44)
            paint.strokeWidth = 0.55f
            c.drawLine(x - 1.8f, y - flap, x, y, paint)
            c.drawLine(x, y, x + 1.8f, y - flap, paint)
        }
        plane(c, t)
    }

    private fun clouds(c: Canvas, depth: Float, count: Int, height: Float, light: Float) {
        for (i in 0 until count) {
            val r = Random(i * 19 + count)
            val x = wrap(r.nextFloat() * 100f, travelled * depth, 100f)
            val y = 2f + r.nextFloat() * 15f
            val v = (255 * light).toInt() + 0
            val shade = Color.rgb(min(255, v + 40), min(255, v + 45), min(255, v + 60))
            rect(c, x, y, x + height * 2f, y + 1.4f, shade)
            rect(c, x + height * 0.5f, y - 1f, x + height * 1.5f, y, shade)
        }
    }

    /** Farmland and the runway, sinking away as it climbs. */
    private fun ground(c: Canvas) {
        val sink = camera * 2f
        rect(c, -64f, 26f + sink, 128f, 60f, Color.rgb(70, 140, 70))
        for (i in 0 until 9) {
            val x = wrap(i * 15f, travelled * 0.9f, 136f)
            rect(c, x, 27.5f + sink, x + 6f, 29f + sink, if (i % 2 == 0) Color.rgb(205, 180, 90) else Color.rgb(50, 110, 56))
        }
        rect(c, -64f, 25f + sink, 128f, 26.2f + sink, Color.rgb(70, 72, 80))
    }

    private fun plane(c: Canvas, t: Long) {
        val speed = min(1f, mph / 70f)
        val y = 21f - camera * 6f + sin(t / 700f) * camera * 0.8f
        if (mph > 3f) {
            val trail = 4f + speed * 22f
            rect(c, 18f - trail, y - 0.4f, 18f, y + 0.2f, Color.argb(190, 240, 245, 255))
        }
        val tilt = camera * 1.6f
        path(c, Color.rgb(240, 242, 248), 18f, y - 1.3f + tilt, 19f, y + 1f + tilt * 0.7f, 34f, y + 1.3f - tilt * 0.4f, 36f, y - 0.3f - tilt * 0.5f, 33f, y - 2f - tilt * 0.4f, 24f, y - 2f + tilt * 0.3f)
        path(c, Color.rgb(205, 45, 45), 17.5f, y - 4.5f + tilt, 19.5f, y - 4.5f + tilt, 22f, y - 1.2f + tilt * 0.7f, 18.5f, y - 1f + tilt)
        path(c, Color.rgb(34, 62, 130), 24f, y + 0.2f, 30f, y + 0.2f - tilt * 0.2f, 27f, y + 3.2f, 23f, y + 3.2f)
        rect(c, 19f, y + 0.1f + tilt * 0.6f, 34f, y + 0.6f, Color.rgb(205, 45, 45))
        path(c, Color.rgb(120, 190, 235), 29f, y - 2f, 33f, y - 2f - tilt * 0.3f, 34.5f, y - 0.6f - tilt * 0.4f, 29f, y - 0.6f)
        if (camera < 0.4f) {
            rect(c, 25.5f, y + 1f, 26f, y + 3.6f, Color.rgb(50, 50, 60))
            circle(c, 25.8f, y + 3.8f, 1f, Color.rgb(30, 30, 36))
        }
        val spinning = mph > 0.5f
        val blade = if (spinning || (t / 700) % 2 == 0L) 2.8f else 0.8f
        val shown = if (spinning) (if (t / 33 % 2 == 0L) 3.2f else 2.2f) else blade
        rect(c, 36.2f, y - shown, 36.8f, y + shown - 0.6f, if (spinning) Color.argb(170, 230, 230, 235) else Color.rgb(50, 50, 60))
    }
}
