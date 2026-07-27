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
    private const val KEY_MIN_BRIGHTNESS = "min_brightness"
    private const val KEY_BALL_ERODE_RADIUS = "ball_erode_radius"
    private const val KEY_BALL_DILATE_GROW = "ball_dilate_grow"
    private const val KEY_MIN_LINE_PIXELS = "min_line_pixels"
    private const val KEY_OUTLIER_TRIM_K = "outlier_trim_k"
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
        Tunables.minBrightness = getMinBrightness()
        Tunables.ballErodeRadius = getBallErodeRadius()
        Tunables.ballDilateGrow = getBallDilateGrow()
        Tunables.minLinePixels = getMinLinePixels()
        Tunables.outlierTrimK = getOutlierTrimK()
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
