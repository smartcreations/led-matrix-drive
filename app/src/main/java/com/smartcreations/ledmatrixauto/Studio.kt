package com.smartcreations.ledmatrixauto

import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What's on the LED display right now, shared by the car (LedScreen) and the phone screen. */
object Studio {
    sealed interface State {
        data object Intro : State
        data object Listening : State
        data class Drawing(val prompt: String) : State
        data class Showing(val animation: Animation) : State
        data class Failed(val prompt: String, val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Intro)
    val state: StateFlow<State> = _state

    /** Animations made this session, newest first. */
    val history = mutableListOf<Animation>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private val deck = ArrayDeque<String>()
    @Volatile private var stopRequested = false

    fun draw(prompt: String) {
        val text = prompt.trim()
        if (text.isEmpty()) return intro()
        job?.cancel()
        _state.value = State.Drawing(text)
        job = scope.launch {
            try {
                val animation = withContext(Dispatchers.IO) { Server.animation(text) }
                remember(animation)
                _state.value = State.Showing(animation)
                Sounds.play(Sounds.Sound.Done)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = State.Failed(text, e.message ?: "No connection")
                Sounds.play(Sounds.Sound.Error)
            }
        }
    }

    /**
     * The next idea from a shuffled deck, so nothing repeats until they've all been used. The deck
     * is seeded with the time: every process forked from Android's zygote can start with the same
     * default random numbers, which is why Surprise kept opening with the same idea.
     */
    fun surprise() {
        if (deck.isEmpty()) deck.addAll(IDEAS.shuffled(Random(System.nanoTime())))
        draw(deck.removeLast())
    }

    /** Records an idea with [recorder], then has the server hear it and draw it. */
    fun listen(recorder: VoiceRecorder) {
        job?.cancel()
        stopRequested = false
        _state.value = State.Listening
        job = scope.launch {
            try {
                val audio = withContext(Dispatchers.IO) {
                    Sounds.playNow(Sounds.Sound.Listening)
                    recorder.record { stopRequested }
                }
                Sounds.play(Sounds.Sound.GotIt)
                _state.value = State.Drawing("…")
                val animation = withContext(Dispatchers.IO) { Server.voice(audio) }
                remember(animation)
                _state.value = State.Showing(animation)
                Sounds.play(Sounds.Sound.Done)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = State.Failed("", e.message ?: "No connection")
                Sounds.play(Sounds.Sound.Error)
            }
        }
    }

    /** Stops recording early; what was heard so far still gets drawn. */
    fun stopListening() {
        stopRequested = true
    }

    fun show(animation: Animation) {
        job?.cancel()
        _state.value = State.Showing(animation)
    }

    private fun remember(animation: Animation) {
        history.add(0, animation)
        if (history.size > 20) history.removeAt(history.lastIndex)
    }

    fun intro() {
        job?.cancel()
        _state.value = State.Intro
    }

    /** The animation to play for a state: built-in ones while there's nothing from the server. */
    fun animationFor(state: State): Animation = when (state) {
        State.Intro -> BuiltIn.intro
        State.Listening -> BuiltIn.listening
        is State.Drawing -> BuiltIn.rings
        is State.Showing -> state.animation
        is State.Failed -> BuiltIn.oops
    }

    fun titleFor(state: State): String = when (state) {
        State.Intro -> "LED Matrix"
        State.Listening -> "Listening…"
        is State.Drawing -> "Drawing…"
        is State.Showing -> state.animation.title
        is State.Failed -> "Couldn't draw that"
    }

    fun subtitleFor(state: State): String = when (state) {
        State.Intro -> "Tap the mic and say an idea"
        State.Listening -> "Say an idea"
        is State.Drawing -> state.prompt
        is State.Showing -> state.animation.prompt
        is State.Failed -> state.reason
    }

    private val IDEAS = listOf(
        "a rocket launching into space",
        "fireworks over a city skyline",
        "a cat chasing a laser dot",
        "rain on a neon street at night",
        "a pixel racing car on a highway",
        "a jellyfish floating in the deep sea",
        "a robot dancing",
        "a sunset over the ocean with seagulls",
        "a dragon breathing fire",
        "a pirate ship on stormy waves",
        "a unicorn galloping over a rainbow",
        "a volcano erupting at night",
        "a whale jumping out of the water",
        "a ninja running over rooftops",
        "an astronaut floating past Saturn",
        "a train crossing a bridge",
        "a hot air balloon over mountains",
        "a shark swimming past a diver",
        "bees flying around a flower",
        "a butterfly landing on a daisy",
        "a lighthouse beam sweeping the sea",
        "a surfer riding a huge wave",
        "a snowman melting in the sun",
        "penguins sliding on ice",
        "a skateboarder doing a kickflip",
        "a wizard casting a spell",
        "a ghost floating through a graveyard",
        "a bat flying past a full moon",
        "a spider spinning a web",
        "a hummingbird drinking from a flower",
        "a dog chasing its tail",
        "a monkey swinging through the jungle",
        "a lion roaring at sunrise",
        "a fox running through snow",
        "an owl blinking in a tree",
        "a UFO abducting a cow",
        "aliens waving from a flying saucer",
        "a comet streaking across the sky",
        "a black hole swallowing stars",
        "the northern lights over a frozen lake",
        "a cherry blossom tree losing its petals",
        "autumn leaves falling in a park",
        "a tornado crossing a field",
        "a rainbow after a storm",
        "a campfire under the stars",
        "a waterfall into a jungle pool",
        "a sailboat at dusk",
        "a submarine exploring a coral reef",
        "a coffee cup steaming",
        "a pizza being sliced",
        "a hamburger assembling itself",
        "a donut with sprinkles spinning",
        "popcorn popping",
        "a disco ball spinning",
        "a DJ scratching records",
        "a guitar on fire",
        "a heartbeat monitor pulsing",
        "a beating heart with sparkles",
        "a magic carpet flying over a desert",
        "a knight fighting a dragon",
        "a samurai under falling petals",
        "a tiny city waking up at sunrise",
        "a bowling strike",
        "a basketball swishing through a hoop",
        "a soccer goal celebration",
    )
}
