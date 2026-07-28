package com.yas.linedebugger

import android.app.Service
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// How far a handle is allowed to slide past the edge of the screen before
// it stops, expressed as a fraction of the handle's own hitbox size — 0.10
// means only the outer 10% may hang off the edge. Ported from the Manual
// app's calibration-handle clamp and now also applied to the Ray Circle
// and the manual CUE/TARGET handles.
private const val EDGE_LIMIT_FRACTION = 0.10f

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

// Bug #5 (artificial-line clipping): the Ray Zone is the Ray Circle's
// footprint expanded by this factor. No artificial line — main,
// bank-reflected, or double — may be drawn inside it. Requested range was
// 10-30% beyond the boundary; 1.20 (20%) is used here.
private const val RAY_ZONE_EXCLUSION_FACTOR = 1.20f

// Bug #3 (floating panel size): the panel's height used to be a flat
// 900px. Halved per the bug report.
private const val PANEL_HEIGHT_PX = 450

object OverlayController {

    @Volatile var circleCenterX: Int = 400
    @Volatile var circleCenterY: Int = 800
    @Volatile var lastResult: DetectionResult? = null

    // --- Candidate lock (anti-blink, but switches fast on big angle change) ---
    private var lockedResult: DetectionResult? = null
    private var lockHoldFrames: Int = 0
    private const val LOCK_HOLD = 6            // frames to keep a good lock on the *same* line
    private const val ANGLE_TOL = 0.18         // ~10 degrees - considered "same line"

    // Temporal EMA on displayed angle / centroid — kills per-frame jitter from
    // pixel noise without adding multi-second lag. Alpha closer to 1 = snappier.
    private const val SMOOTH_ALPHA = 0.42
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

    fun attach(svc: Service) {
        service = svc
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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { applyFullScreenFlags() }
        wm.addView(dView, drawParams)

        attachCircleHandle(svc, wm)
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
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
                    wm.updateViewLayout(v, hParams)
                    circleCenterX = hParams.x + size / 2
                    circleCenterY = hParams.y + size / 2
                    drawView?.invalidate()
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
            onCalibrate = { toggleCalibrationMode() }
        )
        scroll.addView(settings)
        panel.addView(scroll)

        // Floating panel width: cap to ~52% of screen so the full panel
        // (including correction ↑/↓ buttons) stays visible on narrow
        // devices like the Galaxy A32 (~720–1080px wide). Content is
        // forced to MATCH_PARENT so seekbar rows shrink instead of
        // clipping the right-side buttons.
        //
        // CRITICAL: ScrollView's child must use FrameLayout LayoutParams
        // (ScrollView extends FrameLayout). LinearLayout.LayoutParams on
        // the settings child caused ClassCastException on Start when
        // ScrollView measured its child.
        val panelWidth = (screenWidth * 0.52f).toInt().coerceIn(280, 420)
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }
        panelParams = pParams

        var downRawX = 0f; var downRawY = 0f; var downX = 0; var downY = 0
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
                    wm.updateViewLayout(panel, pParams)
                    true
                }
                else -> false
            }
        }

        panelView = panel
        wm.addView(panel, pParams)
        applyPanelVisibility(panel, pParams)
    }

    /** Bug #1: Hide must conceal the draggable Ray Circle controller too,
     * not just the canvas-drawn aim line. Same VISIBLE/INVISIBLE +
     * FLAG_NOT_TOUCHABLE pattern as [applyPanelVisibility], so a hidden
     * controller also stops swallowing drags. Also hides the manual
     * CUE/TARGET handles (if attached) the same way, so the global
     * Hide/Show notification action is a single master switch for every
     * draggable overlay, not just the automatic ones. */
    private fun applyHandleVisibility() {
        val wm = windowManager ?: return
        val hView = handleView ?: return
        val params = handleParams ?: return
        if (Tunables.aimVisible) {
            hView.visibility = View.VISIBLE
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            hView.visibility = View.INVISIBLE
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { wm.updateViewLayout(hView, params) }
        manualCueHandle?.setHidden(!Tunables.aimVisible)
        manualTargetHandle?.setHidden(!Tunables.aimVisible)
    }

    private fun applyPanelVisibility(panel: View, params: WindowManager.LayoutParams) {
        val wm = windowManager ?: return
        if (Tunables.tweakPanelVisible) {
            panel.visibility = View.VISIBLE
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            panel.visibility = View.INVISIBLE
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { wm.updateViewLayout(panel, params) }
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
        val panel = panelView
        val params = panelParams
        if (panel != null && params != null) {
            applyPanelVisibility(panel, params)
        }
    }

    // ---------------- Table calibration ----------------

    fun toggleCalibrationMode() {
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

        edgeAHandle?.remove(); edgeAHandle = null
        edgeBHandle?.remove(); edgeBHandle = null
        edgeADPad?.remove(); edgeADPad = null
        edgeBDPad?.remove(); edgeBDPad = null
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
            svc, wm, screenWidth, screenHeight, ManualRole.CUE, manualCueX, manualCueY
        ) { x, y -> manualCueX = x; manualCueY = y; drawView?.invalidate() }

        manualTargetHandle = ManualHandle(
            svc, wm, screenWidth, screenHeight, ManualRole.TARGET, manualTargetX, manualTargetY
        ) { x, y -> manualTargetX = x; manualTargetY = y; drawView?.invalidate() }

        applyHandleVisibility()
        drawView?.invalidate()
    }

    private fun detachManualHandles() {
        manualCueHandle?.remove(); manualCueHandle = null
        manualTargetHandle?.remove(); manualTargetHandle = null
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
            if (!smoothInit) {
                smoothAngle = base.angleRad
                smoothOffX = base.offsetX
                smoothOffY = base.offsetY
                smoothInit = true
            } else {
                // Shortest-path angle blend on the circle (period π for undirected lines)
                var d = base.angleRad - smoothAngle
                while (d > Math.PI / 2) d -= Math.PI
                while (d < -Math.PI / 2) d += Math.PI
                smoothAngle += SMOOTH_ALPHA * d
                // Keep in (-π/2, π/2] for stability
                if (smoothAngle > Math.PI / 2) smoothAngle -= Math.PI
                if (smoothAngle <= -Math.PI / 2) smoothAngle += Math.PI
                smoothOffX += (SMOOTH_ALPHA * (base.offsetX - smoothOffX)).toFloat()
                smoothOffY += (SMOOTH_ALPHA * (base.offsetY - smoothOffY)).toFloat()
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
        manualCueHandle?.remove(); manualCueHandle = null
        manualTargetHandle?.remove(); manualTargetHandle = null
        drawView = null; handleView = null; panelView = null; windowManager = null
        service = null
        calibrationMode = false
        lockedResult = null
        lockHoldFrames = 0
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
    private val onMoved: (Float, Float) -> Unit
) {
    private val view = EdgeHandleView(context)
    private val params = WindowManager.LayoutParams(
        EDGE_HANDLE_HITBOX_PX,
        EDGE_HANDLE_HITBOX_PX,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    )

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
        runCatching { wm.updateViewLayout(view, params) }
        onMoved(params.x + size / 2f, params.y + size / 2f)
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

private class EdgeHandleView(context: Context) : View(context) {
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 3f
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val half = EDGE_HANDLE_VISUAL_PX / 2f
        canvas.drawRect(cx - half, cy - half, cx + half, cy + half, outline)
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
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
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

/** CUE draws as a red-tinted circle handle with a ball outline + red
 * center dot; TARGET draws as a black-tinted square handle with NO ball
 * outline. That asymmetry is intentional and ported verbatim from the
 * Manual app's DraggableHandle — see ManualHandleView. */
private enum class ManualRole { CUE, TARGET }

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
    private val onMoved: (Float, Float) -> Unit
) {
    private val view = ManualHandleView(context, role, Tunables.ghostBallDiameterPx)
    private val params = WindowManager.LayoutParams(
        MANUAL_HANDLE_HITBOX_PX,
        MANUAL_HANDLE_HITBOX_PX,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    )

    init {
        params.gravity = Gravity.TOP or Gravity.START
        params.x = (initX - MANUAL_HANDLE_HITBOX_PX / 2f).toInt()
        params.y = (initY - MANUAL_HANDLE_HITBOX_PX / 2f).toInt()

        var lastRawX = 0f; var lastRawY = 0f
        var exactX = 0f; var exactY = 0f
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastRawX = event.rawX; lastRawY = event.rawY
                    exactX = params.x.toFloat(); exactY = params.y.toFloat()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val rawX = event.rawX
                    val rawY = event.rawY
                    exactX += (rawX - lastRawX) * Tunables.manualSensitivity
                    exactY += (rawY - lastRawY) * Tunables.manualSensitivity

                    // When the table is calibrated, the handle centre is
                    // limited to the INSET table rect (table edge ± half
                    // ghost-ball). That way the ghost ball's outer edge
                    // hugs the yellow calibration line — the red-dot
                    // centre never sits on the rail itself.
                    val halfHit = MANUAL_HANDLE_HITBOX_PX / 2f
                    if (Tunables.tableLeft >= 0f) {
                        val halfBall = Tunables.ghostBallDiameterPx / 2f
                        val minCX = Tunables.tableLeft + halfBall
                        val maxCX = Tunables.tableRight - halfBall
                        val minCY = Tunables.tableTop + halfBall
                        val maxCY = Tunables.tableBottom - halfBall
                        var cx = exactX + halfHit
                        var cy = exactY + halfHit
                        // Guard against inverted rect if ball > table.
                        if (maxCX > minCX) {
                            if (cx < minCX) cx = minCX else if (cx > maxCX) cx = maxCX
                        }
                        if (maxCY > minCY) {
                            if (cy < minCY) cy = minCY else if (cy > maxCY) cy = maxCY
                        }
                        exactX = cx - halfHit
                        exactY = cy - halfHit
                    } else {
                        val margin = MANUAL_HANDLE_HITBOX_PX * EDGE_LIMIT_FRACTION
                        val minX = -margin
                        val maxX = screenWidth - MANUAL_HANDLE_HITBOX_PX + margin
                        val minY = -margin
                        val maxY = screenHeight - MANUAL_HANDLE_HITBOX_PX + margin
                        if (exactX < minX) exactX = minX else if (exactX > maxX) exactX = maxX
                        if (exactY < minY) exactY = minY else if (exactY > maxY) exactY = maxY
                    }

                    params.x = Math.round(exactX)
                    params.y = Math.round(exactY)
                    lastRawX = rawX; lastRawY = rawY

                    runCatching { wm.updateViewLayout(view, params) }
                    onMoved(params.x + MANUAL_HANDLE_HITBOX_PX / 2f, params.y + MANUAL_HANDLE_HITBOX_PX / 2f)
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

    fun setHidden(hidden: Boolean) {
        view.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        params.flags = if (hidden) {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        runCatching { wm.updateViewLayout(view, params) }
    }

    fun remove() {
        runCatching { wm.removeView(view) }
    }
}

/**
 * Visual for a manual handle — a big translucent hitbox (red circle for
 * CUE, black square for TARGET) plus a ball-diameter ghost-ball outline
 * with a red center dot for BOTH roles. The ghost ball on TARGET must
 * always be visible; only the bank-shot trajectory line is gated on
 * "TARGET is on a rail."
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
        if (role == ManualRole.CUE) {
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

    fun setVisualDiameterPx(diameterPx: Float) {
        visualDiameterPx = diameterPx
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val ch = minOf(width, height) / 2f - 8f

        if (role == ManualRole.CUE) {
            canvas.drawCircle(cx, cy, ch, controllerFill)
        } else {
            canvas.drawRect(cx - ch, cy - ch, cx + ch, cy + ch, controllerFill)
        }

        // Ghost ball always drawn for both CUE and TARGET.
        val r = visualDiameterPx / 2f - outline.strokeWidth / 2f
        if (r > 1f) {
            canvas.drawCircle(cx, cy, r, outline)
            canvas.drawCircle(cx, cy, 4f, centerDot)
        }
    }
}

/**
 * Renders the Ray Circle, the detected-guideline segments (with bank-shot
 * reflection off the table edges once calibrated), the Ray Monitor debug
 * preview, and the manual CUE/TARGET controller's own line.
 */
class DrawOverlayView(context: Context) : View(context) {
    private val circlePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
        isAntiAlias = true
    }
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
    private val manualMarkerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.BLACK; strokeWidth = 3f
    }
    private val manualMarkerDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.RED
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

        circlePaint.alpha = Tunables.circleAlpha
        canvas.drawCircle(cx, cy, half, circlePaint)

        val result = OverlayController.lastResult

        if (Tunables.rayMonitorEnabled) {
            if (result != null && result.previewArgb.isNotEmpty()) {
                val size = Tunables.circleDiameter
                if (result.previewArgb.size == size * size) {
                    val bmp = Bitmap.createBitmap(result.previewArgb, size, size, Bitmap.Config.ARGB_8888)
                    val r = RectF(20f, 84f, 20f + size * 3f, 84f + size * 3f)
                    canvas.drawRect(r, bgPaint)
                    canvas.drawBitmap(bmp, null, r, null)
                }
            }
            canvas.drawRect(16f, 16f, 720f, 76f, bgPaint)
            if (result != null && result.hasLine) {
                val deg = Math.toDegrees(result.angleRad)
                canvas.drawText(
                    "angle=%.1f  px=%d  w=%.1f  score=%.1f".format(
                        deg, result.pixelCount, result.widthPx, result.score
                    ),
                    24f, 60f, textPaint
                )
            } else {
                canvas.drawText("no line detected", 24f, 60f, textPaint)
            }
        }

        // Manual controller draws independently of whether the automatic
        // ray currently has a detected line — it has its own cue/target
        // points, not derived from `result` at all.
        drawManualController(canvas)

        if (result == null || !result.hasLine) return

        val alphaScale = Tunables.autoAimOpacity / 255f
        centerPaint.color = Tunables.autoAimColor
        centerPaint.strokeWidth = Tunables.autoAimWidthPx
        centerPaint.alpha = (255 * alphaScale).toInt()
        borderPaint.alpha = (255 * alphaScale).toInt()
        bankCenterPaint.color = Tunables.autoAimColor
        bankCenterPaint.strokeWidth = Tunables.autoAimWidthPx
        bankCenterPaint.alpha = (190 * alphaScale).toInt()
        bankBorderPaint.alpha = (150 * alphaScale).toInt()
        doublePaint.alpha = (100 * alphaScale).toInt()
        bankDoublePaint.alpha = (70 * alphaScale).toInt()
        markerRing.alpha = (255 * alphaScale).toInt()
        markerDot.alpha = (255 * alphaScale).toInt()

        val ax = cx - half + result.offsetX
        val ay = cy - half + result.offsetY
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
                for (piece in clipOutsideRayZone(curX + px, curY + py, endX + px, endY + py, zoneCx, zoneCy, zoneR)) {
                    canvas.drawLine(piece[0], piece[1], piece[2], piece[3], segDouble)
                }
                for (piece in clipOutsideRayZone(curX - px, curY - py, endX - px, endY - py, zoneCx, zoneCy, zoneR)) {
                    canvas.drawLine(piece[0], piece[1], piece[2], piece[3], segDouble)
                }
            }

            remaining -= tDraw
            if (tDraw < tWall - 0.01f) break

            val hitVertical = tWall == tX

            if (Tunables.bankMarkerEnabled && segment + 1 < maxLines &&
                hypot(endX - zoneCx, endY - zoneCy) >= zoneR
            ) {
                // Bug #3 fix: ring radius now matches the same ball radius
                // used to inset the wall above, so the marker's edge sits
                // flush on the true table edge and its center (where the
                // angle line actually terminates) is the ball's center —
                // not a fixed, ball-size-independent 10px like before.
                canvas.drawCircle(endX, endY, (halfBall - markerRing.strokeWidth / 2f).coerceAtLeast(1f), markerRing)
                canvas.drawCircle(endX, endY, 4f, markerDot)
            }

            val reflected = BankShot.reflect(dx, dy, hitVertical) ?: break
            dx = reflected[0]; dy = reflected[1]
            curX = endX; curY = endY
        }
    }

    private fun drawClippedSegLine(
        canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, paint: Paint,
        zoneCx: Float, zoneCy: Float, zoneR: Float
    ) {
        for (piece in clipOutsideRayZone(x1, y1, x2, y2, zoneCx, zoneCy, zoneR)) {
            drawSegLine(canvas, piece[0], piece[1], piece[2], piece[3], paint)
        }
    }

    /**
     * Splits the segment [x1,y1]-[x2,y2] into the piece(s) that lie outside
     * the circular Ray Zone (center zoneCx/zoneCy, radius zoneR), dropping
     * whatever portion would fall inside it. Returns an empty list if the
     * whole segment is inside, the segment unchanged (as a single piece) if
     * it never touches the zone, or two pieces if it passes all the way
     * through (entry side + exit side). This is the one unified rule bug #5
     * asks for — every caller (main line, bank-reflected lines, doubles)
     * routes through here, so nothing needs its own zone-avoidance logic.
     */
    private fun clipOutsideRayZone(
        x1: Float, y1: Float, x2: Float, y2: Float,
        zoneCx: Float, zoneCy: Float, zoneR: Float
    ): List<FloatArray> {
        val dx = x2 - x1
        val dy = y2 - y1
        val fx = x1 - zoneCx
        val fy = y1 - zoneCy
        val a = dx * dx + dy * dy
        if (a < 1e-6f) {
            return if (hypot(fx, fy) >= zoneR) listOf(floatArrayOf(x1, y1, x2, y2)) else emptyList()
        }

        val b = 2f * (fx * dx + fy * dy)
        val c = fx * fx + fy * fy - zoneR * zoneR
        val disc = b * b - 4f * a * c
        if (disc < 0f) return listOf(floatArrayOf(x1, y1, x2, y2))

        val sqrtDisc = kotlin.math.sqrt(disc)
        val rawT1 = (-b - sqrtDisc) / (2f * a)
        val rawT2 = (-b + sqrtDisc) / (2f * a)
        // Intersection interval doesn't overlap [0,1] at all -> the circle
        // doesn't actually clip this bounded segment; keep it whole.
        if (rawT2 < 0f || rawT1 > 1f) return listOf(floatArrayOf(x1, y1, x2, y2))

        val tLo = rawT1.coerceIn(0f, 1f)
        val tHi = rawT2.coerceIn(0f, 1f)

        val pieces = ArrayList<FloatArray>(2)
        if (tLo > 0.0001f) pieces.add(floatArrayOf(x1, y1, x1 + dx * tLo, y1 + dy * tLo))
        if (tHi < 0.9999f) pieces.add(floatArrayOf(x1 + dx * tHi, y1 + dy * tHi, x2, y2))
        return pieces
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
        manualBankBorderPaint.alpha = (150 * alphaScale).toInt()
        manualBankCenterPaint.alpha = (190 * alphaScale).toInt()
        manualDoublePaint.alpha = (100 * alphaScale).toInt()
        manualBankDoublePaint.alpha = (70 * alphaScale).toInt()
        manualMarkerRing.alpha = (255 * alphaScale).toInt()
        manualMarkerDot.alpha = (255 * alphaScale).toInt()

        // ---- Always: CUE → TARGET aim line ----
        val nearT = (len - halfBall).coerceAtLeast(0f)
        if (nearT > 1f) {
            val endNearX = cueX + dx * nearT
            val endNearY = cueY + dy * nearT
            drawManualSegLine(canvas, cueX, cueY, endNearX, endNearY, manualBorderPaint)
            drawManualSegLine(canvas, cueX, cueY, endNearX, endNearY, manualCenterPaint)

            if (Tunables.manualDoubleLineEnabled) {
                var doubleHalfWidth = halfBall - Tunables.manualDoubleLineWidthOffsetPx / 2f
                if (doubleHalfWidth < 0f) doubleHalfWidth = 0f
                val px = -dy * doubleHalfWidth
                val py = dx * doubleHalfWidth
                canvas.drawLine(cueX + px, cueY + py, endNearX + px, endNearY + py, manualDoublePaint)
                canvas.drawLine(cueX - px, cueY - py, endNearX - px, endNearY - py, manualDoublePaint)
            }
        }

        // ---- Bank trajectory only when TARGET hugs a rail ----
        if (!targetOnEdge) return

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
                    drawManualSegLine(canvas, farX, farY, endX, endY, segBorder)
                    drawManualSegLine(canvas, farX, farY, endX, endY, segCenter)
                    if (Tunables.manualDoubleLineEnabled) {
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
                drawManualSegLine(canvas, curX, curY, endX, endY, segBorder)
                drawManualSegLine(canvas, curX, curY, endX, endY, segCenter)
                if (Tunables.manualDoubleLineEnabled) {
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

            val hitVertical = abs(tWall - tX) < 1e-3f

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

            val reflected = BankShot.reflect(segDx, segDy, hitVertical) ?: break
            segDx = reflected[0]; segDy = reflected[1]
            curX = endX; curY = endY
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
