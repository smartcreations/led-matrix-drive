package com.smartcreations.ledmatrixauto

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/** The Android Auto side: a navigation app, so it gets the whole car screen to draw the LEDs on. */
class LedCarAppService : CarAppService() {
    override fun onCreate() {
        super.onCreate()
        EventLog.install(this)
    }

    // A personal, sideloaded app: any car host may connect.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = LedScreen(carContext)
    }
}
