package com.smartcreations.ledmatrixauto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect

/** Draws frames as round LEDs, the way the real panel looks. */
object LedRenderer {
    private val OFF = Color.rgb(22, 22, 26)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Draws [frame] with its top left corner at ([left], [top]), each LED [cellWidth] x [cellHeight]
     * pixels. The dots stay round, sized by the smaller side. A [tint] draws every LED in that one
     * color, as bright as its own color was.
     */
    fun draw(
        canvas: Canvas,
        animation: Animation,
        frame: IntArray,
        left: Float,
        top: Float,
        cellWidth: Float,
        cellHeight: Float = cellWidth,
        tint: Int? = null,
    ) {
        val radius = minOf(cellWidth, cellHeight) * 0.42f
        for (y in 0 until animation.height) {
            for (x in 0 until animation.width) {
                val c = frame[y * animation.width + x]
                paint.color = ledColor(c, tint)
                canvas.drawCircle(left + (x + 0.5f) * cellWidth, top + (y + 0.5f) * cellHeight, radius, paint)
            }
        }
    }

    /** The color an LED showing [c] lights up in. */
    fun ledColor(c: Int, tint: Int?): Int = when {
        c == 0 -> OFF
        tint != null -> scaled(tint, maxOf(Color.red(c), Color.green(c), Color.blue(c)))
        else -> c
    }

    private fun scaled(color: Int, level: Int) =
        Color.rgb(Color.red(color) * level / 255, Color.green(color) * level / 255, Color.blue(color) * level / 255)
}

/**
 * Draws one size of animation fast, for the car screen's many LEDs: the frame's pixels are blown up
 * by a whole number and seen through a mask of round holes drawn once. Whole-number LEDs stay crisp
 * and cheap to scale, with any leftover screen as a thin black border.
 */
class LedDots(val width: Int, val height: Int, val screenWidth: Int, val screenHeight: Int) {
    val cellWidth = (screenWidth / width).coerceAtLeast(1)
    val cellHeight = (screenHeight / height).coerceAtLeast(1)
    private val leds = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val mask = Bitmap.createBitmap(cellWidth * width, cellHeight * height, Bitmap.Config.ARGB_8888)
    private val colors = IntArray(width * height)
    private val left = (screenWidth - mask.width) / 2
    private val top = (screenHeight - mask.height) / 2
    private val target = Rect(left, top, left + mask.width, top + mask.height)
    private val pixelsPaint = Paint()

    init {
        val radius = minOf(cellWidth, cellHeight) * 0.42f
        val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        val canvas = Canvas(mask)
        canvas.drawColor(Color.BLACK)
        for (y in 0 until height) for (x in 0 until width) {
            canvas.drawCircle((x + 0.5f) * cellWidth, (y + 0.5f) * cellHeight, radius, hole)
        }
    }

    /** With [glow], smoothing blends each LED's color into its neighbors, except with square pixels, which stay crisp. */
    fun draw(canvas: Canvas, frame: IntArray, tint: Int? = null, glow: Boolean = false, square: Boolean = Settings.square) {
        for (i in colors.indices) colors[i] = LedRenderer.ledColor(frame[i], tint)
        leds.setPixels(colors, 0, width, 0, 0, width, height)
        pixelsPaint.isFilterBitmap = glow && !square
        canvas.drawBitmap(leds, null, target, pixelsPaint)
        if (!square) canvas.drawBitmap(mask, left.toFloat(), top.toFloat(), null)
    }
}
