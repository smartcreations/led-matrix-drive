package com.smartcreations.ledmatrixauto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle

/** The car's settings, opened from the gear tab. */
class SettingsScreen(carContext: CarContext, private val onCarData: () -> Unit) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val sound = Row.Builder()
            .setTitle("Sound effects")
            .setToggle(Toggle.Builder { on -> Settings.saveMuted(carContext, !on) }.setChecked(!Settings.muted).build())
            .build()
        val color = Row.Builder()
            .setTitle("Color")
            .addText(Settings.tint?.let { "Single color: ${it.title}" } ?: "Full color")
            .setBrowsable(true)
            .setOnClickListener { screenManager.push(ColorScreen(carContext)) }
            .build()
        val resolution = Row.Builder()
            .setTitle("Fine resolution")
            .addText(if (Settings.fine) "128×64: Car tab and new animations" else "64×32")
            .setToggle(Toggle.Builder { on -> Settings.saveFine(carContext, on); invalidate() }.setChecked(Settings.fine).build())
            .build()
        val shape = Row.Builder()
            .setTitle("Square pixels")
            .addText(if (Settings.square) "Square, touching, no gaps" else "Round LEDs with gaps")
            .setToggle(Toggle.Builder { on -> Settings.saveSquare(carContext, on); invalidate() }.setChecked(Settings.square).build())
            .build()
        val glow = Row.Builder()
            .setTitle("Glow")
            .addText(if (Settings.glow) "Soft light around each LED" else "Crisp dots")
            .setToggle(Toggle.Builder { on -> Settings.saveGlow(carContext, on); invalidate() }.setChecked(Settings.glow).build())
            .build()
        val carData = Row.Builder()
            .setTitle("Car data (debug)")
            .setBrowsable(true)
            .setOnClickListener {
                screenManager.pop()
                onCarData()
            }
            .build()
        val list = ItemList.Builder().addItem(sound).addItem(color).addItem(resolution).addItem(shape).addItem(glow).addItem(carData)
        if (BuildConfig.DEBUG) {
            list.addItem(
                Row.Builder()
                    .setTitle("Simulate speed (test)")
                    .addText("Resets when the app restarts")
                    .setToggle(Toggle.Builder { on -> Settings.simulateSpeed = on }.setChecked(Settings.simulateSpeed).build())
                    .build(),
            )
        }
        return ListTemplate.Builder()
            .setTitle("Settings")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}

/** Picks full color or one of the single LED colors. */
class ColorScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
            .addItem(Row.Builder().setTitle("Full color").build())
        Settings.Tint.entries.forEach { list.addItem(Row.Builder().setTitle(it.title).build()) }
        list.setSelectedIndex(Settings.tint?.let { it.ordinal + 1 } ?: 0)
        list.setOnSelectedListener { index ->
            Settings.saveTint(carContext, Settings.Tint.entries.getOrNull(index - 1))
        }
        return ListTemplate.Builder()
            .setTitle("Color")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}
