package com.smartcreations.ledmatrixauto

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import androidx.car.app.CarContext
import androidx.car.app.media.CarAudioRecord
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/** Records one spoken idea from the car's microphone as a WAV. */
class VoiceRecorder(private val carContext: CarContext) {
    /**
     * Blocks until the speaker goes quiet after talking, nobody talks for a while, it runs too
     * long, or [stop] says so. Returns the WAV even when nothing was heard: the server says so.
     */
    @SuppressLint("MissingPermission")
    fun record(stop: () -> Boolean): ByteArray {
        val audio = carContext.getSystemService(AudioManager::class.java)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        audio.requestAudioFocus(focus)
        val record = CarAudioRecord.create(carContext)
        val pcm = ByteArrayOutputStream()
        try {
            record.startRecording()
            val buffer = ByteArray(CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE)
            val startedAt = SystemClock.elapsedRealtime()
            var lastLoudAt = 0L
            while (!stop()) {
                val n = record.read(buffer, 0, buffer.size)
                if (n < 0) break
                pcm.write(buffer, 0, n)
                val now = SystemClock.elapsedRealtime()
                if (rms(buffer, n) > SPEECH_RMS) lastLoudAt = now
                val heard = lastLoudAt > 0
                if (heard && now - lastLoudAt > QUIET_AFTER_SPEECH_MS) break
                if (!heard && now - startedAt > NO_SPEECH_MS) break
                if (now - startedAt > MAX_MS) break
            }
        } finally {
            record.stopRecording()
            audio.abandonAudioFocusRequest(focus)
        }
        return wav(pcm.toByteArray(), CarAudioRecord.AUDIO_CONTENT_SAMPLING_RATE)
    }

    /** Loudness of [n] bytes of 16-bit little-endian samples. */
    private fun rms(buffer: ByteArray, n: Int): Double {
        var sum = 0.0
        val samples = n / 2
        for (i in 0 until samples) {
            val s = ((buffer[2 * i + 1].toInt() shl 8) or (buffer[2 * i].toInt() and 0xff)).toShort().toDouble()
            sum += s * s
        }
        return if (samples == 0) 0.0 else sqrt(sum / samples)
    }

    private companion object {
        /** Louder than this counts as someone talking. */
        const val SPEECH_RMS = 700.0
        const val QUIET_AFTER_SPEECH_MS = 1200L
        const val NO_SPEECH_MS = 6000L
        const val MAX_MS = 12000L
    }
}
