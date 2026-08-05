package com.yas.linedebugger

import android.app.Service
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// How far a handle is allowed to slide past the edge of the screen before
// it stops, expressed as a fraction of the handle's own hitbox size — 0.10
// means only the outer 10% may hang off the edge. Ported from the Manual
// app's calibration-handle clamp and now also applied to the Ray Circle
// and the manual CUE/TARGET handles.
private const val EDGE_LIMIT_FRACTION = 0.10f

// Matches CaptureService.minFrameIntervalMs — nothing in the app, capture
// or interactive, does more update work per second than this.
private const val FRAME_INTERVAL_MS = 33L

/**
 * Caps relayout/redraw work from touch-drag handlers to a fixed 30fps
 * cadence, regardless of the display's actual refresh rate (60/90/120Hz
 * digitizers all sample faster than that). Requests made while a run is
 * already scheduled collapse into a single trailing apply of whatever
 * state is current when it fires — no dropped final position, no
 * redundant WindowManager transactions in between.
 */
private class FrameThrottle(private val view: View, private val intervalMs: Long = FRAME_INTERVAL_MS) {
    private var scheduled = false
    private var lastRunMs = 0L
    fun request(action: () -> Unit) {
        if (scheduled) return
        scheduled = true
        val wait = (intervalMs - (android.os.SystemClock.uptimeMillis() - lastRunMs)).coerceIn(0L, intervalMs)
        view.postDelayed({
            scheduled = false
            lastRunMs = android.os.SystemClock.uptimeMillis()
            action()
        }, wait)
    }
}

private const val EDGE_HANDLE_VISUAL_PX = 46f
private const val EDGE_HANDLE_HITBOX_PX = 64

private const val DPAD_SIZE_PX = 150
private const val DPAD_NUDGE_PX = 1f
private const val DPAD_GAP_PX = 20f

private const val DASH_LEN_PX = 26f
private const val DASH_GAP_PX = 16f

// Manual CUE/TARGET handle hitbox — ported from the Manual app's
// CONTROLLER_VISUAL_PX. Deliberately much bigger than the ball itself
// (Tunables.ghostBallDiameterPx) so it's easy to grab with a finger; not
// user-tunable, same as it wasn't in the Manual app.
private const val MANUAL_HANDLE_HITBOX_PX = 250

// DEST (kiss-shot pocket marker) doesn't need nearly as much grab area as
// CUE/TARGET — it's a small dot, not a ball, and the full-size hitbox was
// swallowing touches meant for whatever's near it on screen. Half size.
private const val DEST_HANDLE_HITBOX_PX = MANUAL_HANDLE_HITBOX_PX / 2

// A touch that moves less than this many RAW screen pixels between DOWN
// and UP counts as a tap rather than a drag (used by DEST's tap-to-toggle
// — see ManualHandle's onTapped). Measured in raw finger pixels, not
// sensitivity-scaled ones, so the sensitivity slider can't make tapping
// feel twitchier or a real short drag get misread as a tap.
private const val TAP_SLOP_PX = 18f

// How far (in raw drag pixels) TARGET must be pulled past a calibrated
// rail edge before it actually lets go and moves back toward the table
// centre. Only TARGET gets this — it's the bank-shot handle, so sitting
// perfectly on the rail matters and a stray touch shouldn't be able to
// bump it off. Sliding TOWARD or ALONG an edge is never resisted, only
// peeling away from one. CUE (and DEST) keep the plain hard clamp
// (stickyPx=0).
private const val TARGET_EDGE_STICKY_PX = 22f

// Bug #5 (artificial-line clipping): the Ray Zone is the Ray Circle's
// footprint expanded by this factor. No artificial line — main,
// bank-reflected, or double — may be drawn inside it. Requested range was
// 10-30% beyond the boundary; 1.20 (20%) is used here.
private const val RAY_ZONE_EXCLUSION_FACTOR = 1.20f

// Floating tweak panel: almost full phone width, modest height so it
// doesn't eat the whole screen while still showing several sliders.
private const val PANEL_HEIGHT_PX = 520


object OverlayController {

    @Volatile var circleCenterX: Int = 400
    @Volatile var circleCenterY: Int = 800
    @Volatile var lastResult: DetectionResult? = null

    // Feature request: Manual Only mode (no screen recording). When true,
    // there's no CaptureService frame pipeline running at all, so the Ray
    // Circle drag-handle and the Ray Monitor debug preview are both
    // meaningless clutter — attach() skips creating the circle handle, and
    // DrawOverlayView skips drawing both. Everything else (manual
    // CUE/TARGET/DEST controller, kiss shot, table calibration, tweak
    // panel) is identical either way, since none of it depends on capture.
    @Volatile var captureless: Boolean = false
        private set

    // Whether a real capture session is currently backing this overlay —
    // distinct from captureless (fixed at attach time). Flips false when
    // CaptureService kills MediaProjection/VirtualDisplay/ImageReader (see
    // Hide-stops-capture below), so Show doesn't resurrect dead auto-detect
    // UI (Ray Circle, Ray Monitor) for a capture pipeline that no longer
    // exists.
    @Volatile var captureAlive: Boolean = false
        private set

    fun setCaptureAlive(alive: Boolean) {
        captureAlive = alive
        applyHandleVisibility()
    }

    // --- Candidate lock (anti-blink, but switches fast on big angle change) ---
    private var lockedResult: DetectionResult? = null
    private var lockHoldFrames: Int = 0
    private const val LOCK_HOLD = 6            // frames to keep a good lock on the *same* line
    private const val ANGLE_TOL = 0.18         // ~10 degrees - considered "same line"

    // Displayed angle/centroid filter. Plain EMA and deadband+snap both
    // failed here: EMA scales response to delta size (small real motion
    // gets lost); snap-above-floor removes lag but also removes noise
    // rejection, so it shows raw per-frame jitter during motion (the
    // "throbbing while dragging, locks when still" symptom). One Euro
    // filter fixes both: smoothing strength is driven by estimated speed —
    // heavy smoothing near-zero speed (kills jitter, converges to exact
    // value once stopped), light smoothing at drag speed (kills jitter
    // without re-adding lag).
    private class OneEuroFilter(
        val minCutoff: () -> Double,
        val beta: () -> Double,
        val dCutoff: Double = 1.0
    ) {
        private var xPrev: Double = 0.0
        private var dxPrev: Double = 0.0
        private var lastTimeMs: Long = -1L
        private var init = false

        private fun alpha(cutoff: Double, dt: Double): Double {
            val tau = 1.0 / (2.0 * Math.PI * cutoff)
            return 1.0 / (1.0 + tau / dt)
        }

        fun reset(x: Double) {
            xPrev = x; dxPrev = 0.0; lastTimeMs = -1L; init = true
        }

        fun filter(x: Double, nowMs: Long): Double {
            if (!init) { reset(x); return x }
            val dt = ((nowMs - lastTimeMs).coerceAtLeast(1)) / 1000.0
            lastTimeMs = nowMs
            val dx = (x - xPrev) / dt
            val edx = dxPrev + alpha(dCutoff, dt) * (dx - dxPrev)
            dxPrev = edx
            val cutoff = minCutoff() + beta() * abs(edx)
            val xHat = xPrev + alpha(cutoff, dt) * (x - xPrev)
            xPrev = xHat
            return xHat
        }
    }

    private val angleFilter = OneEuroFilter(
        minCutoff = { Tunables.angleMinCutoff.toDouble() },
        beta = { Tunables.angleBeta.toDouble() }
    )
    private val offXFilter = OneEuroFilter(
        minCutoff = { Tunables.offsetMinCutoff.toDouble() },
        beta = { Tunables.offsetBeta.toDouble() }
    )
    private val offYFilter = OneEuroFilter(
        minCutoff = { Tunables.offsetMinCutoff.toDouble() },
        beta = { Tunables.offsetBeta.toDouble() }
    )
    private var rawAngleUnwrapped: Double = 0.0
    private var lastRawAngle: Double = 0.0
    private var smoothAngle: Double = 0.0
    private var smoothOffX: Float = 0f
    private var smoothOffY: Float = 0f
    private var smoothInit = false

    private var service: Service? = null
    private var windowManager: WindowManager? = null
    private var drawView: DrawOverlayView? = null
    private var handleView: View? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var screenWidth: Int = 0
    private var screenHeight: Int = 0

    // --- Table calibration state ---
    @Volatile var calibrationMode: Boolean = false
        private set
    @Volatile var edgeAX: Float = 0f
    @Volatile var edgeAY: Float = 0f
    @Volatile var edgeBX: Float = 0f
    @Volatile var edgeBY: Float = 0f
    private var edgeAHandle: EdgeHandle? = null
    private var edgeBHandle: EdgeHandle? = null
    private var edgeADPad: EdgeDPad? = null
    private var edgeBDPad: EdgeDPad? = null

    // --- Semi-automatic table calibration state ---
    // Builds on the manual (2-corner) calibration above: every full
    // manual calibration also captures its width/height as a "template"
    // (Tunables.tableTemplateWidth/Height, see saveCalibrationAndRemoveHandles).
    // A real table's rails are rigid, so once that size is known, semi-auto
    // mode only needs the table's center pixel — one crosshair, one D-Pad —
    // and reconstructs all four rail edges from center +/- template/2.
    @Volatile var semiAutoCalibrationMode: Boolean = false
    @Volatile var semiCenterX: Float = 0f
    @Volatile var semiCenterY: Float = 0f
    private var semiCenterHandle: EdgeHandle? = null
    private var semiCenterDPad: EdgeDPad? = null

    // --- Manual CUE / TARGET controller state (feature request #1) ---
    // Live positions only — never persisted, exactly like the Manual
    // app's cueX/cueY/targetX/targetY, which always reset to a
    // screen-relative default on service start rather than remembering
    // where they were left.
    @Volatile var manualCueX: Float = 0f
    @Volatile var manualCueY: Float = 0f
    @Volatile var manualTargetX: Float = 0f
    @Volatile var manualTargetY: Float = 0f
    private var manualCueHandle: ManualHandle? = null
    private var manualTargetHandle: ManualHandle? = null

    // --- Manual DEST controller state (kiss-shot assist) ---
    // Kiss shot is fully reconstructed from the existing CUE/TARGET pair
    // (TARGET = the ball being kissed off of, CUE = the moving ball's
    // approach point — same "not necessarily the real cue ball" idea as
    // bank shots). DEST (pocket target) is the only new point needed.
    @Volatile var manualDestX: Float = 0f
    @Volatile var manualDestY: Float = 0f
    private var manualDestHandle: ManualHandle? = null

    // --- Manual COMBO controller state (combo-shot assist) ---
    // Fully independent of DEST/Kiss above — its own destination handle
    // (yellow octagon), same full size as CUE/TARGET rather than DEST's
    // small dot. Always renders when Tunables.manualComboEnabled, whether
    // or not Kiss Shot is also on.
    @Volatile var manualComboX: Float = 0f
    @Volatile var manualComboY: Float = 0f
    private var manualComboHandle: ManualHandle? = null

    private const val OVERLAY_TYPE = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun WindowManager.LayoutParams.applyFullScreenFlags() {
        flags = flags or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    fun attach(svc: Service, captureless: Boolean = false) {
        service = svc
        OverlayController.captureless = captureless
        captureAlive = !captureless
        val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val realMetrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(realMetrics)
        screenWidth = realMetrics.widthPixels
        screenHeight = realMetrics.heightPixels
        circleCenterX = screenWidth / 2
        circleCenterY = screenHeight / 2

        val dView = DrawOverlayView(svc)
        drawView = dView
        val drawParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            OVERLAY_TYPE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply { applyFullScreenFlags() }
        wm.addView(dView, drawParams)

        // Manual Only mode: no capture pipeline is running, so the Ray
        // Circle drag-handle has nothing to detect against — applyHandleVisibility
        // below only creates it when !captureless (and aimVisible/captureAlive hold).
        applyHandleVisibility()
        buildPanel(svc, wm)

        // Manual controller: only attach the CUE/TARGET handles if the
        // user had it enabled last session — see setManualControllerEnabled.
        if (Tunables.manualControllerEnabled) {
            attachManualHandles()
        }
    }

    private fun attachCircleHandle(service: Service, wm: WindowManager) {
        val diam = Tunables.circleDiameter
        val hParams = WindowManager.LayoutParams(
            diam,
            diam,
            OVERLAY_TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = circleCenterX - diam / 2
            y = circleCenterY - diam / 2
            applyFullScreenFlags()
        }
        handleParams = hParams

        val hView = View(service)
        var downRawX = 0f; var downRawY = 0f; var downX = 0; var downY = 0
        val circleThrottle = FrameThrottle(hView)
        hView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX; downRawY = event.rawY
                    downX = hParams.x; downY = hParams.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    var newX = downX + (event.rawX - downRawX)
                    var newY = downY + (event.rawY - downRawY)

                    val size = hParams.width
                    val margin = size * EDGE_LIMIT_FRACTION
                    val minX = -margin
                    val maxX = screenWidth - size + margin
                    val minY = -margin
                    val maxY = screenHeight - size + margin
                    if (newX < minX) newX = minX else if (newX > maxX) newX = maxX
                    if (newY < minY) newY = minY else if (newY > maxY) newY = maxY

                    hParams.x = newX.toInt()
                    hParams.y = newY.toInt()
                    circleThrottle.request {
                        runCatching { wm.updateViewLayout(v, hParams) }
                        circleCenterX = hParams.x + size / 2
                        circleCenterY = hParams.y + size / 2
                        drawView?.invalidate()
                    }
                    true
                }
                else -> false
            }
        }
        handleView = hView
        wm.addView(hView, hParams)
    }

    /** Called by the settings UI when the circle-diameter slider moves. */
    fun onCircleDiameterChanged(newDiameter: Int) {
        val wm = windowManager ?: return
        val hView = handleView ?: return
        val p = handleParams ?: return
        val centerX = p.x + p.width / 2
        val centerY = p.y + p.height / 2
        p.width = newDiameter
        p.height = newDiameter
        p.x = centerX - newDiameter / 2
        p.y = centerY - newDiameter / 2
        runCatching { wm.updateViewLayout(hView, p) }
        circleCenterX = p.x + newDiameter / 2
        circleCenterY = p.y + newDiameter / 2
        drawView?.invalidate()
    }

    private fun buildPanel(service: Service, wm: WindowManager) {
        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            // Tighter padding so correction ↑/↓ buttons fit on narrow
            // screens (A32) without the right edge being clipped.
            setPadding(12, 16, 12, 16)
            setBackgroundColor(0xCC000000.toInt())
        }

        val header = TextView(service).apply { text = "tweaks (drag here)"; setTextColor(Color.WHITE) }
        panel.addView(header)

        val scroll = ScrollView(service)
        val settings = SettingsPanelBuilder.build(
            service,
            onChanged = { drawView?.invalidate() },
            onCalibrate = { toggleCalibrationMode() },
            onSemiAutoCalibrate = { toggleSemiAutoCalibrationMode() }
        )
        scroll.addView(settings)
        panel.addView(scroll)

        // Floating panel: almost phone-width (small side margins) so sliders
        // and ↑/↓ correction buttons are usable. Height stays modest.
        //
        // CRITICAL: ScrollView's child must use FrameLayout LayoutParams
        // (ScrollView extends FrameLayout). LinearLayout.LayoutParams on
        // the settings child caused ClassCastException on Start when
        // ScrollView measured its child.
        val panelWidth = (screenWidth * 0.94f).toInt().coerceAtLeast(320)
        scroll.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
        settings.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        )

        val pParams = WindowManager.LayoutParams(
            panelWidth,
            PANEL_HEIGHT_PX,
            OVERLAY_TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }
        panelParams = pParams

        var downRawX = 0f; var downRawY = 0f; var downX = 0; var downY = 0
        val panelThrottle = FrameThrottle(panel)
        header.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX; downRawY = event.rawY
                    downX = pParams.x; downY = pParams.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    pParams.x = downX + (event.rawX - downRawX).toInt()
                    pParams.y = downY + (event.rawY - downRawY).toInt()
                    panelThrottle.request {
                        runCatching { wm.updateViewLayout(panel, pParams) }
                    }
                    true
                }
                else -> false
            }
        }

        panelView = panel
        wm.addView(panel, pParams)
        applyPanelVisibility()
    }

    /** Hide now tears the handle windows down instead of just marking them
     * INVISIBLE — an invisible SYSTEM_ALERT_WINDOW is still a live surface
     * the compositor blends every frame, so the old approach cost the same
     * either way. Show rebuilds whatever should exist: the circle handle
     * only if this session ever had a live capture pipeline (captureAlive),
     * the manual handles only if the controller is enabled. */
    private fun applyHandleVisibility() {
        val wm = windowManager
        if (Tunables.aimVisible) {
            val svc = service
            if (svc != null && wm != null) {
                if (!captureless && captureAlive && handleView == null) {
                    attachCircleHandle(svc, wm)
                }
                if (Tunables.manualControllerEnabled && manualCueHandle == null) {
                    attachManualHandles()
                }
            }
        } else {
            handleView?.let { v -> wm?.let { runCatching { it.removeView(v) } } }
            handleView = null
            handleParams = null
            detachManualHandles()
        }
    }

    private fun applyPanelVisibility() {
        val wm = windowManager
        val svc = service
        if (Tunables.tweakPanelVisible) {
            if (svc != null && wm != null && panelView == null) {
                buildPanel(svc, wm)
            }
        } else {
            panelView?.let { v -> wm?.let { runCatching { it.removeView(v) } } }
            panelView = null
            panelParams = null
        }
    }

    // ---------------- Notification-driven toggles ----------------

    /** Safe to call whether or not the overlay is currently attached — a
     * no-op if CaptureService (and thus the draw view) isn't running yet.
     * Used by MainActivity's settings screen after a slider edit. */
    fun requestRedraw() {
        drawView?.invalidate()
    }

    fun isAimVisible(): Boolean = Tunables.aimVisible

    fun toggleAimVisible() {
        Tunables.aimVisible = !Tunables.aimVisible
        AutoAimPrefs.setAimVisible(Tunables.aimVisible)
        applyHandleVisibility()
        drawView?.invalidate()
    }

    fun isTweakPanelVisible(): Boolean = Tunables.tweakPanelVisible

    fun toggleTweakPanelVisible() {
        Tunables.tweakPanelVisible = !Tunables.tweakPanelVisible
        AutoAimPrefs.setTweakPanelVisible(Tunables.tweakPanelVisible)
        applyPanelVisibility()
    }

    // ---------------- Table calibration ----------------

    fun toggleCalibrationMode() {
        if (!calibrationMode && semiAutoCalibrationMode) {
            service?.let { Toast.makeText(it, "Finish or cancel Semi-Auto Calibrate first.", Toast.LENGTH_SHORT).show() }
            return
        }
        calibrationMode = !calibrationMode
        if (calibrationMode) {
            startCalibrationHandles()
        } else {
            saveCalibrationAndRemoveHandles()
        }
        drawView?.invalidate()
    }

    private fun startCalibrationHandles() {
        val svc = service ?: return
        val wm = windowManager ?: return

        edgeAX = if (Tunables.tableLeft >= 0f) Tunables.tableLeft else screenWidth * 0.12f
        edgeAY = if (Tunables.tableTop >= 0f) Tunables.tableTop else screenHeight * 0.12f
        edgeBX = if (Tunables.tableRight >= 0f) Tunables.tableRight else screenWidth * 0.88f
        edgeBY = if (Tunables.tableBottom >= 0f) Tunables.tableBottom else screenHeight * 0.88f

        val aHandle = EdgeHandle(svc, wm, screenWidth, screenHeight, edgeAX, edgeAY) { x, y ->
            edgeAX = x; edgeAY = y
            edgeADPad?.reposition()
            drawView?.invalidate()
        }
        val bHandle = EdgeHandle(svc, wm, screenWidth, screenHeight, edgeBX, edgeBY) { x, y ->
            edgeBX = x; edgeBY = y
            edgeBDPad?.reposition()
            drawView?.invalidate()
        }
        edgeAHandle = aHandle
        edgeBHandle = bHandle

        edgeADPad = EdgeDPad(svc, wm, screenWidth, screenHeight, aHandle) { drawView?.invalidate() }
        edgeBDPad = EdgeDPad(svc, wm, screenWidth, screenHeight, bHandle) { drawView?.invalidate() }
    }

    private fun saveCalibrationAndRemoveHandles() {
        val left = minOf(edgeAX, edgeBX)
        val right = maxOf(edgeAX, edgeBX)
        val top = minOf(edgeAY, edgeBY)
        val bottom = maxOf(edgeAY, edgeBY)

        AutoAimPrefs.saveTableBounds(left, top, right, bottom)
        Tunables.tableLeft = left
        Tunables.tableTop = top
        Tunables.tableRight = right
        Tunables.tableBottom = bottom

        // Capture this calibration's exact size as the semi-auto template.
        // A real table's rails don't change shape shot to shot, so this
        // width/height stays valid until the next full manual calibration
        // overwrites it (e.g. after a resolution or layout change).
        val width = right - left
        val height = bottom - top
        AutoAimPrefs.saveTableTemplate(width, height)
        Tunables.tableTemplateWidth = width
        Tunables.tableTemplateHeight = height

        edgeAHandle?.remove(); edgeAHandle = null
        edgeBHandle?.remove(); edgeBHandle = null
        edgeADPad?.remove(); edgeADPad = null
        edgeBDPad?.remove(); edgeBDPad = null
    }

    // ---------------- Semi-automatic table calibration ----------------
    // Re-uses the template captured above: instead of dragging two
    // corners, the user places one crosshair on the table's exact center
    // pixel (drag + the same 1px-per-tap D-Pad as manual mode), and the
    // four rail edges are reconstructed as center +/- template/2. One
    // point to place instead of two corners means less room for
    // cumulative human error, so it lands as pixel-perfect as the manual
    // pass it was templated from.

    fun toggleSemiAutoCalibrationMode() {
        if (!semiAutoCalibrationMode) {
            if (calibrationMode) {
                service?.let { Toast.makeText(it, "Finish or cancel Calibrate Table first.", Toast.LENGTH_SHORT).show() }
                return
            }
            if (!AutoAimPrefs.hasTableTemplate()) {
                service?.let {
                    Toast.makeText(
                        it,
                        "Run \"Calibrate Table\" (both corners) once first — Semi-Auto needs a known table size to work from.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return
            }
            semiAutoCalibrationMode = true
            startSemiAutoCalibrationHandles()
        } else {
            semiAutoCalibrationMode = false
            saveSemiAutoCalibrationAndRemoveHandles()
        }
        drawView?.invalidate()
    }

    private fun startSemiAutoCalibrationHandles() {
        val svc = service ?: return
        val wm = windowManager ?: return

        // Start the crosshair at the current calibration's center if one
        // exists, else the screen center.
        semiCenterX = if (Tunables.tableLeft >= 0f) (Tunables.tableLeft + Tunables.tableRight) / 2f else screenWidth / 2f
        semiCenterY = if (Tunables.tableTop >= 0f) (Tunables.tableTop + Tunables.tableBottom) / 2f else screenHeight / 2f

        val handle = EdgeHandle(svc, wm, screenWidth, screenHeight, semiCenterX, semiCenterY, crosshair = true) { x, y ->
            semiCenterX = x; semiCenterY = y
            semiCenterDPad?.reposition()
            drawView?.invalidate()
        }
        semiCenterHandle = handle
        semiCenterDPad = EdgeDPad(svc, wm, screenWidth, screenHeight, handle) { drawView?.invalidate() }
    }

    private fun saveSemiAutoCalibrationAndRemoveHandles() {
        val halfW = Tunables.tableTemplateWidth / 2f
        val halfH = Tunables.tableTemplateHeight / 2f
        val left = semiCenterX - halfW
        val right = semiCenterX + halfW
        val top = semiCenterY - halfH
        val bottom = semiCenterY + halfH

        AutoAimPrefs.saveTableBounds(left, top, right, bottom)
        Tunables.tableLeft = left
        Tunables.tableTop = top
        Tunables.tableRight = right
        Tunables.tableBottom = bottom
        // Deliberately NOT touching the template here — semi-auto only
        // re-centers a known size, it never redefines it.

        semiCenterHandle?.remove(); semiCenterHandle = null
        semiCenterDPad?.remove(); semiCenterDPad = null
    }

    // ---------------- Manual CUE / TARGET controller ----------------
    // Feature request #1: port of the Manual app's Handle. The line/bank
    // rendering itself lives in DrawOverlayView.drawManualController; this
    // section only owns the two draggable ball windows.

    /** Called by MainActivity's "Enable manual CUE / TARGET controller"
     * checkbox. Safe to call whether or not the service is currently
     * running — always persists the preference so it takes effect on the
     * next Start even if toggled while stopped; only touches live windows
     * when attached. */
    fun setManualControllerEnabled(enabled: Boolean) {
        Tunables.manualControllerEnabled = enabled
        AutoAimPrefs.setManualControllerEnabled(enabled)
        if (enabled) attachManualHandles() else detachManualHandles()
        drawView?.invalidate()
    }

    private fun attachManualHandles() {
        val svc = service ?: return
        val wm = windowManager ?: return
        if (manualCueHandle != null) return // already attached

        // Same screen-relative starting spot as the Manual app: cue low
        // and centered, target above it — never persisted across restarts.
        manualCueX = screenWidth * 0.5f
        manualCueY = screenHeight * 0.75f
        manualTargetX = screenWidth * 0.5f
        manualTargetY = screenHeight * 0.45f

        manualCueHandle = ManualHandle(
            svc, wm, screenWidth, screenHeight, ManualRole.CUE, manualCueX, manualCueY,
            onMoved = { x, y -> manualCueX = x; manualCueY = y; drawView?.invalidate() }
        )

        manualTargetHandle = ManualHandle(
            svc, wm, screenWidth, screenHeight, ManualRole.TARGET, manualTargetX, manualTargetY,
            onMoved = { x, y -> manualTargetX = x; manualTargetY = y; drawView?.invalidate() }
        )

        if (Tunables.manualKissEnabled) attachManualDestHandle()
        if (Tunables.manualComboEnabled) attachManualComboHandle()

        applyHandleVisibility()
        drawView?.invalidate()
    }

    private fun detachManualHandles() {
        manualCueHandle?.remove(); manualCueHandle = null
        manualTargetHandle?.remove(); manualTargetHandle = null
        detachManualDestHandle()
        detachManualComboHandle()
        drawView?.invalidate()
    }

    /** Called by the "Enable Kiss Shot" checkbox. Same safe-to-call-anytime
     * contract as [setManualControllerEnabled]. Reuses CUE (the striking
     * ball's approach point) and TARGET (the ball being hit) — DEST is the
     * only extra point. Combo Shot is entirely separate — see
     * [setManualComboEnabled]. */
    fun setManualKissEnabled(enabled: Boolean) {
        Tunables.manualKissEnabled = enabled
        AutoAimPrefs.setManualKissEnabled(enabled)
        if (enabled) attachManualDestHandle() else detachManualDestHandle()
        drawView?.invalidate()
    }

    /** Sets whether DEST is currently showing Kiss Shot (green) or parked
     * off (red). Toggled by tapping (not dragging) the DEST marker — see
     * ManualHandle's onTapped, wired up in [attachManualDestHandle] via
     * [toggleManualKissActive]. Unlike [setManualKissEnabled], this never
     * touches the handle itself: DEST stays right where it is, it just
     * switches whether the kiss trajectory is showing. */
    fun setManualKissActive(active: Boolean) {
        Tunables.manualKissActive = active
        AutoAimPrefs.setManualKissActive(active)
        manualDestHandle?.refreshVisual()
        drawView?.invalidate()
    }

    /** DEST's tap-to-toggle: red (off) <-> green (kiss shot). */
    fun toggleManualKissActive() {
        setManualKissActive(!Tunables.manualKissActive)
    }

    private fun attachManualDestHandle() {
        val svc = service ?: return
        val wm = windowManager ?: return
        if (!Tunables.manualControllerEnabled) return // needs CUE/TARGET as the base shot
        if (manualDestHandle != null) return // already attached

        manualDestX = screenWidth * 0.15f
        manualDestY = screenHeight * 0.15f

        manualDestHandle = ManualHandle(
            svc, wm, screenWidth, screenHeight, ManualRole.DEST, manualDestX, manualDestY,
            onMoved = { x, y -> manualDestX = x; manualDestY = y; drawView?.invalidate() },
            onTapped = { toggleManualKissActive() }
        )

        applyHandleVisibility()
        drawView?.invalidate()
    }

    private fun detachManualDestHandle() {
        manualDestHandle?.remove(); manualDestHandle = null
        drawView?.invalidate()
    }

    /** Called by the "Enable Combo Shot" checkbox. Fully independent of
     * Kiss Shot — attaches its own yellow-octagon destination handle
     * (COMBO_DEST), full CUE/TARGET size rather than DEST's small dot.
     * Once enabled, Combo always renders as an overlay regardless of
     * Kiss Shot's on/off state — see drawManualController. */
    fun setManualComboEnabled(enabled: Boolean) {
        Tunables.manualComboEnabled = enabled
        AutoAimPrefs.setManualComboEnabled(enabled)
        if (enabled) attachManualComboHandle() else detachManualComboHandle()
        drawView?.invalidate()
    }

    private fun attachManualComboHandle() {
        val svc = service ?: return
        val wm = windowManager ?: return
        if (!Tunables.manualControllerEnabled) return // needs TARGET as the base ball
        if (manualComboHandle != null) return // already attached

        manualComboX = screenWidth * 0.85f
        manualComboY = screenHeight * 0.15f

        manualComboHandle = ManualHandle(
            svc, wm, screenWidth, screenHeight, ManualRole.COMBO_DEST, manualComboX, manualComboY,
            onMoved = { x, y -> manualComboX = x; manualComboY = y; drawView?.invalidate() }
        )

        applyHandleVisibility()
        drawView?.invalidate()
    }

    private fun detachManualComboHandle() {
        manualComboHandle?.remove(); manualComboHandle = null
        drawView?.invalidate()
    }

    /** Live-resizes both manual balls' visual diameter, keeping each
     * handle's on-screen center fixed. Called when the shared "Ghost ball
     * size" slider moves (it's the same physical ball as the rail-bounce
     * marker — see bug #3), mirroring the Manual app's applyBallSize(). */
    fun onGhostBallDiameterChanged(newDiameterPx: Float) {
        manualCueHandle?.setVisualDiameter(newDiameterPx)
        manualTargetHandle?.setVisualDiameter(newDiameterPx)
        drawView?.invalidate()
    }

    // ---------------- Detection result handling ----------------

    /**
     * Anti-blink lock that still switches quickly when you drag onto a
     * different line.
     * - Same / similar angle -> keep updating the lock (smooth)
     * - Big angle change (> ~20 deg) -> switch immediately, short hold
     * - Temporary loss of line -> keep previous lock for a few frames only
     */
    fun updateResult(result: DetectionResult) {
        try {
            updateResultInner(result)
        } catch (t: Throwable) {
            android.util.Log.e("OverlayController", "updateResult failed", t)
            lastResult = result
            drawView?.postInvalidate()
        }
    }

    private fun updateResultInner(result: DetectionResult) {
        val prev = lockedResult

        if (!result.hasLine) {
            if (lockHoldFrames > 0) {
                lockHoldFrames--
            } else {
                lockedResult = null
                smoothInit = false
            }
        } else {
            val angleChangedALot = prev != null && prev.hasLine &&
                    angleDiff(prev.angleRad, result.angleRad) > 0.35   // ~20 degrees

            val accept = prev == null || !prev.hasLine ||
                    angleDiff(prev.angleRad, result.angleRad) < ANGLE_TOL ||
                    angleChangedALot ||
                    result.score > prev.score * 1.15f

            if (accept) {
                lockedResult = result
                lockHoldFrames = if (angleChangedALot) 3 else LOCK_HOLD
                if (angleChangedALot) smoothInit = false  // snap on big direction change
            }
        }

        val base = lockedResult ?: result
        if (base.hasLine) {
            val nowMs = android.os.SystemClock.elapsedRealtime()
            if (!smoothInit) {
                rawAngleUnwrapped = base.angleRad
                lastRawAngle = base.angleRad
                smoothAngle = base.angleRad
                smoothOffX = base.offsetX
                smoothOffY = base.offsetY
                angleFilter.reset(base.angleRad)
                offXFilter.reset(base.offsetX.toDouble())
                offYFilter.reset(base.offsetY.toDouble())
                smoothInit = true
            } else if (!Tunables.smoothingEnabled) {
                // Bypass: draw the raw per-frame detection directly, zero
                // added lag. Filters are simply left unfed while this is
                // on — re-enabling smoothing mid-session re-syncs from the
                // current raw value within a frame or two rather than
                // jumping from stale internal state.
                smoothAngle = base.angleRad
                smoothOffX = base.offsetX
                smoothOffY = base.offsetY
                lastRawAngle = base.angleRad
                rawAngleUnwrapped = base.angleRad
            } else {
                // Track the raw angle as a continuously-unwrapped scalar
                // (shortest-path delta from the last raw sample, not the
                // filtered one) so the One Euro filter below is a plain
                // linear filter — no period-π special-casing inside it.
                var dRaw = base.angleRad - lastRawAngle
                while (dRaw > Math.PI / 2) dRaw -= Math.PI
                while (dRaw < -Math.PI / 2) dRaw += Math.PI
                rawAngleUnwrapped += dRaw
                lastRawAngle = base.angleRad

                var filtered = angleFilter.filter(rawAngleUnwrapped, nowMs)
                while (filtered > Math.PI / 2) filtered -= Math.PI
                while (filtered <= -Math.PI / 2) filtered += Math.PI
                smoothAngle = filtered
                smoothOffX = offXFilter.filter(base.offsetX.toDouble(), nowMs).toFloat()
                smoothOffY = offYFilter.filter(base.offsetY.toDouble(), nowMs).toFloat()
            }
            lastResult = base.copy(
                angleRad = smoothAngle,
                offsetX = smoothOffX,
                offsetY = smoothOffY
            )
        } else {
            lastResult = base
        }
        drawView?.invalidate()
    }

    private fun angleDiff(a: Double, b: Double): Double {
        var d = abs(a - b) % Math.PI
        if (d > Math.PI / 2) d = Math.PI - d
        return d
    }

    fun detach() {
        val wm = windowManager ?: return
        drawView?.let { runCatching { wm.removeView(it) } }
        handleView?.let { runCatching { wm.removeView(it) } }
        panelView?.let { runCatching { wm.removeView(it) } }
        edgeAHandle?.remove(); edgeAHandle = null
        edgeBHandle?.remove(); edgeBHandle = null
        edgeADPad?.remove(); edgeADPad = null
        edgeBDPad?.remove(); edgeBDPad = null
        semiCenterHandle?.remove(); semiCenterHandle = null
        semiCenterDPad?.remove(); semiCenterDPad = null
        manualCueHandle?.remove(); manualCueHandle = null
        manualTargetHandle?.remove(); manualTargetHandle = null
        manualDestHandle?.remove(); manualDestHandle = null
        manualComboHandle?.remove(); manualComboHandle = null
        drawView = null; handleView = null; panelView = null; windowManager = null
        service = null
        calibrationMode = false
        semiAutoCalibrationMode = false
        lockedResult = null
        lockHoldFrames = 0
        captureAlive = false
    }
}

/**
 * A small draggable calibration box, ported from the Manual app's Handle.
 * Clamped to the same 10%-past-the-edge limit as everything else.
 */
private class EdgeHandle(
    context: Context,
    private val wm: WindowManager,
    private val screenWidth: Int,
    private val screenHeight: Int,
    initX: Float,
    initY: Float,
    crosshair: Boolean = false,
    private val onMoved: (Float, Float) -> Unit
) {
    private val view = EdgeHandleView(context, crosshair)
    private val params = WindowManager.LayoutParams(
        EDGE_HANDLE_HITBOX_PX,
        EDGE_HANDLE_HITBOX_PX,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    )
    private val throttle = FrameThrottle(view)

    init {
        params.gravity = Gravity.TOP or Gravity.START
        params.x = (initX - EDGE_HANDLE_HITBOX_PX / 2f).toInt()
        params.y = (initY - EDGE_HANDLE_HITBOX_PX / 2f).toInt()

        var downRawX = 0f; var downRawY = 0f; var downX = 0; var downY = 0
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX; downRawY = event.rawY
                    downX = params.x; downY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newX = downX + (event.rawX - downRawX)
                    val newY = downY + (event.rawY - downRawY)
                    clampAndApply(newX, newY)
                    true
                }
                else -> false
            }
        }
        wm.addView(view, params)
    }

    private fun clampAndApply(x: Float, y: Float) {
        var newX = x
        var newY = y
        val size = EDGE_HANDLE_HITBOX_PX
        val margin = size * EDGE_LIMIT_FRACTION
        val minX = -margin
        val maxX = screenWidth - size + margin
        val minY = -margin
        val maxY = screenHeight - size + margin
        if (newX < minX) newX = minX else if (newX > maxX) newX = maxX
        if (newY < minY) newY = minY else if (newY > maxY) newY = maxY

        params.x = newX.toInt()
        params.y = newY.toInt()
        throttle.request {
            runCatching { wm.updateViewLayout(view, params) }
            onMoved(params.x + size / 2f, params.y + size / 2f)
        }
    }

    fun getCenterX(): Float = params.x + EDGE_HANDLE_HITBOX_PX / 2f
    fun getCenterY(): Float = params.y + EDGE_HANDLE_HITBOX_PX / 2f
    fun getSize(): Int = EDGE_HANDLE_HITBOX_PX

    /** Nudges the box by a small pixel delta - used by the attached D-Pad
     * for pixel-perfect fine alignment. Reuses the same edge clamp. */
    fun moveBy(dx: Float, dy: Float) {
        clampAndApply(params.x + dx, params.y + dy)
    }

    fun remove() {
        runCatching { wm.removeView(view) }
    }
}

private class EdgeHandleView(context: Context, private val crosshair: Boolean = false) : View(context) {
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 3f
    }
    private val crosshairLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 3f
    }
    private val crosshairCore = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.YELLOW
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val half = EDGE_HANDLE_VISUAL_PX / 2f
        if (crosshair) {
            // A crosshair reads as "find this exact point" much more
            // clearly than a square outline — used for the semi-auto
            // center marker, where the whole job is placing one pixel
            // precisely rather than framing a corner.
            canvas.drawLine(cx - half, cy, cx + half, cy, crosshairLine)
            canvas.drawLine(cx, cy - half, cx, cy + half, crosshairLine)
            canvas.drawCircle(cx, cy, half * 0.22f, crosshairCore)
            canvas.drawCircle(cx, cy, half * 0.22f, outline)
        } else {
            canvas.drawRect(cx - half, cy - half, cx + half, cy + half, outline)
        }
    }
}

/**
 * A small on-screen D-Pad tied to one calibration box (EdgeHandle). Tapping
 * an arrow nudges the box by NUDGE_PX in that direction — much finer than
 * dragging, for the last-mile pixel-perfect alignment pass. Ported from the
 * Manual app.
 */
private class EdgeDPad(
    context: Context,
    private val wm: WindowManager,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val target: EdgeHandle,
    private val onNudged: () -> Unit
) {
    private val view = DPadView(context)
    private val params = WindowManager.LayoutParams(
        DPAD_SIZE_PX,
        DPAD_SIZE_PX,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    )

    init {
        params.gravity = Gravity.TOP or Gravity.START

        view.setOnTouchListener { _, event ->
            if (event.actionMasked != MotionEvent.ACTION_DOWN) return@setOnTouchListener false
            val cx = DPAD_SIZE_PX / 2f
            val cy = DPAD_SIZE_PX / 2f
            val ddx = event.x - cx
            val ddy = event.y - cy

            val deadZoneR = DPAD_SIZE_PX * 0.18f
            if (hypot(ddx, ddy) < deadZoneR) return@setOnTouchListener true

            var nudgeX = 0f
            var nudgeY = 0f
            if (abs(ddx) > abs(ddy)) {
                nudgeX = if (ddx > 0f) DPAD_NUDGE_PX else -DPAD_NUDGE_PX
            } else {
                nudgeY = if (ddy > 0f) DPAD_NUDGE_PX else -DPAD_NUDGE_PX
            }

            target.moveBy(nudgeX, nudgeY)
            reposition()
            onNudged()
            true
        }

        reposition()
        wm.addView(view, params)
    }

    /** Keeps the pad glued just outside its box — to the right by default,
     * flipping left if that would run off the screen edge, clamped
     * vertically so it always stays visible. */
    fun reposition() {
        val boxCenterX = target.getCenterX()
        val boxCenterY = target.getCenterY()
        val boxHalf = target.getSize() / 2f

        var x = boxCenterX + boxHalf + DPAD_GAP_PX
        if (x + DPAD_SIZE_PX > screenWidth) {
            x = boxCenterX - boxHalf - DPAD_GAP_PX - DPAD_SIZE_PX
        }
        if (x < 0f) x = 0f

        var y = boxCenterY - DPAD_SIZE_PX / 2f
        if (y < 0f) y = 0f
        else if (y + DPAD_SIZE_PX > screenHeight) y = screenHeight - DPAD_SIZE_PX.toFloat()

        params.x = x.toInt()
        params.y = y.toInt()
        if (view.windowToken != null) {
            runCatching { wm.updateViewLayout(view, params) }
        }
    }

    fun remove() {
        runCatching { wm.removeView(view) }
    }
}

private class DPadView(context: Context) : View(context) {
    private val pad = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.DKGRAY
        alpha = 170
    }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) / 2f - 4f

        canvas.drawCircle(cx, cy, r, pad)

        val armStart = r * 0.32f
        val armTip = r * 0.82f
        val halfWidth = r * 0.22f

        drawArrow(canvas, cx, cy - armStart, cx, cy - armTip, halfWidth)
        drawArrow(canvas, cx, cy + armStart, cx, cy + armTip, halfWidth)
        drawArrow(canvas, cx - armStart, cy, cx - armTip, cy, halfWidth)
        drawArrow(canvas, cx + armStart, cy, cx + armTip, cy, halfWidth)
    }

    private fun drawArrow(canvas: Canvas, baseX: Float, baseY: Float, tipX: Float, tipY: Float, halfWidth: Float) {
        val dx = tipX - baseX
        val dy = tipY - baseY
        val len = hypot(dx, dy)
        if (len < 1f) return
        val ux = dx / len
        val uy = dy / len
        val px = -uy * halfWidth
        val py = ux * halfWidth

        val path = android.graphics.Path()
        path.moveTo(baseX + px, baseY + py)
        path.lineTo(baseX - px, baseY - py)
        path.lineTo(tipX, tipY)
        path.close()
        canvas.drawPath(path, arrow)
    }
}

/** TARGET (the bank-shot end) draws as a red-tinted circle handle; CUE
 * draws as a black-tinted square handle. Both also draw a ball-diameter
 * ghost-ball outline + red center dot — see ManualHandleView. DEST (kiss
 * shot) is its own thing: just a small dot, no ball involved. COMBO_DEST
 * (combo shot's destination) draws as a yellow octagon, full CUE/TARGET
 * size — its own handle, independent of DEST. */
private enum class ManualRole { CUE, TARGET, DEST, COMBO_DEST }

/**
 * A draggable CUE or TARGET ball handle for the manual controller — ported
 * from the Manual app's Handle. Unlike the Ray Circle and calibration
 * handles, movement is scaled by Tunables.manualSensitivity (a concept
 * that only makes sense for something you drag by hand — the automatic
 * controller has nothing analogous), and it can be resized live via
 * setVisualDiameter() when the shared Ghost ball size slider moves.
 */
private class ManualHandle(
    context: Context,
    private val wm: WindowManager,
    private val screenWidth: Int,
    private val screenHeight: Int,
    role: ManualRole,
    initX: Float,
    initY: Float,
    private val onMoved: (Float, Float) -> Unit,
    private val onTapped: (() -> Unit)? = null
) {
    private val view = ManualHandleView(context, role, Tunables.ghostBallDiameterPx)
    // DEST is a small pocket-aim dot, not a ball — it doesn't need
    // CUE/TARGET's big grab area. See DEST_HANDLE_HITBOX_PX.
    private val hitboxPx: Int = if (role == ManualRole.DEST) DEST_HANDLE_HITBOX_PX else MANUAL_HANDLE_HITBOX_PX
    private val params = WindowManager.LayoutParams(
        hitboxPx,
        hitboxPx,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    )

    init {
        params.gravity = Gravity.TOP or Gravity.START
        params.x = (initX - hitboxPx / 2f).toInt()
        params.y = (initY - hitboxPx / 2f).toInt()

        var lastRawX = 0f; var lastRawY = 0f
        var exactX = 0f; var exactY = 0f
        // Tap-vs-drag tracking for onTapped (DEST only — see TAP_SLOP_PX).
        // Total RAW finger travel this gesture, unaffected by sensitivity
        // or by sticky-rail resistance eating some of the drag distance.
        var rawTravel = 0f
        // Sticky-rail state (TARGET only — see TARGET_EDGE_STICKY_PX).
        // stuckEdge*: -1 = pinned at the min-edge, 0 = free, 1 = pinned at
        // the max-edge. pullDebt*: cumulative RAW (unscaled by sensitivity)
        // drag distance in the release direction since pinned; uncapped.
        // Position stays glued to the rail while pullDebt < stickyPx, then
        // tracks continuously as pullDebt overflows past it — no freeze-
        // then-teleport, so there's nothing to feel like a snap or wobble.
        // Pushing back INTO the rail drains it back toward 0 instead of
        // going negative, so jitter right at the wall can't bank progress.
        var stuckEdgeX = 0; var stuckEdgeY = 0
        var pullDebtX = 0f; var pullDebtY = 0f
        // Same 30fps cap as attachCircleHandle/EdgeHandle — CUE/TARGET/DEST
        // get dragged constantly during a session, so this is the single
        // biggest source of redundant WM transactions + full-screen redraws.
        val throttle = FrameThrottle(view)
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastRawX = event.rawX; lastRawY = event.rawY
                    exactX = params.x.toFloat(); exactY = params.y.toFloat()
                    rawTravel = 0f
                    pullDebtX = 0f; pullDebtY = 0f
                    // Re-derive "is it currently resting on an edge" from
                    // where the handle actually is, so a fresh touch on an
                    // already-pinned TARGET is sticky right away instead of
                    // only mid-gesture.
                    stuckEdgeX = 0; stuckEdgeY = 0
                    if (role == ManualRole.TARGET && Tunables.tableLeft >= 0f) {
                        val halfHit = hitboxPx / 2f
                        val halfBall = Tunables.ghostBallDiameterPx / 2f
                        val minCX = Tunables.tableLeft + halfBall
                        val maxCX = Tunables.tableRight - halfBall
                        val minCY = Tunables.tableTop + halfBall
                        val maxCY = Tunables.tableBottom - halfBall
                        val cx = exactX + halfHit
                        val cy = exactY + halfHit
                        if (maxCX > minCX) {
                            stuckEdgeX = if (cx <= minCX + 0.5f) -1 else if (cx >= maxCX - 0.5f) 1 else 0
                        }
                        if (maxCY > minCY) {
                            stuckEdgeY = if (cy <= minCY + 0.5f) -1 else if (cy >= maxCY - 0.5f) 1 else 0
                        }
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val rawX = event.rawX
                    val rawY = event.rawY
                    // True unscaled finger deltas — used for pullDebt so the
                    // sticky threshold means the same finger-distance no
                    // matter the sensitivity setting (same rule TAP_SLOP_PX
                    // already follows). dxRaw/dyRaw stay sensitivity-scaled
                    // since that's the unit exactX/exactY move in.
                    val rawDx = rawX - lastRawX
                    val rawDy = rawY - lastRawY
                    val dxRaw = rawDx * Tunables.manualSensitivity
                    val dyRaw = rawDy * Tunables.manualSensitivity
                    rawTravel += kotlin.math.hypot(rawDx.toDouble(), rawDy.toDouble()).toFloat()
                    val halfHit = hitboxPx / 2f

                    // When the table is calibrated, TARGET (and DEST /
                    // COMBO_DEST) centres stay limited to the INSET table
                    // rect (table edge ± half ghost-ball) so the ghost
                    // ball's outer edge hugs the yellow calibration line.
                    // CUE is exempt: it may float anywhere on screen with
                    // only a 1% hitbox margin so it can't fully slide off.
                    if (Tunables.tableLeft >= 0f && role != ManualRole.CUE) {
                        val halfBall = Tunables.ghostBallDiameterPx / 2f
                        val minCX = Tunables.tableLeft + halfBall
                        val maxCX = Tunables.tableRight - halfBall
                        val minCY = Tunables.tableTop + halfBall
                        val maxCY = Tunables.tableBottom - halfBall
                        val sticky = role == ManualRole.TARGET

                        if (maxCX > minCX && sticky && stuckEdgeX != 0) {
                            val outward = if (stuckEdgeX == -1) rawDx else -rawDx
                            pullDebtX = (pullDebtX + outward).coerceAtLeast(0f)
                            val extra = (pullDebtX - TARGET_EDGE_STICKY_PX).coerceAtLeast(0f) * Tunables.manualSensitivity
                            exactX = (if (stuckEdgeX == -1) minCX + extra else maxCX - extra) - halfHit
                            if (extra > 0f) stuckEdgeX = 0
                        } else {
                            exactX += dxRaw
                        }

                        if (maxCY > minCY && sticky && stuckEdgeY != 0) {
                            val outward = if (stuckEdgeY == -1) rawDy else -rawDy
                            pullDebtY = (pullDebtY + outward).coerceAtLeast(0f)
                            val extra = (pullDebtY - TARGET_EDGE_STICKY_PX).coerceAtLeast(0f) * Tunables.manualSensitivity
                            exactY = (if (stuckEdgeY == -1) minCY + extra else maxCY - extra) - halfHit
                            if (extra > 0f) stuckEdgeY = 0
                        } else {
                            exactY += dyRaw
                        }

                        var cx = exactX + halfHit
                        var cy = exactY + halfHit
                        // Guard against inverted rect if ball > table. Also
                        // where stickiness re-arms: crossing back onto an
                        // edge in free mode pins it fresh. Un-pinning is
                        // handled above (extra > 0), not here — cx sits
                        // exactly ON the boundary while pinned-with-zero-
                        // extra, so an "else un-pin" here would release it
                        // immediately every frame.
                        if (maxCX > minCX) {
                            if (cx < minCX) { cx = minCX; if (sticky) { stuckEdgeX = -1; pullDebtX = 0f } }
                            else if (cx > maxCX) { cx = maxCX; if (sticky) { stuckEdgeX = 1; pullDebtX = 0f } }
                        }
                        if (maxCY > minCY) {
                            if (cy < minCY) { cy = minCY; if (sticky) { stuckEdgeY = -1; pullDebtY = 0f } }
                            else if (cy > maxCY) { cy = maxCY; if (sticky) { stuckEdgeY = 1; pullDebtY = 0f } }
                        }
                        exactX = cx - halfHit
                        exactY = cy - halfHit
                    } else {
                        exactX += dxRaw
                        exactY += dyRaw
                        // CUE always uses a tight 1% margin; other roles
                        // only reach this branch when the table is not
                        // calibrated and keep the original 10% margin.
                        val edgeFrac = if (role == ManualRole.CUE) 0.01f else EDGE_LIMIT_FRACTION
                        val margin = hitboxPx * edgeFrac
                        val minX = -margin
                        val maxX = screenWidth - hitboxPx + margin
                        val minY = -margin
                        val maxY = screenHeight - hitboxPx + margin
                        if (exactX < minX) exactX = minX else if (exactX > maxX) exactX = maxX
                        if (exactY < minY) exactY = minY else if (exactY > maxY) exactY = maxY
                    }

                    params.x = Math.round(exactX)
                    params.y = Math.round(exactY)
                    lastRawX = rawX; lastRawY = rawY

                    throttle.request {
                        runCatching { wm.updateViewLayout(view, params) }
                        onMoved(params.x + hitboxPx / 2f, params.y + hitboxPx / 2f)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (onTapped != null && rawTravel < TAP_SLOP_PX) onTapped.invoke()
                    true
                }
                else -> false
            }
        }
        wm.addView(view, params)
    }

    fun setVisualDiameter(diameterPx: Float) {
        view.setVisualDiameterPx(diameterPx)
    }

    /** Forces the handle's own view to redraw — e.g. after a tap-toggled
     * state change that onDraw reads directly from Tunables. */
    fun refreshVisual() {
        view.invalidate()
    }

    fun remove() {
        runCatching { wm.removeView(view) }
    }
}

/**
 * Visual for a manual handle — a big translucent hitbox (red circle for
 * TARGET, black square for CUE, yellow octagon for COMBO_DEST) plus a
 * ball-diameter ghost-ball outline with a red center dot for CUE/TARGET.
 * DEST is its own thing: a small dot (no ball involved, it's a pocket aim
 * point) that toggles red (off) <-> green (kiss shot) — tap it to toggle
 * (see ManualHandle's onTapped and OverlayController.toggleManualKissActive).
 * It also gets a smaller touch hitbox than the others — see
 * DEST_HANDLE_HITBOX_PX.
 */
private class ManualHandleView(
    context: Context,
    private val role: ManualRole,
    private var visualDiameterPx: Float
) : View(context) {
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 3f
    }
    private val controllerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        if (role == ManualRole.TARGET) {
            color = Color.RED
            alpha = 40
        } else {
            color = Color.BLACK
            alpha = 45
        }
    }
    private val centerDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.RED
    }
    // DEST's own dot color reflects whether Kiss Shot is toggled on — see
    // Tunables.manualKissActive and ManualHandle's onTapped. Separate
    // Paints from centerDot above so CUE/TARGET's ghost-ball dot (always
    // red) is never affected.
    private val destKissDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.GREEN
    }
    private val destOffDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.RED
    }
    // COMBO_DEST's controller shape — same opacity as TARGET's fill above.
    private val comboFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.YELLOW
        alpha = 40
    }

    fun setVisualDiameterPx(diameterPx: Float) {
        visualDiameterPx = diameterPx
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val ch = minOf(width, height) / 2f - 8f

        if (role == ManualRole.DEST) {
            // Just a pocket-aim dot — no ball, no ghost ring. Color shows
            // whether Kiss Shot is toggled on (tap to toggle).
            val dot = if (Tunables.manualKissActive) destKissDot else destOffDot
            canvas.drawCircle(cx, cy, 16f, dot)
            canvas.drawCircle(cx, cy, 16f, outline)
            return
        }

        if (role == ManualRole.COMBO_DEST) {
            // Combo's own destination handle — a plain filled octagon, no
            // ghost ring (it's a destination point, not a ball). Note: this
            // is the CONTROLLER's shape only; Combo Shot's ghost-ball
            // visual (drawn at the solved point in drawManualController)
            // is unchanged.
            drawOctagon(canvas, cx, cy, ch, comboFill)
            return
        }

        if (role == ManualRole.TARGET) {
            canvas.drawCircle(cx, cy, ch, controllerFill)
        } else {
            canvas.drawRect(cx - ch, cy - ch, cx + ch, cy + ch, controllerFill)
        }

        // Ghost ball always drawn for CUE, TARGET, and KISS.
        val r = visualDiameterPx / 2f - outline.strokeWidth / 2f
        if (r > 1f) {
            canvas.drawCircle(cx, cy, r, outline)
            canvas.drawCircle(cx, cy, 4f, centerDot)
        }
    }

    /** Regular 8-point octagon inscribed in a circle of [radius] centered
     * at ([cx],[cy]) — flat-topped (first vertex at 22.5°). */
    private fun drawOctagon(canvas: Canvas, cx: Float, cy: Float, radius: Float, paint: Paint) {
        val path = Path()
        for (i in 0 until 8) {
            val a = Math.toRadians(22.5 + i * 45.0)
            val px = cx + radius * cos(a).toFloat()
            val py = cy + radius * sin(a).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        canvas.drawPath(path, paint)
    }
}

/**
 * Renders the Ray Circle, the detected-guideline segments (with bank-shot
 * reflection off the table edges once calibrated), the Ray Monitor debug
 * preview, and the manual CUE/TARGET controller's own line.
 */
class DrawOverlayView(context: Context) : View(context) {
    // Ray Zone controller ring — a square, same shape/side length as the
    // crop extractCrop() actually grabs (Tunables.circleDiameter).
    private val circlePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
        isAntiAlias = true
    }
    // Ray Monitor preview: the debug thumbnail is a square buffer already
    // (LineDetector's preview is size x size), and the Ray Zone it mirrors
    // is a square too now, so it's just blitted straight in — no clip
    // shape/shader needed.
    private val monitorSquarePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }
    private val previewRectF = RectF()
    private val monitorClipPath = Path()
    private var previewBitmap: Bitmap? = null
    private var previewSide: Int = -1
    private val clipPieceA = FloatArray(4)
    private val clipPieceB = FloatArray(4)
    private val textPaint = Paint().apply {
        color = Color.GREEN
        textSize = 32f
        isAntiAlias = true
    }
    private val bgPaint = Paint().apply {
        color = 0xAA000000.toInt()
        style = Paint.Style.FILL
    }
    private val calRectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.YELLOW
        strokeWidth = 3f
    }
    // Semi-auto mode never has literal placed corners — this rect is a
    // reconstructed *preview* (center +/- template/2), so it's drawn
    // dashed to read as "computed" rather than "placed", same color
    // family as manual mode for a consistent calibration look.
    private val calRectPreviewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.YELLOW
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }

    // First-segment paints (border + colored center), and dimmer variants
    // for every segment after a bank reflection — same palette as the
    // Manual app so a bank always reads visually "one shade back."
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; strokeWidth = 7f; strokeCap = Paint.Cap.ROUND
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val bankBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; strokeWidth = 7f; strokeCap = Paint.Cap.ROUND
    }
    private val bankCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val doublePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = 2.5f; strokeCap = Paint.Cap.ROUND
    }
    private val bankDoublePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY; strokeWidth = 2.5f; strokeCap = Paint.Cap.ROUND
    }
    private val markerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.BLACK; strokeWidth = 3f
    }
    private val markerDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.RED
    }

    // Manual controller paints — independent instances so manual-only
    // tweaks (color/width/opacity/dashed/double/ghost) never touch the
    // automatic line's paints above, per feature request #1's "must not
    // affect automatic aim" rule.
    private val manualBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; strokeWidth = 7f; strokeCap = Paint.Cap.ROUND
    }
    private val manualCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val manualBankBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; strokeWidth = 7f; strokeCap = Paint.Cap.ROUND
    }
    private val manualBankCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val manualDoublePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = 2.5f; strokeCap = Paint.Cap.ROUND
    }
    private val manualBankDoublePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY; strokeWidth = 2.5f; strokeCap = Paint.Cap.ROUND
    }
    // Band style: one wide translucent stroke centered on the aim line
    // (width set per-segment from the ball-diameter math below) instead
    // of two thin flanking lines with a gap. Separate paints from the
    // classic double-line ones above since their strokeWidth is set once
    // per frame while band width is recomputed at each draw site.
    private val manualBandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeCap = Paint.Cap.ROUND
    }
    private val manualBankBandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY; strokeCap = Paint.Cap.ROUND
    }
    private val manualMarkerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.BLACK; strokeWidth = 3f
    }
    private val manualMarkerDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.RED
    }

    // Kiss-shot / combo-shot assist: a tiny contact dot on TARGET's edge
    // (kept small on purpose — a few pixels is plenty for aiming accuracy
    // and a big marker just gets in the way), colored green for Kiss and
    // orange for Combo. The guide lines (CUE→ghost→DEST for kiss, or
    // TARGET→[bend→]COMBO_DEST for combo) don't have their own paint —
    // they're drawn with manualBorderPaint/manualCenterPaint (and
    // manualBankBorderPaint/manualBankCenterPaint for combo's post-bank
    // segment) below, same color/width/opacity/dashing as the CUE/TARGET
    // line, just without the double-line flanks.
    private val kissContactDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.GREEN
    }
    private val comboContactDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.rgb(255, 165, 0) // orange
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (OverlayController.calibrationMode) {
            canvas.drawRect(
                minOf(OverlayController.edgeAX, OverlayController.edgeBX),
                minOf(OverlayController.edgeAY, OverlayController.edgeBY),
                maxOf(OverlayController.edgeAX, OverlayController.edgeBX),
                maxOf(OverlayController.edgeAY, OverlayController.edgeBY),
                calRectPaint
            )
        }

        if (OverlayController.semiAutoCalibrationMode) {
            val halfW = Tunables.tableTemplateWidth / 2f
            val halfH = Tunables.tableTemplateHeight / 2f
            canvas.drawRect(
                OverlayController.semiCenterX - halfW,
                OverlayController.semiCenterY - halfH,
                OverlayController.semiCenterX + halfW,
                OverlayController.semiCenterY + halfH,
                calRectPreviewPaint
            )
        }

        // Bug #1: Hide must conceal every related piece together — the aim
        // overlay, the Ray Circle controller ring, the Ray Monitor preview,
        // its status text, and (new) the manual controller's line. One
        // early return here covers all of it instead of each having its
        // own (previously inconsistent) check. The draggable handle
        // windows themselves are hidden separately in
        // OverlayController.applyHandleVisibility, since they're different
        // windows and aren't part of this canvas.
        if (!Tunables.aimVisible) return

        val cx = OverlayController.circleCenterX.toFloat()
        val cy = OverlayController.circleCenterY.toFloat()
        val half = Tunables.circleDiameter / 2f

        // Manual Only mode: no capture pipeline exists, so the Ray Circle
        // ring and Ray Monitor preview have nothing to show — never draw
        // them (the drag-handle itself is already never created, see
        // OverlayController.attach). Falls through to drawManualController
        // below either way — that's independent of capture entirely.
        val result = OverlayController.lastResult
        if (!OverlayController.captureless && OverlayController.captureAlive) {
            circlePaint.alpha = Tunables.circleAlpha
            if (Tunables.rayZoneCircleMode) {
                canvas.drawCircle(cx, cy, half, circlePaint)
            } else {
                canvas.drawRect(cx - half, cy - half, cx + half, cy + half, circlePaint)
            }

            if (Tunables.rayMonitorEnabled) {
            if (result != null && result.previewArgb.isNotEmpty()) {
                // Preview is captured at capture-scale resolution (can be
                // smaller than the on-screen Ray Zone). Displayed here at
                // the SAME on-screen side length as the Ray Zone controller
                // (Tunables.circleDiameter). Both are squares now, so the
                // buffer can be blitted straight in — no clip shape needed.
                val n = result.previewArgb.size
                val side = kotlin.math.sqrt(n.toDouble()).toInt()
                if (side * side == n && side > 0) {
                    if (previewBitmap == null || previewSide != side) {
                        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
                        previewBitmap = bmp
                        previewSide = side
                    }
                    previewBitmap!!.setPixels(result.previewArgb, 0, side, 0, 0, side, side)

                    val disp = Tunables.circleDiameter.toFloat().coerceAtLeast(1f)
                    val previewLeft = 20f
                    val previewTop = 84f
                    previewRectF.set(previewLeft, previewTop, previewLeft + disp, previewTop + disp)

                    if (Tunables.rayZoneCircleMode) {
                        canvas.save()
                        monitorClipPath.reset()
                        monitorClipPath.addCircle(
                            previewRectF.centerX(), previewRectF.centerY(),
                            disp / 2f, Path.Direction.CW
                        )
                        canvas.clipPath(monitorClipPath)
                        canvas.drawRect(previewRectF, bgPaint)
                        canvas.drawBitmap(previewBitmap!!, null, previewRectF, monitorSquarePaint)
                        canvas.restore()
                    } else {
                        canvas.drawRect(previewRectF, bgPaint)
                        canvas.drawBitmap(previewBitmap!!, null, previewRectF, monitorSquarePaint)
                    }
                }
            }
            canvas.drawRect(16f, 16f, 720f, 76f, bgPaint)
            if (result != null && result.hasLine) {
                val deg = Math.toDegrees(result.angleRad)
                canvas.drawText(
                    "angle=%.1f  px=%d  w=%.1f  score=%.1f".format(Locale.US, 
                        deg, result.pixelCount, result.widthPx, result.score
                    ),
                    24f, 60f, textPaint
                )
            } else {
                canvas.drawText("no line detected", 24f, 60f, textPaint)
            }
        }
        }

        // Manual controller draws independently of whether the automatic
        // ray currently has a detected line — it has its own cue/target
        // points, not derived from `result` at all.
        drawManualController(canvas)

        if (OverlayController.captureless || !OverlayController.captureAlive) return
        if (result == null || !result.hasLine) return

        val alphaScale = Tunables.autoAimOpacity / 255f
        centerPaint.color = Tunables.autoAimColor
        centerPaint.strokeWidth = Tunables.autoAimWidthPx
        centerPaint.alpha = (255 * alphaScale).toInt()
        borderPaint.alpha = (255 * alphaScale).toInt()
        bankCenterPaint.color = Tunables.autoAimColor
        bankCenterPaint.strokeWidth = Tunables.autoAimWidthPx
        bankCenterPaint.alpha = (255 * alphaScale).toInt()
        bankBorderPaint.alpha = (255 * alphaScale).toInt()
        doublePaint.alpha = (100 * alphaScale).toInt()
        bankDoublePaint.alpha = (70 * alphaScale).toInt()
        markerRing.alpha = (255 * alphaScale).toInt()
        markerDot.alpha = (255 * alphaScale).toInt()

        // offsetX/offsetY are now an ABSOLUTE full-screen coordinate — the
        // crop's true pixel-center anchor, computed once in CaptureService
        // from the exact rounding it used to grab the buffer (see the
        // comment there). Do NOT reconstruct it again from
        // circleCenter/circleDiameter here; that second, independently-
        // rounded path was the actual source of the spot-to-spot 1-3px
        // drift — the fix is to only ever compute it once.
        val ax = result.offsetX
        val ay = result.offsetY
        val dirX = cos(result.angleRad).toFloat()
        val dirY = sin(result.angleRad).toFloat()

        // Bug #5: a single Ray Zone that every artificial line — main,
        // bank-reflected, and doubles alike — gets clipped against, instead
        // of only nudging the first segment's starting point away from it
        // (which is what let post-bank "angle" segments render inside the
        // zone). Both directions now start from the true anchor point; the
        // clip in drawDirection removes whatever portion of any segment
        // would fall inside the zone.
        val zoneR = half * RAY_ZONE_EXCLUSION_FACTOR

        // Once calibrated, walls are the table edges — the ray has no
        // effect beyond them. Uncalibrated falls back to the full screen,
        // same as before.
        val calibrated = Tunables.tableLeft >= 0f
        val left: Float; val top: Float; val right: Float; val bottom: Float
        if (calibrated) {
            // Bug #3 fix: inset by the ghost-ball radius so a rail bounce
            // reflects off the ball's CENTER — flush against the true
            // edge, with the ball's near side touching it — instead of
            // off the table edge itself (which was being treated as the
            // ball's perimeter). Falls back to the raw calibrated rect if
            // the table is somehow smaller than the ball, so the rect
            // never inverts into a negative-size one.
            val halfBall = Tunables.ghostBallDiameterPx / 2f
            val insetLeft = Tunables.tableLeft + halfBall
            val insetTop = Tunables.tableTop + halfBall
            val insetRight = Tunables.tableRight - halfBall
            val insetBottom = Tunables.tableBottom - halfBall
            if (insetRight > insetLeft && insetBottom > insetTop) {
                left = insetLeft; top = insetTop; right = insetRight; bottom = insetBottom
            } else {
                left = Tunables.tableLeft; top = Tunables.tableTop
                right = Tunables.tableRight; bottom = Tunables.tableBottom
            }
        } else {
            left = 0f; top = 0f; right = width.toFloat(); bottom = height.toFloat()
        }

        drawDirection(canvas, ax, ay, dirX, dirY, left, top, right, bottom, cx, cy, zoneR)
        drawDirection(canvas, ax, ay, -dirX, -dirY, left, top, right, bottom, cx, cy, zoneR)
    }

    /** Walks one direction out from the anchor point, reflecting off the
     * table walls up to Tunables.maxLines total segments — ported from the
     * Manual app's per-direction bank-shot walk. Every segment (and its
     * double, if enabled) is clipped against the Ray Zone circle
     * (zoneCx/zoneCy/zoneR) before drawing — see bug #5. */
    private fun drawDirection(
        canvas: Canvas,
        startX: Float, startY: Float,
        initDx: Float, initDy: Float,
        left: Float, top: Float, right: Float, bottom: Float,
        zoneCx: Float, zoneCy: Float, zoneR: Float
    ) {
        var dx = initDx
        var dy = initDy
        // Robustness near the rail: `left/top/right/bottom` are already
        // inset by the ball radius (bug #3 fix above), so an anchor that
        // sits right up against a cushion can end up just outside that
        // inset rect even though it's clearly still on the table. Clamp
        // the start point in rather than silently drawing no line at all
        // — see the Final Note about small bugs specifically around the
        // rail. left<=right and top<=bottom always hold by construction.
        var curX = startX.coerceIn(left, right)
        var curY = startY.coerceIn(top, bottom)

        val maxTotalLength = ((right - left) + (bottom - top)) * 1.4f
        var remaining = maxTotalLength
        val maxLines = Tunables.maxLines
        val halfBall = Tunables.ghostBallDiameterPx / 2f

        for (segment in 0 until maxLines) {
            if (remaining <= 1f) break

            var tX = Float.MAX_VALUE
            var tY = Float.MAX_VALUE
            if (dx > 1e-4f) tX = (right - curX) / dx else if (dx < -1e-4f) tX = (left - curX) / dx
            if (dy > 1e-4f) tY = (bottom - curY) / dy else if (dy < -1e-4f) tY = (top - curY) / dy

            val tWall = minOf(tX, tY)
            if (tWall == Float.MAX_VALUE || tWall < 0.5f) break

            val tDraw = minOf(tWall, remaining)
            val endX = curX + dx * tDraw
            val endY = curY + dy * tDraw

            val segBorder = if (segment == 0) borderPaint else bankBorderPaint
            val segCenter = if (segment == 0) centerPaint else bankCenterPaint
            drawClippedSegLine(canvas, curX, curY, endX, endY, segBorder, zoneCx, zoneCy, zoneR)
            drawClippedSegLine(canvas, curX, curY, endX, endY, segCenter, zoneCx, zoneCy, zoneR)

            if (Tunables.doubleLineEnabled) {
                val segDouble = if (segment == 0) doublePaint else bankDoublePaint
                val halfWidth = Tunables.doubleLineWidthPx / 2f
                val px = -dy * halfWidth
                val py = dx * halfWidth
                var cnt = clipOutsideRayZone(curX + px, curY + py, endX + px, endY + py, zoneCx, zoneCy, zoneR)
                if (cnt >= 1) canvas.drawLine(clipPieceA[0], clipPieceA[1], clipPieceA[2], clipPieceA[3], segDouble)
                if (cnt >= 2) canvas.drawLine(clipPieceB[0], clipPieceB[1], clipPieceB[2], clipPieceB[3], segDouble)
                cnt = clipOutsideRayZone(curX - px, curY - py, endX - px, endY - py, zoneCx, zoneCy, zoneR)
                if (cnt >= 1) canvas.drawLine(clipPieceA[0], clipPieceA[1], clipPieceA[2], clipPieceA[3], segDouble)
                if (cnt >= 2) canvas.drawLine(clipPieceB[0], clipPieceB[1], clipPieceB[2], clipPieceB[3], segDouble)
            }

            remaining -= tDraw
            if (tDraw < tWall - 0.01f) break

            // Prefer vertical when both are essentially equal (corner), same as manual path.
            val hitVertical = abs(tWall - tX) <= abs(tWall - tY)

            if (Tunables.bankMarkerEnabled && segment + 1 < maxLines &&
                (abs(endX - zoneCx) > zoneR || abs(endY - zoneCy) > zoneR)
            ) {
                // Bug #3 fix: ring radius now matches the same ball radius
                // used to inset the wall above, so the marker's edge sits
                // flush on the true table edge and its center (where the
                // angle line actually terminates) is the ball's center —
                // not a fixed, ball-size-independent 10px like before.
                canvas.drawCircle(endX, endY, (halfBall - markerRing.strokeWidth / 2f).coerceAtLeast(1f), markerRing)
                canvas.drawCircle(endX, endY, 4f, markerDot)
            }

            val reflected = if (segment == 0) {
                BankShot.reflect(dx, dy, hitVertical)
            } else {
                // Bug fix (Double Bank Shots): the correction curve is only
                // valid for a clean no-spin first bounce — every bounce
                // after that gets a pure mirror instead. See
                // BankShot.reflectMirror's doc for why.
                BankShot.reflectMirror(dx, dy, hitVertical)
            }
            if (reflected == null) break
            dx = reflected[0]; dy = reflected[1]
            curX = endX; curY = endY
        }
    }

    private fun drawClippedSegLine(
        canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, paint: Paint,
        zoneCx: Float, zoneCy: Float, zoneR: Float
    ) {
        val cnt = clipOutsideRayZone(x1, y1, x2, y2, zoneCx, zoneCy, zoneR)
        if (cnt >= 1) drawSegLine(canvas, clipPieceA[0], clipPieceA[1], clipPieceA[2], clipPieceA[3], paint)
        if (cnt >= 2) drawSegLine(canvas, clipPieceB[0], clipPieceB[1], clipPieceB[2], clipPieceB[3], paint)
    }

    /**
     * Splits the segment [x1,y1]-[x2,y2] into the piece(s) that lie outside
     * the SQUARE Ray Zone (center zoneCx/zoneCy, half-side zoneR), dropping
     * whatever portion would fall inside it. Writes into clipPieceA (and
     * clipPieceB if there's a second piece) and returns the piece count
     * (0, 1, or 2) instead of allocating — this runs up to a few dozen
     * times per onDraw, so it's a real GC-pressure source at capture
     * frame rate. Every caller (main line, bank-reflected lines, doubles)
     * routes through here, so nothing needs its own zone-avoidance logic.
     *
     * Same "at most 2 outside pieces" structure as the old circle version:
     * a square is convex, so a line can only be inside it over a single
     * contiguous parameter interval [tLo, tHi] — everything before tLo and
     * after tHi (if any, once clamped to [0,1]) is outside. That interval
     * is found with the standard box/slab method instead of the circle's
     * quadratic — intersect the segment's parameter range against each
     * axis's [min,max] slab in turn.
     */
    private fun clipOutsideRayZone(
        x1: Float, y1: Float, x2: Float, y2: Float,
        zoneCx: Float, zoneCy: Float, zoneR: Float
    ): Int {
        val dx = x2 - x1
        val dy = y2 - y1
        val xMin = zoneCx - zoneR; val xMax = zoneCx + zoneR
        val yMin = zoneCy - zoneR; val yMax = zoneCy + zoneR

        var tLoRaw = 0f
        var tHiRaw = 1f

        if (abs(dx) > 1e-6f) {
            var ta = (xMin - x1) / dx
            var tb = (xMax - x1) / dx
            if (ta > tb) { val tmp = ta; ta = tb; tb = tmp }
            if (ta > tLoRaw) tLoRaw = ta
            if (tb < tHiRaw) tHiRaw = tb
        } else if (x1 < xMin || x1 > xMax) {
            tHiRaw = -1f // segment is vertical and entirely outside the X slab
        }

        if (abs(dy) > 1e-6f) {
            var ta = (yMin - y1) / dy
            var tb = (yMax - y1) / dy
            if (ta > tb) { val tmp = ta; ta = tb; tb = tmp }
            if (ta > tLoRaw) tLoRaw = ta
            if (tb < tHiRaw) tHiRaw = tb
        } else if (y1 < yMin || y1 > yMax) {
            tHiRaw = -1f // segment is horizontal and entirely outside the Y slab
        }

        // No overlap with the square at all inside [0,1] -> the square
        // doesn't clip this bounded segment; keep it whole.
        if (tLoRaw > tHiRaw || tHiRaw < 0f || tLoRaw > 1f) {
            clipPieceA[0] = x1; clipPieceA[1] = y1; clipPieceA[2] = x2; clipPieceA[3] = y2
            return 1
        }

        val tLo = tLoRaw.coerceIn(0f, 1f)
        val tHi = tHiRaw.coerceIn(0f, 1f)

        var count = 0
        if (tLo > 0.0001f) {
            clipPieceA[0] = x1; clipPieceA[1] = y1
            clipPieceA[2] = x1 + dx * tLo; clipPieceA[3] = y1 + dy * tLo
            count++
        }
        if (tHi < 0.9999f) {
            val piece = if (count == 0) clipPieceA else clipPieceB
            piece[0] = x1 + dx * tHi; piece[1] = y1 + dy * tHi
            piece[2] = x2; piece[3] = y2
            count++
        }
        return count
    }

    private fun drawSegLine(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, paint: Paint) {
        if (Tunables.dashedLineEnabled) {
            drawDashedLine(canvas, x1, y1, x2, y2, paint)
        } else {
            canvas.drawLine(x1, y1, x2, y2, paint)
        }
    }

    private fun drawDashedLine(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, paint: Paint) {
        val dx = x2 - x1
        val dy = y2 - y1
        val length = hypot(dx, dy)
        if (length < 1f) return
        val ux = dx / length
        val uy = dy / length
        val pattern = DASH_LEN_PX + DASH_GAP_PX

        var pos = 0f
        while (pos < length) {
            val segEnd = minOf(pos + DASH_LEN_PX, length)
            canvas.drawLine(x1 + ux * pos, y1 + uy * pos, x1 + ux * segEnd, y1 + uy * segEnd, paint)
            pos += pattern
        }
    }

    // ---------------- Manual CUE / TARGET controller rendering ----------------
    // Ported from the Manual app's LineOverlay.onDraw — see feature
    // request #1. Shares BankShot.reflect, the app's table calibration,
    // and the ghost-ball radius (bug #3 fix) with the automatic path
    // above; every OTHER tweak referenced here (color/width/opacity/
    // dashed/double/ghost-marker/sensitivity) is manual-only and never
    // touched by the automatic rendering above.

    /**
     * Renders the manual CUE->TARGET aim line and its bank segments.
     *
     * Behaviour:
     * - CUE → TARGET line is always drawn.
     * - Ghost ball on TARGET handle is always drawn (ManualHandleView).
     * - Bank trajectory (post-rail reflection) only when TARGET is on a
     *   rail — i.e. its centre sits at the inset limit so the ghost
     *   ball's outer edge hugs the yellow calibration line.
     */
    private fun drawManualController(canvas: Canvas) {
        if (!Tunables.manualControllerEnabled) return

        val cueX = OverlayController.manualCueX
        val cueY = OverlayController.manualCueY
        val targetX = OverlayController.manualTargetX
        val targetY = OverlayController.manualTargetY

        var dx = targetX - cueX
        var dy = targetY - cueY
        val len = hypot(dx, dy)
        if (len < 1f) return
        dx /= len; dy /= len

        val halfBall = Tunables.ghostBallDiameterPx / 2f

        val calibrated = Tunables.tableLeft >= 0f
        val left: Float; val top: Float; val right: Float; val bottom: Float
        if (calibrated) {
            val insetLeft = Tunables.tableLeft + halfBall
            val insetTop = Tunables.tableTop + halfBall
            val insetRight = Tunables.tableRight - halfBall
            val insetBottom = Tunables.tableBottom - halfBall
            if (insetRight > insetLeft && insetBottom > insetTop) {
                left = insetLeft; top = insetTop; right = insetRight; bottom = insetBottom
            } else {
                left = Tunables.tableLeft; top = Tunables.tableTop
                right = Tunables.tableRight; bottom = Tunables.tableBottom
            }
        } else {
            left = 0f; top = 0f; right = width.toFloat(); bottom = height.toFloat()
        }

        // On-edge = TARGET centre is at the inset clamp limit (ghost-ball
        // outer edge flush on the yellow calibration line). Generous tol
        // so a near-edge drag still counts.
        val edgeTol = 12f
        val targetOnEdge = calibrated && (
            targetX <= left + edgeTol ||
            targetX >= right - edgeTol ||
            targetY <= top + edgeTol ||
            targetY >= bottom - edgeTol
        )

        val alphaScale = Tunables.manualLineOpacity / 255f
        manualCenterPaint.color = Tunables.manualLineColor
        manualCenterPaint.strokeWidth = Tunables.manualLineWidthPx
        manualBankCenterPaint.color = Tunables.manualLineColor
        manualBankCenterPaint.strokeWidth = Tunables.manualLineWidthPx
        manualBorderPaint.alpha = (255 * alphaScale).toInt()
        manualCenterPaint.alpha = (255 * alphaScale).toInt()
        manualBankBorderPaint.alpha = (255 * alphaScale).toInt()
        manualBankCenterPaint.alpha = (255 * alphaScale).toInt()
        manualDoublePaint.strokeWidth = Tunables.manualDoubleLineWidthPx
        manualBankDoublePaint.strokeWidth = Tunables.manualDoubleLineWidthPx
        manualDoublePaint.alpha = Tunables.manualDoubleLineOpacity
        manualBankDoublePaint.alpha = Tunables.manualDoubleLineOpacity
        manualBandPaint.alpha = Tunables.manualDoubleLineOpacity
        manualBankBandPaint.alpha = Tunables.manualDoubleLineOpacity
        manualMarkerRing.alpha = (255 * alphaScale).toInt()
        manualMarkerDot.alpha = (255 * alphaScale).toInt()

        // Whether DEST is currently showing Kiss Shot — DEST must exist
        // (manualKissEnabled) AND be toggled on (manualKissActive, green
        // not red). Combo Shot no longer lives on DEST at all — see the
        // independent block near the end of this function.
        val kissActive = Tunables.manualKissEnabled && Tunables.manualKissActive

        // ---- CUE → TARGET aim line (bank/cut mode only) ----
        // Skipped while kiss-shot mode is active — it draws its own guide
        // below instead, and this line + its double/band lines would
        // otherwise stay visible underneath it, cluttering the view.
        val startT = halfBall.coerceAtMost(len)
        val centerEndT = len + halfBall
        val doubleEndT = (len - halfBall).coerceAtLeast(startT)
        if ((centerEndT - startT) > 1f && !kissActive) {
            val startNearX = cueX + dx * startT
            val startNearY = cueY + dy * startT
            val endNearX = cueX + dx * centerEndT
            val endNearY = cueY + dy * centerEndT
            val doubleEndX = cueX + dx * doubleEndT
            val doubleEndY = cueY + dy * doubleEndT

            if (Tunables.manualDoubleLineEnabled && Tunables.manualBandStyleEnabled && doubleEndT - startT > 1f) {
                var doubleHalfWidth = halfBall - Tunables.manualDoubleLineWidthOffsetPx / 2f
                if (doubleHalfWidth < 0f) doubleHalfWidth = 0f
                manualBandPaint.strokeWidth = doubleHalfWidth * 2f
                canvas.drawLine(startNearX, startNearY, doubleEndX, doubleEndY, manualBandPaint)
            }

            drawManualSegLine(canvas, startNearX, startNearY, endNearX, endNearY, manualBorderPaint)
            drawManualSegLine(canvas, startNearX, startNearY, endNearX, endNearY, manualCenterPaint)

            if (Tunables.manualDoubleLineEnabled && !Tunables.manualBandStyleEnabled && doubleEndT - startT > 1f) {
                var doubleHalfWidth = halfBall - Tunables.manualDoubleLineWidthOffsetPx / 2f
                if (doubleHalfWidth < 0f) doubleHalfWidth = 0f
                val px = -dy * doubleHalfWidth
                val py = dx * doubleHalfWidth
                canvas.drawLine(startNearX + px, startNearY + py, doubleEndX + px, doubleEndY + py, manualDoublePaint)
                canvas.drawLine(startNearX - px, startNearY - py, doubleEndX - px, doubleEndY - py, manualDoublePaint)
            }
        }

        // ---- Kiss shot assist ----
        // TARGET now plays the "ball being kissed off of" role instead of
        // "ball going to a pocket directly" — same handle, different job,
        // exactly like TARGET means different things between a plain cut
        // and a bank shot. CUE is still just the moving ball's approach
        // point. So this replaces the plain bank branch below entirely
        // rather than running alongside it — but only while DEST is
        // toggled on (green). manualKissEnabled just means DEST *exists*;
        // tapping it (not dragging) toggles it red/off <-> green/on, so
        // you're never forced to leave the settings panel to flip back and
        // forth mid-session. Combo Shot no longer shares this marker — see
        // the independent block near the end of this function.
        if (kissActive) {
            val destX = OverlayController.manualDestX
            val destY = OverlayController.manualDestY
            val solved = KissShot.solve(
                targetX, targetY, destX, destY, cueX, cueY,
                Tunables.ghostBallDiameterPx, Tunables.manualKissRadiusScalePercent,
                Tunables.manualKissThrowAngleDeg, Tunables.manualKissSideLock
            )
            // solved == null means this geometry is impossible (either the
            // destination is unreachably close to TARGET, or neither valid
            // contact point is on the side TARGET can actually be struck
            // from given CUE's approach) — draw nothing rather than a
            // wrong answer.
            if (solved != null) {
                val ghostX = solved[0]; val ghostY = solved[1]
                val contactX = solved[2]; val contactY = solved[3]
                // Approach guide: actual CUE position straight to the
                // GHOST BALL centre — where CUE's own centre needs to be
                // at the moment of contact — not the contact point itself
                // (that's only the surface touch, one radius short of the
                // ghost ball's middle). Departure guide: ghost ball centre
                // onward to DEST, showing TARGET's expected path after the
                // kiss. Both lines share the ghost-ball point as their
                // join, so together they read as one continuous path from
                // CUE through the moment of contact to DEST. Rendered with
                // manualBorderPaint + manualCenterPaint (already configured
                // above from Tunables.manualLineColor/Width/Opacity) so the
                // kiss line always matches the CUE/TARGET line exactly —
                // just without the double-line flanks.
                drawManualSegLine(canvas, cueX, cueY, ghostX, ghostY, manualBorderPaint)
                drawManualSegLine(canvas, cueX, cueY, ghostX, ghostY, manualCenterPaint)
                drawManualSegLine(canvas, ghostX, ghostY, destX, destY, manualBorderPaint)
                drawManualSegLine(canvas, ghostX, ghostY, destX, destY, manualCenterPaint)
                // The actual aim point: CUE's centre needs to land HERE,
                // one full ball diameter from TARGET's centre — not at the
                // contact dot below, which is only the surface-touch point
                // (half a ball short of this). Same ball-sized ring +
                // centre-dot style as the bank walk's rail markers, just
                // always shown (this is the whole point of kiss shot, not
                // an optional extra) and colored to match kiss mode.
                canvas.drawCircle(
                    ghostX, ghostY,
                    (halfBall - manualMarkerRing.strokeWidth / 2f).coerceAtLeast(1f),
                    manualMarkerRing
                )
                canvas.drawCircle(ghostX, ghostY, 5f, kissContactDot)
                // Contact dot: where the two balls' surfaces actually
                // touch — useful as a reference, but never aim here.
                canvas.drawCircle(contactX, contactY, 3f, kissContactDot)
            }
        } else if (targetOnEdge) {
            // Full original bank walk (same as pre-change behaviour).
            val maxTotalLength = ((right - left) + (bottom - top)) * 1.4f
            var curX = cueX.coerceIn(left, right)
            var curY = cueY.coerceIn(top, bottom)
            var remaining = maxTotalLength
            val maxLines = Tunables.maxLines
            var segDx = dx
            var segDy = dy

            for (segment in 0 until maxLines) {
                if (remaining <= 1f) break

                var tX = Float.MAX_VALUE
                var tY = Float.MAX_VALUE
                if (segDx > 1e-4f) tX = (right - curX) / segDx else if (segDx < -1e-4f) tX = (left - curX) / segDx
                if (segDy > 1e-4f) tY = (bottom - curY) / segDy else if (segDy < -1e-4f) tY = (top - curY) / segDy

                val tWall = minOf(tX, tY)
                if (tWall == Float.MAX_VALUE || tWall < 0.5f) break

                val tDraw = minOf(tWall, remaining)
                val endX = curX + segDx * tDraw
                val endY = curY + segDy * tDraw

                val segBorder = if (segment == 0) manualBorderPaint else manualBankBorderPaint
                val segCenter = if (segment == 0) manualCenterPaint else manualBankCenterPaint

                if (segment == 0 && len > halfBall) {
                    // Gap around TARGET; draw only the part past the ghost ball
                    // (CUE→TARGET already drawn above).
                    val farT = len + halfBall
                    if (tDraw > farT) {
                        val farX = curX + segDx * farT
                        val farY = curY + segDy * farT
                        if (Tunables.manualDoubleLineEnabled && Tunables.manualBandStyleEnabled) {
                            var doubleHalfWidth = halfBall - Tunables.manualDoubleLineWidthOffsetPx / 2f
                            if (doubleHalfWidth < 0f) doubleHalfWidth = 0f
                            manualBandPaint.strokeWidth = doubleHalfWidth * 2f
                            canvas.drawLine(farX, farY, endX, endY, manualBandPaint)
                        }
                        drawManualSegLine(canvas, farX, farY, endX, endY, segBorder)
                        drawManualSegLine(canvas, farX, farY, endX, endY, segCenter)
                        if (Tunables.manualDoubleLineEnabled && !Tunables.manualBandStyleEnabled) {
                            var doubleHalfWidth = halfBall - Tunables.manualDoubleLineWidthOffsetPx / 2f
                            if (doubleHalfWidth < 0f) doubleHalfWidth = 0f
                            val px = -segDy * doubleHalfWidth
                            val py = segDx * doubleHalfWidth
                            val segDouble = manualDoublePaint
                            canvas.drawLine(farX + px, farY + py, endX + px, endY + py, segDouble)
                            canvas.drawLine(farX - px, farY - py, endX - px, endY - py, segDouble)
                        }
                    }
                } else if (segment > 0) {
                    // Reflected bank segments.
                    if (Tunables.manualDoubleLineEnabled && Tunables.manualBandStyleEnabled) {
                        var doubleHalfWidth = halfBall - Tunables.manualDoubleLineWidthOffsetPx / 2f
                        if (doubleHalfWidth < 0f) doubleHalfWidth = 0f
                        manualBankBandPaint.strokeWidth = doubleHalfWidth * 2f
                        canvas.drawLine(curX, curY, endX, endY, manualBankBandPaint)
                    }
                    drawManualSegLine(canvas, curX, curY, endX, endY, segBorder)
                    drawManualSegLine(canvas, curX, curY, endX, endY, segCenter)
                    if (Tunables.manualDoubleLineEnabled && !Tunables.manualBandStyleEnabled) {
                        var doubleHalfWidth = halfBall - Tunables.manualDoubleLineWidthOffsetPx / 2f
                        if (doubleHalfWidth < 0f) doubleHalfWidth = 0f
                        val px = -segDy * doubleHalfWidth
                        val py = segDx * doubleHalfWidth
                        canvas.drawLine(curX + px, curY + py, endX + px, endY + py, manualBankDoublePaint)
                        canvas.drawLine(curX - px, curY - py, endX - px, endY - py, manualBankDoublePaint)
                    }
                }

                remaining -= tDraw
                if (tDraw < tWall - 0.01f) break

                // Prefer vertical when both are essentially equal (corner), same
                // as the automatic ray's drawDirection — was a separate latent
                // inconsistency between the two loops, not the double-bank
                // cause itself, but worth fixing alongside it.
                val hitVertical = abs(tWall - tX) <= abs(tWall - tY)

                if (Tunables.manualGhostRailEnabled && segment + 1 < maxLines) {
                    // Centre sits on the inset wall → ghost-ball edge flush on
                    // the true (yellow) table edge.
                    canvas.drawCircle(
                        endX, endY,
                        (halfBall - manualMarkerRing.strokeWidth / 2f).coerceAtLeast(1f),
                        manualMarkerRing
                    )
                    canvas.drawCircle(endX, endY, 4f, manualMarkerDot)
                }

                val reflected = if (segment == 0) {
                    BankShot.reflect(segDx, segDy, hitVertical)
                } else {
                    BankShot.reflectMirror(segDx, segDy, hitVertical)
                }
                if (reflected == null) break
                segDx = reflected[0]; segDy = reflected[1]
                if (segment == 0) {
                    // TARGET's own ghost ball sits at this bend (endX,endY is
                    // its center). The incoming line above stopped halfBall
                    // short of it (nearT); without this offset the reflected
                    // segment started exactly at that center, so it drew
                    // through the ball's near half while leaving the incoming
                    // side's gap disconnected. Starting halfBall past the bend
                    // along the new direction mirrors the same gap on the far
                    // side instead — symmetric, connected.
                    curX = endX + segDx * halfBall
                    curY = endY + segDy * halfBall
                    remaining -= halfBall
                } else {
                    curX = endX; curY = endY
                }
            }
        }

        // ---- Combo shot assist ----
        // Fully independent of Kiss Shot and the plain bank walk above —
        // always renders when enabled (Tunables.manualComboEnabled),
        // regardless of whether Kiss Shot is on or off. Uses TARGET (the
        // ball being hit) and its own destination handle (yellow octagon,
        // manualComboX/Y) instead of sharing DEST with Kiss. CUE isn't
        // part of this at all — aim the real shot with the separate
        // auto-line feature instead. Solved in ComboShot — a plain closed
        // form, see its header doc.
        if (Tunables.manualComboEnabled) {
            val comboDestX = OverlayController.manualComboX
            val comboDestY = OverlayController.manualComboY
            val solved = ComboShot.solve(
                targetX, targetY, comboDestX, comboDestY,
                Tunables.ghostBallDiameterPx, Tunables.manualComboRadiusScalePercent,
                Tunables.manualComboOffsetPx
            )
            // solved == null means the octagon sits inside TARGET's own
            // footprint (given the current offset) — draw nothing rather
            // than a wrong answer, same "no false positives" rule as kiss
            // shot.
            if (solved != null) {
                val ghostX = solved.ghostX; val ghostY = solved.ghostY
                val contactX = solved.contactX; val contactY = solved.contactY
                // TARGET travels straight from its own centre to the
                // octagon — the "collision mark" dot is where on TARGET's
                // edge to land the real shot (aimed separately, for real,
                // with the auto-line feature).
                drawManualSegLine(canvas, targetX, targetY, comboDestX, comboDestY, manualBorderPaint)
                drawManualSegLine(canvas, targetX, targetY, comboDestX, comboDestY, manualCenterPaint)
                // The actual aim point: the striking ball's centre needs
                // to land HERE, one full ball diameter (plus the Combo
                // offset tweak) from TARGET's centre — not at the small
                // contact dot below, which is only the surface-touch point.
                canvas.drawCircle(
                    ghostX, ghostY,
                    (halfBall - manualMarkerRing.strokeWidth / 2f).coerceAtLeast(1f),
                    manualMarkerRing
                )
                canvas.drawCircle(ghostX, ghostY, 5f, comboContactDot)
                // Contact dot: where the two balls' surfaces would touch at
                // zero offset — useful as a reference, but never aim here.
                canvas.drawCircle(contactX, contactY, 3f, comboContactDot)

                // ---- Bank continuation past the octagon, when it hugs a rail ----
                // The octagon now plays the role TARGET plays in the plain
                // Bank Shot walk above: the point where the ball actually
                // contacts the rail. The TARGET→octagon segment just drawn
                // above IS that walk's first leg — this continues it past
                // the bounce, reusing the exact same rail-reflection code.
                val comboDestOnEdge = calibrated && (
                    comboDestX <= left + edgeTol || comboDestX >= right - edgeTol ||
                    comboDestY <= top + edgeTol || comboDestY >= bottom - edgeTol
                )
                val comboMaxLines = Tunables.maxLines
                if (comboDestOnEdge && comboMaxLines > 1) {
                    val comboLen = hypot(comboDestX - targetX, comboDestY - targetY)
                    if (comboLen > 1f) {
                        val cdx = (comboDestX - targetX) / comboLen
                        val cdy = (comboDestY - targetY) / comboLen
                        // Re-derive which wall the octagon is actually
                        // against from the ray itself (not the edge-
                        // proximity check above) so the bend point and
                        // hitVertical are exactly consistent with where the
                        // ray really lands — same approach the plain Bank
                        // Shot walk uses.
                        var tX = Float.MAX_VALUE
                        var tY = Float.MAX_VALUE
                        if (cdx > 1e-4f) tX = (right - targetX) / cdx else if (cdx < -1e-4f) tX = (left - targetX) / cdx
                        if (cdy > 1e-4f) tY = (bottom - targetY) / cdy else if (cdy < -1e-4f) tY = (top - targetY) / cdy
                        val tWall = minOf(tX, tY)
                        if (tWall != Float.MAX_VALUE && tWall > 0.5f) {
                            val hitVertical = abs(tWall - tX) <= abs(tWall - tY)
                            val bendX = targetX + cdx * tWall
                            val bendY = targetY + cdy * tWall
                            val reflected = BankShot.reflect(cdx, cdy, hitVertical)
                            if (reflected != null) {
                                if (Tunables.manualGhostRailEnabled) {
                                    canvas.drawCircle(
                                        bendX, bendY,
                                        (halfBall - manualMarkerRing.strokeWidth / 2f).coerceAtLeast(1f),
                                        manualMarkerRing
                                    )
                                    canvas.drawCircle(bendX, bendY, 4f, manualMarkerDot)
                                }
                                val comboMaxTotalLength = ((right - left) + (bottom - top)) * 1.4f
                                drawBankSegments(
                                    canvas, bendX, bendY, reflected[0], reflected[1],
                                    (comboMaxTotalLength - tWall).coerceAtLeast(0f), 1, comboMaxLines, false,
                                    left, top, right, bottom, halfBall
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Draws consecutive rail-bounce segments starting at (startX,startY)
     * heading in direction (startDx,startDy), reflecting off whichever
     * rail each one hits along the way. Used by Combo Shot's post-octagon
     * bank continuation (see drawManualController) for every bounce after
     * the first — the plain CUE/TARGET Bank Shot walk below has its own,
     * separate copy of this same logic for its first segment (which needs
     * extra gap-around-the-ghost-ball handling this one doesn't) and
     * isn't changed here.
     *
     * [firstBounceExact] should be true only when the very first bounce
     * (the one already used to get to (startX,startY)) hasn't happened
     * yet and needs the real, correction-curve-accurate reflection (see
     * BankShot.reflect) rather than a pure mirror (BankShot.reflectMirror)
     * — pass the post-bounce direction and false once that's already been
     * done by the caller.
     */
    private fun drawBankSegments(
        canvas: Canvas,
        startX: Float, startY: Float, startDx: Float, startDy: Float,
        remainingLenStart: Float, segmentsUsed: Int, maxLines: Int,
        firstBounceExact: Boolean,
        left: Float, top: Float, right: Float, bottom: Float, halfBall: Float
    ) {
        var curX = startX; var curY = startY
        var curDx = startDx; var curDy = startDy
        var remaining = remainingLenStart
        var segment = segmentsUsed
        var exact = firstBounceExact
        while (segment < maxLines) {
            if (remaining <= 1f) break

            var tX = Float.MAX_VALUE
            var tY = Float.MAX_VALUE
            if (curDx > 1e-4f) tX = (right - curX) / curDx else if (curDx < -1e-4f) tX = (left - curX) / curDx
            if (curDy > 1e-4f) tY = (bottom - curY) / curDy else if (curDy < -1e-4f) tY = (top - curY) / curDy
            val tWall = minOf(tX, tY)
            if (tWall == Float.MAX_VALUE || tWall < 0.5f) break

            val tDraw = minOf(tWall, remaining)
            val endX = curX + curDx * tDraw
            val endY = curY + curDy * tDraw

            if (Tunables.manualDoubleLineEnabled && Tunables.manualBandStyleEnabled) {
                var doubleHalfWidth = halfBall - Tunables.manualDoubleLineWidthOffsetPx / 2f
                if (doubleHalfWidth < 0f) doubleHalfWidth = 0f
                manualBankBandPaint.strokeWidth = doubleHalfWidth * 2f
                canvas.drawLine(curX, curY, endX, endY, manualBankBandPaint)
            }
            drawManualSegLine(canvas, curX, curY, endX, endY, manualBankBorderPaint)
            drawManualSegLine(canvas, curX, curY, endX, endY, manualBankCenterPaint)
            if (Tunables.manualDoubleLineEnabled && !Tunables.manualBandStyleEnabled) {
                var doubleHalfWidth = halfBall - Tunables.manualDoubleLineWidthOffsetPx / 2f
                if (doubleHalfWidth < 0f) doubleHalfWidth = 0f
                val px = -curDy * doubleHalfWidth
                val py = curDx * doubleHalfWidth
                canvas.drawLine(curX + px, curY + py, endX + px, endY + py, manualBankDoublePaint)
                canvas.drawLine(curX - px, curY - py, endX - px, endY - py, manualBankDoublePaint)
            }

            remaining -= tDraw
            if (tDraw < tWall - 0.01f) break

            val hitVertical = abs(tWall - tX) <= abs(tWall - tY)
            if (Tunables.manualGhostRailEnabled && segment + 1 < maxLines) {
                canvas.drawCircle(
                    endX, endY,
                    (halfBall - manualMarkerRing.strokeWidth / 2f).coerceAtLeast(1f),
                    manualMarkerRing
                )
                canvas.drawCircle(endX, endY, 4f, manualMarkerDot)
            }

            val reflected = if (exact) BankShot.reflect(curDx, curDy, hitVertical) else BankShot.reflectMirror(curDx, curDy, hitVertical)
            if (reflected == null) break
            curDx = reflected[0]; curDy = reflected[1]
            curX = endX; curY = endY
            exact = false
            segment++
        }
    }

    private fun drawManualSegLine(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, paint: Paint) {
        if (Tunables.manualDashedLineEnabled) {
            drawDashedLine(canvas, x1, y1, x2, y2, paint)
        } else {
            canvas.drawLine(x1, y1, x2, y2, paint)
        }
    }
}
