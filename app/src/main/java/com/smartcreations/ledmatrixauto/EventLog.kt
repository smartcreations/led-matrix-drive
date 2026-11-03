package com.smartcreations.ledmatrixauto

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A rolling log on the phone, for what happened on a drive with no computer attached: key events
 * and any crash. Read it with the phone app's Log button, or over USB:
 * adb exec-out run-as com.smartcreations.ledmatrixauto cat files/led-log.txt
 */
object EventLog {
    private const val MAX_BYTES = 256 * 1024L
    private const val SHARE_CHARS = 60_000

    private val writer = Executors.newSingleThreadExecutor()
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var file: File? = null

    /** Starts the log and catches crashes. Safe to call from everything that can start first. */
    @Synchronized
    fun install(context: Context) {
        Sounds.install(context)
        if (file != null) return
        file = File(context.filesDir, "led-log.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            write("CRASH on ${thread.name}: ${error.stackTraceToString()}")
            previous?.uncaughtException(thread, error)
        }
        add("App started")
    }

    fun add(event: String) {
        writer.execute { write(event) }
    }

    /** The end of the log, small enough to hand to another app. */
    fun recent(): String = file?.takeIf { it.exists() }?.readText()?.takeLast(SHARE_CHARS).orEmpty()

    @Synchronized
    private fun write(event: String) {
        val log = file ?: return
        try {
            log.appendText("${time.format(Date())} $event\n")
            if (log.length() > MAX_BYTES) log.writeText(log.readText().takeLast((MAX_BYTES / 2).toInt()))
        } catch (e: Exception) {
            // Logging must never be what breaks the app.
        }
    }
}
