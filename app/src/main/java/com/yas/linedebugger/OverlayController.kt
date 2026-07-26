// ============================================================
// FILE: app/src/main/java/com/yas/linedebugger/OverlayController.kt
// FULL REPLACEMENT – less sticky lock (fast switch when you drag to a new line)
// ============================================================
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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object OverlayController {

    @Volatile var circleCenterX: Int = 400
    @Volatile var circleCenterY: Int = 800
    @Volatile var lastResult: DetectionResult? = null

    // --- Candidate lock (anti-blink, but switches fast on big angle change) ---
    private var lockedResult: DetectionResult? = null
    private var lockHoldFrames: Int = 0
    private const val LOCK_HOLD = 6            // frames to keep a good lock on the *same* line
    private const val ANGLE_TOL = 0.18         // \~10 degrees – considered “same line”

    private var windowManager: WindowManager? = null
    private var drawView: DrawOverlayView? = null
    private var handleView: View? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var panelView: View? = null

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

    fun attach(service: Service) {
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val realMetrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(realMetrics)
        circleCenterX = realMetrics.widthPixels / 2
        circleCenterY = realMetrics.heightPixels / 2

        val dView = DrawOverlayView(service)
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

        val half = Tunables.circleDiameter / 2
        val hParams = WindowManager.LayoutParams(
            Tunables.circleDiameter,
            Tunables.circleDiameter,
            OVERLAY_TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = circleCenterX - half
            y = circleCenterY - half
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
                    hParams.x = downX + (event.rawX - downRawX).toInt()
                    hParams.y = downY + (event.rawY - downRawY).toInt()
                    wm.updateViewLayout(v, hParams)
                    circleCenterX = hParams.x + Tunables.circleDiameter / 2
                    circleCenterY = hParams.y + Tunables.circleDiameter / 2
                    dView.invalidate()
                    true
                }
                else -> false
            }
        }
        handleView = hView
        wm.addView(hView, hParams)

        buildPanel(service, wm)
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
        val slidersBox = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(slidersBox)

        addSlider(slidersBox, "green diff", 0, 60, Tunables.greenDiff) { Tunables.greenDiff = it }
        addSlider(slidersBox, "min brightness", 0, 120, Tunables.minBrightness) { Tunables.minBrightness = it }
        addSlider(slidersBox, "ball erode r", 1, 25, Tunables.ballErodeRadius) { Tunables.ballErodeRadius = it }
        addSlider(slidersBox, "ball grow", 0, 25, Tunables.ballDilateGrow) { Tunables.ballDilateGrow = it }
        addSlider(slidersBox, "min line px", 1, 150, Tunables.minLinePixels) { Tunables.minLinePixels = it }
        addSlider(slidersBox, "trim K x10", 5, 60, (Tunables.outlierTrimK * 10).toInt()) { Tunables.outlierTrimK = it / 10f }
        addSlider(slidersBox, "circle diam", 40, 220, Tunables.circleDiameter) {
            Tunables.circleDiameter = it
            handleParams?.let { p ->
                p.width = it; p.height = it
                runCatching { windowManager?.updateViewLayout(handleView, p) }
            }
        }
        addSlider(slidersBox, "ray width x10", 5, 100, (Tunables.widthMultiplier * 10).toInt()) { Tunables.widthMultiplier = it / 10f }
        addSlider(slidersBox, "circle alpha", 20, 255, Tunables.circleAlpha) { Tunables.circleAlpha = it }

        panel.addView(scroll)

        val previewToggle = Button(service).apply { text = "preview: on" }
        previewToggle.setOnClickListener {
            Tunables.showDebugPreview = !Tunables.showDebugPreview
            previewToggle.text = if (Tunables.showDebugPreview) "preview: on" else "preview: off"
        }
        panel.addView(previewToggle)

        val pParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            700,
            OVERLAY_TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

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
    }

    private fun addSlider(container: LinearLayout, label: String, min: Int, max: Int, initial: Int, onChange: (Int) -> Unit) {
        val ctx = container.context
        val tv = TextView(ctx).apply { text = "$label: $initial"; setTextColor(Color.WHITE) }
        container.addView(tv)
        val sb = SeekBar(ctx).apply {
            this.max = (max - min).coerceAtLeast(1)
            progress = (initial - min).coerceIn(0, this.max)
        }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, p: Int, fromUser: Boolean) {
                val v = p + min
                tv.text = "$label: $v"
                onChange(v)
                drawView?.invalidate()
            }
            override fun onStartTrackingTouch(seek: SeekBar?) {}
            override fun onStopTrackingTouch(seek: SeekBar?) {}
        })
        container.addView(sb)
    }

    /**
     * Anti-blink lock that still switches quickly when you drag onto a different line.
     * - Same / similar angle → keep updating the lock (smooth)
     * - Big angle change (> \~20°) → switch immediately and use a short hold
     * - Temporary loss of line → keep previous lock for a few frames only
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
                    angleDiff(prev.angleRad, result.angleRad) > 0.35   // \~20 degrees

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
        drawView = null; handleView = null; panelView = null; windowManager = null
        lockedResult = null
        lockHoldFrames = 0
    }
}

class DrawOverlayView(context: Context) : View(context) {
    private val circlePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
        isAntiAlias = true
    }
    private val linePaint = Paint().apply {
        style = Paint.Style.STROKE
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = OverlayController.circleCenterX.toFloat()
        val cy = OverlayController.circleCenterY.toFloat()
        val d = Tunables.circleDiameter.toFloat()
        val half = d / 2f
        val result = OverlayController.lastResult

        if (Tunables.showDebugPreview && result != null && result.previewArgb.isNotEmpty()) {
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
            val ax = cx - half + result.offsetX
            val ay = cy - half + result.offsetY

            linePaint.color = result.colorArgb
            linePaint.strokeWidth = (result.widthPx * Tunables.widthMultiplier).coerceAtLeast(2f)

            val dx = cos(result.angleRad).toFloat()
            val dy = sin(result.angleRad).toFloat()

            val excludeRadius = half * 1.50f
            val reach = 4000f
            val startDist = excludeRadius

            canvas.drawLine(
                ax + dx * startDist, ay + dy * startDist,
                ax + dx * reach, ay + dy * reach,
                linePaint
            )
            canvas.drawLine(
                ax - dx * startDist, ay - dy * startDist,
                ax - dx * reach, ay - dy * reach,
                linePaint
            )

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

        circlePaint.alpha = Tunables.circleAlpha
        canvas.drawCircle(cx, cy, half, circlePaint)
    }
}
