package com.smartcreations.ledmatrixauto

import android.os.SystemClock
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarUnit
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.Accelerometer
import androidx.car.app.hardware.info.CarHardwareLocation
import androidx.car.app.hardware.info.CarSensors
import androidx.car.app.hardware.info.Compass
import androidx.car.app.hardware.info.EnergyLevel
import androidx.car.app.hardware.info.EnergyProfile
import androidx.car.app.hardware.info.EvStatus
import androidx.car.app.hardware.info.Gyroscope
import androidx.car.app.hardware.info.Mileage
import androidx.car.app.hardware.info.Speed
import androidx.car.app.hardware.info.TollCard
import androidx.core.content.ContextCompat

/**
 * Everything Android Auto lets an app read from the car, for the debug screen. Each value is shown
 * and logged as its value, "waiting" or why it's missing, with when it last arrived.
 */
class CarData(private val carContext: CarContext) {
    /** A value or what's wrong with it, and when it last arrived. */
    class Reading(val text: String, val ok: Boolean, val at: Long = 0L)

    /** Every value by name, in the order they're shown. */
    val readings = LinkedHashMap<String, Reading>().apply {
        for (name in NAMES) put(name, Reading(WAITING, false))
    }

    private var speedUnit = "km/h"

    private val executor = ContextCompat.getMainExecutor(carContext)
    private val hardware get() = carContext.getCarService(CarHardwareManager::class.java)
    private var running = false

    private val onSpeed = OnCarDataAvailableListener<Speed> {
        speedUnit = if (it.speedDisplayUnit.value == CarUnit.MILES_PER_HOUR) "mph" else "km/h"
        set("Speed", describe(it.rawSpeedMetersPerSecond) { mps -> "%.2f m/s, %.1f %s".format(mps, toUnit(mps), speedUnit) })
        set("Dash speed", describe(it.displaySpeedMetersPerSecond) { mps -> "%.0f %s".format(toUnit(mps), speedUnit) })
    }
    private val onMileage = OnCarDataAvailableListener<Mileage> {
        set("Odometer", describe(it.odometerMeters) { m -> distance(m, it.distanceDisplayUnit.value) })
    }
    private val onEnergy = OnCarDataAvailableListener<EnergyLevel> {
        set("Fuel", describe(it.fuelPercent) { p -> "%.0f%%".format(p) })
        set("Battery", describe(it.batteryPercent) { p -> "%.0f%%".format(p) })
        set("Range", describe(it.rangeRemainingMeters) { m -> distance(m, it.distanceDisplayUnit.value) })
        set("Low energy", describe(it.energyIsLow) { low -> if (low) "yes" else "no" })
    }
    private val onToll = OnCarDataAvailableListener<TollCard> {
        set("Toll card", describe(it.cardState) { s -> constantName(TollCard::class.java, "TOLLCARD_STATE_", s) })
    }
    private val onEv = OnCarDataAvailableListener<EvStatus> {
        set("Charge port", describe(it.evChargePortConnected) { c -> if (c) "connected" else "not connected" })
    }
    private val onCompass = OnCarDataAvailableListener<Compass> {
        set("Compass", describe(it.orientations) { o -> o.firstOrNull()?.let { d -> "%.0f°".format(d) } ?: "empty" })
    }
    private val onAccel = OnCarDataAvailableListener<Accelerometer> {
        set("Accel", describe(it.forces) { f -> f.joinToString(" ") { v -> "%.1f".format(v) } })
    }
    private val onGyro = OnCarDataAvailableListener<Gyroscope> {
        set("Gyro", describe(it.rotations) { r -> r.joinToString(" ") { v -> "%.2f".format(v) } })
    }
    private val onLocation = OnCarDataAvailableListener<CarHardwareLocation> {
        set("Car GPS", describe(it.location) { l -> "%.4f, %.4f".format(l.latitude, l.longitude) })
    }

    fun start() {
        if (running) return
        running = true
        val info = hardware.carInfo
        val sensors = hardware.carSensors
        ask("Model") {
            info.fetchModel(executor) {
                set("Model", Reading(
                    listOf(it.manufacturer, it.name, it.year).joinToString(" ") { v -> describe(v) { x -> x.toString() }.text },
                    it.name.status == CarValue.STATUS_SUCCESS,
                ))
            }
        }
        ask("Fuel type", "EV plug") {
            info.fetchEnergyProfile(executor) {
                set("Fuel type", describe(it.fuelTypes) { t -> t.joinToString { v -> constantName(EnergyProfile::class.java, "FUEL_TYPE_", v) } })
                set("EV plug", describe(it.evConnectorTypes) { t -> t.joinToString { v -> constantName(EnergyProfile::class.java, "EVCONNECTOR_TYPE_", v) } })
            }
        }
        ask("Speed", "Dash speed") { info.addSpeedListener(executor, onSpeed) }
        ask("Odometer") { info.addMileageListener(executor, onMileage) }
        ask("Fuel", "Battery", "Range", "Low energy") { info.addEnergyLevelListener(executor, onEnergy) }
        ask("Charge port") { info.addEvStatusListener(executor, onEv) }
        ask("Toll card") { info.addTollListener(executor, onToll) }
        ask("Compass") { sensors.addCompassListener(CarSensors.UPDATE_RATE_NORMAL, executor, onCompass) }
        ask("Accel") { sensors.addAccelerometerListener(CarSensors.UPDATE_RATE_NORMAL, executor, onAccel) }
        ask("Gyro") { sensors.addGyroscopeListener(CarSensors.UPDATE_RATE_NORMAL, executor, onGyro) }
        ask("Car GPS") { sensors.addCarHardwareLocationListener(CarSensors.UPDATE_RATE_NORMAL, executor, onLocation) }
    }

    fun stop() {
        if (!running) return
        running = false
        val info = hardware.carInfo
        val sensors = hardware.carSensors
        runCatching { info.removeSpeedListener(onSpeed) }
        runCatching { info.removeMileageListener(onMileage) }
        runCatching { info.removeEnergyLevelListener(onEnergy) }
        runCatching { info.removeTollListener(onToll) }
        runCatching { info.removeEvStatusListener(onEv) }
        runCatching { sensors.removeCompassListener(onCompass) }
        runCatching { sensors.removeAccelerometerListener(onAccel) }
        runCatching { sensors.removeGyroscopeListener(onGyro) }
        runCatching { sensors.removeCarHardwareLocationListener(onLocation) }
    }

    /** Runs one request; if the car or a missing permission refuses it, says so on its [names]. */
    private fun ask(vararg names: String, request: () -> Unit) {
        try {
            request()
        } catch (e: SecurityException) {
            for (name in names) set(name, Reading("no permission", false))
        } catch (e: Exception) {
            for (name in names) set(name, Reading("error: ${e.javaClass.simpleName}", false))
            EventLog.add("${names.first()} failed: $e")
        }
    }

    private fun set(name: String, reading: Reading) {
        val changed = readings[name]?.text != reading.text
        readings[name] = Reading(reading.text, reading.ok, SystemClock.elapsedRealtime())
        if (changed) EventLog.add("Car data: $name = ${reading.text}")
    }

    private fun <T> describe(value: CarValue<T>, show: (T) -> String): Reading = when (value.status) {
        CarValue.STATUS_SUCCESS -> value.value?.let { Reading(show(it), true) } ?: Reading("empty", false)
        CarValue.STATUS_UNIMPLEMENTED -> Reading(NOT_SHARED, false)
        CarValue.STATUS_UNAVAILABLE -> Reading("unavailable now", false)
        else -> Reading("unknown", false)
    }

    private fun toUnit(metersPerSecond: Float) = metersPerSecond * if (speedUnit == "mph") 2.23694f else 3.6f

    private fun distance(meters: Float, unit: Int?) =
        if (unit == CarUnit.MILE) "%,.0f mi".format(meters / 1609.344f) else "%,.0f km".format(meters / 1000f)

    /** The name of the [prefix] constant on [type] with [value], for readable enums. */
    private fun constantName(type: Class<*>, prefix: String, value: Int): String =
        type.fields.firstOrNull { it.name.startsWith(prefix) && runCatching { it.getInt(null) }.getOrNull() == value }
            ?.name?.removePrefix(prefix)?.lowercase() ?: value.toString()

    companion object {
        const val WAITING = "waiting"
        const val NOT_SHARED = "not shared"
        val NAMES = listOf(
            "Model", "Speed", "Dash speed", "Odometer", "Fuel", "Battery", "Range", "Low energy",
            "Fuel type", "EV plug", "Charge port", "Toll card", "Compass", "Accel", "Gyro", "Car GPS",
        )
    }
}
