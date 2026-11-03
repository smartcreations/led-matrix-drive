package com.smartcreations.ledmatrixauto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Car tab: an Audi Q4 e-tron on a California road. Parked, the camera sits close on the car.
 * Driving, it pulls back to show the road scrolling by as fast as the real one, and the speed fades
 * in. Everything is laid out in [UNITS_W] x [UNITS_H] units, drawn at [scale] world pixels per unit
 * and filmed onto the LEDs, so [fine] LEDs show finer shapes, not bigger blocks. Each [update]
 * redraws the single frame of [animation].
 */
class DriveScene(val fine: Boolean) {
    private val ledW = if (fine) UNITS_W * 2 else UNITS_W
    private val ledH = if (fine) UNITS_H * 2 else UNITS_H
    private val scale = if (fine) 6 else 3
    private val worldW = UNITS_W * scale
    private val worldH = UNITS_H * scale
    private val pixels = IntArray(ledW * ledH)
    private val world = IntArray(worldW * worldH)
    private val sky = IntArray(worldH) { y ->
        val t = (y / (ROAD * scale).toFloat()).coerceAtMost(1f)
        Color.rgb((70 + 180 * t).toInt(), (150 + 20 * t).toInt(), (235 - 125 * t).toInt())
    }
    private val artScale = scale / 3
    private val carArt = carArt(artScale)
    private var distance = 0.0
    private var smoothed = 0f
    private var lastUpdate = 0L
    private var driving = false
    private var pullBack = 0f

    /** Always one frame, redrawn in place. */
    val animation = Animation("Car", "", 1, ledW, ledH, listOf(pixels))

    /** True when parked and fully zoomed in, so the picture has stopped changing. */
    val settled get() = pullBack == 0f && smoothed < 0.01f

    /** Moves everything on to [nowMs]; [metersPerSecond] is null until the car has said. */
    fun update(metersPerSecond: Float?, unit: String, nowMs: Long) {
        val seconds = if (lastUpdate == 0L) 0f else ((nowMs - lastUpdate) / 1000f).coerceAtMost(0.2f)
        lastUpdate = nowMs
        // The car only reports now and then, so the road eases towards each new speed.
        smoothed += ((metersPerSecond ?: 0f) - smoothed) * (seconds * SMOOTHING).coerceAtMost(1f)
        distance += smoothed * UNITS_PER_METER * seconds

        val mph = smoothed * MPH
        if (mph > START_MPH) driving = true else if (mph < STOP_MPH) driving = false
        pullBack = (pullBack + (if (driving) seconds else -seconds) / CAMERA_SECONDS).coerceIn(0f, 1f)
        val camera = pullBack * pullBack * (3 - 2 * pullBack)

        for (y in 0 until worldH) world.fill(sky[y], y * worldW, (y + 1) * worldW)
        drawSun()
        drawMountains()
        drawPalms()
        drawRoad()
        drawCar(mph)
        film(PARKED_ZOOM + (1f - PARKED_ZOOM) * camera)
        if (camera > 0f) drawSpeed((smoothed * if (unit == "mph") MPH else KMH).roundToInt(), unit, camera)
    }

    /** Copies the world to the LEDs. With glow on, each LED shows the average of the patch under it. */
    private fun film(zoom: Float) {
        val focus = (zoom - 1f) / (PARKED_ZOOM - 1f)
        val centerX = worldW / 2f + (carCenterX() - worldW / 2f) * focus
        val centerY = worldH / 2f + (carCenterY() - worldH / 2f) * focus
        val step = worldW / ledW.toFloat() / zoom
        val samples = if (Settings.glow && !Settings.square) step.roundToInt().coerceAtLeast(1) else 1
        for (y in 0 until ledH) {
            val top = centerY + (y - ledH / 2f) * step
            for (x in 0 until ledW) {
                val left = centerX + (x - ledW / 2f) * step
                var r = 0
                var g = 0
                var b = 0
                for (sy in 0 until samples) for (sx in 0 until samples) {
                    val wx = (left + (sx + 0.5f) * step / samples).toInt().coerceIn(0, worldW - 1)
                    val wy = (top + (sy + 0.5f) * step / samples).toInt().coerceIn(0, worldH - 1)
                    val c = world[wy * worldW + wx]
                    r += c shr 16 and 0xff
                    g += c shr 8 and 0xff
                    b += c and 0xff
                }
                val n = samples * samples
                pixels[y * ledW + x] = Color.rgb(r / n, g / n, b / n)
            }
        }
    }

    private fun carCenterX() = CAR_LEFT * scale + CAR_ART_W * artScale / 2f
    private fun carCenterY() = CAR_TOP * scale + CAR_ART_H * artScale / 2f

    /** Stays put: it's far too far away to pass. */
    private fun drawSun() {
        val reach = (6.5f * scale).toInt()
        val cx = SUN_X * scale
        val cy = SUN_Y * scale
        for (dy in -reach..reach) for (dx in -reach..reach) {
            val r = sqrt((dx * dx + dy * dy).toFloat()) / scale
            val angle = atan2(dy.toFloat(), dx.toFloat()) * 4 / PI.toFloat()
            val ray = abs(angle - angle.roundToInt()) < 0.14f
            when {
                r <= 2.3f -> put(cx + dx, cy + dy, SUN)
                r <= 3.3f -> put(cx + dx, cy + dy, SUN_EDGE)
                r in 4.4f..6.2f && ray -> put(cx + dx, cy + dy, SUN_EDGE)
            }
        }
    }

    private fun drawMountains() {
        for (wx in 0 until worldW) {
            val x = wx / scale.toDouble()
            val far = x + distance * 0.05
            val near = x + distance * 0.15
            val farTop = 16 - 4 * peak(far, 23.0) - 2 * peak(far, 9.0)
            val nearTop = 20 - 4 * peak(near, 17.0) - sin(near * 0.4)
            for (wy in (farTop * scale).toInt() until ROAD * scale) put(wx, wy, MOUNTAIN_FAR)
            for (wy in (nearTop * scale).toInt() until ROAD * scale) put(wx, wy, MOUNTAIN_NEAR)
        }
        box(0f, ROAD - 1f, UNITS_W.toFloat(), ROAD.toFloat(), SAND)
    }

    /** A jagged 0 to 1 ridge repeating every [width] units. */
    private fun peak(x: Double, width: Double): Double = 1 - abs((x / width) % 1 * 2 - 1)

    private fun drawPalms() {
        val shift = (distance * 0.5 % PALM_GAP).toFloat()
        for (i in 0..3) {
            val x = i * PALM_GAP + 2 - shift
            // A trunk leaning a little, then fronds drooping away on both sides.
            for (y in 0 until (ROAD - 11) * scale) {
                val t = y / ((ROAD - 11f) * scale)
                val lean = x + 1f - t * t
                box(lean, 11 + t * (ROAD - 11), lean + 0.8f, 11 + t * (ROAD - 11) + 1f / scale, TRUNK)
            }
            for ((length, droop) in FRONDS) {
                for (step in 0..16) {
                    val t = step / 16f
                    for (side in intArrayOf(-1, 1)) {
                        val fx = x + 1.4f + side * length * t
                        val fy = 11f - 1.5f * t + droop * t * t
                        box(fx - 0.35f, fy - 0.35f, fx + 0.35f, fy + 0.35f, if (droop > 3.5f) FROND_DARK else FROND)
                    }
                }
            }
        }
    }

    private fun drawRoad() {
        box(0f, ROAD.toFloat(), UNITS_W.toFloat(), UNITS_H.toFloat(), ASPHALT)
        box(0f, ROAD.toFloat(), UNITS_W.toFloat(), ROAD + 0.8f, CURB)
        val shift = (distance % DASH_GAP).toFloat()
        for (i in 0..6) {
            val x = i * DASH_GAP - shift
            box(x, 29f, x + DASH_GAP / 2f, 29.9f, DASH)
        }
    }

    private fun drawCar(mph: Float) {
        val left = CAR_LEFT * scale
        val top = CAR_TOP * scale - if (mph > 70 && (distance / 6).toInt() % 2 == 0) scale / 2 else 0
        val artW = CAR_ART_W * artScale
        for (y in 0 until CAR_ART_H * artScale) for (x in 0 until artW) {
            val c = carArt[y * artW + x]
            if (c != 0) put(left + x, top + y, c)
        }
        val angle = (distance * 0.5 % (2 * PI)).toFloat()
        drawWheel(left + WHEEL_REAR_X * artScale, top + WHEEL_Y * artScale, angle)
        drawWheel(left + WHEEL_FRONT_X * artScale, top + WHEEL_Y * artScale, angle)

        if (mph > 3) {
            // A puff of exhaust drifting off behind.
            val puff = (distance * 0.4 % 7).toFloat()
            val x = CAR_LEFT - 1 - puff
            val y = CAR_TOP + 7 - puff / 3
            box(x, y, x + 1, y + 1, STREAK)
        }
        if (mph > 40) {
            val length = mph / 25
            for (row in intArrayOf(CAR_TOP + 3, CAR_TOP + 5, CAR_TOP + 8)) {
                val gap = ((distance * 0.5 + row * 3) % 6).toFloat()
                box(CAR_LEFT - 2 - gap - length, row.toFloat(), CAR_LEFT - 2 - gap, row + 0.7f, STREAK)
            }
        }
    }

    /** A big wheel around ([x], [y]): tire, five spokes turned by [angle], hub. */
    private fun drawWheel(x: Int, y: Int, angle: Float) {
        val tire = WHEEL_R * artScale
        val rim = RIM_R * artScale
        val rimInner = (RIM_R - 1) * artScale
        val hub = artScale * 2
        for (dy in -tire..tire) for (dx in -tire..tire) {
            val r = dx * dx + dy * dy
            if (r > tire * tire) continue
            val turn = (atan2(dy.toFloat(), dx.toFloat()) + angle) * 5 / (2 * PI.toFloat())
            put(
                x + dx,
                y + dy,
                when {
                    r > rim * rim -> TIRE
                    r > rimInner * rimInner -> RIM
                    r <= hub -> HUB
                    turn - floor(turn) < 0.45f -> RIM
                    else -> SPOKE_GAP
                },
            )
        }
    }

    /** The speed in the top right corner with its unit underneath, faded in by [fade]. */
    private fun drawSpeed(value: Int, unit: String, fade: Float) {
        val size = if (fine) 2 else 1
        val digits = value.toString()
        val label = unit.uppercase()
        badge(max(digits.length, label.length) * 6 * size, 16 * size, size * 3, fade)
        digits.forEachIndexed { i, c -> glyph(c, ledW - (digits.length - i) * 6 * size, size, size, Color.WHITE, fade) }
        label.forEachIndexed { i, c -> glyph(c, ledW - (label.length - i) * 6 * size, 9 * size, size, READOUT_GRAY, fade) }
    }

    /** A dark rounded patch in the top right corner, so the readout shows against any scenery. */
    private fun badge(width: Int, height: Int, corner: Int, fade: Float) {
        val left = ledW - width - 2
        for (y in 0 until height) for (x in left until ledW) {
            val fromLeft = x - left
            val fromBottom = height - 1 - y
            val cut = fromLeft < corner && (min(y, fromBottom) + fromLeft < corner)
            if (!cut) blend(x, y, BADGE, fade * 0.66f)
        }
    }

    private fun glyph(c: Char, left: Int, top: Int, size: Int, color: Int, fade: Float) {
        val rows = FONT[c] ?: return
        for (row in 0 until 7) {
            for (column in 0 until 5) {
                if (rows[row] shr (4 - column) and 1 == 0) continue
                for (dy in 0 until size) for (dx in 0 until size) {
                    blend(left + column * size + dx, top + row * size + dy, color, fade)
                }
            }
        }
    }

    /** Mixes [color] into one LED, [amount] of the way. */
    private fun blend(x: Int, y: Int, color: Int, amount: Float) {
        if (x !in 0 until ledW || y !in 0 until ledH) return
        val old = pixels[y * ledW + x]
        val mix = { shift: Int ->
            val from = old shr shift and 0xff
            (from + ((color shr shift and 0xff) - from) * amount).toInt()
        }
        pixels[y * ledW + x] = Color.rgb(mix(16), mix(8), mix(0))
    }

    /** Fills a box given in scene units. */
    private fun box(left: Float, top: Float, right: Float, bottom: Float, color: Int) {
        val x0 = (left * scale).toInt().coerceIn(0, worldW)
        val x1 = (right * scale).toInt().coerceIn(0, worldW)
        val y0 = (top * scale).toInt().coerceIn(0, worldH)
        val y1 = (bottom * scale).toInt().coerceAtLeast(y0 + 1).coerceIn(0, worldH)
        if (x1 > x0) for (y in y0 until y1) world.fill(color, y * worldW + x0, y * worldW + x1)
    }

    /** Sets one world pixel. */
    private fun put(x: Int, y: Int, color: Int) {
        if (x in 0 until worldW && y in 0 until worldH) world[y * worldW + x] = color
    }

    private companion object {
        /** The scene's size in units, which are one LED each at the standard resolution. */
        const val UNITS_W = 64
        const val UNITS_H = 32
        const val ROAD = 23
        const val PALM_GAP = 28
        const val DASH_GAP = 12
        const val SUN_X = 7
        const val SUN_Y = 7
        const val CAR_LEFT = 8
        const val CAR_TOP = 16
        const val CAR_ART_W = 72
        const val CAR_ART_H = 36
        const val WHEEL_REAR_X = 18
        const val WHEEL_FRONT_X = 54
        const val WHEEL_Y = 27
        const val WHEEL_R = 9
        const val RIM_R = 7
        /** How close the camera sits on the parked car, enough to fill the screen. */
        const val PARKED_ZOOM = 2.6f
        /** It pulls back above the first speed and closes in again below the second, so it can't flicker. */
        const val START_MPH = 3f
        const val STOP_MPH = 1f
        const val CAMERA_SECONDS = 1.5f
        /** How far the road moves for each meter the car travels. */
        const val UNITS_PER_METER = 1.4f
        /** How quickly the road catches up with a new speed, per second. */
        const val SMOOTHING = 4f
        const val MPH = 2.23694f
        const val KMH = 3.6f

        val SUN = Color.rgb(255, 225, 90)
        val SUN_EDGE = Color.rgb(255, 165, 40)
        val MOUNTAIN_FAR = Color.rgb(120, 86, 130)
        val MOUNTAIN_NEAR = Color.rgb(112, 74, 62)
        val SAND = Color.rgb(205, 165, 105)
        val TRUNK = Color.rgb(120, 82, 46)
        val FROND = Color.rgb(40, 190, 80)
        val FROND_DARK = Color.rgb(20, 120, 56)
        val CURB = Color.rgb(170, 170, 178)
        val ASPHALT = Color.rgb(84, 84, 92)
        val DASH = Color.rgb(240, 225, 170)
        val BODY = Color.rgb(34, 62, 130)
        val BODY_LIGHT = Color.rgb(64, 104, 186)
        val TRIM = Color.rgb(22, 24, 32)
        val ARCH = Color.rgb(12, 12, 16)
        val GLASS = Color.rgb(120, 170, 200)
        val SILVER = Color.rgb(190, 195, 205)
        val HEADLIGHT = Color.rgb(255, 255, 255)
        val TAIL_LIGHT = Color.rgb(255, 30, 30)
        val TIRE = Color.rgb(64, 64, 72)
        val SPOKE_GAP = Color.rgb(30, 30, 36)
        val RIM = Color.rgb(200, 204, 214)
        val HUB = Color.rgb(245, 245, 250)
        val STREAK = Color.rgb(235, 235, 240)
        val READOUT_GRAY = Color.rgb(205, 210, 220)
        val BADGE = Color.rgb(8, 10, 20)


        /** One frond on each side of a palm: how far it reaches and how much it droops. */
        val FRONDS = listOf(5.5f to 4.5f, 4.5f to 2.5f, 3f to 4f, 2f to 1.5f)

        private val carArts = HashMap<Int, IntArray>()

        /** The car without its wheels, facing right, [scale] times its base size. 0 is see-through. */
        fun carArt(scale: Int): IntArray = carArts.getOrPut(scale) {
            val width = CAR_ART_W * scale
            val height = CAR_ART_H * scale
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(scale.toFloat(), scale.toFloat())
            val paint = Paint().apply { isAntiAlias = false }
            val body = Path().apply {
                moveTo(1f, 29f)
                lineTo(1f, 16f)
                lineTo(9f, 10f)
                lineTo(20f, 3f)
                lineTo(38f, 1f)
                lineTo(54f, 10f)
                lineTo(66f, 13f)
                lineTo(71f, 18f)
                lineTo(71f, 29f)
                close()
            }
            paint.color = BODY
            canvas.drawPath(body, paint)
            canvas.save()
            canvas.clipPath(body)
            paint.color = BODY_LIGHT
            canvas.drawRect(0f, 12f, 72f, 15f, paint)
            paint.color = TRIM
            canvas.drawRect(0f, 27f, 72f, 30f, paint)
            canvas.restore()

            val glass = Path().apply {
                moveTo(13f, 10f)
                lineTo(22f, 4f)
                lineTo(37f, 3f)
                lineTo(49f, 10f)
                close()
            }
            paint.color = GLASS
            canvas.drawPath(glass, paint)
            paint.color = TRIM
            paint.style = Paint.Style.STROKE
            canvas.drawPath(glass, paint)
            canvas.drawLine(33f, 3f, 33f, 26f, paint)
            canvas.drawLine(13f, 11f, 13f, 26f, paint)
            canvas.drawLine(50f, 11f, 50f, 26f, paint)
            paint.color = SILVER
            canvas.drawLine(19f, 2f, 38f, 0f, paint)
            paint.style = Paint.Style.FILL

            paint.color = BODY_LIGHT
            canvas.drawRect(26f, 16f, 31f, 17f, paint)
            canvas.drawRect(41f, 16f, 46f, 17f, paint)
            paint.color = BODY
            canvas.drawRect(50f, 9f, 54f, 12f, paint)
            paint.color = TAIL_LIGHT
            canvas.drawRect(1f, 15f, 10f, 17f, paint)
            paint.color = HEADLIGHT
            canvas.drawRect(62f, 15f, 71f, 17f, paint)
            paint.color = ARCH
            canvas.drawCircle(WHEEL_REAR_X.toFloat(), WHEEL_Y.toFloat(), 11.5f, paint)
            canvas.drawCircle(WHEEL_FRONT_X.toFloat(), WHEEL_Y.toFloat(), 11.5f, paint)

            IntArray(width * height).also {
                bitmap.getPixels(it, 0, width, 0, 0, width, height)
                bitmap.recycle()
            }
        }

        /** 5x7 letters: one number per row, bit 4 is the leftmost pixel. */
        val FONT = mapOf(
            '0' to intArrayOf(0x0E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E),
            '1' to intArrayOf(0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E),
            '2' to intArrayOf(0x0E, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1F),
            '3' to intArrayOf(0x1F, 0x02, 0x04, 0x02, 0x01, 0x11, 0x0E),
            '4' to intArrayOf(0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02),
            '5' to intArrayOf(0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E),
            '6' to intArrayOf(0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E),
            '7' to intArrayOf(0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08),
            '8' to intArrayOf(0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E),
            '9' to intArrayOf(0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C),
            '-' to intArrayOf(0x00, 0x00, 0x00, 0x1F, 0x00, 0x00, 0x00),
            '/' to intArrayOf(0x01, 0x01, 0x02, 0x04, 0x08, 0x10, 0x10),
            'H' to intArrayOf(0x11, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11),
            'K' to intArrayOf(0x11, 0x12, 0x14, 0x18, 0x14, 0x12, 0x11),
            'M' to intArrayOf(0x11, 0x1B, 0x15, 0x15, 0x11, 0x11, 0x11),
            'P' to intArrayOf(0x1E, 0x11, 0x11, 0x1E, 0x10, 0x10, 0x10),
        )
    }
}
