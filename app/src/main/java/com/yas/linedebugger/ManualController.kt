package com.yas.linedebugger

import android.app.Service
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.hypot
import kotlin.math.round

// Ported unchanged from the Manual app's (AimOverlay) OverlayService — same
// edge-of-screen drag clamp fraction and fixed drag-hitbox size. Only the
// *visual* diameter of the handles is tunable (Tunables.railGhostBallDiameterPx,
// shared with the automatic controller's bank-shot geometry); the hitbox
// itself was always a flat constant in the legacy app and stays one here.
private const val MANUAL_EDGE_LIMIT_FRACTION = 0.10f
private const val MANUAL_HANDLE_HITBOX_PX = 250

private const val MANUAL_DASH_LEN_PX = 26f
private const val MANUAL_DASH_GAP_PX = 16f

private enum class ManualRole { CUE, TARGET }

/**
 * Ported legacy manual CUE/TARGET controllers (from the Manual app's
 * AimOverlay project — OverlayService's Handle/DraggableHandle/LineOverlay
 * trio). The user drags two handles onto the cue ball and the object ball;
 * the resulting aim line walks the same bank-shot wall-reflection loop as
 * the automatic controller — same [BankShot.reflect] physics, same shared
 * table calibration ([Tunables.tableLeft] etc.), same shared rail-ghost-
 * ball diameter ([Tunables.railGhostBallDiameterPx]) — so a bank plays out
 * identically no matter which controller is active. Only the tweaks the
 * bug report calls out as manual-only (dashed line, double line width
 * offset, show rail ghost ball) — plus this controller's own drag
 * sensitivity and line look — live here and never touch the automatic
 * path in [OverlayController].
 *
 * Mutually exclusive with the automatic Ray Circle controller, switched
 * via [Tunables.manualModeEnabled] (see [OverlayController.setManualModeEnabled]).
 * Attached/detached alongside the automatic controller by [OverlayController].
 */
object ManualController {

    private var windowManager: WindowManager? = null
    private var screenWidth = 0
    private var screenHeight = 0

    private var drawView: ManualDrawView? = null
    private var cueHandle: ManualHandle? = null
    private var targetHandle: ManualHandle? = null

    @Volatile var cueX: Float = 0f
    @Volatile var cueY: Float = 0f
    @Volatile var targetX: Float = 0f
    @Volatile var targetY: Float = 0f

    fun attach(service: Service, wm: WindowManager, screenW: Int, screenH: Int) {
        windowManager = wm
        screenWidth = screenW
        screenHeight = screenH

        // Same default placement as the Manual app: cue near the bottom,
        // target above it.
        cueX = screenW * 0.5f
        cueY = screenH * 0.75f
        targetX = screenW * 0.5f
        targetY = screenH * 0.45f

        val dView = ManualDrawView(service)
        drawView = dView
        val drawParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        runCatching { wm.addView(dView, drawParams) }

        cueHandle = ManualHandle(service, wm, screenW, screenH, ManualRole.CUE, cueX, cueY) { x, y ->
            cueX = x; cueY = y; drawView?.invalidate()
        }
        targetHandle = ManualHandle(service, wm, screenW, screenH, ManualRole.TARGET, targetX, targetY) { x, y ->
            targetX = x; targetY = y; drawView?.invalidate()
        }
    }

    fun detach() {
        val wm = windowManager ?: return
        drawView?.let { runCatching { wm.removeView(it) } }
        cueHandle?.remove(); cueHandle = null
        targetHandle?.remove(); targetHandle = null
        drawView = null
        windowManager = null
    }

    /** Mirrors [OverlayController]'s Bug #1 visibility pattern: hidden
     * means both invisible AND non-touchable, so a hidden handle can never
     * swallow a drag meant for something underneath it. Visible only when
     * aim is on AND Manual is the active controller mode. */
    fun applyVisibility() {
        val visible = Tunables.aimVisible && Tunables.manualModeEnabled
        cueHandle?.setHidden(!visible)
        targetHandle?.setHidden(!visible)
        drawView?.invalidate()
    }

    /** Called by [OverlayController.onRailGhostBallDiameterChanged] when
     * the shared ghost-ball diameter slider moves — resizes both handles'
     * visuals to match, exactly like the Manual app's ballSizePx did. */
    fun onBallDiameterChanged(newDiameterPx: Float) {
        cueHandle?.setVisualDiameter(newDiameterPx)
        targetHandle?.setVisualDiameter(newDiameterPx)
        drawView?.invalidate()
    }

    fun requestRedraw() {
        drawView?.invalidate()
    }
}

/**
 * A single draggable handle window (cue or target ball marker). Ported
 * from the Manual app's Handle inner class — same drag-with-sensitivity
 * math and same edge-of-screen clamp (only the outer 10% of the handle may
 * hang off-screen).
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
    private val view = ManualHandleView(context, role)
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

        var lastRawX = 0f
        var lastRawY = 0f
        var exactX = 0f
        var exactY = 0f

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    exactX = params.x.toFloat()
                    exactY = params.y.toFloat()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val rawX = event.rawX
                    val rawY = event.rawY

                    exactX += (rawX - lastRawX) * Tunables.manualSensitivity
                    exactY += (rawY - lastRawY) * Tunables.manualSensitivity

                    val margin = MANUAL_HANDLE_HITBOX_PX * MANUAL_EDGE_LIMIT_FRACTION
                    val minX = -margin
                    val maxX = screenWidth - MANUAL_HANDLE_HITBOX_PX + margin
                    val minY = -margin
                    val maxY = screenHeight - MANUAL_HANDLE_HITBOX_PX + margin
                    if (exactX < minX) exactX = minX else if (exactX > maxX) exactX = maxX
                    if (exactY < minY) exactY = minY else if (exactY > maxY) exactY = maxY

                    params.x = round(exactX).toInt()
                    params.y = round(exactY).toInt()

                    lastRawX = rawX
                    lastRawY = rawY

                    runCatching { wm.updateViewLayout(v, params) }
                    onMoved(params.x + MANUAL_HANDLE_HITBOX_PX / 2f, params.y + MANUAL_HANDLE_HITBOX_PX / 2f)
                    true
                }
                else -> false
            }
        }

        runCatching { wm.addView(view, params) }
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

    fun setVisualDiameter(diameterPx: Float) {
        view.setVisualDiameterPx(diameterPx)
    }

    fun remove() {
        runCatching { wm.removeView(view) }
    }
}

/** Draws one handle's on-screen marker — ported from the Manual app's
 * DraggableHandle. The CUE handle draws a ball-sized outline ring plus a
 * red center dot (the point the aim line is measured from); the TARGET
 * handle draws only its translucent touch-region square, matching the
 * legacy app's actual rendered behavior exactly (its accent-color ring was
 * defined but never drawn there either). */
private class ManualHandleView(context: Context, private val role: ManualRole) : View(context) {
    private var visualDiameterPx: Float = Tunables.railGhostBallDiameterPx

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 3f
    }
    private val controllerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val centerDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.RED
    }

    init {
        when (role) {
            ManualRole.CUE -> {
                controllerFill.color = Color.RED
                controllerFill.alpha = 40
            }
            ManualRole.TARGET -> {
                controllerFill.color = Color.BLACK
                controllerFill.alpha = 45
            }
        }
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

        if (role == ManualRole.CUE) {
            val r = visualDiameterPx / 2f - outline.strokeWidth / 2f
            canvas.drawCircle(cx, cy, r, outline)
            canvas.drawCircle(cx, cy, 4f, centerDot)
        }
    }
}

/**
 * Renders the manual aim line — ported from the Manual app's LineOverlay,
 * adapted to the shared bank-shot ecosystem (BankShot.reflect, shared
 * table calibration, shared railGhostBallDiameterPx). The calibration
 * rectangle is intentionally NOT drawn here — [OverlayController]'s
 * DrawOverlayView already draws it (shared between both controllers), so
 * duplicating it here would double-draw it.
 */
private class ManualDrawView(context: Context) : View(context) {
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; strokeWidth = 7f; strokeCap = Paint.Cap.ROUND
    }
    private val center = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val bankBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; strokeWidth = 7f; strokeCap = Paint.Cap.ROUND
    }
    private val bankCenter = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val doubleLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = 2.5f; strokeCap = Paint.Cap.ROUND
    }
    private val bankDoubleLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY; strokeWidth = 2.5f; strokeCap = Paint.Cap.ROUND
    }
    private val ghostRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.BLACK; strokeWidth = 3f
    }
    private val ghostCenterDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.RED
    }

    // Manual-only: honors Tunables.manualDashedLineEnabled, never the
    // automatic controller's Tunables.dashedLineEnabled.
    private fun drawMainLine(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, paint: Paint) {
        if (Tunables.manualDashedLineEnabled) {
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
        val pattern = MANUAL_DASH_LEN_PX + MANUAL_DASH_GAP_PX

        var pos = 0f
        while (pos < length) {
            val segEnd = minOf(pos + MANUAL_DASH_LEN_PX, length)
            canvas.drawLine(x1 + ux * pos, y1 + uy * pos, x1 + ux * segEnd, y1 + uy * segEnd, paint)
            pos += pattern
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!Tunables.aimVisible || !Tunables.manualModeEnabled) return

        val alphaScale = Tunables.manualOpacity / 255f

        center.color = Tunables.manualLineColor
        center.strokeWidth = Tunables.manualLineWidthPx
        bankCenter.color = Tunables.manualLineColor
        bankCenter.strokeWidth = Tunables.manualLineWidthPx

        border.alpha = (255 * alphaScale).toInt()
        center.alpha = (255 * alphaScale).toInt()
        bankBorder.alpha = (150 * alphaScale).toInt()
        bankCenter.alpha = (190 * alphaScale).toInt()
        doubleLine.alpha = (100 * alphaScale).toInt()
        bankDoubleLine.alpha = (70 * alphaScale).toInt()
        ghostRing.alpha = (255 * alphaScale).toInt()
        ghostCenterDot.alpha = (255 * alphaScale).toInt()

        val cueX = ManualController.cueX
        val cueY = ManualController.cueY
        val targetX = ManualController.targetX
        val targetY = ManualController.targetY

        var dx = targetX - cueX
        var dy = targetY - cueY
        val len = hypot(dx, dy)
        if (len < 1f) return
        dx /= len
        dy /= len

        // Bug #3 fix, shared: same halfBallWidth rail inset the automatic
        // controller uses (Tunables.railGhostBallDiameterPx), so a bank
        // line always ends at the ghost ball's center on either controller.
        val halfBallWidth = Tunables.railGhostBallDiameterPx / 2f

        val calibrated = Tunables.tableLeft >= 0f
        val left: Float
        val top: Float
        val right: Float
        val bottom: Float
        if (calibrated) {
            left = Tunables.tableLeft + halfBallWidth
            top = Tunables.tableTop + halfBallWidth
            right = Tunables.tableRight - halfBallWidth
            bottom = Tunables.tableBottom - halfBallWidth
        } else {
            left = 0f; top = 0f; right = width.toFloat(); bottom = height.toFloat()
        }

        val maxTotalLength = ((right - left) + (bottom - top)) * 1.4f

        var curX = cueX
        var curY = cueY
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

            val segBorder = if (segment == 0) border else bankBorder
            val segCenter = if (segment == 0) center else bankCenter

            if (segment == 0 && len > halfBallWidth) {
                // Skip drawing inside the cue ball's own footprint —
                // start the visible line at its edge, not its center.
                val nearT = minOf(len - halfBallWidth, tDraw)
                drawMainLine(canvas, curX, curY, curX + dx * nearT, curY + dy * nearT, segBorder)
                drawMainLine(canvas, curX, curY, curX + dx * nearT, curY + dy * nearT, segCenter)

                // ...and inside the target ball's footprint too.
                val farT = len + halfBallWidth
                if (tDraw > farT) {
                    val farX = curX + dx * farT
                    val farY = curY + dy * farT
                    drawMainLine(canvas, farX, farY, endX, endY, segBorder)
                    drawMainLine(canvas, farX, farY, endX, endY, segCenter)
                }
            } else {
                drawMainLine(canvas, curX, curY, endX, endY, segBorder)
                drawMainLine(canvas, curX, curY, endX, endY, segCenter)
            }

            if (Tunables.doubleLineEnabled) {
                val segDouble = if (segment == 0) doubleLine else bankDoubleLine
                // Manual-only: ball-radius-relative OFFSET (legacy
                // semantics), never the automatic controller's absolute
                // Tunables.doubleLineWidthPx.
                var doubleHalfWidth = halfBallWidth - (Tunables.manualDoubleLineWidthOffsetPx / 2f)
                if (doubleHalfWidth < 0f) doubleHalfWidth = 0f
                val px = -dy * doubleHalfWidth
                val py = dx * doubleHalfWidth
                canvas.drawLine(curX + px, curY + py, endX + px, endY + py, segDouble)
                canvas.drawLine(curX - px, curY - py, endX - px, endY - py, segDouble)
            }

            remaining -= tDraw
            if (tDraw < tWall - 0.01f) break

            val hitVertical = tWall == tX

            // Manual-only toggle: Tunables.manualShowRailGhostBall, never
            // the automatic controller's Tunables.bankMarkerEnabled.
            if (Tunables.manualShowRailGhostBall && segment + 1 < maxLines) {
                canvas.drawCircle(endX, endY, halfBallWidth - ghostRing.strokeWidth / 2f, ghostRing)
                canvas.drawCircle(endX, endY, 4f, ghostCenterDot)
            }

            val reflected = BankShot.reflect(dx, dy, hitVertical) ?: break
            dx = reflected[0]; dy = reflected[1]
            curX = endX; curY = endY
        }
    }
}
