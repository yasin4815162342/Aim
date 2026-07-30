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
 * Builds the Manual CUE/TARGET controller section — feature request #1.
 * MainActivity-only, per the floating tweak panel already being at
 * capacity: this is never shown in the floating panel, only appended to
 * MainActivity's own scroll view, alongside (but separately built from)
 * [SettingsPanelBuilder]'s shared controls.
 *
 * Every field wired up here lives under Tunables' "Manual CUE / TARGET
 * controller" section and is read ONLY by the manual rendering path in
 * OverlayController's DrawOverlayView — none of it is read by the
 * automatic path, so nothing here can change how the automatic aim line
 * looks. The controller itself still shares the app's existing table
 * calibration and BankShot's reflection math with the automatic path —
 * see [OverlayController.setManualControllerEnabled] and
 * `DrawOverlayView.drawManualController`.
 *
 * [onChanged] fires after every edit so the caller can request a redraw
 * (safe no-op if the overlay isn't currently running).
 */
object ManualControlPanelBuilder {

    fun build(context: Context, onChanged: () -> Unit): LinearLayout {
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

        sectionLabel("Manual Controller")
        hint("Ported from the Manual app (AimOverlay). Adds a draggable CUE ball and TARGET you position by hand, using the same table calibration and BankShot reflection math as the automatic ray above — none of the tweaks below affect the automatic aim line.")
        checkbox("Enable manual CUE / TARGET controller", Tunables.manualControllerEnabled) {
            OverlayController.setManualControllerEnabled(it)
        }
        hint("When enabled, drag the red CUE ball and the black TARGET square that appear over the table. The line always stops short of TARGET (it represents a ball) and, if it reaches a calibrated rail, banks off it exactly like the automatic ray.")

        floatSlider(
            "Drag sensitivity", AutoAimPrefs.MANUAL_SENSITIVITY_MIN, AutoAimPrefs.MANUAL_SENSITIVITY_MAX,
            Tunables.manualSensitivity, 140, { "%.2fx".format(it) }
        ) { Tunables.manualSensitivity = it; AutoAimPrefs.setManualSensitivity(it) }

        floatSlider(
            "Ball size (shared with Bank Shot section in the floating panel)",
            AutoAimPrefs.GHOST_BALL_DIAMETER_MIN_PX, AutoAimPrefs.GHOST_BALL_DIAMETER_MAX_PX,
            Tunables.ghostBallDiameterPx, 100, { "%.0f px".format(it) }
        ) {
            Tunables.ghostBallDiameterPx = it
            AutoAimPrefs.setGhostBallDiameterPx(it)
            OverlayController.onGhostBallDiameterChanged(it)
        }
        hint("Same ball diameter the automatic ray's rail bounce uses (bug #3) — kept as one shared value since it's the same physical ball either way.")

        sectionLabel("Manual Line Look")
        floatSlider(
            "Line width", AutoAimPrefs.MANUAL_LINE_WIDTH_MIN_PX, AutoAimPrefs.MANUAL_LINE_WIDTH_MAX_PX,
            Tunables.manualLineWidthPx, 50, { "%.1f px".format(it) }
        ) { Tunables.manualLineWidthPx = it; AutoAimPrefs.setManualLineWidthPx(it) }

        intSlider(
            "Line opacity", AutoAimPrefs.MANUAL_LINE_OPACITY_MIN, AutoAimPrefs.MANUAL_LINE_OPACITY_MAX,
            Tunables.manualLineOpacity
        ) { Tunables.manualLineOpacity = it; AutoAimPrefs.setManualLineOpacity(it) }

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
                    Tunables.manualLineColor = color
                    AutoAimPrefs.setManualLineColor(color)
                    onChanged()
                }
            })
        }
        swatch(Color.WHITE); swatch(Color.YELLOW); swatch(Color.CYAN)
        swatch(Color.GREEN); swatch(Color.rgb(255, 140, 0)); swatch(Color.MAGENTA)
        root.addView(swatches)

        checkbox("Dashed line", Tunables.manualDashedLineEnabled) {
            Tunables.manualDashedLineEnabled = it; AutoAimPrefs.setManualDashedLineEnabled(it)
        }
        checkbox("Double line", Tunables.manualDoubleLineEnabled) {
            Tunables.manualDoubleLineEnabled = it; AutoAimPrefs.setManualDoubleLineEnabled(it)
        }
        hint("Offset from the ball's own radius — 0 keeps the double lines exactly ball-width apart, same as the Manual app's original slider.")
        floatSlider(
            "Double line width offset",
            AutoAimPrefs.MANUAL_DOUBLE_LINE_WIDTH_OFFSET_MIN_PX, AutoAimPrefs.MANUAL_DOUBLE_LINE_WIDTH_OFFSET_MAX_PX,
            Tunables.manualDoubleLineWidthOffsetPx, 120, { "%.0f px".format(it) }
        ) { Tunables.manualDoubleLineWidthOffsetPx = it; AutoAimPrefs.setManualDoubleLineWidthOffsetPx(it) }

        checkbox("Show rail ghost ball", Tunables.manualGhostRailEnabled) {
            Tunables.manualGhostRailEnabled = it; AutoAimPrefs.setManualGhostRailEnabled(it)
        }
        hint("Bug #3 fix applies here too: the ghost ball sits flush against the calibrated rail with its center — not its edge — as the bounce/reflection point.")

        sectionLabel("Kiss Shot")
        hint("No new ball handle — reuses CUE and TARGET. While this is on: TARGET = the ball you're kissing off of (not a rail-bank point), CUE = where the moving ball starts from. Only new point is DEST (red dot) — place it on the pocket. A tiny green dot marks exactly where on TARGET's edge contact needs to happen. Bank-shot detection is skipped while this is on, since TARGET means something different now. Solved against the game's real ball collision physics (0.95 elasticity), using the CUE→TARGET line as the approach direction. If a shot is geometrically impossible from that approach, nothing gets drawn — no false positives.")
        checkbox("Enable Kiss Shot assist", Tunables.manualKissEnabled) {
            OverlayController.setManualKissEnabled(it)
        }

        floatSlider(
            "Kiss ball-size calibration",
            AutoAimPrefs.MANUAL_KISS_RADIUS_SCALE_MIN_PERCENT, AutoAimPrefs.MANUAL_KISS_RADIUS_SCALE_MAX_PERCENT,
            Tunables.manualKissRadiusScalePercent, 40, { "%.0f%%".format(it) }
        ) { Tunables.manualKissRadiusScalePercent = it; AutoAimPrefs.setManualKissRadiusScalePercent(it) }
        hint("If kiss shots consistently land short/long of the pocket, the game's ball collision size probably doesn't quite match the ghost-ball diameter above — tune this until it lines up.")

        floatSlider(
            "Kiss fine-tune correction",
            AutoAimPrefs.MANUAL_KISS_THROW_ANGLE_MIN_DEG, AutoAimPrefs.MANUAL_KISS_THROW_ANGLE_MAX_DEG,
            Tunables.manualKissThrowAngleDeg, 32, { "%.1f°".format(it) }
        ) { Tunables.manualKissThrowAngleDeg = it; AutoAimPrefs.setManualKissThrowAngleDeg(it) }
        hint("The collision math itself (0.95 elasticity) is now solved exactly from the game's real physics data. This just mops up what isn't modeled — spin-driven throw and any leftover engine/calibration slop — so it should need only small nudges.")

        val sideRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        fun sideButton(text: String, value: Int) {
            sideRow.addView(Button(context).apply {
                this.text = text
                setOnClickListener {
                    Tunables.manualKissSideLock = value
                    AutoAimPrefs.setManualKissSideLock(value)
                    onChanged()
                }
            })
        }
        sideButton("Auto", 0); sideButton("Side A", 1); sideButton("Side B", 2)
        root.addView(sideRow)
        hint("Two mirror-image contact points always solve the geometry — Auto picks whichever matches where TARGET currently is. If it flips unexpectedly while dragging, lock it to Side A/B.")

        return root
    }
}
