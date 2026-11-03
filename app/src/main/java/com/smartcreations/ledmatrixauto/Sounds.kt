package com.smartcreations.ledmatrixauto

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Marimba feedback sounds, synthesized here so there are no audio files to carry. */
object Sounds {
    private const val RATE = 44100
    private const val CHANNEL_OPEN_MS = 250L

    private val ATTRIBUTES = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    @Volatile private var audio: AudioManager? = null

    /** Gets the audio service that sounds ask for focus from. */
    fun install(context: Context) {
        audio = context.getSystemService(AudioManager::class.java)
    }

    /** Real recordings: see CREDITS.md. */
    private val FARTS = listOf(
        R.raw.fart_01,
        R.raw.fart_02,
        R.raw.fart_03,
        R.raw.fart_04,
        R.raw.fart_05,
        R.raw.fart_06,
        R.raw.fart_07,
        R.raw.fart_08,
        R.raw.fart_09,
        R.raw.fart_10,
        R.raw.fart_11,
    )

    /** One marimba hit: MIDI note [midi], [at] seconds in, ringing for [seconds]. */
    class Hit(val midi: Int, val at: Float, val seconds: Float = 0.5f, val amp: Float = 1f)

    enum class Sound(val peak: Float, val seconds: Float, val hits: List<Hit>) {
        /** "I'm listening". */
        Listening(0.6f, 0.6f, listOf(Hit(72, 0f), Hit(79, 0.1f))),
        /** "Got it, working on it". */
        GotIt(0.6f, 0.6f, listOf(Hit(79, 0f), Hit(72, 0.1f))),
        /** The animation is here. */
        Done(0.6f, 1f, listOf(72, 76, 79, 84).mapIndexed { i, m -> Hit(m, i * 0.08f, 0.7f) }),
        /** Something went wrong. */
        Error(0.45f, 0.62f, listOf(Hit(64, 0f), Hit(57, 0.12f))),
    }

    /** Plays a random recorded fart. It's what the button is for, so mute doesn't apply. */
    fun fart(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java)
        thread(name = "fart") {
            val focus = takeFocus(audio)
            val player = MediaPlayer.create(context, FARTS.random(), ATTRIBUTES, audio.generateAudioSessionId())
            if (player == null) {
                audio.abandonAudioFocusRequest(focus)
                return@thread
            }
            player.setOnCompletionListener {
                it.release()
                audio.abandonAudioFocusRequest(focus)
            }
            player.start()
        }
    }

    /**
     * Android Auto only carries this audio to the car while the app holds audio focus. The short
     * wait lets it open the channel, or the start of the sound gets lost.
     */
    private fun takeFocus(audio: AudioManager): AudioFocusRequest {
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(ATTRIBUTES).build()
        audio.requestAudioFocus(focus)
        Thread.sleep(CHANNEL_OPEN_MS)
        return focus
    }

    /** Plays [sound] in the background. */
    fun play(sound: Sound) {
        thread(name = "sound") { playNow(sound) }
    }

    /** Plays [sound] and returns once it has finished, so the mic doesn't pick it up. */
    fun playNow(sound: Sound) {
        if (Settings.muted) return
        val manager = audio ?: return
        val focus = takeFocus(manager)
        val samples = pcm(sound)
        val track = track(samples)
        try {
            track.play()
            Thread.sleep(samples.size * 1000L / RATE + 60)
        } catch (e: Exception) {
            // No sound is better than no animation.
        } finally {
            track.release()
            manager.abandonAudioFocusRequest(focus)
        }
    }

    private fun track(samples: ShortArray): AudioTrack =
        AudioTrack.Builder()
            .setAudioAttributes(ATTRIBUTES)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
            .also { it.write(samples, 0, samples.size) }

    /** Mixes the hits of [sound] and scales them to its peak. */
    private fun pcm(sound: Sound): ShortArray {
        val mixed = FloatArray((sound.seconds * RATE).toInt())
        for (hit in sound.hits) {
            val hz = 440.0 * 2.0.pow((hit.midi - 69) / 12.0)
            val start = (hit.at * RATE).toInt()
            for (i in 0 until min((hit.seconds * RATE).toInt(), mixed.size - start)) {
                val t = i.toDouble() / RATE
                val tone = sin(2 * PI * hz * t) + 0.35 * sin(2 * PI * 4 * hz * t) * exp(-t / 0.03)
                mixed[start + i] += (hit.amp * tone * exp(-t / 0.12) * min(1.0, t / 0.001)).toFloat()
            }
        }
        val scale = sound.peak * Short.MAX_VALUE / (mixed.maxOf { abs(it) })
        return ShortArray(mixed.size) { (mixed[it] * scale).toInt().toShort() }
    }
}
