package com.nomadnotes.app.probe

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nomadnotes.app.storage.notebooksRoot
import com.nomadnotes.core.StrokePoint
import com.nomadnotes.pen.onyx.OnyxHiddenApi
import com.nomadnotes.pen.onyx.OnyxRawDrawingController
import com.nomadnotes.pen.onyx.OnyxTouchProbeKnobs
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Answers docs/BACKLOG.md item 4 — does this firmware deliver finger [MotionEvent]s while Onyx raw
 * drawing is enabled? — by watching for them at every point they could be lost and letting the
 * operator label ground truth as they go.
 *
 * Nobody has confirmed the answer, so this activity does not assume where fingers are swallowed: it
 * wires up all three plausible observation points at once ([surfaceView]'s touch listener, an
 * [dispatchTouchEvent] override, and a transparent [overlay] view above the surface) and tags every
 * logged line with which one saw it. It also does not assume which `TouchHelper` setting (if any)
 * controls the behaviour: [OnyxRawDrawingController.setFingerTouchEnabled] and each knob in
 * [OnyxTouchProbeKnobs] are exposed as independent on-screen toggles so they can be tried one at a
 * time on the live panel, with the CSV recording exactly when each was flipped.
 *
 * PROBE ONLY — throwaway instrumentation for the 2026-09 finger-touch spike, deleted once
 * docs/BACKLOG.md item 4 is answered. Deliberately not built on [com.nomadnotes.app.SpikeActivity]:
 * that activity is kept as the raw-drawing reference and this probe's toggles (finger touch on,
 * wet ink off, exotic TouchHelper knobs) would corrupt that demo.
 */
class FingerProbeActivity : Activity() {

    private lateinit var surfaceView: SurfaceView
    private lateinit var overlay: View
    private lateinit var excludeStripView: View
    private lateinit var labelIndicator: TextView

    private var controller: OnyxRawDrawingController? = null
    private var probeKnobs: OnyxTouchProbeKnobs? = null

    // Stamped onto every logged touch line until the operator taps a different label button, so an
    // offline read of the CSV can tell a deliberate TWO_FINGER_TAP from an incidental PALM_FLAT.
    private var currentLabel: String = "NONE"

    // Onyx delivers raw pen callbacks off the main thread (see OnyxRawDrawingController); the CSV
    // writer is main-thread-only, so those callbacks are marshalled here before logging.
    private val mainHandler = Handler(Looper.getMainLooper())

    private var writer: BufferedWriter? = null
    private lateinit var outputFile: File
    private var linesSinceFlush = 0

    // Every toggle this probe drives, in the order its button appears, so the CSV header can dump
    // all of their starting states in one line.
    private lateinit var rawDrawingToggle: ProbeToggle
    private lateinit var excludeStripToggle: ProbeToggle
    private val toggles = mutableListOf<ProbeToggle>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Same precondition as every other Onyx entry point: without this, raw drawing's capture
        // region maps to empty and neither pen nor finger reaches the app.
        if (OnyxRawDrawingController.isBooxDevice()) {
            OnyxHiddenApi.exemptOnyxSystemClasses()
        }
        setContentView(buildContentView())
        openCsv()
        surfaceView.holder.addCallback(surfaceCallback)
    }

    override fun onResume() {
        super.onResume()
        if (rawDrawingToggle.state) controller?.resume()
    }

    override fun onPause() {
        controller?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        controller?.close()
        writer?.apply {
            flush()
            close()
        }
        writer = null
        super.onDestroy()
    }

    /**
     * The one override this probe adds that no production Activity has: whether anything is ever
     * logged here — vs. only from [overlay] or [surfaceView] — tells us whether the window itself
     * receives finger events while raw drawing is on, independent of which view would claim them.
     * Never consumes; always defers to the normal dispatch chain.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        logTouch(SOURCE_DISPATCH, ev)
        return super.dispatchTouchEvent(ev)
    }

    // ---- View construction ----------------------------------------------------------------

    private fun buildContentView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val drawingArea = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        surfaceView = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        drawingArea.addView(surfaceView)

        // A visual guide only (no touch handling of its own): marks the band that the exclude-strip
        // toggle registers with the controller, so the operator can see where to touch for that test.
        excludeStripView = View(this).apply {
            setBackgroundColor(EXCLUDE_STRIP_COLOR)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                STRIP_HEIGHT_PX,
                Gravity.BOTTOM,
            )
        }
        drawingArea.addView(excludeStripView)

        // Topmost in z-order, so it gets first look at every ACTION_DOWN; returning false from its
        // listener declines to claim the gesture, which is what lets surfaceView's own listener see
        // the rest of it below (see class doc — this is observation point 3).
        overlay = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setOnTouchListener { _, event ->
                logTouch(SOURCE_OVERLAY, event)
                false
            }
        }
        drawingArea.addView(overlay)

        root.addView(drawingArea)
        root.addView(buildControlPanel())
        return root
    }

    private fun buildControlPanel(): View {
        val density = resources.displayMetrics.density
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.LTGRAY)
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        labelIndicator = TextView(this).apply {
            text = "Label: $currentLabel"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        panel.addView(labelIndicator)

        addSectionTitle(panel, "TouchHelper toggles")
        rawDrawingToggle = addToggle(panel, "rawDrawing", initial = true) { _, value ->
            if (value) controller?.resume() else controller?.pause()
        }
        addToggle(panel, "wetInk", initial = true) { _, value ->
            controller?.setWetInkEnabled(value)
        }
        addToggle(panel, "enableFingerTouch", initial = false) { _, value ->
            controller?.setFingerTouchEnabled(value)
        }
        addToggle(panel, "onlyEnableFingerTouch", initial = false) { _, value ->
            probeKnobs?.onlyEnableFingerTouch(value)
        }
        addToggle(panel, "setTouchListenerEnabled", initial = false) { _, value ->
            probeKnobs?.setTouchListenerEnabled(value)
        }
        addToggle(panel, "setPostInputEvent", initial = false) { _, value ->
            probeKnobs?.setPostInputEvent(value)
        }
        addToggle(panel, "setRawInputReaderEnable", initial = false) { _, value ->
            probeKnobs?.setRawInputReaderEnable(value)
        }
        excludeStripToggle = addToggle(panel, "excludeStrip", initial = false) { _, value ->
            controller?.setExcludeRects(if (value) listOf(stripRect()) else emptyList())
        }

        addButton(panel, "Clear") { clearPanel() }

        addSectionTitle(panel, "Ground truth (tap before the gesture)")
        val labelsRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val labelsRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        panel.addView(labelsRow1)
        panel.addView(labelsRow2)
        listOf(
            "PALM_REST_WRITING_POSTURE", "PALM_FLAT", "TWO_FINGER_TAP", "TWO_FINGER_HOLD",
        ).forEach { addLabelButton(labelsRow1, it) }
        listOf(
            "ONE_FINGER", "PEN_ONLY", "PEN_PLUS_FINGER", "PEN_THEN_FINGER",
        ).forEach { addLabelButton(labelsRow2, it) }
        addButton(panel, "— clear label —") { setLabel("NONE") }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (CONTROL_PANEL_HEIGHT_DP * density).toInt(),
            )
        }
        scroll.addView(panel)
        return scroll
    }

    private fun addSectionTitle(parent: ViewGroup, text: String) {
        parent.addView(
            TextView(this).apply {
                this.text = text
                textSize = 14f
                setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
            },
        )
    }

    private fun addButton(parent: ViewGroup, text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 16f
            setOnClickListener { onClick() }
            parent.addView(this)
        }

    private fun addLabelButton(parent: ViewGroup, label: String) {
        addButton(parent, label) { setLabel(label) }
    }

    private fun setLabel(label: String) {
        currentLabel = label
        labelIndicator.text = "Label: $currentLabel"
        logConfig("label", label)
    }

    /** Creates a toggle button wired to [onChange], applies no state until the operator presses it
     *  (see [ProbeToggle]), and registers it in [toggles] so the CSV header can dump every knob's
     *  starting value in one line. */
    private fun addToggle(
        parent: ViewGroup,
        key: String,
        initial: Boolean,
        onChange: (key: String, value: Boolean) -> Unit,
    ): ProbeToggle {
        val button = Button(this).apply { textSize = 16f; parent.addView(this) }
        val toggle = ProbeToggle(button, key, initial) { k, v ->
            onChange(k, v)
            logConfig(k, v)
        }
        toggles += toggle
        return toggle
    }

    private fun clearPanel() {
        val c = controller ?: return
        c.renderToScreen(blankBitmap(), clean = true)
    }

    private fun blankBitmap(): Bitmap =
        Bitmap.createBitmap(surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }

    // ---- Onyx wiring ------------------------------------------------------------------------

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            if (surfaceView.width <= 0 || surfaceView.height <= 0) {
                surfaceView.post { surfaceCreated(holder) }
                return
            }
            initDrawingBackend()
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
        override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
    }

    private fun initDrawingBackend() {
        // This probe exists to answer an Onyx-firmware question; off Boox there is nothing to
        // observe on the raw-drawing channel, but the touch observers below still work, so an
        // emulator run can at least confirm the CSV/label plumbing.
        if (!OnyxRawDrawingController.isBooxDevice()) {
            Log.w(TAG, "Not a Boox device; TouchHelper toggles are no-ops on this run.")
            surfaceView.setOnTouchListener { _, event -> logTouch(SOURCE_SURFACE, event); true }
            return
        }
        val created = OnyxRawDrawingController(
            surfaceView = surfaceView,
            onDrawingGesture = { points -> mainHandler.post { logOnyx("onyxRawDrawing", points) } },
            onEraseGesture = { points -> mainHandler.post { logOnyx("onyxRawErase", points) } },
            onGestureStarted = { mainHandler.post { logConfig("onyxGestureStarted", currentLabel) } },
        )
        // Onyx requires an already-drawn surface before raw drawing opens (see
        // OnyxRawDrawingController); a blank page is enough since this probe never persists ink.
        created.renderToScreen(blankBitmap())
        created.openRawDrawing(
            Rect(0, 0, surfaceView.width, surfaceView.height),
            if (excludeStripToggle.state) listOf(stripRect()) else emptyList(),
        )
        controller = created
        probeKnobs = OnyxTouchProbeKnobs(created)
        rawDrawingToggle.applyCurrent()

        // The primary question, wired exactly as production would (see OnyxPenBackend's lasso
        // listener): claim the gesture like a real feature's touch handler would, so this reflects
        // what two-finger-undo or the lasso gesture would actually see.
        surfaceView.setOnTouchListener { _, event -> logTouch(SOURCE_SURFACE, event); true }
        Log.i(TAG, "Onyx raw drawing opened for the finger probe")
    }

    private fun stripRect(): Rect {
        val h = surfaceView.height
        return Rect(0, h - STRIP_HEIGHT_PX, surfaceView.width, h)
    }

    // ---- CSV logging --------------------------------------------------------------------------

    private fun openCsv() {
        val dir = File(notebooksRoot(this), "probe").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        outputFile = File(dir, "finger-probe-$timestamp.csv")
        writer = BufferedWriter(FileWriter(outputFile, false))
        writeHeader()
        Log.i(TAG, "Logging to ${outputFile.absolutePath}")
    }

    private fun writeHeader() {
        val metrics = resources.displayMetrics
        writeRaw(
            "#RUN,fingerprint=${Build.FINGERPRINT},screenW=${metrics.widthPixels}," +
                "screenH=${metrics.heightPixels},density=${metrics.density}",
        )
        writeRaw(
            "#TOGGLES," + toggles.joinToString(",") { "${it.key}=${if (it.state) "ON" else "OFF"}" },
        )
        writeRaw(
            "#COLUMNS,source,eventTimeMs,downTimeMs,action,actionIndex,pointerCount,label," +
                "(pointerId,toolType,x,y,pressure,size,touchMajor,touchMinor,orientation)*pointerCount," +
                "deviceId,inputSource,buttonState,flags,historySize",
        )
    }

    /** Logs one touch event as a single CSV line: the per-event columns, then the nine-column
     *  pointer group repeated [MotionEvent.getPointerCount] times, then the per-event trailer. */
    private fun logTouch(source: String, event: MotionEvent) {
        val row = StringBuilder()
        row.append(source).append(',')
            .append(event.eventTime).append(',')
            .append(event.downTime).append(',')
            .append(actionName(event.actionMasked)).append(',')
            .append(event.actionIndex).append(',')
            .append(event.pointerCount).append(',')
            .append(currentLabel).append(',')
        for (i in 0 until event.pointerCount) {
            row.append(event.getPointerId(i)).append(',')
                .append(toolTypeName(event.getToolType(i))).append(',')
                .append(event.getX(i)).append(',')
                .append(event.getY(i)).append(',')
                .append(event.getPressure(i)).append(',')
                .append(event.getSize(i)).append(',')
                .append(event.getTouchMajor(i)).append(',')
                .append(event.getTouchMinor(i)).append(',')
                .append(event.getOrientation(i)).append(',')
        }
        row.append(event.deviceId).append(',')
            .append(event.source).append(',')
            .append(event.buttonState).append(',')
            .append(event.flags).append(',')
            .append(event.historySize)
        writeRaw(row.toString())
        Log.i(TAG, row.toString())
    }

    // The pen's own raw channel (see OnyxRawDrawingController): a different schema (TouchPoint has
    // no pointer id/tool type/etc.), so it gets its own comment-prefixed line rather than forcing it
    // into the touch-event columns above.
    private fun logOnyx(source: String, points: List<StrokePoint>) {
        val line = "#ONYX,t=${SystemClock.elapsedRealtime()},source=$source,label=$currentLabel,points=${points.size}"
        writeRaw(line)
        Log.i(TAG, line)
    }

    private fun logConfig(key: String, value: Boolean) = logConfig(key, if (value) "ON" else "OFF")

    private fun logConfig(key: String, value: String) {
        val line = "#CONFIG,t=${SystemClock.elapsedRealtime()},$key=$value"
        writeRaw(line)
        Log.i(TAG, line)
    }

    private fun writeRaw(line: String) {
        val w = writer ?: return
        w.write(line)
        w.newLine()
        // Deliberately not flushed per line: an fsync here would perturb the very touch latency this
        // probe is trying to measure. Flushed periodically instead, and always on pause/destroy.
        linesSinceFlush++
        if (linesSinceFlush >= FLUSH_INTERVAL) {
            w.flush()
            linesSinceFlush = 0
        }
    }

    private fun actionName(action: Int): String = when (action) {
        MotionEvent.ACTION_DOWN -> "DOWN"
        MotionEvent.ACTION_UP -> "UP"
        MotionEvent.ACTION_MOVE -> "MOVE"
        MotionEvent.ACTION_CANCEL -> "CANCEL"
        MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
        MotionEvent.ACTION_POINTER_UP -> "POINTER_UP"
        MotionEvent.ACTION_OUTSIDE -> "OUTSIDE"
        else -> "ACTION_$action"
    }

    private fun toolTypeName(toolType: Int): String = when (toolType) {
        MotionEvent.TOOL_TYPE_FINGER -> "FINGER"
        MotionEvent.TOOL_TYPE_STYLUS -> "STYLUS"
        MotionEvent.TOOL_TYPE_ERASER -> "ERASER"
        MotionEvent.TOOL_TYPE_MOUSE -> "MOUSE"
        else -> "UNKNOWN"
    }

    companion object {
        private const val TAG = "FingerProbe"
        private const val SOURCE_DISPATCH = "dispatchTouchEvent"
        private const val SOURCE_OVERLAY = "overlay"
        private const val SOURCE_SURFACE = "surfaceView"

        private const val FLUSH_INTERVAL = 200
        private const val CONTROL_PANEL_HEIGHT_DP = 340

        /** The exclude-rect-strip test band, in raw surface pixels (TouchHelper's own coordinate
         *  space — see OnyxRawDrawingController), matching the "~200px band" this spike measures. */
        private const val STRIP_HEIGHT_PX = 200
        private val EXCLUDE_STRIP_COLOR = Color.argb(80, 255, 200, 0)
    }
}

/**
 * One on-screen boolean knob. The button shows the current state in its own label; [onChange] —
 * which both applies the setting and logs it — fires only when the operator presses it, never
 * implicitly, so the CSV's #CONFIG trail is a complete record of every state this knob was ever in.
 */
private class ProbeToggle(
    private val button: Button,
    val key: String,
    initial: Boolean,
    private val onChange: (key: String, value: Boolean) -> Unit,
) {
    var state: Boolean = initial
        private set

    init {
        render()
        button.setOnClickListener {
            state = !state
            onChange(key, state)
            render()
        }
    }

    /** Re-asserts the current state without flipping it or logging it as an operator action; used
     *  once, right after the Onyx controller is created, so a knob whose initial value must
     *  actually be applied (raw drawing itself) starts in sync with what its button shows. */
    fun applyCurrent() = onChange(key, state)

    private fun render() {
        button.text = "$key: ${if (state) "ON" else "OFF"}"
    }
}
