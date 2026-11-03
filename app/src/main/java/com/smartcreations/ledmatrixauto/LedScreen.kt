package com.smartcreations.ledmatrixauto

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.launch

/** The car screen: the animation or the Car view, with a column of tabs down the right side. */
class LedScreen(carContext: CarContext) : Screen(carContext) {
    // Drawing has its own thread: a stalled car surface must never hold up the main thread.
    private val drawing = HandlerThread("leds").apply { start() }
    private val handler = Handler(drawing.looper)
    private val surfaceLock = Any()
    private var frames = 0
    private var notedAt = -NOTE_EVERY_MS
    private var heartbeatAt = 0L
    private val recorder = VoiceRecorder(carContext)
    @Volatile private var surface: Surface? = null
    @Volatile private var surfaceSize = Rect()

    @Volatile private var state: Studio.State = Studio.state.value
    private var listenedOnOpen = false
    private val carSpeed = CarSpeed(carContext)
    private var scene: DriveScene? = null
    private val carData = CarData(carContext)
    @Volatile private var view = View.Leds
    private val shows: List<Show?> = listOf(
        null, // The Car view
        RocketShow(), BoatShow(), PlaneShow(), ClockShow(), CaliforniaShow(), FireworksShow(), FlagsShow(), FartShow(carContext),
        StarfieldShow(), AquariumShow(), LavaShow(), CityShow(), WhackShow(), SnakeShow(),
        FireplaceShow(), PumpkinShow(), SnowCabinShow(), AuroraShow(), BeachShow(), CodeRainShow(),
        HelixShow(), SynthRoadShow(), StormShow(), BalloonsShow(), CatShow(), PlanetsShow(),
    )
    @Volatile private var openShow: Show? = null
    private var showPixels = IntArray(0)
    private val tronbyt: List<Show?> = TRONBYT_APPS.map { ClipShow(carContext, it) }
    @Volatile private var grid = shows
    private val previews = arrayOfNulls<Bitmap>(max(shows.size, TRONBYT_APPS.size))
    private var previewDots: LedDots? = null
    private val previewPixels = IntArray(64 * 32)
    private val previewCar = DriveScene(false)
    private var previewLook = ""
    @Volatile private var scrollPx = 0f
    @Volatile private var scrollTarget = 0f
    private var lastFrameMs = 0L
    private val areaRect = Rect()
    private var lastKey = Long.MIN_VALUE
    private var lastDrawnAt = 0L
    private var drawnLastTick = false
    private val frameTimes = LongArray(STATS_FRAMES)
    private var frameSlot = 0
    private var sameFrames = 0L
    private var lateFrames = 0L
    private var averageMs = 0.0
    private var p95Ms = 0L
    private var thermal = 0
    private var batteryC = 0f
    @Volatile private var surfaceDpi = 0
    @Volatile private var stableArea: Rect? = null
    @Volatile private var visibleArea: Rect? = null
    private var railBitmap: Bitmap? = null
    private var railKey = Long.MIN_VALUE
    private var fps = 0
    private var fpsFrames = 0
    private var pressedTab: Tab? = null
    private var pressedAt = 0L
    private var dots: LedDots? = null
    private var startedAt = SystemClock.elapsedRealtime()

    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY }
    private val band = Paint().apply { color = Color.argb(170, 0, 0, 0) }
    private val railPaint = Paint().apply { color = RAIL }
    private val tabPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    /** One chain of frames: a flag, so stopping can't leave a second one running beside a new one. */
    @Volatile private var drawingOn = false
    private val ticker = object : Runnable {
        override fun run() {
            if (!drawingOn) return
            val started = SystemClock.elapsedRealtime()
            render()
            heartbeat()
            handler.postDelayed(this, (FRAME_MS - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(1))
        }
    }

    init {
        EventLog.install(carContext)
        Settings.restore(carContext)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(object : SurfaceCallback {
            override fun onSurfaceAvailable(container: SurfaceContainer) {
                EventLog.add("Surface ${container.width}x${container.height}")
                synchronized(surfaceLock) {
                    surface = container.surface
                    surfaceSize = Rect(0, 0, container.width, container.height)
                    surfaceDpi = container.dpi
                    lastKey = Long.MIN_VALUE
                }
            }

            override fun onVisibleAreaChanged(area: Rect) {
                visibleArea = area
            }

            override fun onStableAreaChanged(area: Rect) {
                stableArea = area
            }

            override fun onSurfaceDestroyed(container: SurfaceContainer) {
                EventLog.add("Surface gone")
                synchronized(surfaceLock) { surface = null }
            }

            override fun onClick(x: Float, y: Float) = onTap(x, y)

            override fun onScroll(distanceX: Float, distanceY: Float) {
                scrollTarget += distanceY
            }

            override fun onFling(velocityX: Float, velocityY: Float) {
                scrollTarget -= velocityY * FLING_SECONDS
            }
        })
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                EventLog.add("Screen started")
                drawingOn = true
                handler.removeCallbacks(ticker)
                handler.post(ticker)
                syncSpeed()
                // Once per car session, so "Hey Google, open LED Matrix" goes straight to listening.
                if (LISTEN_ON_OPEN && !listenedOnOpen) {
                    listenedOnOpen = true
                    if (state !is Studio.State.Listening) onMic()
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                EventLog.add("Screen stopped")
                drawingOn = false
                handler.removeCallbacks(ticker)
                carSpeed.stop()
                carData.stop()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                drawing.quitSafely()
            }
        })
        lifecycleScope.launch {
            Studio.state.collect {
                if (it !== state) {
                    state = it
                    startedAt = SystemClock.elapsedRealtime()
                }
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        // The template insists on one button of Android Auto's own. It's invisible, over the mic tab.
        val mic = Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_blank)).build())
            .setBackgroundColor(CarColor.createCustom(Color.TRANSPARENT, Color.TRANSPARENT))
            .setFlags(Action.FLAG_PRIMARY)
            .setOnClickListener { onMic() }
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(mic).build())
            .build()
    }

    private fun onMic() {
        when {
            state is Studio.State.Listening -> Studio.stopListening()
            ContextCompat.checkSelfPermission(carContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ->
                Studio.listen(recorder)
            else -> carContext.requestPermissions(listOf(Manifest.permission.RECORD_AUDIO)) { granted, _ ->
                if (Manifest.permission.RECORD_AUDIO in granted) Studio.listen(recorder)
            }
        }
    }

    /** Listens to the car's speed only while the Car tab shows it. */
    private fun syncSpeed() {
        // Stops come first: both listen to the same car speed, and stopping one after starting the
        // other can cancel the new one.
        val wanted = view == View.Car || view == View.Tiles || view == View.Show
        if (view != View.Debug) carData.stop()
        if (!wanted) carSpeed.stop()
        if (view == View.Debug) carData.start()
        when {
            !wanted -> Unit
            ContextCompat.checkSelfPermission(carContext, CAR_SPEED) == PackageManager.PERMISSION_GRANTED -> carSpeed.start()
            else -> carContext.requestPermissions(listOf(CAR_SPEED)) { _, _ -> if (view != View.Leds) carSpeed.start() }
        }
    }

    /** A tap on the picture: picks a tile, or goes to the show that's playing. */
    private fun onLedsTap(x: Float, y: Float) {
        val width = (surfaceSize.width() - railWidth()).toFloat()
        val height = surfaceSize.height().toFloat()
        when (view) {
            View.Tiles -> {
                val pagerW = width * PAGER_WIDTH
                val rowHeight = height / TILE_ROWS
                when {
                    x < width - pagerW -> openTile(
                        (x / (width - pagerW) * TILE_COLUMNS).toInt().coerceAtMost(TILE_COLUMNS - 1) +
                            ((y + scrollPx) / rowHeight).toInt() * TILE_COLUMNS,
                    )
                    y < height / 2 -> scrollTarget -= rowHeight * TILE_ROWS
                    else -> scrollTarget += rowHeight * TILE_ROWS
                }
            }
            View.Show -> openShow?.tap(x / width, y / height)
            else -> Unit
        }
    }

    /** Shows a grid of tiles, starting from the top if it's a different one. */
    private fun openGrid(tiles: List<Show?>) {
        if (grid !== tiles) {
            grid = tiles
            scrollPx = 0f
            scrollTarget = 0f
            previewLook = ""
        }
        view = View.Tiles
    }

    private fun openTile(index: Int) {
        if (index >= grid.size) return
        val show = grid[index]
        EventLog.add("Tile: ${show?.title ?: "Car"}")
        if (show == null) {
            view = View.Car
        } else if (show.game && moving()) {
            CarToast.makeText(carContext, "Park to play", CarToast.LENGTH_SHORT).show()
            return
        } else {
            openShow = show
            view = View.Show
        }
        syncSpeed()
    }

    /** Only true when the car says it's moving: unknown counts as parked. */
    private fun moving() = (speedNow() ?: 0f) > MOVING_METERS_PER_SECOND

    /** The car's speed, or the pretend drive when a test build asks for it. */
    private fun speedNow(): Float? =
        if (Settings.simulateSpeed) DriveTest.metersPerSecond(SystemClock.elapsedRealtime()) else carSpeed.metersPerSecond

    private fun speedUnit() = if (Settings.simulateSpeed) "mph" else carSpeed.unit

    /** Opens the debug screen, asking for whatever car data isn't allowed yet. */
    private fun openCarData() {
        view = View.Debug
        val missing = DEBUG_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(carContext, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) syncSpeed() else carContext.requestPermissions(missing) { _, _ -> syncSpeed() }
    }

    /** A tap on the car screen: the app draws its own tabs, so it works out which one was hit. */
    private fun onTap(x: Float, y: Float) {
        Log.i(TAG, "Tap at $x, $y")
        val tab = Tab.entries.firstOrNull { tabRect(it).contains(x, y) } ?: return onLedsTap(x, y)
        EventLog.add("Tab: $tab")
        pressedTab = tab
        pressedAt = SystemClock.elapsedRealtime()
        when (tab) {
            Tab.Mic -> {
                // What's said gets drawn on the LEDs, so go there to watch.
                view = View.Leds
                onMic()
            }
            Tab.Leds -> view = View.Leds
            Tab.Tiles -> openGrid(shows)
            Tab.Tronbyt -> openGrid(tronbyt)
            Tab.Surprise -> {
                view = View.Leds
                Studio.surprise()
            }
            Tab.Settings -> screenManager.push(SettingsScreen(carContext, ::openCarData))
        }
        syncSpeed()
    }

    /** The LEDs fill everything left of the tab column. Frames that wouldn't change aren't drawn. */
    private fun render() {
        synchronized(surfaceLock) {
            val s = surface?.takeIf { it.isValid } ?: return
            if (surfaceSize.isEmpty) return
            val area = areaRect.apply { set(0, 0, surfaceSize.width() - railWidth(), surfaceSize.height()) }
            val started = SystemClock.elapsedRealtime()
            val key = frameKey(started)
            if (key == lastKey) {
                sameFrames++
                drawnLastTick = false
                return
            }
            lastKey = key
            if (drawnLastTick && started - lastDrawnAt > FRAME_MS * 2 + 5) lateFrames++
            drawnLastTick = true
            // A software canvas: the GPU one aborts the whole app when the car swaps the surface mid-frame.
            val canvas = try {
                s.lockCanvas(null)
            } catch (e: Exception) {
                note("Couldn't lock the surface: $e")
                return
            }
            try {
                canvas.drawColor(Color.BLACK)
                when (view) {
                    View.Leds -> renderLeds(canvas, area, started)
                    View.Car -> renderCar(canvas, area, started)
                    View.Debug -> renderDebug(canvas, area, started)
                    View.Tiles -> renderTiles(canvas, area)
                    View.Show -> renderShow(canvas, area, started)
                }
                renderRail(canvas, started)
                frames++
                fpsFrames++
            } catch (e: Exception) {
                note("Couldn't draw: ${e.stackTraceToString()}")
            } finally {
                try { s.unlockCanvasAndPost(canvas) } catch (e: Exception) { note("Couldn't show the frame: $e") }
            }
            val done = SystemClock.elapsedRealtime()
            frameTimes[frameSlot++ % frameTimes.size] = done - started
            lastDrawnAt = started
            lastFrameMs = done - started
        }
    }

    /** Differs from the last frame's whenever the picture would. */
    private fun frameKey(now: Long): Long {
        val content = when (view) {
            View.Leds -> {
                val a = Studio.animationFor(state)
                a.hashCode().toLong() * 31 + a.frameIndexAt(now - startedAt)
            }
            View.Car -> if ((speedNow() ?: 0f) > 0f || scene?.settled != true) now else 1L
            View.Debug -> now / 500
            View.Tiles -> if (abs(scrollTarget - scrollPx) > 0.5f) now else scrollPx.toLong() * 2 + (if (moving()) 1 else 0)
            View.Show -> openShow?.let {
                it.metersPerSecond = speedNow() ?: 0f
                it.frameKey(now)
            } ?: 0L
        }
        val pressed = pressedTab != null && now - pressedAt < PRESS_MS
        var key = content * 31 + view.ordinal
        key = key * 31 + if (pressed) 1 else 0
        key = key * 31 + (state.hashCode())
        key = key * 31 + (Settings.tint?.ordinal ?: -1)
        key = key * 31 + (if (Settings.glow) 1 else 0) + (if (Settings.square) 2 else 0) + (if (Settings.fine) 4 else 0)
        return key
    }

    /** Logs a drawing problem, once a minute at most: they repeat every frame. */
    private fun note(problem: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - notedAt < NOTE_EVERY_MS) return
        notedAt = now
        Log.w(TAG, problem)
        EventLog.add(problem)
    }

    /** A pulse in the log, which tells a frozen screen from a car that isn't sending speed. */
    private fun heartbeat() {
        val now = SystemClock.elapsedRealtime()
        if (now - heartbeatAt < HEARTBEAT_MS) return
        fps = if (heartbeatAt == 0L) 0 else (fpsFrames * 1000 / (now - heartbeatAt)).toInt()
        fpsFrames = 0
        heartbeatAt = now
        val count = min(frameSlot, frameTimes.size)
        val sorted = frameTimes.copyOf(count).also { it.sort() }
        averageMs = if (count == 0) 0.0 else sorted.average()
        p95Ms = if (count == 0) 0L else sorted[(count * 95 / 100).coerceAtMost(count - 1)]
        thermal = carContext.getSystemService(PowerManager::class.java).currentThermalStatus
        batteryC = (carContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        EventLog.add("Drawing: $frames frames, $fps fps, avg %.1f ms, p95 $p95Ms ms, $sameFrames unchanged, $lateFrames late, $view, thermal $thermal, battery $batteryC C, speed ${carSpeed.metersPerSecond}, surface $surfaceSize".format(averageMs))
    }

    /** The show that's open, at the chosen resolution. */
    private fun renderShow(canvas: Canvas, area: Rect, now: Long) {
        val show = openShow ?: return
        if (show.game && moving()) {
            view = View.Tiles
            return
        }
        val w = if (Settings.fine) 128 else 64
        val h = w / 2
        if (showPixels.size != w * h) showPixels = IntArray(w * h)
        (show as? SpeedShow)?.unit = speedUnit()
        show.render(showPixels, w, h, now)
        dotsFor(w, h, area).draw(canvas, showPixels, Settings.tint?.color, Settings.glow)
    }

    /** Four across and three to the screen, scrolling down through the rest. Previews are stills. */
    private fun renderTiles(canvas: Canvas, area: Rect) {
        val rows = ceil(grid.size / TILE_COLUMNS.toFloat()).toInt()
        val pagerW = (area.width() * PAGER_WIDTH).toInt()
        val tileW = (area.width() - pagerW) / TILE_COLUMNS
        val tileH = area.height() / TILE_ROWS
        val inset = area.height() * 0.012f
        val previewW = (tileW - 2 * inset).toInt()
        val previewH = (tileH - 2 * inset).toInt()
        val look = "${grid.size}${Settings.tint}$previewW"
        if (previewDots?.let { it.screenWidth != previewW || it.screenHeight != previewH } != false) {
            previewDots = LedDots(64, 32, previewW, previewH)
        }
        if (look != previewLook) {
            previewLook = look
            previews.fill(null)
        }
        val maxScroll = ((rows - TILE_ROWS) * tileH).toFloat()
        scrollPx = scrollPx.coerceIn(0f, maxScroll)
        scrollTarget = scrollTarget.coerceIn(0f, maxScroll)
        scrollPx += (scrollTarget - scrollPx) * 0.3f
        val radius = tileH * 0.1f
        for (i in grid.indices) {
            val x = (i % TILE_COLUMNS) * tileW + inset
            val y = (i / TILE_COLUMNS) * tileH + inset - scrollPx
            if (y + previewH < 0 || y > area.height()) continue
            if (previews[i] == null) refreshPreview(i, previewW, previewH)
            val r = RectF(x, y, x + previewW, y + previewH)
            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) })
            previews[i]?.let { canvas.drawBitmap(it, x, y, null) }
            canvas.restore()
            if (grid[i]?.game == true && moving()) {
                label.color = Color.WHITE
                label.textSize = tileH * 0.13f
                canvas.drawRect(r, band)
                canvas.drawText("Park to play", r.centerX(), r.centerY(), label)
            }
        }
        // Up and down arrows for screens that don't send swipes, and a marker for where you are.
        val left = (area.width() - pagerW).toFloat()
        canvas.drawRect(left, 0f, area.width().toFloat(), area.height().toFloat(), railPaint)
        icon.color = if (scrollTarget > 0) Color.WHITE else INK
        arrow(canvas, left + pagerW / 2f, area.height() * 0.1f, pagerW * 0.34f, -1f)
        icon.color = if (scrollTarget < maxScroll) Color.WHITE else INK
        arrow(canvas, left + pagerW / 2f, area.height() * 0.9f, pagerW * 0.34f, 1f)
        val track = area.height() * 0.6f
        val markerTop = area.height() * 0.2f + track * 0.5f * (if (maxScroll > 0) scrollPx / maxScroll else 0f)
        icon.color = ACCENT
        canvas.drawRoundRect(left + pagerW * 0.4f, markerTop, left + pagerW * 0.6f, markerTop + track * 0.5f, pagerW * 0.1f, pagerW * 0.1f, icon)
    }

    private fun arrow(canvas: Canvas, x: Float, y: Float, size: Float, direction: Float) {
        canvas.drawPath(Path().apply {
            moveTo(x, y + size * direction)
            lineTo(x - size, y - size * direction * 0.5f)
            lineTo(x + size, y - size * direction * 0.5f)
            close()
        }, icon)
    }

    private fun refreshPreview(i: Int, w: Int, h: Int) {
        val show = grid[i]
        val frame = if (show == null) {
            previewCar.update(null, "mph", SystemClock.elapsedRealtime())
            previewCar.animation.frames[0]
        } else {
            show.render(previewPixels, 64, 32, PREVIEW_TIME + i * 1700L)
            previewPixels
        }
        val bitmap = previews[i] ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { previews[i] = it }
        // Previews always look their best, whatever the shows themselves are set to.
        previewDots?.draw(Canvas(bitmap), frame, Settings.tint?.color.takeIf { show != null }, glow = false, square = true)
    }

    /** Every value the car shares, with how long ago it arrived, then how the app is doing. */
    private fun renderDebug(canvas: Canvas, area: Rect, now: Long) {
        val rows = carData.readings.entries.map { (name, r) ->
            val age = if (r.at == 0L) "" else "  %.1fs ago".format((now - r.at) / 1000f)
            Triple(name, r.text + age, if (r.ok) DEBUG_OK else if (r.text == CarData.WAITING) Color.LTGRAY else DEBUG_MISSING)
        } + listOf(
            Triple("View", "$view", Color.LTGRAY),
            Triple("Surface", "${surfaceSize.width()}x${surfaceSize.height()} at $surfaceDpi dpi", Color.LTGRAY),
            Triple("Stable area", "${stableArea?.toShortString()}", Color.LTGRAY),
            Triple("Visible area", "${visibleArea?.toShortString()}", Color.LTGRAY),
            Triple("Car API", "${carContext.carAppApiLevel}", Color.LTGRAY),
            Triple("Drawing", "$fps fps, avg %.1f ms, p95 $p95Ms ms".format(averageMs), Color.LTGRAY),
            Triple("Frames", "$sameFrames unchanged, $lateFrames late", Color.LTGRAY),
            Triple("LEDs", dots?.let { "${it.width}x${it.height}, pitch ${it.cellWidth}x${it.cellHeight} px" } ?: "none yet", Color.LTGRAY),
            Triple("Phone heat", "thermal $thermal, battery $batteryC C", Color.LTGRAY),
            Triple("Last server call", Server.lastCall, Color.LTGRAY),
        )
        val columns = 2
        val perColumn = (rows.size + 1) / columns
        val lineHeight = area.height() / perColumn.toFloat()
        val columnWidth = area.width() / columns.toFloat()
        text.textSize = lineHeight * 0.5f
        rows.forEachIndexed { i, (name, value, color) ->
            val x = (i / perColumn) * columnWidth + columnWidth * 0.03f
            val y = (i % perColumn) * lineHeight + lineHeight * 0.68f
            text.color = Color.GRAY
            canvas.drawText("$name ", x, y, text)
            val nameWidth = text.measureText("$name ")
            text.color = color
            val shown = TextUtils.ellipsize(value, text, columnWidth * 0.95f - nameWidth, TextUtils.TruncateAt.END)
            canvas.drawText(shown, 0, shown.length, x + nameWidth, y, text)
        }
        text.color = Color.LTGRAY
    }

    private fun renderCar(canvas: Canvas, area: Rect, now: Long) {
        val drive = scene?.takeIf { it.fine == Settings.fine } ?: DriveScene(Settings.fine).also { scene = it }
        drive.update(speedNow(), speedUnit(), now)
        val leds = drive.animation
        dotsFor(leds.width, leds.height, area).draw(canvas, leds.frames[0], glow = Settings.glow)
    }

    private fun renderLeds(canvas: Canvas, area: Rect, now: Long) {
        val a = Studio.animationFor(state)
        val frame = a.frames[a.frameIndexAt(now - startedAt)]
        val tint = if (state is Studio.State.Showing || state is Studio.State.Intro) Settings.tint?.color else null
        dotsFor(a.width, a.height, area).draw(canvas, frame, tint, glow = Settings.glow)

        // The animations say it all, except what went wrong.
        val caption = (state as? Studio.State.Failed)?.let(Studio::subtitleFor) ?: return
        text.textSize = area.height() * 0.06f
        val layout = caption(caption, (area.width() * 0.9f).toInt())
        val pad = text.textSize * 0.5f
        val bandTop = area.height() - layout.height - 2 * pad
        canvas.drawRect(0f, bandTop, area.width().toFloat(), area.height().toFloat(), band)
        canvas.save()
        canvas.translate(area.exactCenterX() - layout.width / 2f, bandTop + pad)
        layout.draw(canvas)
        canvas.restore()
    }

    /** Square tabs: as wide as the screen's height shared between them. */
    /** The prepared LEDs for a [width] x [height] grid on [area], made again only when either changes. */
    private fun dotsFor(width: Int, height: Int, area: Rect): LedDots {
        val current = dots
        if (current != null && current.width == width && current.height == height &&
            current.screenWidth == area.width() && current.screenHeight == area.height()
        ) return current
        return LedDots(width, height, area.width(), area.height()).also { dots = it }
    }

    private fun railWidth() = surfaceSize.height() / Tab.entries.size

    /** Where a tab sits in the column. */
    private fun tabRect(tab: Tab): RectF {
        val margin = surfaceSize.height() * 0.012f
        val size = railWidth().toFloat()
        val left = surfaceSize.width() - size
        val top = tab.ordinal * size
        return RectF(left + margin, top + margin, left + size - margin, top + size - margin)
    }

    /** The tab column is only drawn again when a tab changes; otherwise it's a saved picture. */
    private fun renderRail(canvas: Canvas, now: Long) {
        val pressed = pressedTab?.takeIf { now - pressedAt < PRESS_MS }
        val key = (view.ordinal * 31L + (pressed?.ordinal ?: -1)) * 31 + (if (state is Studio.State.Listening) 1 else 0) + surfaceSize.height() * 1000L
        val left = surfaceSize.width() - railWidth()
        val saved = railBitmap?.takeIf { it.height == surfaceSize.height() && it.width == railWidth() }
        val bitmap = saved ?: Bitmap.createBitmap(railWidth(), surfaceSize.height(), Bitmap.Config.ARGB_8888).also { railBitmap = it }
        if (saved == null || key != railKey) {
            railKey = key
            bitmap.eraseColor(RAIL)
            drawRail(Canvas(bitmap).apply { translate(-left.toFloat(), 0f) }, now)
        }
        canvas.drawBitmap(bitmap, left.toFloat(), 0f, null)
    }

    /** The column on the right: rounded square tabs, with the active one lit up. */
    private fun drawRail(canvas: Canvas, now: Long) {
        val w = surfaceSize.width().toFloat()
        val h = surfaceSize.height().toFloat()
        canvas.drawRect(w - railWidth(), 0f, w, h, railPaint)
        val listening = state is Studio.State.Listening
        for (tab in Tab.entries) {
            val r = tabRect(tab)
            val radius = r.height() * 0.24f
            val active = when (tab) {
                Tab.Mic -> listening
                Tab.Tiles -> grid === shows && (view == View.Car || view == View.Tiles || view == View.Show)
                Tab.Tronbyt -> grid === tronbyt && (view == View.Tiles || view == View.Show)
                Tab.Leds -> view == View.Leds
                Tab.Surprise, Tab.Settings -> false
            }
            val pressed = tab == pressedTab && now - pressedAt < PRESS_MS
            val lit = active || pressed
            tabPaint.shader = if (lit) LinearGradient(r.left, r.top, r.right, r.bottom, ACCENT_DARK, ACCENT, Shader.TileMode.CLAMP) else null
            tabPaint.color = if (lit) Color.WHITE else TAB
            canvas.drawRoundRect(r.left, r.top + h * 0.006f, r.right, r.bottom + h * 0.006f, radius, radius, shadow)
            canvas.drawRoundRect(r, radius, radius, tabPaint)
            val ink = if (lit) Color.WHITE else INK
            drawIcon(canvas, tab, listening, r.centerX(), r.centerY(), r.height() * 0.27f, ink)
        }
    }

    private fun drawIcon(canvas: Canvas, tab: Tab, listening: Boolean, x: Float, y: Float, size: Float, color: Int) {
        icon.color = color
        when (tab) {
            Tab.Mic -> if (listening) {
                canvas.drawRoundRect(x - size * 0.7f, y - size * 0.7f, x + size * 0.7f, y + size * 0.7f, size * 0.2f, size * 0.2f, icon)
            } else {
                canvas.drawRoundRect(x - size * 0.42f, y - size, x + size * 0.42f, y + size * 0.3f, size * 0.42f, size * 0.42f, icon)
                icon.style = Paint.Style.STROKE
                icon.strokeWidth = size * 0.2f
                canvas.drawArc(x - size * 0.8f, y - size * 0.6f, x + size * 0.8f, y + size * 0.72f, 0f, 180f, false, icon)
                canvas.drawLine(x, y + size * 0.72f, x, y + size, icon)
                icon.style = Paint.Style.FILL
            }
            Tab.Leds -> for (dy in -1..1) for (dx in -1..1) {
                canvas.drawCircle(x + dx * size * 0.75f, y + dy * size * 0.75f, size * 0.26f, icon)
            }
            // A "T" in LED dots, for Tronbyt.
            Tab.Tronbyt -> for (dot in TRONBYT_ICON) {
                canvas.drawCircle(x + (dot.first - 2) * size * 0.46f, y + (dot.second - 2) * size * 0.46f, size * 0.19f, icon)
            }
            Tab.Tiles -> for (dy in 0..1) for (dx in 0..1) {
                val left = x - size + dx * size * 1.08f
                val top = y - size + dy * size * 1.08f
                canvas.drawRoundRect(left, top, left + size * 0.92f, top + size * 0.92f, size * 0.2f, size * 0.2f, icon)
            }
            Tab.Settings -> {
                val gear = Path().apply {
                    fillType = Path.FillType.EVEN_ODD
                    val teeth = 8
                    for (i in 0 until teeth * 4) {
                        val r = size * if (i % 4 < 2) 1.1f else 0.8f
                        val a = 2 * Math.PI * (i - 0.5) / (teeth * 4)
                        val px = x + r * cos(a).toFloat()
                        val py = y + r * sin(a).toFloat()
                        if (i == 0) moveTo(px, py) else lineTo(px, py)
                    }
                    close()
                    addCircle(x, y, size * 0.38f, Path.Direction.CW)
                }
                canvas.drawPath(gear, icon)
            }
            Tab.Surprise -> {
                val path = Path().apply {
                    moveTo(x, y - size)
                    quadTo(x, y, x + size, y)
                    quadTo(x, y, x, y + size)
                    quadTo(x, y, x - size, y)
                    quadTo(x, y, x, y - size)
                    close()
                }
                canvas.drawPath(path, icon)
            }
        }
    }

    /** [caption] centered in [width], wrapped to two lines at most and cut with "…" past that. */
    private fun caption(caption: String, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(caption, 0, caption.length, text, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    private enum class View { Leds, Car, Debug, Tiles, Show }

    private enum class Tab { Mic, Leds, Tiles, Tronbyt, Surprise, Settings }

    private companion object {
        const val TAG = "LedScreen"
        const val PRESS_MS = 180L
        const val TILE_COLUMNS = 4
        const val TILE_ROWS = 3
        val TRONBYT_APPS = listOf(
            "aquarium", "fireworks", "yulelog", "citylights", "billiards", "brownian",
            "langtonsant", "life", "maze", "perlinnoise", "tvstatic", "snake",
        )
        val TRONBYT_ICON = listOf(0 to 0, 1 to 0, 2 to 0, 3 to 0, 4 to 0, 2 to 1, 2 to 2, 2 to 3, 2 to 4)
        const val PAGER_WIDTH = 0.055f
        const val FLING_SECONDS = 0.25f
        /** Where in a show its still preview is taken. */
        const val PREVIEW_TIME = 3_000_000L
        /** About 2 mph: faster than this, the games close. */
        const val MOVING_METERS_PER_SECOND = 1f
        val DEBUG_PERMISSIONS = listOf(
            "com.google.android.gms.permission.CAR_SPEED",
            "com.google.android.gms.permission.CAR_MILEAGE",
            "com.google.android.gms.permission.CAR_FUEL",
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        val DEBUG_OK = Color.rgb(46, 230, 166)
        val DEBUG_MISSING = Color.rgb(255, 120, 80)
        const val NOTE_EVERY_MS = 60_000L
        const val HEARTBEAT_MS = 10_000L
        val RAIL = Color.rgb(12, 14, 22)
        val TAB = Color.rgb(30, 34, 46)
        val INK = Color.rgb(170, 178, 196)
        val ACCENT_DARK = Color.rgb(0x1B, 0x2F, 0x5C)
        val ACCENT = Color.rgb(34, 190, 220)
        const val CAR_SPEED = "com.google.android.gms.permission.CAR_SPEED"
        /** Android Auto streams at about 30 frames a second, so more only makes heat. */
        const val FRAME_MS = 33L
        const val STATS_FRAMES = 300
        /** Start listening as soon as the car screen opens, as if the mic was tapped. */
        const val LISTEN_ON_OPEN = false
    }
}
