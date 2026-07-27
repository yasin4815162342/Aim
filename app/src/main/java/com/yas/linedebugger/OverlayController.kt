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
// app's calibration-handle clamp and now also applied to the Ray Circle.
private const val EDGE_LIMIT_FRACTION = 0.10f

private const val EDGE_HANDLE_VISUAL_PX = 46f
private const val EDGE_HANDLE_HITBOX_PX = 64

private const val DPAD_SIZE_PX = 150
private const val DPAD_NUDGE_PX = 1f
private const val DPAD_GAP_PX = 20f

private const val DASH_LEN_PX = 26f
private const val DASH_GAP_PX = 16f

object OverlayController {

    @Volatile var circleCenterX: Int = 400
    @Volatile var circleCenterY: Int = 800
    @Volatile var lastResult: DetectionResult? = null

    // --- Candidate lock (anti-blink, but switches fast on big angle change) ---
    private var lockedResult: DetectionResult? = null
    private var lockHoldFrames: Int = 0
    private const val LOCK_HOLD = 6            // frames to keep a good lock on the *same* line
    private const val ANGLE_TOL = 0.18         // ~10 degrees - considered "same line"

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
        buildPanel(svc, wm)
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
            setPadding(24, 24, 24, 24)
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

        val pParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            900,
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

    // ---------------- Detection result handling ----------------

    /**
     * Anti-blink lock that still switches quickly when you drag onto a
     * different line.
     * - Same / similar angle -> keep updating the lock (smooth)
     * - Big angle change (> ~20 deg) -> switch immediately, short hold
     * - Temporary loss of line -> keep previous lock for a few frames only
     */
    fun updateResult(result: DetectionResult) {
        val prev = lockedResult

        if (!result.hasLine) {
            if (lockHoldFrames > 0) {
                lockHoldFrames--
            } else {
                lockedResult = null
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
            }
        }

        lastResult = lockedResult ?: result
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

/**
 * Renders the Ray Circle, the detected-guideline segments (with bank-shot
 * reflection off the table edges once calibrated), and the Ray Monitor
 * debug preview.
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

        if (!Tunables.aimVisible) return
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
        val excludeRadius = half * 1.50f

        // Once calibrated, walls are the table edges — the ray has no
        // effect beyond them. Uncalibrated falls back to the full screen,
        // same as before.
        val calibrated = Tunables.tableLeft >= 0f
        val left: Float; val top: Float; val right: Float; val bottom: Float
        if (calibrated) {
            left = Tunables.tableLeft; top = Tunables.tableTop
            right = Tunables.tableRight; bottom = Tunables.tableBottom
        } else {
            left = 0f; top = 0f; right = width.toFloat(); bottom = height.toFloat()
        }

        drawDirection(
            canvas,
            ax + dirX * excludeRadius, ay + dirY * excludeRadius,
            dirX, dirY, left, top, right, bottom
        )
        drawDirection(
            canvas,
            ax - dirX * excludeRadius, ay - dirY * excludeRadius,
            -dirX, -dirY, left, top, right, bottom
        )
    }

    /** Walks one direction out from the anchor point, reflecting off the
     * table walls up to Tunables.maxLines total segments — ported from the
     * Manual app's per-direction bank-shot walk. */
    private fun drawDirection(
        canvas: Canvas,
        startX: Float, startY: Float,
        initDx: Float, initDy: Float,
        left: Float, top: Float, right: Float, bottom: Float
    ) {
        var dx = initDx
        var dy = initDy
        var curX = startX
        var curY = startY

        val maxTotalLength = ((right - left) + (bottom - top)) * 1.4f
        var remaining = maxTotalLength
        val maxLines = Tunables.maxLines

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
            drawSegLine(canvas, curX, curY, endX, endY, segBorder)
            drawSegLine(canvas, curX, curY, endX, endY, segCenter)

            if (Tunables.doubleLineEnabled) {
                val segDouble = if (segment == 0) doublePaint else bankDoublePaint
                val halfWidth = Tunables.doubleLineWidthPx / 2f
                val px = -dy * halfWidth
                val py = dx * halfWidth
                canvas.drawLine(curX + px, curY + py, endX + px, endY + py, segDouble)
                canvas.drawLine(curX - px, curY - py, endX - px, endY - py, segDouble)
            }

            remaining -= tDraw
            if (tDraw < tWall - 0.01f) break

            val hitVertical = tWall == tX

            if (Tunables.bankMarkerEnabled && segment + 1 < maxLines) {
                canvas.drawCircle(endX, endY, 10f, markerRing)
                canvas.drawCircle(endX, endY, 4f, markerDot)
            }

            val reflected = BankShot.reflect(dx, dy, hitVertical) ?: break
            dx = reflected[0]; dy = reflected[1]
            curX = endX; curY = endY
        }
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
}
