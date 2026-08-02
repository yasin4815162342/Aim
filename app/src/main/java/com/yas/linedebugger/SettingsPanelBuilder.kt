package com.yas.linedebugger

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Builds the full set of tweak controls exactly once, so the in-app
 * settings screen (MainActivity) and the floating tweak panel
 * (OverlayController) always show the same controls wired to the same
 * persisted values — no drift between the two.
 *
 * [onChanged] is called after every user edit so the caller can trigger a
 * redraw of the live overlay (a no-op if it isn't running yet).
 * [onCalibrate], if non-null, adds a "Calibrate Table" button.
 * [onSemiAutoCalibrate], if non-null, adds a "Semi-Auto Calibrate (center
 * only)" button — re-centers the table using the size template captured
 * by the last full manual calibration, instead of dragging both corners.
 */
object SettingsPanelBuilder {

    private fun detectionModeLabel(mode: Int): String = when (mode) {
        AutoAimPrefs.DETECTION_MODE_HSV -> "HSV (new, primary)"
        AutoAimPrefs.DETECTION_MODE_LEGACY -> "Legacy RGB (fallback)"
        AutoAimPrefs.DETECTION_MODE_WHITE -> "White (fastest, white-only)"
        else -> "unknown"
    }

    fun build(context: Context, onChanged: () -> Unit, onCalibrate: (() -> Unit)?, onSemiAutoCalibrate: (() -> Unit)? = null): LinearLayout {
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
        ): Pair<TextView, SeekBar> {
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
            return tv to sb
        }

        // Bug #4: the correction sliders (Bank Correction Curve) span a wide
        // range with a fine 0.1 step, which makes landing on a precise
        // value by dragging impractical. This adds a [-] ... slider ... [+]
        // row so each tap nudges by exactly one step (0.1) instead.
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

            // Compact buttons so the ↑/↓ + seekbar row fits inside the
            // narrow floating panel on phones like the Galaxy A32.
            val btnSize = (36 * density).toInt()
            val decBtn = Button(context).apply {
                text = "\u2193" // ↓ decrement
                minimumWidth = 0
                minimumHeight = 0
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(btnSize, LinearLayout.LayoutParams.WRAP_CONTENT)
                setOnClickListener { applyValue(current - stepSize, fromButton = true) }
            }
            val incBtn = Button(context).apply {
                text = "\u2191" // ↑ increment
                minimumWidth = 0
                minimumHeight = 0
                setPadding(0, 0, 0, 0)
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

        // ==================== Detection ====================
        sectionLabel("Detection")
        intSlider("Green diff", AutoAimPrefs.GREEN_DIFF_MIN, AutoAimPrefs.GREEN_DIFF_MAX, Tunables.greenDiff) {
            Tunables.greenDiff = it; AutoAimPrefs.setGreenDiff(it)
        }
        hint("Green pixels at or above this brightness count as guideline, not felt — raise this if a green-ball line isn't showing.")
        intSlider(
            "Green line brightness", AutoAimPrefs.GREEN_LINE_BRIGHTNESS_MIN, AutoAimPrefs.GREEN_LINE_BRIGHTNESS_MAX,
            Tunables.greenLineBrightness
        ) {
            Tunables.greenLineBrightness = it; AutoAimPrefs.setGreenLineBrightness(it)
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
            Tunables.outlierTrimK, 55, { "%.1f".format(Locale.US, it) }
        ) { Tunables.outlierTrimK = it; AutoAimPrefs.setOutlierTrimK(it) }

        // ==================== Detection: color strategy (bug #2) ====================
        // Green/brown/yellow guideline recovery. Three selectable
        // candidate-pixel classifiers feeding the same erosion/dilation +
        // line-fit pipeline above — see LineDetector.
        sectionLabel("Detection: Color Mode")
        val modeLabel = TextView(context).apply {
            text = "Mode: ${detectionModeLabel(Tunables.detectionMode)}"
            setTextColor(Color.WHITE)
        }
        root.addView(modeLabel)
        hint("HSV is the new default — hue/saturation/value distance from the felt (and optional rail) reference below. Legacy is the original red/green-diff filter, kept as a no-regression fallback. White ignores color reference matching entirely — it only asks \"is this pixel bright and close to gray/white\", which is all a cue-ball guideline needs. Cheapest of the three; use it if your line is always white.")
        val modeRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        fun modeButton(label: String, mode: Int) {
            modeRow.addView(Button(context).apply {
                text = label
                textSize = 11f
                setOnClickListener {
                    Tunables.detectionMode = mode
                    AutoAimPrefs.setDetectionMode(mode)
                    modeLabel.text = "Mode: ${detectionModeLabel(mode)}"
                    onChanged()
                }
            })
        }
        modeButton("HSV", AutoAimPrefs.DETECTION_MODE_HSV)
        modeButton("Legacy", AutoAimPrefs.DETECTION_MODE_LEGACY)
        modeButton("White", AutoAimPrefs.DETECTION_MODE_WHITE)
        root.addView(modeRow)

        sectionLabel("White Line Mode")
        hint("A pixel counts as candidate line if it's brighter than \"Min brightness\" above AND max(R,G,B) - min(R,G,B) is at or below this spread. Saturated colors — green felt, colored balls, rails — have a large spread; a white/gray highlight has a small one. Raise this if a slightly off-white or shadowed part of the line is getting missed; lower it if a bright ball or rail edge is leaking in.")
        intSlider("White max spread", AutoAimPrefs.WHITE_MAX_SPREAD_MIN, AutoAimPrefs.WHITE_MAX_SPREAD_MAX, Tunables.whiteMaxSpread) {
            Tunables.whiteMaxSpread = it; AutoAimPrefs.setWhiteMaxSpread(it)
        }

        sectionLabel("Felt Color Reference (HSV mode)")
        hint("A pixel close to this hue AND saturation AND value is felt and gets discarded. Differing enough in hue, OR in saturation, OR in brightness alone is enough to survive as a candidate — that's what lets a green guideline through even though its hue matches the felt's.")
        floatSlider(
            "Felt hue", 0f, 360f, Tunables.feltHueDeg, 360, { "%.0f°".format(Locale.US, it) }
        ) { Tunables.feltHueDeg = it; AutoAimPrefs.setFeltHueDeg(it) }
        floatSlider(
            "Felt hue tolerance", 0f, 180f, Tunables.feltHueToleranceDeg, 180, { "%.0f°".format(Locale.US, it) }
        ) { Tunables.feltHueToleranceDeg = it; AutoAimPrefs.setFeltHueToleranceDeg(it) }
        intSlider("Felt saturation", 0, 255, Tunables.feltSat) {
            Tunables.feltSat = it; AutoAimPrefs.setFeltSat(it)
        }
        intSlider("Felt saturation tolerance", 0, 255, Tunables.feltSatTolerance) {
            Tunables.feltSatTolerance = it; AutoAimPrefs.setFeltSatTolerance(it)
        }
        intSlider("Felt brightness (value)", 0, 255, Tunables.feltVal) {
            Tunables.feltVal = it; AutoAimPrefs.setFeltVal(it)
        }
        intSlider("Felt brightness tolerance", 0, 255, Tunables.feltValTolerance) {
            Tunables.feltValTolerance = it; AutoAimPrefs.setFeltValTolerance(it)
        }

        sectionLabel("Rail / Cushion Color (optional, HSV mode)")
        hint("Off by default. Turn on if a brown guideline near the rail is getting contaminated by the wood cushion color — same close-in-hue-AND-sat-AND-value rule, applied as a second background class alongside felt.")
        checkbox("Exclude rail/cushion color", Tunables.railExclusionEnabled) {
            Tunables.railExclusionEnabled = it; AutoAimPrefs.setRailExclusionEnabled(it)
        }
        floatSlider(
            "Rail hue", 0f, 360f, Tunables.railHueDeg, 360, { "%.0f°".format(Locale.US, it) }
        ) { Tunables.railHueDeg = it; AutoAimPrefs.setRailHueDeg(it) }
        floatSlider(
            "Rail hue tolerance", 0f, 180f, Tunables.railHueToleranceDeg, 180, { "%.0f°".format(Locale.US, it) }
        ) { Tunables.railHueToleranceDeg = it; AutoAimPrefs.setRailHueToleranceDeg(it) }
        intSlider("Rail saturation", 0, 255, Tunables.railSat) {
            Tunables.railSat = it; AutoAimPrefs.setRailSat(it)
        }
        intSlider("Rail saturation tolerance", 0, 255, Tunables.railSatTolerance) {
            Tunables.railSatTolerance = it; AutoAimPrefs.setRailSatTolerance(it)
        }
        intSlider("Rail brightness (value)", 0, 255, Tunables.railVal) {
            Tunables.railVal = it; AutoAimPrefs.setRailVal(it)
        }
        intSlider("Rail brightness tolerance", 0, 255, Tunables.railValTolerance) {
            Tunables.railValTolerance = it; AutoAimPrefs.setRailValTolerance(it)
        }

        sectionLabel("Smoothing (Extended Line)")
        hint("One Euro filter smooths the displayed angle and position so a genuinely-still line doesn't jitter pixel to pixel. Off = raw detection every frame, zero added lag, but jitter returns when the phone is still. Min cutoff sets resting smoothness (higher = less resting lag, more resting jitter); Beta sets how fast that smoothing backs off once the line starts moving (higher = snappier while dragging).")
        checkbox("Smoothing enabled", Tunables.smoothingEnabled) {
            Tunables.smoothingEnabled = it; AutoAimPrefs.setSmoothingEnabled(it)
        }
        floatSlider(
            "Angle min cutoff", AutoAimPrefs.ANGLE_MIN_CUTOFF_MIN, AutoAimPrefs.ANGLE_MIN_CUTOFF_MAX,
            Tunables.angleMinCutoff, 98, { "%.2f".format(Locale.US, it) }
        ) { Tunables.angleMinCutoff = it; AutoAimPrefs.setAngleMinCutoff(it) }
        floatSlider(
            "Angle beta", AutoAimPrefs.ANGLE_BETA_MIN, AutoAimPrefs.ANGLE_BETA_MAX,
            Tunables.angleBeta, 200, { "%.2f".format(Locale.US, it) }
        ) { Tunables.angleBeta = it; AutoAimPrefs.setAngleBeta(it) }
        floatSlider(
            "Offset min cutoff", AutoAimPrefs.OFFSET_MIN_CUTOFF_MIN, AutoAimPrefs.OFFSET_MIN_CUTOFF_MAX,
            Tunables.offsetMinCutoff, 98, { "%.2f".format(Locale.US, it) }
        ) { Tunables.offsetMinCutoff = it; AutoAimPrefs.setOffsetMinCutoff(it) }
        floatSlider(
            "Offset beta", AutoAimPrefs.OFFSET_BETA_MIN, AutoAimPrefs.OFFSET_BETA_MAX,
            Tunables.offsetBeta, 200, { "%.3f".format(Locale.US, it) }
        ) { Tunables.offsetBeta = it; AutoAimPrefs.setOffsetBeta(it) }

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
        floatSlider(
            "Capture scale", AutoAimPrefs.CAPTURE_SCALE_MIN, AutoAimPrefs.CAPTURE_SCALE_MAX,
            Tunables.captureScale, 60, { "%.2fx".format(Locale.US, it) }
        ) { Tunables.captureScale = it; AutoAimPrefs.setCaptureScale(it) }
        hint("1.00x = native resolution, zero resolution-driven position error, but the heaviest render cost. Lower it if fps/battery/heat suffer on your device. Takes effect on the next Stop → Start of capture, not live.")

        // ==================== Auto Aim line look ====================
        sectionLabel("Auto Aim Line")
        hint("Width / opacity / color are manual now — no longer set from the detected ball.")
        floatSlider(
            "Line width", AutoAimPrefs.AUTO_AIM_WIDTH_MIN_PX, AutoAimPrefs.AUTO_AIM_WIDTH_MAX_PX,
            Tunables.autoAimWidthPx, 90, { "%.1f px".format(Locale.US, it) }
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
        floatSlider(
            "Ghost ball size", AutoAimPrefs.GHOST_BALL_DIAMETER_MIN_PX, AutoAimPrefs.GHOST_BALL_DIAMETER_MAX_PX,
            Tunables.ghostBallDiameterPx, 100, { "%.0f px".format(Locale.US, it) }
        ) {
            Tunables.ghostBallDiameterPx = it
            AutoAimPrefs.setGhostBallDiameterPx(it)
            OverlayController.onGhostBallDiameterChanged(it)
        }
        hint("Bug #3 fix: also insets the wall so a bank reflects off the ball's center, not the table edge. Shared by the automatic ray and the manual CUE/TARGET balls — see the Manual Controller section in the main app screen.")
        checkbox("Double line", Tunables.doubleLineEnabled) {
            Tunables.doubleLineEnabled = it; AutoAimPrefs.setDoubleLineEnabled(it)
        }
        hint("No ball-size baseline here, so this is an absolute width — wide range on purpose.")
        floatSlider(
            "Double line width", AutoAimPrefs.DOUBLE_LINE_WIDTH_MIN_PX, AutoAimPrefs.DOUBLE_LINE_WIDTH_MAX_PX,
            Tunables.doubleLineWidthPx, 200, { "%.0f px".format(Locale.US, it) }
        ) { Tunables.doubleLineWidthPx = it; AutoAimPrefs.setDoubleLineWidthPx(it) }
        checkbox("Dashed line", Tunables.dashedLineEnabled) {
            Tunables.dashedLineEnabled = it; AutoAimPrefs.setDashedLineEnabled(it)
        }
        checkbox("Show bank point marker", Tunables.bankMarkerEnabled) {
            Tunables.bankMarkerEnabled = it; AutoAimPrefs.setBankMarkerEnabled(it)
        }

        sectionLabel("Bank Correction Curve")
        hint("Range: -50° to 40°. Defaults now match Chipmunk's real rail physics (not a mirror): tighter than a mirror almost everywhere, biggest gap around 64°, crossing to a true mirror near 18°, then slightly wider than a mirror from 16° down to 4°. 90° (dead-on) and 0° (pure graze) are both fixed at 0 and have no sliders — neither one can bank. Nudge a slider only if you want to further compensate for a specific physical table.")
        val bankStepSize = 0.1f
        val bankSteps = Math.round((AutoAimPrefs.BANK_CORRECTION_MAX - AutoAimPrefs.BANK_CORRECTION_MIN) / bankStepSize)
        for (i in AutoAimPrefs.BANK_ANGLES.indices) {
            val idx = i
            val angleLabel = AutoAimPrefs.BANK_ANGLES[i].toInt()
            correctionSlider(
                "Correction @ ${angleLabel}°", AutoAimPrefs.BANK_CORRECTION_MIN, AutoAimPrefs.BANK_CORRECTION_MAX,
                AutoAimPrefs.getBankCorrection(idx), bankSteps, bankStepSize, { "%.1f°".format(Locale.US, it) }
            ) { v ->
                AutoAimPrefs.setBankCorrection(idx, v)
                AutoAimPrefs.pushBankCurve()
            }
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
            hint("Drag both corners onto the rails, fine-tune with the D-Pads (1px/tap). This also sets the table size used by Semi-Auto Calibrate below.")
        }

        if (onSemiAutoCalibrate != null) {
            root.addView(Button(context).apply {
                text = "Semi-Auto Calibrate (center only)"
                setOnClickListener { onSemiAutoCalibrate() }
            })
            hint("Re-centers the table using the size from your last full Calibrate Table pass — place one crosshair on the table's exact center instead of both corners. Run Calibrate Table at least once first.")
        }

        // Force-push the bank curve every time the panel is built so the
        // live BankShot table can never sit uninitialized from a cold start
        // (that was one way slider changes appeared to do nothing) —
        // separate from the persisted per-angle values being 0° by design.
        AutoAimPrefs.pushBankCurve()

        return root
    }
}
