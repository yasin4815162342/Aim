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
 * Builds the tweak controls. [build] is the original, always-on set shown
 * in BOTH the in-app settings screen (MainActivity) and the floating tweak
 * panel (OverlayController) — unchanged, so there's no drift between the
 * two. [buildExtras] is new: the ghost-ball / color-detection / manual-
 * controller tweaks added in this pass. Per the bug report ("floating
 * panel is already at capacity; place every new control exclusively in
 * the main application UI"), [buildExtras] must ONLY ever be called from
 * MainActivity — never from the floating panel.
 *
 * [onChanged] is called after every user edit so the caller can trigger a
 * redraw of the live overlay (a no-op if it isn't running yet).
 * [onCalibrate], if non-null, adds a "Calibrate Table" button.
 */
object SettingsPanelBuilder {

    fun build(context: Context, onChanged: () -> Unit, onCalibrate: (() -> Unit)?): LinearLayout {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val density = context.resources.displayMetrics.density
        val ui = ControlFactory(context, root, density, onChanged)

        // ==================== Detection ====================
        ui.sectionLabel("Detection")
        ui.intSlider("Green diff", AutoAimPrefs.GREEN_DIFF_MIN, AutoAimPrefs.GREEN_DIFF_MAX, Tunables.greenDiff) {
            Tunables.greenDiff = it; AutoAimPrefs.setGreenDiff(it)
        }
        ui.hint("Green pixels at or above this brightness count as guideline, not felt — raise this if a green-ball line isn't showing. Only used by the Legacy Green-Diff color mode below.")
        ui.intSlider(
            "Green line brightness", AutoAimPrefs.GREEN_LINE_BRIGHTNESS_MIN, AutoAimPrefs.GREEN_LINE_BRIGHTNESS_MAX,
            Tunables.greenLineBrightness
        ) {
            Tunables.greenLineBrightness = it; AutoAimPrefs.setGreenLineBrightness(it)
        }
        ui.intSlider("Min brightness", AutoAimPrefs.MIN_BRIGHTNESS_MIN, AutoAimPrefs.MIN_BRIGHTNESS_MAX, Tunables.minBrightness) {
            Tunables.minBrightness = it; AutoAimPrefs.setMinBrightness(it)
        }
        ui.intSlider("Ball erode radius", AutoAimPrefs.BALL_ERODE_RADIUS_MIN, AutoAimPrefs.BALL_ERODE_RADIUS_MAX, Tunables.ballErodeRadius) {
            Tunables.ballErodeRadius = it; AutoAimPrefs.setBallErodeRadius(it)
        }
        ui.intSlider("Ball grow", AutoAimPrefs.BALL_DILATE_GROW_MIN, AutoAimPrefs.BALL_DILATE_GROW_MAX, Tunables.ballDilateGrow) {
            Tunables.ballDilateGrow = it; AutoAimPrefs.setBallDilateGrow(it)
        }
        ui.intSlider("Min line px", AutoAimPrefs.MIN_LINE_PIXELS_MIN, AutoAimPrefs.MIN_LINE_PIXELS_MAX, Tunables.minLinePixels) {
            Tunables.minLinePixels = it; AutoAimPrefs.setMinLinePixels(it)
        }
        ui.floatSlider(
            "Outlier trim K", AutoAimPrefs.OUTLIER_TRIM_K_MIN, AutoAimPrefs.OUTLIER_TRIM_K_MAX,
            Tunables.outlierTrimK, 55, { "%.1f".format(it) }
        ) { Tunables.outlierTrimK = it; AutoAimPrefs.setOutlierTrimK(it) }

        // ==================== Ray Circle ====================
        ui.sectionLabel("Ray Circle")
        ui.intSlider("Circle diameter", AutoAimPrefs.CIRCLE_DIAMETER_MIN, AutoAimPrefs.CIRCLE_DIAMETER_MAX, Tunables.circleDiameter) {
            Tunables.circleDiameter = it
            AutoAimPrefs.setCircleDiameter(it)
            OverlayController.onCircleDiameterChanged(it)
        }
        ui.intSlider("Circle alpha", AutoAimPrefs.CIRCLE_ALPHA_MIN, AutoAimPrefs.CIRCLE_ALPHA_MAX, Tunables.circleAlpha) {
            Tunables.circleAlpha = it; AutoAimPrefs.setCircleAlpha(it)
        }

        // ==================== Auto Aim line look ====================
        ui.sectionLabel("Auto Aim Line")
        ui.hint("Width / opacity / color are manual now — no longer set from the detected ball.")
        ui.floatSlider(
            "Line width", AutoAimPrefs.AUTO_AIM_WIDTH_MIN_PX, AutoAimPrefs.AUTO_AIM_WIDTH_MAX_PX,
            Tunables.autoAimWidthPx, 90, { "%.1f px".format(it) }
        ) { Tunables.autoAimWidthPx = it; AutoAimPrefs.setAutoAimWidthPx(it) }
        ui.intSlider("Line opacity", AutoAimPrefs.AUTO_AIM_OPACITY_MIN, AutoAimPrefs.AUTO_AIM_OPACITY_MAX, Tunables.autoAimOpacity) {
            Tunables.autoAimOpacity = it; AutoAimPrefs.setAutoAimOpacity(it)
        }
        ui.colorSwatches(
            listOf(Color.WHITE, Color.YELLOW, Color.CYAN, Color.GREEN, Color.rgb(255, 140, 0), Color.MAGENTA)
        ) { color -> Tunables.autoAimColor = color; AutoAimPrefs.setAutoAimColor(color) }

        // ==================== Bank Shot ====================
        ui.sectionLabel("Bank Shot")
        ui.intSlider("Max lines (total segments)", AutoAimPrefs.MAX_LINES_MIN, AutoAimPrefs.MAX_LINES_MAX, Tunables.maxLines) {
            Tunables.maxLines = it; AutoAimPrefs.setMaxLines(it)
        }
        ui.checkbox("Double line", Tunables.doubleLineEnabled) {
            Tunables.doubleLineEnabled = it; AutoAimPrefs.setDoubleLineEnabled(it)
        }
        ui.hint("No ball-size baseline here, so this is an absolute width — wide range on purpose.")
        ui.floatSlider(
            "Double line width", AutoAimPrefs.DOUBLE_LINE_WIDTH_MIN_PX, AutoAimPrefs.DOUBLE_LINE_WIDTH_MAX_PX,
            Tunables.doubleLineWidthPx, 200, { "%.0f px".format(it) }
        ) { Tunables.doubleLineWidthPx = it; AutoAimPrefs.setDoubleLineWidthPx(it) }
        ui.checkbox("Dashed line", Tunables.dashedLineEnabled) {
            Tunables.dashedLineEnabled = it; AutoAimPrefs.setDashedLineEnabled(it)
        }
        ui.checkbox("Show bank point marker", Tunables.bankMarkerEnabled) {
            Tunables.bankMarkerEnabled = it; AutoAimPrefs.setBankMarkerEnabled(it)
        }

        ui.sectionLabel("Bank Correction Curve")
        ui.hint("Range: -50° to 40°. 90° (dead-on) is fixed at 0 and not adjustable.")
        val bankStepSize = 0.1f
        val bankSteps = Math.round((AutoAimPrefs.BANK_CORRECTION_MAX - AutoAimPrefs.BANK_CORRECTION_MIN) / bankStepSize)
        for (i in AutoAimPrefs.BANK_ANGLES.indices) {
            val idx = i
            val angleLabel = AutoAimPrefs.BANK_ANGLES[i].toInt()
            ui.correctionSlider(
                "Correction @ ${angleLabel}°", AutoAimPrefs.BANK_CORRECTION_MIN, AutoAimPrefs.BANK_CORRECTION_MAX,
                AutoAimPrefs.getBankCorrection(idx), bankSteps, bankStepSize, { "%.1f°".format(it) }
            ) { v ->
                AutoAimPrefs.setBankCorrection(idx, v)
                AutoAimPrefs.pushBankCurve()
            }
        }
        ui.floatSlider(
            "Rebound intensity", AutoAimPrefs.REBOUND_INTENSITY_MIN, AutoAimPrefs.REBOUND_INTENSITY_MAX,
            AutoAimPrefs.getReboundIntensity(), 200, { "${it.toInt()}%" }
        ) { v ->
            AutoAimPrefs.setReboundIntensity(v)
            AutoAimPrefs.pushBankCurve()
        }

        // ==================== Visibility ====================
        ui.sectionLabel("Visibility")
        ui.checkbox("Ray Monitor (pixel preview + status text)", Tunables.rayMonitorEnabled) {
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

    /**
     * New tweaks added in this pass: color-detection mode (green/brown/
     * yellow guideline recovery), the shared rail-ghost-ball diameter
     * (bug #3), the controller-mode switch, and the ported manual
     * CUE/TARGET controller's own settings. In-app UI ONLY — call this
     * from MainActivity and nowhere else.
     */
    fun buildExtras(context: Context, onChanged: () -> Unit): LinearLayout {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val density = context.resources.displayMetrics.density
        val ui = ControlFactory(context, root, density, onChanged)

        // ==================== Color Detection Mode ====================
        ui.sectionLabel("Color Detection Mode")
        ui.hint("Fixes green/brown/yellow guidelines going undetected. Adaptive samples the felt's real hue+brightness each frame instead of assuming green, so every guideline color (including green-on-green) is treated the same way red always worked.")
        ui.modeSelector(
            listOf(
                "Legacy Green-Diff" to AutoAimPrefs.COLOR_MODE_LEGACY_GREEN_DIFF,
                "Adaptive (default)" to AutoAimPrefs.COLOR_MODE_ADAPTIVE_FELT_SAMPLE,
                "Manual Hue" to AutoAimPrefs.COLOR_MODE_MANUAL_HUE
            ),
            Tunables.colorDetectionMode
        ) { mode -> Tunables.colorDetectionMode = mode; AutoAimPrefs.setColorDetectionMode(mode) }

        ui.intSlider(
            "Felt hue tolerance", AutoAimPrefs.FELT_HUE_TOLERANCE_MIN_DEG.toInt(), AutoAimPrefs.FELT_HUE_TOLERANCE_MAX_DEG.toInt(),
            Tunables.feltHueToleranceDeg.toInt()
        ) { Tunables.feltHueToleranceDeg = it.toFloat(); AutoAimPrefs.setFeltHueToleranceDeg(it.toFloat()) }
        ui.hint("Degrees on the hue wheel. Lower = stricter (fewer false positives on brown/red); raise if a hue-distinct guideline still isn't showing.")

        ui.intSlider(
            "Felt brightness differential", AutoAimPrefs.FELT_BRIGHTNESS_DIFF_MIN, AutoAimPrefs.FELT_BRIGHTNESS_DIFF_MAX,
            Tunables.feltBrightnessDiff
        ) { Tunables.feltBrightnessDiff = it; AutoAimPrefs.setFeltBrightnessDiff(it) }
        ui.hint("How much brighter/darker than the felt a same-hue pixel must be to count as guideline. This is what recovers a green-ball guideline on green felt.")

        ui.intSlider(
            "Felt sampling min saturation", AutoAimPrefs.FELT_SATURATION_MIN_MIN, AutoAimPrefs.FELT_SATURATION_MIN_MAX,
            Tunables.feltSaturationMin
        ) { Tunables.feltSaturationMin = it; AutoAimPrefs.setFeltSaturationMin(it) }
        ui.hint("Adaptive mode only. Near-gray pixels below this saturation are excluded when sampling the felt's hue (their hue is unreliable).")

        ui.intSlider(
            "Manual felt hue", AutoAimPrefs.MANUAL_FELT_HUE_MIN_DEG.toInt(), AutoAimPrefs.MANUAL_FELT_HUE_MAX_DEG.toInt(),
            Tunables.manualFeltHueDeg.toInt()
        ) { Tunables.manualFeltHueDeg = it.toFloat(); AutoAimPrefs.setManualFeltHueDeg(it.toFloat()) }
        ui.hint("Manual Hue mode only. 0/360=red, 120=green, 240=blue — dial this to your actual felt color if Adaptive mis-samples.")

        // ==================== Rail Ghost Ball (shared, bug #3) ====================
        ui.sectionLabel("Rail Ghost Ball")
        ui.hint("Object-ball diameter shared by BOTH controllers. Fixes the bank-shot bug where the angle line ended at the raw table edge instead of the ghost ball's center — this is what the line now reflects around.")
        ui.floatSlider(
            "Ball diameter", AutoAimPrefs.RAIL_GHOST_BALL_DIAMETER_MIN_PX, AutoAimPrefs.RAIL_GHOST_BALL_DIAMETER_MAX_PX,
            Tunables.railGhostBallDiameterPx, 100, { "%.0f px".format(it) }
        ) {
            Tunables.railGhostBallDiameterPx = it
            AutoAimPrefs.setRailGhostBallDiameterPx(it)
            OverlayController.onRailGhostBallDiameterChanged(it)
        }

        // ==================== Controller Mode ====================
        ui.sectionLabel("Controller Mode")
        ui.hint("Automatic and Manual are mutually exclusive — switching hides/disables the other so they never fight over the same drag input.")
        ui.checkbox("Use Manual CUE/TARGET controller", Tunables.manualModeEnabled) {
            OverlayController.setManualModeEnabled(it)
        }

        // ==================== Manual Controller ====================
        // Ported from the Manual app (AimOverlay project). Table
        // calibration, Max lines, Double line (on/off), and the Bank
        // Correction Curve above are all SHARED with the automatic
        // controller. Only these tweaks — and the ones explicitly called
        // out below — are manual-only and never touch automatic aim.
        ui.sectionLabel("Manual Controller")
        ui.floatSlider(
            "Drag sensitivity", AutoAimPrefs.MANUAL_SENSITIVITY_MIN, AutoAimPrefs.MANUAL_SENSITIVITY_MAX,
            Tunables.manualSensitivity, 70, { "%.2f".format(it) }
        ) { Tunables.manualSensitivity = it; AutoAimPrefs.setManualSensitivity(it) }

        ui.intSlider("Line opacity", AutoAimPrefs.MANUAL_OPACITY_MIN, AutoAimPrefs.MANUAL_OPACITY_MAX, Tunables.manualOpacity) {
            Tunables.manualOpacity = it; AutoAimPrefs.setManualOpacity(it)
        }
        ui.floatSlider(
            "Line width", AutoAimPrefs.MANUAL_LINE_WIDTH_MIN_PX, AutoAimPrefs.MANUAL_LINE_WIDTH_MAX_PX,
            Tunables.manualLineWidthPx, 50, { "%.1f px".format(it) }
        ) { Tunables.manualLineWidthPx = it; AutoAimPrefs.setManualLineWidthPx(it) }
        ui.colorSwatches(
            listOf(Color.WHITE, Color.YELLOW, Color.CYAN, Color.GREEN, Color.rgb(255, 140, 0), Color.MAGENTA)
        ) { color -> Tunables.manualLineColor = color; AutoAimPrefs.setManualLineColor(color) }

        ui.checkbox("Dashed line (manual only)", Tunables.manualDashedLineEnabled) {
            Tunables.manualDashedLineEnabled = it; AutoAimPrefs.setManualDashedLineEnabled(it)
        }
        ui.hint("Ball-radius-relative, like the legacy app — e.g. an offset of 2 pulls the double line in by 1px per side while the ghost ball itself stays the same size.")
        ui.floatSlider(
            "Double line width offset (manual only)", AutoAimPrefs.MANUAL_DOUBLE_LINE_WIDTH_OFFSET_MIN_PX,
            AutoAimPrefs.MANUAL_DOUBLE_LINE_WIDTH_OFFSET_MAX_PX, Tunables.manualDoubleLineWidthOffsetPx, 48,
            { "%.1f px".format(it) }
        ) { Tunables.manualDoubleLineWidthOffsetPx = it; AutoAimPrefs.setManualDoubleLineWidthOffsetPx(it) }
        ui.checkbox("Show rail ghost ball (manual only)", Tunables.manualShowRailGhostBall) {
            Tunables.manualShowRailGhostBall = it; AutoAimPrefs.setManualShowRailGhostBall(it)
        }

        return root
    }
}

/** Shared widget-building helpers so [SettingsPanelBuilder.build] and
 * [SettingsPanelBuilder.buildExtras] wire identically-styled controls to
 * Tunables/AutoAimPrefs without duplicating the SeekBar/CheckBox plumbing. */
private class ControlFactory(
    private val context: Context,
    private val root: LinearLayout,
    private val density: Float,
    private val onChanged: () -> Unit
) {
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

    // Bug #4: the correction sliders (Bank Correction Curve) span a wide
    // range with a fine 0.1 step, which makes landing on a precise value
    // by dragging impractical. This adds a [-] ... slider ... [+] row so
    // each tap nudges by exactly one step (0.1) instead.
    fun correctionSlider(
        label: String, min: Float, max: Float, initial: Float, steps: Int, stepSize: Float,
        format: (Float) -> String, onSet: (Float) -> Unit
    ) {
        var current = initial
        val tv = TextView(context).apply { text = "$label: ${format(current)}"; setTextColor(Color.WHITE) }
        root.addView(tv)

        val sb = SeekBar(context).apply {
            this.max = steps
            progress = Math.round((current - min) / (max - min) * steps).coerceIn(0, steps)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        fun applyValue(v: Float, fromButton: Boolean) {
            current = v.coerceIn(min, max)
            tv.text = "$label: ${format(current)}"
            if (fromButton) {
                sb.progress = Math.round((current - min) / (max - min) * steps).coerceIn(0, steps)
            }
            onSet(current)
            onChanged()
        }

        val btnSize = (44 * density).toInt()
        val decBtn = Button(context).apply {
            text = "\u2193" // ↓ decrement
            minimumWidth = 0
            minimumHeight = 0
            layoutParams = LinearLayout.LayoutParams(btnSize, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { applyValue(current - stepSize, fromButton = true) }
        }
        val incBtn = Button(context).apply {
            text = "\u2191" // ↑ increment
            minimumWidth = 0
            minimumHeight = 0
            layoutParams = LinearLayout.LayoutParams(btnSize, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { applyValue(current + stepSize, fromButton = true) }
        }

        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    val v = min + (max - min) * (p / steps.toFloat())
                    applyValue(v, fromButton = false)
                }
            }
            override fun onStartTrackingTouch(seek: SeekBar?) {}
            override fun onStopTrackingTouch(seek: SeekBar?) {}
        })

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(decBtn)
        row.addView(sb)
        row.addView(incBtn)
        root.addView(row)
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

    /** A row of tappable color swatch buttons — factored out of the
     * original inline auto-aim-color code so both the auto and manual
     * line-color pickers share it. */
    fun colorSwatches(colors: List<Int>, onPick: (Int) -> Unit) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (color in colors) {
            val dp = (40 * density).toInt()
            row.addView(Button(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp, dp).apply { marginEnd = (8 * density).toInt() }
                minimumWidth = 0
                minimumHeight = 0
                backgroundTintList = ColorStateList.valueOf(color)
                text = ""
                setOnClickListener { onPick(color); onChanged() }
            })
        }
        root.addView(row)
    }

    /** A row of tappable mode buttons (e.g. detection-mode selector) —
     * highlights whichever option is currently active. */
    fun modeSelector(options: List<Pair<String, Int>>, current: Int, onSet: (Int) -> Unit) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, value) in options) {
            row.addView(Button(context).apply {
                text = label
                textSize = 11f
                minimumWidth = 0
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = (4 * density).toInt()
                }
                backgroundTintList = ColorStateList.valueOf(
                    if (value == current) 0xFF3F51B5.toInt() else 0xFF444444.toInt()
                )
                setTextColor(Color.WHITE)
                setOnClickListener { onSet(value); onChanged() }
            })
        }
        root.addView(row)
    }
}
