package com.smartcreations.ledmatrixauto

import kotlin.math.sin

/** A pretend drive for trying the Car tab without a car: 0 up to 100 mph, down to 50, up to 70, and stop. */
object DriveTest {
    private const val MPS_PER_MPH = 0.44704f

    /** Seconds into the drive and the speed in mph at that moment. */
    private val DRIVE = listOf(0f to 0f, 9f to 100f, 12f to 100f, 17f to 50f, 20f to 50f, 24f to 70f, 28f to 70f, 35f to 0f, 37f to 0f)

    /** The speed [elapsedMs] into the drive, which starts over at the end. */
    fun metersPerSecond(elapsedMs: Long): Float {
        val t = (elapsedMs % (DRIVE.last().first * 1000).toLong()) / 1000f
        val (from, to) = DRIVE.zipWithNext().first { t < it.second.first }
        val progress = (t - from.first) / (to.first - from.first)
        val mph = from.second + (to.second - from.second) * progress * progress * (3 - 2 * progress)
        // A little wobble, like a real foot on the pedal.
        val wobble = if (mph > 1) sin(t * 2.3f) * 0.8f else 0f
        return (mph + wobble).coerceAtLeast(0f) * MPS_PER_MPH
    }
}
