package com.smartcreations.ledmatrixauto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** The animations the app draws itself: the intro, the listening and "drawing" rings and the error face. */
object BuiltIn {
    private const val W = 64
    private const val H = 32
    private const val RINGS = 4
    private const val RING_RADIUS = 7.5f
    private const val RING_GAP = 12f

    /** "LED MATRIX" with a rainbow sliding across it. */
    val intro: Animation by lazy {
        val mask = textMask("LED", "MATRIX")
        val frames = List(40) { f ->
            IntArray(W * H) { i ->
                if (!mask[i]) 0 else Color.HSVToColor(floatArrayOf(((i % W) * 6f + f * 9f) % 360f, 0.9f, 1f))
            }
        }
        Animation("LED Matrix", "", 12, W, H, frames)
    }

    /** Four linked rings, still and bright, while the mic is on. */
    val listening: Animation by lazy {
        Animation("Listening…", "", 1, W, H, listOf(ringsFrame { 1f }))
    }

    /** The same rings with the light rolling along them while the server draws. */
    val rings: Animation by lazy {
        val total = 24
        val frames = List(total) { f ->
            ringsFrame { ring ->
                0.25f + 0.75f * (0.5f + 0.5f * cos(2 * Math.PI.toFloat() * (f / total.toFloat() - ring / RINGS.toFloat())))
            }
        }
        Animation("Drawing…", "", 15, W, H, frames)
    }

    /** The four rings side by side, each as bright as [level] says. */
    private fun ringsFrame(level: (Int) -> Float): IntArray = IntArray(W * H).also { px ->
        for (y in 0 until H) for (x in 0 until W) {
            var brightest = 0f
            for (ring in 0 until RINGS) {
                val dx = x - (W / 2 + (ring - (RINGS - 1) / 2f) * RING_GAP)
                val dy = y - (H / 2 - 0.5f)
                if (abs(sqrt(dx * dx + dy * dy) - RING_RADIUS) <= 0.8f) brightest = max(brightest, level(ring))
            }
            if (brightest > 0f) {
                val v = (255 * brightest).toInt()
                px[y * W + x] = Color.rgb(v, v, (v + 12).coerceAtMost(255))
            }
        }
    }

    /** "OOPS" blinking in red. */
    val oops: Animation by lazy {
        val mask = textMask("OOPS")
        val red = Color.rgb(255, 59, 47)
        val on = IntArray(W * H) { if (mask[it]) red else 0 }
        Animation("Oops", "", 2, W, H, listOf(on, on, on, IntArray(W * H)))
    }

    /** Which pixels a line or two of text covers, drawn without smoothing so it reads as LEDs. */
    private fun textMask(vararg lines: String): BooleanArray {
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            isAntiAlias = false
            color = Color.WHITE
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = if (lines.size == 1) 18f else 12f
            textAlign = Paint.Align.CENTER
        }
        val lineHeight = H / lines.size.toFloat()
        lines.forEachIndexed { i, line ->
            val baseline = lineHeight * i + (lineHeight - paint.ascent() - paint.descent()) / 2
            canvas.drawText(line, W / 2f, baseline, paint)
        }
        val pixels = IntArray(W * H)
        bitmap.getPixels(pixels, 0, W, 0, 0, W, H)
        bitmap.recycle()
        return BooleanArray(W * H) { Color.alpha(pixels[it]) > 100 }
    }
}
