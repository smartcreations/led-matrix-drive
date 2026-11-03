package com.smartcreations.ledmatrixauto

import android.os.SystemClock
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarUnit
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.Speed
import androidx.core.content.ContextCompat

/** The car's speed through Android Auto, as shown on its dash when it says so. */
class CarSpeed(private val carContext: CarContext) {
    private var latest: Float? = null
    private var latestAt = 0L
    private var loggedAt = 0L
    var unit = "km/h"
        private set

    /** The latest speed, or null when the car hasn't said anything for a while. */
    val metersPerSecond: Float?
        get() = latest?.takeIf { SystemClock.elapsedRealtime() - latestAt < STALE_MS }

    private val carInfo get() = carContext.getCarService(CarHardwareManager::class.java).carInfo
    private var running = false

    private val listener = OnCarDataAvailableListener<Speed> {
        unit = if (it.speedDisplayUnit.value == CarUnit.MILES_PER_HOUR) "mph" else "km/h"
        val shown = if (it.displaySpeedMetersPerSecond.status == CarValue.STATUS_SUCCESS) it.displaySpeedMetersPerSecond else it.rawSpeedMetersPerSecond
        latest = shown.value?.takeIf { shown.status == CarValue.STATUS_SUCCESS }
        latestAt = SystemClock.elapsedRealtime()
        if (latestAt - loggedAt >= LOG_EVERY_MS) {
            loggedAt = latestAt
            EventLog.add("Speed: $latest m/s, display status ${it.displaySpeedMetersPerSecond.status}, raw status ${it.rawSpeedMetersPerSecond.status}, $unit")
        }
    }

    fun start() {
        if (running) return
        running = true
        EventLog.add("Speed listener on")
        try {
            carInfo.addSpeedListener(ContextCompat.getMainExecutor(carContext), listener)
        } catch (e: Exception) {
            Log.w(TAG, "No speed from the car", e)
            EventLog.add("No speed from the car: $e")
        }
    }

    fun stop() {
        if (!running) return
        running = false
        latest = null
        EventLog.add("Speed listener off")
        runCatching { carInfo.removeSpeedListener(listener) }
    }

    private companion object {
        const val TAG = "LedCarSpeed"
        /** Past this with no word from the car, the speed counts as unknown. */
        const val STALE_MS = 10_000L
        const val LOG_EVERY_MS = 1_000L
    }
}
