package com.yas.linedebugger

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

/**
 * Persistent settings store for the auto-aim app. Ported from the Manual
 * app's AimPrefs (AimOverlay project), extended with auto-aim-only knobs.
 *
 * There's no "ball size" concept here (detection just measures a guideline,
 * not a cue/target ball pair), so the double-line width is stored as an
 * absolute pixel width instead of an offset from a ghost-ball radius — see
 * DOUBLE_LINE_WIDTH_MIN_PX / MAX_PX below.
 *
 * Call [init] once before touching anything else here. Both MainActivity
 * and CaptureService call it on create; it's a no-op after the first call.
 */
object AutoAimPrefs {

    private const val FILE = "auto_aim_prefs"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    // ---------------- Keys ----------------

    private const val KEY_GREEN_DIFF = "green_diff"
    private const val KEY_GREEN_LINE_BRIGHTNESS = "green_line_brightness"
    private const val KEY_MIN_BRIGHTNESS = "min_brightness"
    private const val KEY_BALL_ERODE_RADIUS = "ball_erode_radius"
    private const val KEY_BALL_DILATE_GROW = "ball_dilate_grow"
    private const val KEY_MIN_LINE_PIXELS = "min_line_pixels"
    private const val KEY_OUTLIER_TRIM_K = "outlier_trim_k"

    private const val KEY_COLOR_DETECTION_MODE = "color_detection_mode"
    private const val KEY_FELT_HUE_TOLERANCE_DEG = "felt_hue_tolerance_deg"
    private const val KEY_FELT_BRIGHTNESS_DIFF = "felt_brightness_diff"
    private const val KEY_FELT_SATURATION_MIN = "felt_saturation_min"
    private const val KEY_MANUAL_FELT_HUE_DEG = "manual_felt_hue_deg"

    private const val KEY_CIRCLE_DIAMETER = "circle_diameter"
    private const val KEY_CIRCLE_ALPHA = "circle_alpha"
    private const val KEY_RAY_MONITOR_ENABLED = "ray_monitor_enabled"

    private const val KEY_AUTO_AIM_WIDTH_PX = "auto_aim_width_px"
    private const val KEY_AUTO_AIM_OPACITY = "auto_aim_opacity"
    private const val KEY_AUTO_AIM_COLOR = "auto_aim_color"

    private const val KEY_MAX_LINES = "max_lines"
    private const val KEY_DOUBLE_LINE_ENABLED = "double_line_enabled"
    private const val KEY_DASHED_LINE_ENABLED = "dashed_line_enabled"
    private const val KEY_DOUBLE_LINE_WIDTH_PX = "double_line_width_px"
    private const val KEY_BANK_MARKER_ENABLED = "bank_marker_enabled"

    private const val KEY_RAIL_GHOST_BALL_DIAMETER_PX = "rail_ghost_ball_diameter_px"
    private const val KEY_MANUAL_MODE_ENABLED = "manual_mode_enabled"

    private const val KEY_MANUAL_SENSITIVITY = "manual_sensitivity"
    private const val KEY_MANUAL_OPACITY = "manual_opacity"
    private const val KEY_MANUAL_LINE_WIDTH_PX = "manual_line_width_px"
    private const val KEY_MANUAL_LINE_COLOR = "manual_line_color"
    private const val KEY_MANUAL_DASHED_LINE_ENABLED = "manual_dashed_line_enabled"
    private const val KEY_MANUAL_DOUBLE_LINE_WIDTH_OFFSET_PX = "manual_double_line_width_offset_px"
    private const val KEY_MANUAL_SHOW_RAIL_GHOST_BALL = "manual_show_rail_ghost_ball"

    private const val KEY_BANK_CORRECTION_PREFIX = "bank_correction_"
    private const val KEY_REBOUND_INTENSITY = "rebound_intensity"

    private const val KEY_TABLE_LEFT = "table_left"
    private const val KEY_TABLE_TOP = "table_top"
    private const val KEY_TABLE_RIGHT = "table_right"
    private const val KEY_TABLE_BOTTOM = "table_bottom"

    private const val KEY_AIM_VISIBLE = "aim_visible"
    private const val KEY_TWEAK_PANEL_VISIBLE = "tweak_panel_visible"

    // ---------------- Defaults & ranges ----------------

    const val GREEN_DIFF_MIN = 0
    const val GREEN_DIFF_MAX = 60
    const val DEFAULT_GREEN_DIFF = 15

    // Bug #2 (green felt conflict): a green-hued pixel is only treated as
    // felt (and thrown away) if it's dimmer than this. Bright green pixels
    // — the green-ball guideline, which is described as much brighter than
    // the felt and moderately brighter than the balls — are let through
    // into the normal candidate mask instead, where the existing
    // erode/dilate + circularity blob-killer already strips the round green
    // ball back out, leaving just the elongated line. Non-green lines never
    // hit this check at all (they fail the hue test earlier), so their
    // detection is untouched.
    const val GREEN_LINE_BRIGHTNESS_MIN = 0
    const val GREEN_LINE_BRIGHTNESS_MAX = 255
    const val DEFAULT_GREEN_LINE_BRIGHTNESS = 190

    // Raised from the old 0-120 range to 0-200 so brighter felt / shadow
    // tones can still be pushed out of the "line" bucket.
    const val MIN_BRIGHTNESS_MIN = 0
    const val MIN_BRIGHTNESS_MAX = 200
    const val DEFAULT_MIN_BRIGHTNESS = 20

    const val BALL_ERODE_RADIUS_MIN = 1
    const val BALL_ERODE_RADIUS_MAX = 25
    const val DEFAULT_BALL_ERODE_RADIUS = 4

    const val BALL_DILATE_GROW_MIN = 0
    const val BALL_DILATE_GROW_MAX = 25
    const val DEFAULT_BALL_DILATE_GROW = 5

    const val MIN_LINE_PIXELS_MIN = 1
    const val MIN_LINE_PIXELS_MAX = 150
    const val DEFAULT_MIN_LINE_PIXELS = 15

    const val OUTLIER_TRIM_K_MIN = 0.5f
    const val OUTLIER_TRIM_K_MAX = 6.0f
    const val DEFAULT_OUTLIER_TRIM_K = 2.5f

    // Bug (auto ray-pixel detection - color failures): 0 = Legacy Green-Diff
    // (original hardcoded-green algorithm, kept for comparison / fallback).
    // 1 = Adaptive Felt Sample — the new default primary solution. Samples
    // the actual felt hue+brightness per frame instead of assuming green,
    // so green/brown/yellow (and every other color) guidelines are treated
    // the same way red already was. 2 = Manual Hue Reference — same
    // classifier, fixed hue slider instead of auto-sampling.
    const val COLOR_MODE_LEGACY_GREEN_DIFF = 0
    const val COLOR_MODE_ADAPTIVE_FELT_SAMPLE = 1
    const val COLOR_MODE_MANUAL_HUE = 2
    const val DEFAULT_COLOR_DETECTION_MODE = COLOR_MODE_ADAPTIVE_FELT_SAMPLE

    // How far (in hue degrees, 0-360 hue wheel) a pixel may sit from the
    // sampled/manual felt hue and still count as felt. Guideline colors
    // that are hue-distinct from the felt (red, blue, orange, brown...)
    // clear this easily; a green guideline on green felt relies on the
    // brightness differential below instead.
    const val FELT_HUE_TOLERANCE_MIN_DEG = 5f
    const val FELT_HUE_TOLERANCE_MAX_DEG = 60f
    const val DEFAULT_FELT_HUE_TOLERANCE_DEG = 22f

    // How far a same-hue pixel's brightness must differ from the sampled
    // felt brightness to count as guideline instead of felt (shading/
    // highlight/shadow on the felt itself). This is what lets a green-ball
    // guideline — same hue as the felt, much brighter — through.
    const val FELT_BRIGHTNESS_DIFF_MIN = 10
    const val FELT_BRIGHTNESS_DIFF_MAX = 120
    const val DEFAULT_FELT_BRIGHTNESS_DIFF = 45

    // Below this saturation (0-100, i.e. near-gray/white/black), a pixel's
    // hue is unreliable and it's excluded from felt-hue *sampling* (it can
    // still be classified using brightness alone once a felt hue exists).
    const val FELT_SATURATION_MIN_MIN = 0
    const val FELT_SATURATION_MIN_MAX = 60
    const val DEFAULT_FELT_SATURATION_MIN = 15

    // Manual Hue Reference mode's fixed felt-hue slider, 0-360 on the hue
    // wheel (0/360 = red, 120 = green, 240 = blue). Default sits on green
    // felt, the common case, but any felt color can be dialed in.
    const val MANUAL_FELT_HUE_MIN_DEG = 0f
    const val MANUAL_FELT_HUE_MAX_DEG = 360f
    const val DEFAULT_MANUAL_FELT_HUE_DEG = 120f

    const val CIRCLE_DIAMETER_MIN = 40
    const val CIRCLE_DIAMETER_MAX = 220
    const val DEFAULT_CIRCLE_DIAMETER = 100

    const val CIRCLE_ALPHA_MIN = 20
    const val CIRCLE_ALPHA_MAX = 255
    const val DEFAULT_CIRCLE_ALPHA = 140

    const val DEFAULT_RAY_MONITOR_ENABLED = true

    const val AUTO_AIM_WIDTH_MIN_PX = 1.0f
    const val AUTO_AIM_WIDTH_MAX_PX = 10.0f
    const val DEFAULT_AUTO_AIM_WIDTH_PX = 2.5f

    const val AUTO_AIM_OPACITY_MIN = 40
    const val AUTO_AIM_OPACITY_MAX = 255
    const val DEFAULT_AUTO_AIM_OPACITY = 255

    val DEFAULT_AUTO_AIM_COLOR = Color.WHITE

    const val MAX_LINES_MIN = 1
    const val MAX_LINES_MAX = 3
    const val DEFAULT_MAX_LINES = 2

    const val DEFAULT_DOUBLE_LINE_ENABLED = true
    const val DEFAULT_DASHED_LINE_ENABLED = false

    // No ball-size baseline in the auto-aim app — unlike the Manual app's
    // ball-relative offset, this is an absolute total width, with a wide
    // enough range to dial in by eye against whatever scale the table is at.
    const val DOUBLE_LINE_WIDTH_MIN_PX = 4f
    const val DOUBLE_LINE_WIDTH_MAX_PX = 400f
    const val DEFAULT_DOUBLE_LINE_WIDTH_PX = 60f

    const val DEFAULT_BANK_MARKER_ENABLED = true

    // Bug #3 (ghost-ball alignment): shared object-ball diameter used to
    // inset the rail boundary for BOTH controllers, so the bank-shot angle
    // line always terminates at the ghost ball's center, never the raw
    // table edge. Same range as the legacy Manual app's ball-size slider.
    const val RAIL_GHOST_BALL_DIAMETER_MIN_PX = 20f
    const val RAIL_GHOST_BALL_DIAMETER_MAX_PX = 120f
    const val DEFAULT_RAIL_GHOST_BALL_DIAMETER_PX = 60f

    const val DEFAULT_MANUAL_MODE_ENABLED = false

    const val MANUAL_SENSITIVITY_MIN = 0.1f
    const val MANUAL_SENSITIVITY_MAX = 1.5f
    const val DEFAULT_MANUAL_SENSITIVITY = 1.0f

    const val MANUAL_OPACITY_MIN = 60
    const val MANUAL_OPACITY_MAX = 255
    const val DEFAULT_MANUAL_OPACITY = 255

    const val MANUAL_LINE_WIDTH_MIN_PX = 1.0f
    const val MANUAL_LINE_WIDTH_MAX_PX = 6.0f
    const val DEFAULT_MANUAL_LINE_WIDTH_PX = 2.2f

    val DEFAULT_MANUAL_LINE_COLOR = Color.WHITE

    const val DEFAULT_MANUAL_DASHED_LINE_ENABLED = false

    const val MANUAL_DOUBLE_LINE_WIDTH_OFFSET_MIN_PX = -6f
    const val MANUAL_DOUBLE_LINE_WIDTH_OFFSET_MAX_PX = 6f
    const val DEFAULT_MANUAL_DOUBLE_LINE_WIDTH_OFFSET_PX = 0f

    const val DEFAULT_MANUAL_SHOW_RAIL_GHOST_BALL = true

    const val BANK_CORRECTION_MIN = -50f
    const val BANK_CORRECTION_MAX = 40f
    const val REBOUND_INTENSITY_MIN = 0f
    const val REBOUND_INTENSITY_MAX = 200f
    const val DEFAULT_REBOUND_INTENSITY = 100f

    // Angle control points + defaults — identical curve to the Manual app.
    val BANK_ANGLES = floatArrayOf(85f, 80f, 75f, 70f, 65f, 60f, 55f, 50f, 45f, 40f, 35f, 30f, 25f, 20f, 15f, 10f, 5f)
    val DEFAULT_BANK_CORRECTIONS = floatArrayOf(
        0.15f, 0.4f, 0.7f, 1.1f, 1.4f, 2.1f, 2.7f, 3.7f, 4.6f,
        6.3f, 7.9f, 10.3f, 12.6f, 16.3f, 20f, 20f, 20f
    )

    const val DEFAULT_AIM_VISIBLE = true
    const val DEFAULT_TWEAK_PANEL_VISIBLE = true

    // ---------------- Getters / setters ----------------

    fun getGreenDiff() = prefs.getInt(KEY_GREEN_DIFF, DEFAULT_GREEN_DIFF)
    fun setGreenDiff(v: Int) { prefs.edit().putInt(KEY_GREEN_DIFF, v).apply() }

    fun getGreenLineBrightness() = prefs.getInt(KEY_GREEN_LINE_BRIGHTNESS, DEFAULT_GREEN_LINE_BRIGHTNESS)
    fun setGreenLineBrightness(v: Int) { prefs.edit().putInt(KEY_GREEN_LINE_BRIGHTNESS, v).apply() }

    fun getMinBrightness() = prefs.getInt(KEY_MIN_BRIGHTNESS, DEFAULT_MIN_BRIGHTNESS)
    fun setMinBrightness(v: Int) { prefs.edit().putInt(KEY_MIN_BRIGHTNESS, v).apply() }

    fun getBallErodeRadius() = prefs.getInt(KEY_BALL_ERODE_RADIUS, DEFAULT_BALL_ERODE_RADIUS)
    fun setBallErodeRadius(v: Int) { prefs.edit().putInt(KEY_BALL_ERODE_RADIUS, v).apply() }

    fun getBallDilateGrow() = prefs.getInt(KEY_BALL_DILATE_GROW, DEFAULT_BALL_DILATE_GROW)
    fun setBallDilateGrow(v: Int) { prefs.edit().putInt(KEY_BALL_DILATE_GROW, v).apply() }

    fun getMinLinePixels() = prefs.getInt(KEY_MIN_LINE_PIXELS, DEFAULT_MIN_LINE_PIXELS)
    fun setMinLinePixels(v: Int) { prefs.edit().putInt(KEY_MIN_LINE_PIXELS, v).apply() }

    fun getOutlierTrimK() = prefs.getFloat(KEY_OUTLIER_TRIM_K, DEFAULT_OUTLIER_TRIM_K)
    fun setOutlierTrimK(v: Float) { prefs.edit().putFloat(KEY_OUTLIER_TRIM_K, v).apply() }

    fun getColorDetectionMode() = prefs.getInt(KEY_COLOR_DETECTION_MODE, DEFAULT_COLOR_DETECTION_MODE)
    fun setColorDetectionMode(v: Int) { prefs.edit().putInt(KEY_COLOR_DETECTION_MODE, v).apply() }

    fun getFeltHueToleranceDeg() = prefs.getFloat(KEY_FELT_HUE_TOLERANCE_DEG, DEFAULT_FELT_HUE_TOLERANCE_DEG)
    fun setFeltHueToleranceDeg(v: Float) { prefs.edit().putFloat(KEY_FELT_HUE_TOLERANCE_DEG, v).apply() }

    fun getFeltBrightnessDiff() = prefs.getInt(KEY_FELT_BRIGHTNESS_DIFF, DEFAULT_FELT_BRIGHTNESS_DIFF)
    fun setFeltBrightnessDiff(v: Int) { prefs.edit().putInt(KEY_FELT_BRIGHTNESS_DIFF, v).apply() }

    fun getFeltSaturationMin() = prefs.getInt(KEY_FELT_SATURATION_MIN, DEFAULT_FELT_SATURATION_MIN)
    fun setFeltSaturationMin(v: Int) { prefs.edit().putInt(KEY_FELT_SATURATION_MIN, v).apply() }

    fun getManualFeltHueDeg() = prefs.getFloat(KEY_MANUAL_FELT_HUE_DEG, DEFAULT_MANUAL_FELT_HUE_DEG)
    fun setManualFeltHueDeg(v: Float) { prefs.edit().putFloat(KEY_MANUAL_FELT_HUE_DEG, v).apply() }

    fun getCircleDiameter() = prefs.getInt(KEY_CIRCLE_DIAMETER, DEFAULT_CIRCLE_DIAMETER)
    fun setCircleDiameter(v: Int) { prefs.edit().putInt(KEY_CIRCLE_DIAMETER, v).apply() }

    fun getCircleAlpha() = prefs.getInt(KEY_CIRCLE_ALPHA, DEFAULT_CIRCLE_ALPHA)
    fun setCircleAlpha(v: Int) { prefs.edit().putInt(KEY_CIRCLE_ALPHA, v).apply() }

    fun isRayMonitorEnabled() = prefs.getBoolean(KEY_RAY_MONITOR_ENABLED, DEFAULT_RAY_MONITOR_ENABLED)
    fun setRayMonitorEnabled(v: Boolean) { prefs.edit().putBoolean(KEY_RAY_MONITOR_ENABLED, v).apply() }

    fun getAutoAimWidthPx() = prefs.getFloat(KEY_AUTO_AIM_WIDTH_PX, DEFAULT_AUTO_AIM_WIDTH_PX)
    fun setAutoAimWidthPx(v: Float) { prefs.edit().putFloat(KEY_AUTO_AIM_WIDTH_PX, v).apply() }

    fun getAutoAimOpacity() = prefs.getInt(KEY_AUTO_AIM_OPACITY, DEFAULT_AUTO_AIM_OPACITY)
    fun setAutoAimOpacity(v: Int) { prefs.edit().putInt(KEY_AUTO_AIM_OPACITY, v).apply() }

    fun getAutoAimColor() = prefs.getInt(KEY_AUTO_AIM_COLOR, DEFAULT_AUTO_AIM_COLOR)
    fun setAutoAimColor(v: Int) { prefs.edit().putInt(KEY_AUTO_AIM_COLOR, v).apply() }

    fun getMaxLines() = prefs.getInt(KEY_MAX_LINES, DEFAULT_MAX_LINES)
    fun setMaxLines(v: Int) { prefs.edit().putInt(KEY_MAX_LINES, v).apply() }

    fun isDoubleLineEnabled() = prefs.getBoolean(KEY_DOUBLE_LINE_ENABLED, DEFAULT_DOUBLE_LINE_ENABLED)
    fun setDoubleLineEnabled(v: Boolean) { prefs.edit().putBoolean(KEY_DOUBLE_LINE_ENABLED, v).apply() }

    fun isDashedLineEnabled() = prefs.getBoolean(KEY_DASHED_LINE_ENABLED, DEFAULT_DASHED_LINE_ENABLED)
    fun setDashedLineEnabled(v: Boolean) { prefs.edit().putBoolean(KEY_DASHED_LINE_ENABLED, v).apply() }

    fun getDoubleLineWidthPx() = prefs.getFloat(KEY_DOUBLE_LINE_WIDTH_PX, DEFAULT_DOUBLE_LINE_WIDTH_PX)
    fun setDoubleLineWidthPx(v: Float) { prefs.edit().putFloat(KEY_DOUBLE_LINE_WIDTH_PX, v).apply() }

    fun isBankMarkerEnabled() = prefs.getBoolean(KEY_BANK_MARKER_ENABLED, DEFAULT_BANK_MARKER_ENABLED)
    fun setBankMarkerEnabled(v: Boolean) { prefs.edit().putBoolean(KEY_BANK_MARKER_ENABLED, v).apply() }

    fun getRailGhostBallDiameterPx() = prefs.getFloat(KEY_RAIL_GHOST_BALL_DIAMETER_PX, DEFAULT_RAIL_GHOST_BALL_DIAMETER_PX)
    fun setRailGhostBallDiameterPx(v: Float) { prefs.edit().putFloat(KEY_RAIL_GHOST_BALL_DIAMETER_PX, v).apply() }

    fun isManualModeEnabled() = prefs.getBoolean(KEY_MANUAL_MODE_ENABLED, DEFAULT_MANUAL_MODE_ENABLED)
    fun setManualModeEnabled(v: Boolean) { prefs.edit().putBoolean(KEY_MANUAL_MODE_ENABLED, v).apply() }

    fun getManualSensitivity() = prefs.getFloat(KEY_MANUAL_SENSITIVITY, DEFAULT_MANUAL_SENSITIVITY)
    fun setManualSensitivity(v: Float) { prefs.edit().putFloat(KEY_MANUAL_SENSITIVITY, v).apply() }

    fun getManualOpacity() = prefs.getInt(KEY_MANUAL_OPACITY, DEFAULT_MANUAL_OPACITY)
    fun setManualOpacity(v: Int) { prefs.edit().putInt(KEY_MANUAL_OPACITY, v).apply() }

    fun getManualLineWidthPx() = prefs.getFloat(KEY_MANUAL_LINE_WIDTH_PX, DEFAULT_MANUAL_LINE_WIDTH_PX)
    fun setManualLineWidthPx(v: Float) { prefs.edit().putFloat(KEY_MANUAL_LINE_WIDTH_PX, v).apply() }

    fun getManualLineColor() = prefs.getInt(KEY_MANUAL_LINE_COLOR, DEFAULT_MANUAL_LINE_COLOR)
    fun setManualLineColor(v: Int) { prefs.edit().putInt(KEY_MANUAL_LINE_COLOR, v).apply() }

    fun isManualDashedLineEnabled() = prefs.getBoolean(KEY_MANUAL_DASHED_LINE_ENABLED, DEFAULT_MANUAL_DASHED_LINE_ENABLED)
    fun setManualDashedLineEnabled(v: Boolean) { prefs.edit().putBoolean(KEY_MANUAL_DASHED_LINE_ENABLED, v).apply() }

    fun getManualDoubleLineWidthOffsetPx() = prefs.getFloat(KEY_MANUAL_DOUBLE_LINE_WIDTH_OFFSET_PX, DEFAULT_MANUAL_DOUBLE_LINE_WIDTH_OFFSET_PX)
    fun setManualDoubleLineWidthOffsetPx(v: Float) { prefs.edit().putFloat(KEY_MANUAL_DOUBLE_LINE_WIDTH_OFFSET_PX, v).apply() }

    fun isManualShowRailGhostBall() = prefs.getBoolean(KEY_MANUAL_SHOW_RAIL_GHOST_BALL, DEFAULT_MANUAL_SHOW_RAIL_GHOST_BALL)
    fun setManualShowRailGhostBall(v: Boolean) { prefs.edit().putBoolean(KEY_MANUAL_SHOW_RAIL_GHOST_BALL, v).apply() }

    fun getBankCorrection(index: Int) = prefs.getFloat(KEY_BANK_CORRECTION_PREFIX + index, DEFAULT_BANK_CORRECTIONS[index])
    fun setBankCorrection(index: Int, v: Float) { prefs.edit().putFloat(KEY_BANK_CORRECTION_PREFIX + index, v).apply() }

    fun getReboundIntensity() = prefs.getFloat(KEY_REBOUND_INTENSITY, DEFAULT_REBOUND_INTENSITY)
    fun setReboundIntensity(v: Float) { prefs.edit().putFloat(KEY_REBOUND_INTENSITY, v).apply() }

    fun getTableLeft() = prefs.getFloat(KEY_TABLE_LEFT, -1f)
    fun getTableTop() = prefs.getFloat(KEY_TABLE_TOP, -1f)
    fun getTableRight() = prefs.getFloat(KEY_TABLE_RIGHT, -1f)
    fun getTableBottom() = prefs.getFloat(KEY_TABLE_BOTTOM, -1f)
    fun isCalibrated() = getTableLeft() >= 0f

    fun saveTableBounds(left: Float, top: Float, right: Float, bottom: Float) {
        prefs.edit()
            .putFloat(KEY_TABLE_LEFT, left)
            .putFloat(KEY_TABLE_TOP, top)
            .putFloat(KEY_TABLE_RIGHT, right)
            .putFloat(KEY_TABLE_BOTTOM, bottom)
            .apply()
    }

    fun isAimVisible() = prefs.getBoolean(KEY_AIM_VISIBLE, DEFAULT_AIM_VISIBLE)
    fun setAimVisible(v: Boolean) { prefs.edit().putBoolean(KEY_AIM_VISIBLE, v).apply() }

    fun isTweakPanelVisible() = prefs.getBoolean(KEY_TWEAK_PANEL_VISIBLE, DEFAULT_TWEAK_PANEL_VISIBLE)
    fun setTweakPanelVisible(v: Boolean) { prefs.edit().putBoolean(KEY_TWEAK_PANEL_VISIBLE, v).apply() }

    /**
     * Copies every persisted value into the live [Tunables] cache and
     * refreshes the [BankShot] correction curve. Safe to call more than
     * once (e.g. from both MainActivity and CaptureService at startup).
     */
    fun loadIntoTunables() {
        Tunables.greenDiff = getGreenDiff()
        Tunables.greenLineBrightness = getGreenLineBrightness()
        Tunables.minBrightness = getMinBrightness()
        Tunables.ballErodeRadius = getBallErodeRadius()
        Tunables.ballDilateGrow = getBallDilateGrow()
        Tunables.minLinePixels = getMinLinePixels()
        Tunables.outlierTrimK = getOutlierTrimK()

        Tunables.colorDetectionMode = getColorDetectionMode()
        Tunables.feltHueToleranceDeg = getFeltHueToleranceDeg()
        Tunables.feltBrightnessDiff = getFeltBrightnessDiff()
        Tunables.feltSaturationMin = getFeltSaturationMin()
        Tunables.manualFeltHueDeg = getManualFeltHueDeg()

        Tunables.circleDiameter = getCircleDiameter()
        Tunables.circleAlpha = getCircleAlpha()
        Tunables.rayMonitorEnabled = isRayMonitorEnabled()

        Tunables.autoAimWidthPx = getAutoAimWidthPx()
        Tunables.autoAimOpacity = getAutoAimOpacity()
        Tunables.autoAimColor = getAutoAimColor()

        Tunables.maxLines = getMaxLines()
        Tunables.doubleLineEnabled = isDoubleLineEnabled()
        Tunables.dashedLineEnabled = isDashedLineEnabled()
        Tunables.doubleLineWidthPx = getDoubleLineWidthPx()
        Tunables.bankMarkerEnabled = isBankMarkerEnabled()

        Tunables.railGhostBallDiameterPx = getRailGhostBallDiameterPx()
        Tunables.manualModeEnabled = isManualModeEnabled()

        Tunables.manualSensitivity = getManualSensitivity()
        Tunables.manualOpacity = getManualOpacity()
        Tunables.manualLineWidthPx = getManualLineWidthPx()
        Tunables.manualLineColor = getManualLineColor()
        Tunables.manualDashedLineEnabled = isManualDashedLineEnabled()
        Tunables.manualDoubleLineWidthOffsetPx = getManualDoubleLineWidthOffsetPx()
        Tunables.manualShowRailGhostBall = isManualShowRailGhostBall()

        Tunables.tableLeft = getTableLeft()
        Tunables.tableTop = getTableTop()
        Tunables.tableRight = getTableRight()
        Tunables.tableBottom = getTableBottom()

        Tunables.aimVisible = isAimVisible()
        Tunables.tweakPanelVisible = isTweakPanelVisible()

        pushBankCurve()
    }

    fun pushBankCurve() {
        val corrections = FloatArray(BANK_ANGLES.size) { getBankCorrection(it) }
        BankShot.updateCorrectionCurve(corrections, getReboundIntensity())
    }
}
