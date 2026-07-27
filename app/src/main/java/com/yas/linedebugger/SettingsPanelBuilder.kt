package com.yas.linedebugger

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Builds the full set of tweak controls exactly once, so the in-app
 * settings screen (MainActivity) and the floating tweak panel
 * (OverlayController) always show the same controls wired to the same
 * persisted values — no drift between the two.
 *
 * [onChanged] is called after every user edit so the caller can trigger a
 * redraw of the live overlay (a no-op if it isn't running yet).
 * [onCalibrate], if non-null, adds a "Calibrate Table" button.
 */
object SettingsPanelBuilder {

    fun build(context: Context, onChanged: () -> Unit, onCalibrate: (() -> Unit)?): LinearLayout {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val density = context.resources.displayMetrics.density

        fun sectionLabel(text: String) {
            root.addView(TextView(context).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(0, (24 * density).toInt(), 0, (4 * density).toInt())
            })
        }

        fun hint(text: String) {
            root.addView(TextView(context).apply {
                this.text = text
                setTextColor(0xFFAAAAAA.toInt())
                textSize = 12f
            })
        }

        fun intSlider(label: String, min: Int, max: Int, initial: Int, onSet: (Int) -> Unit) {
            val tv = TextView(context).apply { text = "$label: $initial"; setTextColor(Color.WHITE) }
            root.addView(tv)
            val sb = SeekBar(context).apply {
                this.max = (max - min).coerceAtLeast(1)
                progress = (initial - min).coerceIn(0, this.max)
            }
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seek: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p + min
                    tv.text = "$label: $v"
                    if (fromUser) { onSet(v); onChanged() }
                }
                override fun onStartTrackingTouch(seek: SeekBar?) {}
                override fun onStopTrackingTouch(seek: SeekBar?) {}
            })
            root.addView(sb)
        }

        fun floatSlider(
            label: String, min: Float, max: Float, initial: Float, steps: Int,
            format: (Float) -> String, onSet: (Float) -> Unit
        ) {
            val tv = TextView(context).apply { text = "$label: ${format(initial)}"; setTextColor(Color.WHITE) }
            root.addView(tv)
            val sb = SeekBar(context).apply {
                this.max = steps
                progress = Math.round((initial - min) / (max - min) * steps).coerceIn(0, steps)
            }
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seek: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = min + (max - min) * (p / steps.toFloat())
                    tv.text = "$label: ${format(v)}"
                    if (fromUser) { onSet(v); onChanged() }
                }
                override fun onStartTrackingTouch(seek: SeekBar?) {}
                override fun onStopTrackingTouch(seek: SeekBar?) {}
            })
            root.addView(sb)
        }

        fun checkbox(label: String, initial: Boolean, onSet: (Boolean) -> Unit) {
            val cb = CheckBox(context).apply {
                text = label
                setTextColor(Color.WHITE)
                isChecked = initial
            }
            cb.setOnCheckedChangeListener { _, checked -> onSet(checked); onChanged() }
            root.addView(cb)
        }

        // ==================== Detection ====================
        sectionLabel("Detection")
        intSlider("Green diff", AutoAimPrefs.GREEN_DIFF_MIN, AutoAimPrefs.GREEN_DIFF_MAX, Tunables.greenDiff) {
            Tunables.greenDiff = it; AutoAimPrefs.setGreenDiff(it)
        }
        intSlider("Min brightness", AutoAimPrefs.MIN_BRIGHTNESS_MIN, AutoAimPrefs.MIN_BRIGHTNESS_MAX, Tunables.minBrightness) {
            Tunables.minBrightness = it; AutoAimPrefs.setMinBrightness(it)
        }
        intSlider("Ball erode radius", AutoAimPrefs.BALL_ERODE_RADIUS_MIN, AutoAimPrefs.BALL_ERODE_RADIUS_MAX, Tunables.ballErodeRadius) {
            Tunables.ballErodeRadius = it; AutoAimPrefs.setBallErodeRadius(it)
        }
        intSlider("Ball grow", AutoAimPrefs.BALL_DILATE_GROW_MIN, AutoAimPrefs.BALL_DILATE_GROW_MAX, Tunables.ballDilateGrow) {
            Tunables.ballDilateGrow = it; AutoAimPrefs.setBallDilateGrow(it)
        }
        intSlider("Min line px", AutoAimPrefs.MIN_LINE_PIXELS_MIN, AutoAimPrefs.MIN_LINE_PIXELS_MAX, Tunables.minLinePixels) {
            Tunables.minLinePixels = it; AutoAimPrefs.setMinLinePixels(it)
        }
        floatSlider(
            "Outlier trim K", AutoAimPrefs.OUTLIER_TRIM_K_MIN, AutoAimPrefs.OUTLIER_TRIM_K_MAX,
            Tunables.outlierTrimK, 55, { "%.1f".format(it) }
        ) { Tunables.outlierTrimK = it; AutoAimPrefs.setOutlierTrimK(it) }

        // ==================== Ray Circle ====================
        sectionLabel("Ray Circle")
        intSlider("Circle diameter", AutoAimPrefs.CIRCLE_DIAMETER_MIN, AutoAimPrefs.CIRCLE_DIAMETER_MAX, Tunables.circleDiameter) {
            Tunables.circleDiameter = it
            AutoAimPrefs.setCircleDiameter(it)
            OverlayController.onCircleDiameterChanged(it)
        }
        intSlider("Circle alpha", AutoAimPrefs.CIRCLE_ALPHA_MIN, AutoAimPrefs.CIRCLE_ALPHA_MAX, Tunables.circleAlpha) {
            Tunables.circleAlpha = it; AutoAimPrefs.setCircleAlpha(it)
        }

        // ==================== Auto Aim line look ====================
        sectionLabel("Auto Aim Line")
        hint("Width / opacity / color are manual now — no longer set from the detected ball.")
        floatSlider(
            "Line width", AutoAimPrefs.AUTO_AIM_WIDTH_MIN_PX, AutoAimPrefs.AUTO_AIM_WIDTH_MAX_PX,
            Tunables.autoAimWidthPx, 90, { "%.1f px".format(it) }
        ) { Tunables.autoAimWidthPx = it; AutoAimPrefs.setAutoAimWidthPx(it) }
        intSlider("Line opacity", AutoAimPrefs.AUTO_AIM_OPACITY_MIN, AutoAimPrefs.AUTO_AIM_OPACITY_MAX, Tunables.autoAimOpacity) {
            Tunables.autoAimOpacity = it; AutoAimPrefs.setAutoAimOpacity(it)
        }

        val swatches = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        fun swatch(color: Int) {
            val dp = (40 * density).toInt()
            swatches.addView(Button(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp, dp).apply { marginEnd = (8 * density).toInt() }
                minimumWidth = 0
                minimumHeight = 0
                backgroundTintList = ColorStateList.valueOf(color)
                text = ""
                setOnClickListener {
                    Tunables.autoAimColor = color
                    AutoAimPrefs.setAutoAimColor(color)
                    onChanged()
                }
            })
        }
        swatch(Color.WHITE); swatch(Color.YELLOW); swatch(Color.CYAN)
        swatch(Color.GREEN); swatch(Color.rgb(255, 140, 0)); swatch(Color.MAGENTA)
        root.addView(swatches)

        // ==================== Bank Shot ====================
        sectionLabel("Bank Shot")
        intSlider("Max lines (total segments)", AutoAimPrefs.MAX_LINES_MIN, AutoAimPrefs.MAX_LINES_MAX, Tunables.maxLines) {
            Tunables.maxLines = it; AutoAimPrefs.setMaxLines(it)
        }
        checkbox("Double line", Tunables.doubleLineEnabled) {
            Tunables.doubleLineEnabled = it; AutoAimPrefs.setDoubleLineEnabled(it)
        }
        hint("No ball-size baseline here, so this is an absolute width — wide range on purpose.")
        floatSlider(
            "Double line width", AutoAimPrefs.DOUBLE_LINE_WIDTH_MIN_PX, AutoAimPrefs.DOUBLE_LINE_WIDTH_MAX_PX,
            Tunables.doubleLineWidthPx, 200, { "%.0f px".format(it) }
        ) { Tunables.doubleLineWidthPx = it; AutoAimPrefs.setDoubleLineWidthPx(it) }
        checkbox("Dashed line", Tunables.dashedLineEnabled) {
            Tunables.dashedLineEnabled = it; AutoAimPrefs.setDashedLineEnabled(it)
        }
        checkbox("Show bank point marker", Tunables.bankMarkerEnabled) {
            Tunables.bankMarkerEnabled = it; AutoAimPrefs.setBankMarkerEnabled(it)
        }

        sectionLabel("Bank Correction Curve")
        hint("Range: -50° to 40°. 90° (dead-on) is fixed at 0 and not adjustable.")
        val bankSteps = Math.round((AutoAimPrefs.BANK_CORRECTION_MAX - AutoAimPrefs.BANK_CORRECTION_MIN) / 0.1f)
        for (i in AutoAimPrefs.BANK_ANGLES.indices) {
            val idx = i
            val angleLabel = AutoAimPrefs.BANK_ANGLES[i].toInt()
            floatSlider(
                "Correction @ ${angleLabel}°", AutoAimPrefs.BANK_CORRECTION_MIN, AutoAimPrefs.BANK_CORRECTION_MAX,
                AutoAimPrefs.getBankCorrection(idx), bankSteps, { "%.1f°".format(it) }
            ) { v ->
                AutoAimPrefs.setBankCorrection(idx, v)
                AutoAimPrefs.pushBankCurve()
            }
        }
        floatSlider(
            "Rebound intensity", AutoAimPrefs.REBOUND_INTENSITY_MIN, AutoAimPrefs.REBOUND_INTENSITY_MAX,
            AutoAimPrefs.getReboundIntensity(), 200, { "${it.toInt()}%" }
        ) { v ->
            AutoAimPrefs.setReboundIntensity(v)
            AutoAimPrefs.pushBankCurve()
        }

        // ==================== Visibility ====================
        sectionLabel("Visibility")
        checkbox("Ray Monitor (pixel preview + status text)", Tunables.rayMonitorEnabled) {
            Tunables.rayMonitorEnabled = it; AutoAimPrefs.setRayMonitorEnabled(it)
        }

        if (onCalibrate != null) {
            root.addView(Button(context).apply {
                text = "Calibrate Table"
                setOnClickListener { onCalibrate() }
            })
        }

        return root
    }
}
