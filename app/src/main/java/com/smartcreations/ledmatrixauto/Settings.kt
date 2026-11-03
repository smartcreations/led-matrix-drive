package com.smartcreations.ledmatrixauto

import android.content.Context
import android.graphics.Color

/** What the settings screen chooses, saved between drives. */
object Settings {
    private const val FILE = "settings"
    private const val MUTED = "muted"
    private const val TINT = "tint"
    private const val FINE = "fine"
    private const val GLOW = "glow"
    private const val SQUARE = "square"

    /** The single LED color to draw animations in. */
    enum class Tint(val title: String, val color: Int) {
        Red("Red", Color.rgb(255, 59, 47)),
        Amber("Amber", Color.rgb(255, 176, 32)),
        Green("Green", Color.rgb(46, 230, 120)),
        White("White", Color.WHITE),
    }

    /** Silences every sound effect. */
    @Volatile var muted = false
        private set

    /** Null for full color. */
    @Volatile var tint: Tint? = null
        private set

    /** 128x64 instead of 64x32. */
    @Volatile var fine = false
        private set

    /** Lets each LED's light spill softly onto its neighbors; off, every LED stays a crisp dot. */
    @Volatile var glow = true
        private set

    /** Square pixels touching each other, instead of round LEDs with gaps. */
    @Volatile var square = false
        private set

    /** Test builds only: a pretend drive in place of the car's speed. Never saved, so it can't reach the road. */
    @Volatile var simulateSpeed = false

    fun restore(context: Context) {
        val saved = preferences(context)
        muted = saved.getBoolean(MUTED, false)
        tint = saved.getString(TINT, null)?.let { name -> Tint.entries.firstOrNull { it.name == name } }
        fine = saved.getBoolean(FINE, false)
        glow = saved.getBoolean(GLOW, true)
        square = saved.getBoolean(SQUARE, false)
    }

    fun saveMuted(context: Context, value: Boolean) {
        muted = value
        preferences(context).edit().putBoolean(MUTED, value).apply()
    }

    fun saveTint(context: Context, value: Tint?) {
        tint = value
        preferences(context).edit().putString(TINT, value?.name).apply()
    }

    fun saveFine(context: Context, value: Boolean) {
        fine = value
        preferences(context).edit().putBoolean(FINE, value).apply()
    }

    fun saveSquare(context: Context, value: Boolean) {
        square = value
        preferences(context).edit().putBoolean(SQUARE, value).apply()
    }

    fun saveGlow(context: Context, value: Boolean) {
        glow = value
        preferences(context).edit().putBoolean(GLOW, value).apply()
    }

    private fun preferences(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
