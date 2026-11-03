package com.smartcreations.ledmatrixauto

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** The phone screen: the same animation, a mic in the corner, a text box and the Car tab test. */
class MainActivity : ComponentActivity() {
    private val scene by lazy { DriveScene(Settings.fine) }
    private var testStartedAt: Long? = null
    private var testingRocket = false
    private val rocket = RocketShow()
    private val rocketLeds = Animation("Rocket", "", 1, 64, 32, listOf(IntArray(64 * 32)))

    private val listen = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == RESULT_OK && !heard.isNullOrBlank()) Studio.draw(heard)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLog.install(this)
        Settings.restore(this)
        val led = LedView(this)
        val title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            setTextColor(Color.GRAY)
            textSize = 15f
            gravity = Gravity.CENTER
        }
        val input = EditText(this).apply {
            hint = "Type an idea"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.DKGRAY)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        val send = { Studio.draw(input.text.toString()); input.text.clear() }
        input.setOnEditorActionListener { _, _, _ -> send(); true }
        val draw = Button(this).apply { text = "Draw"; setOnClickListener { send() } }
        val mic = Button(this).apply {
            text = "🎤"
            textSize = 24f
            setOnClickListener {
                listen.launch(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say an animation idea"),
                )
            }
        }

        val show = { state: Studio.State ->
            if (testStartedAt == null) {
                led.animation = Studio.animationFor(state)
                title.text = Studio.titleFor(state)
                subtitle.text = Studio.subtitleFor(state)
            }
        }
        val test = Button(this).apply {
            text = "Test"
            // One press tests the car, the next the rocket, the next stops.
            setOnClickListener {
                val rocketNext = testStartedAt != null && !testingRocket
                if (testStartedAt != null && testingRocket) {
                    testStartedAt = null
                    testingRocket = false
                    show(Studio.state.value)
                    text = "Test"
                } else {
                    testStartedAt = SystemClock.elapsedRealtime()
                    testingRocket = rocketNext
                    led.animation = if (rocketNext) rocketLeds else scene.animation
                    title.text = if (rocketNext) "Rocket test" else "Car test"
                    subtitle.text = "0 to 100 mph, down to 50, up to 70"
                    text = if (rocketNext) "Stop" else "Next"
                }
            }
        }
        led.beforeDraw = {
            testStartedAt?.let {
                val now = SystemClock.elapsedRealtime()
                val speed = DriveTest.metersPerSecond(now - it)
                if (testingRocket) {
                    rocket.metersPerSecond = speed
                    rocket.render(rocketLeds.frames[0], rocketLeds.width, rocketLeds.height, now)
                } else {
                    scene.update(speed, "mph", now)
                }
            }
        }

        val log = Button(this).apply {
            text = "Log"
            setOnClickListener {
                val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, EventLog.recent())
                startActivity(Intent.createChooser(share, "Share the log"))
            }
        }
        val row = LinearLayout(this).apply {
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(draw)
            addView(test)
            addView(log)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(led, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(title)
            addView(subtitle)
            addView(row)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(column)
            addView(mic, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END,
            ))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)

        // Asked here once so the car's mic button works without a prompt on the car screen.
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)

        lifecycleScope.launch {
            Studio.state.collect { show(it) }
        }
    }
}

/** Plays an [Animation] as round LEDs at its own frame rate. */
class LedView(context: Context) : View(context) {
    private var startedAt = SystemClock.elapsedRealtime()

    /** Runs before each frame, for animations that are drawn live. */
    var beforeDraw: (() -> Unit)? = null

    var animation: Animation = BuiltIn.intro
        set(value) {
            if (value !== field) startedAt = SystemClock.elapsedRealtime()
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        beforeDraw?.invoke()
        val a = animation
        val cell = minOf(width / a.width.toFloat(), height / a.height.toFloat())
        val left = (width - cell * a.width) / 2
        val top = (height - cell * a.height) / 2
        LedRenderer.draw(canvas, a, a.frames[a.frameIndexAt(SystemClock.elapsedRealtime() - startedAt)], left, top, cell)
        postInvalidateOnAnimation()
    }
}
