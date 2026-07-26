// ============================================================
// FILE: app/src/main/java/com/yas/linedebugger/OverlayController.kt
// FULL REPLACEMENT – coordinate-space fix (FLAG_LAYOUT_IN_SCREEN + real metrics)
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
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object OverlayController {

    @Volatile var circleCenterX: Int = 400
    @Volatile var circleCenterY: Int = 800
    @Volatile var lastResult: DetectionResult? = null

    private var windowManager: WindowManager? = null
    private var drawView: DrawOverlayView? = null
    private var handleView: View? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var panelView: View? = null

    private const val OVERLAY_TYPE = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    /**
     * Force every overlay window into the exact same full-physical-display
     * coordinate space that MediaProjection (sized with getRealMetrics) uses.
     * FLAG_LAYOUT_IN_SCREEN is the critical missing piece that was allowing a
     * fixed origin offset between capture buffer and overlay canvas.
     */
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

        // Use the same real metrics that CaptureService will use for VirtualDisplay
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

        // handleParams MUST resolve (0,0) to the same physical pixel as drawParams.
        // Both now share FLAG_LAYOUT_IN_SCREEN + FLAG_LAYOUT_NO_LIMITS + cutout ALWAYS.
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

    fun updateResult(result: DetectionResult) {
        lastResult = result
        drawView?.invalidate()
    }

    fun detach() {
        val wm = windowManager ?: return
        drawView?.let { runCatching { wm.removeView(it) } }
        handleView?.let { runCatching { wm.removeView(it) } }
        panelView?.let { runCatching { wm.removeView(it) } }
        drawView = null; handleView = null; panelView = null; windowManager = null
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
    // Diagnostic only: marks (ax,ay), the exact point onDraw thinks the detected
    // line passes through. If this dot doesn't sit on the real guideline, the bug
    // is in this window's coordinate space, not in LineDetector's angle/point math.
    private val anchorPaint = Paint().apply {
        color = 0xFF00E5FF.toInt()
        style = Paint.Style.FILL
        isAntiAlias = true
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
            // Screen-space medial axis point
            val ax = cx - half + result.offsetX
            val ay = cy - half + result.offsetY

            linePaint.color = result.colorArgb
            linePaint.strokeWidth = (result.widthPx * Tunables.widthMultiplier).coerceAtLeast(2f)

            if (Tunables.showDebugPreview) {
                canvas.drawCircle(ax, ay, 10f, anchorPaint)
            }

            val dx = cos(result.angleRad).toFloat()
            val dy = sin(result.angleRad).toFloat()

            // ============================================================
            // SELF-RAY EXCLUSION
            // Never draw the overlay ray inside the controller zone + \~50%
            // margin. This keeps the crop under the circle clean so we
            // only ever see the real game guideline.
            // ============================================================
            val excludeRadius = half * 1.50f          // controller radius + 50%
            val reach = 4000f

            // Distance from axis point (ax,ay) to circle center (cx,cy)
            val toCx = cx - ax
            val toCy = cy - ay
            val distToCenter = sqrt(toCx * toCx + toCy * toCy)

            // We start drawing only outside the exclusion circle.
            // Project the exclusion along the ray direction.
            val startDist = if (distToCenter < 1e-3f) {
                excludeRadius
            } else {
                // Conservative: always start at least excludeRadius away from the axis point
                // in both directions (guarantees the whole controller zone stays clear)
                excludeRadius
            }

            // Positive direction
            canvas.drawLine(
                ax + dx * startDist,
                ay + dy * startDist,
                ax + dx * reach,
                ay + dy * reach,
                linePaint
            )
            // Negative direction
            canvas.drawLine(
                ax - dx * startDist,
                ay - dy * startDist,
                ax - dx * reach,
                ay - dy * reach,
                linePaint
            )

            val deg = Math.toDegrees(result.angleRad)
            canvas.drawText(
                "angle=%.1f  px=%d  w=%.1f  ax=%.0f ay=%.0f".format(
                    deg, result.pixelCount, result.widthPx, ax, ay
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
