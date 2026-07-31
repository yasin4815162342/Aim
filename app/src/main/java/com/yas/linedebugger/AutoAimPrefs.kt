package com.yas.linedebugger

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

/**
 * Persistent settings store for the app. Ported from the Manual app's
 * AimPrefs (AimOverlay project), extended with auto-aim-only knobs, and
 * now also the manual-controller-only knobs from feature request #1 (the
 * calibration and bank-correction-curve keys below are shared by both
 * controllers — see Tunables for which fields are shared vs. per-
 * controller).
 *
 * There's no single "ball size" tied to the automatic path's own line
 * (detection just measures a guideline, not a cue/target ball pair), but
 * ghostBallDiameterPx below IS shared — see bug #3 — since a rail bounce
 * is the same physical event either way. The manual controller's double-
 * line width, unlike the automatic path's, is stored as an offset from
 * that shared ball radius rather than an absolute pixel width — see
 * MANUAL_DOUBLE_LINE_WIDTH_OFFSET_MIN_PX / MAX_PX below.
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
    private const val KEY_CIRCLE_DIAMETER = "circle_diameter"
    private const val KEY_CIRCLE_ALPHA = "circle_alpha"
    private const val KEY_RAY_MONITOR_ENABLED = "ray_monitor_enabled"
    private const val KEY_CAPTURE_SCALE = "capture_scale"

    private const val KEY_AUTO_AIM_WIDTH_PX = "auto_aim_width_px"
    private const val KEY_AUTO_AIM_OPACITY = "auto_aim_opacity"
    private const val KEY_AUTO_AIM_COLOR = "auto_aim_color"

    private const val KEY_MAX_LINES = "max_lines"
    private const val KEY_DOUBLE_LINE_ENABLED = "double_line_enabled"
    private const val KEY_DASHED_LINE_ENABLED = "dashed_line_enabled"
    private const val KEY_DOUBLE_LINE_WIDTH_PX = "double_line_width_px"
    private const val KEY_BANK_MARKER_ENABLED = "bank_marker_enabled"
    private const val KEY_GHOST_BALL_DIAMETER_PX = "ghost_ball_diameter_px"

    private const val KEY_BANK_CORRECTION_PREFIX = "bank_correction_"

    private const val KEY_TABLE_LEFT = "table_left"
    private const val KEY_TABLE_TOP = "table_top"
    private const val KEY_TABLE_RIGHT = "table_right"
    private const val KEY_TABLE_BOTTOM = "table_bottom"

    private const val KEY_AIM_VISIBLE = "aim_visible"
    private const val KEY_TWEAK_PANEL_VISIBLE = "tweak_panel_visible"

    // Detection: color strategy (bug #2)
    private const val KEY_DETECTION_MODE = "detection_mode"
    private const val KEY_FELT_HUE_DEG = "felt_hue_deg"
    private const val KEY_FELT_HUE_TOLERANCE_DEG = "felt_hue_tolerance_deg"
    private const val KEY_FELT_SAT = "felt_sat"
    private const val KEY_FELT_SAT_TOLERANCE = "felt_sat_tolerance"
    private const val KEY_FELT_VAL = "felt_val"
    private const val KEY_FELT_VAL_TOLERANCE = "felt_val_tolerance"
    private const val KEY_RAIL_EXCLUSION_ENABLED = "rail_exclusion_enabled"
    private const val KEY_RAIL_HUE_DEG = "rail_hue_deg"
    private const val KEY_RAIL_HUE_TOLERANCE_DEG = "rail_hue_tolerance_deg"
    private const val KEY_RAIL_SAT = "rail_sat"
    private const val KEY_RAIL_SAT_TOLERANCE = "rail_sat_tolerance"
    private const val KEY_RAIL_VAL = "rail_val"
    private const val KEY_RAIL_VAL_TOLERANCE = "rail_val_tolerance"

    // Manual CUE / TARGET controller (feature request #1)
    private const val KEY_MANUAL_CONTROLLER_ENABLED = "manual_controller_enabled"
    private const val KEY_MANUAL_SENSITIVITY = "manual_sensitivity"
    private const val KEY_MANUAL_LINE_WIDTH_PX = "manual_line_width_px"
    private const val KEY_MANUAL_LINE_OPACITY = "manual_line_opacity"
    private const val KEY_MANUAL_LINE_COLOR = "manual_line_color"
    private const val KEY_MANUAL_DOUBLE_LINE_ENABLED = "manual_double_line_enabled"
    private const val KEY_MANUAL_DOUBLE_LINE_WIDTH_OFFSET_PX = "manual_double_line_width_offset_px"
    private const val KEY_MANUAL_DASHED_LINE_ENABLED = "manual_dashed_line_enabled"
    private const val KEY_MANUAL_GHOST_RAIL_ENABLED = "manual_ghost_rail_enabled"

    // Manual KISS / DEST controller (kiss-shot assist)
    private const val KEY_MANUAL_KISS_ENABLED = "manual_kiss_enabled"
    private const val KEY_MANUAL_KISS_ACTIVE = "manual_kiss_active"
    private const val KEY_MANUAL_KISS_RADIUS_SCALE_PERCENT = "manual_kiss_radius_scale_percent"
    private const val KEY_MANUAL_KISS_THROW_ANGLE_DEG = "manual_kiss_throw_angle_deg"
    private const val KEY_MANUAL_KISS_SIDE_LOCK = "manual_kiss_side_lock"
    private const val KEY_MANUAL_KISS_LINE_WIDTH_PX = "manual_kiss_line_width_px"
    private const val KEY_MANUAL_KISS_LINE_OPACITY = "manual_kiss_line_opacity"

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
    // detection is untouched. Only used by DETECTION_MODE_LEGACY now — see
    // feltHueDeg etc. below for the new default HSV mode's equivalent.
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

    const val CIRCLE_DIAMETER_MIN = 40
    const val CIRCLE_DIAMETER_MAX = 220
    const val DEFAULT_CIRCLE_DIAMETER = 100

    const val CIRCLE_ALPHA_MIN = 20
    const val CIRCLE_ALPHA_MAX = 255
    const val DEFAULT_CIRCLE_ALPHA = 140

    const val DEFAULT_RAY_MONITOR_ENABLED = true

    // Capture resolution as a fraction of native. 1.0 = full native res =
    // zero resolution-driven position error, at the highest OS-side mirror/
    // render cost. Only the crop's own bytes are ever processed (see
    // CaptureService.extractCrop), so this cost is the screen compositor's,
    // not the detector's — but on a modest chip (e.g. Galaxy A32) it can
    // still show up as lower fps / more heat. Default is native res since
    // accuracy was explicitly asked for; dial down from the tweak panel if
    // it's too heavy on your specific device. Takes effect on the next
    // capture start (Stop then Start), not live mid-session.
    const val CAPTURE_SCALE_MIN = 0.4f
    const val CAPTURE_SCALE_MAX = 1.0f
    const val DEFAULT_CAPTURE_SCALE = 1.0f

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

    // Bug #3: shared ghost-ball diameter. Default now matches the Chipmunk
    // codebase exactly — PoolPhysics.js sets ball_radius: 20 (ball_w: 40),
    // so 40px is the real ball's diameter, not the old 60px placeholder.
    const val GHOST_BALL_DIAMETER_MIN_PX = 20f
    const val GHOST_BALL_DIAMETER_MAX_PX = 120f
    const val DEFAULT_GHOST_BALL_DIAMETER_PX = 40f

    const val BANK_CORRECTION_MIN = -50f
    const val BANK_CORRECTION_MAX = 40f

    // Angle control points, 90° and 0° locked at 0 (see BankShot) and have
    // no sliders; these 22 cover the range where a correction actually
    // matters. Defaults below are NOT flat zero — they're derived by
    // replaying Chipmunk's own sequential-impulse rail collision formulas
    // (k_scalar / apply_impulse from chipmunk.js, combined ball×rail
    // elasticity 0.95×1=0.95 and friction 0.1×0.8=0.08 from physicsData.js)
    // for a clean, no-spin, no-english first bounce — the same simplifying
    // assumption the curve itself already relies on. A true e=1 mirror was
    // never right: friction at the contact point converts some tangential
    // motion into ball spin via torque, which is NOT uniform across angles.
    // The result: the bank comes off tighter than a mirror (correction
    // negative) almost everywhere, peaking near 64°, crosses back to a true
    // mirror around 18°, then flips to a small "wider than mirror" effect
    // (positive) from 16° down to 4°. The sliders stay available for anyone
    // who wants to further compensate for a specific physical table.
    val BANK_ANGLES = floatArrayOf(
        88f, 84f, 80f, 76f, 72f, 68f, 64f, 60f, 56f, 52f, 48f,
        44f, 40f, 36f, 32f, 28f, 24f, 20f, 16f, 12f, 8f, 4f
    )
    val DEFAULT_BANK_CORRECTIONS = floatArrayOf(
        -0.55f, -1.64f, -2.71f, -3.75f, -4.74f, -5.67f, -6.52f, -6.08f, -5.37f, -4.65f, -3.92f,
        -3.21f, -2.53f, -1.90f, -1.33f, -0.85f, -0.44f, -0.13f, 0.08f, 0.20f, 0.23f, 0.16f
    )

    const val DEFAULT_AIM_VISIBLE = true
    const val DEFAULT_TWEAK_PANEL_VISIBLE = true

    // ---- Detection: color strategy (bug #2) ----
    // Three selectable candidate-pixel classifiers — see LineDetector.
    const val DETECTION_MODE_HSV = 0      // new primary: hue/sat/value distance from a felt (+ optional rail) reference
    const val DETECTION_MODE_LEGACY = 1   // original red/green-diff filter, unchanged, kept as a fallback
    const val DETECTION_MODE_HYBRID = 2   // candidate if EITHER of the above would accept it
    const val DEFAULT_DETECTION_MODE = DETECTION_MODE_HSV

    const val DEFAULT_FELT_HUE_DEG = 115f
    const val DEFAULT_FELT_HUE_TOLERANCE_DEG = 40f
    const val DEFAULT_FELT_SAT = 150
    const val DEFAULT_FELT_SAT_TOLERANCE = 90
    const val DEFAULT_FELT_VAL = 130
    const val DEFAULT_FELT_VAL_TOLERANCE = 70

    const val DEFAULT_RAIL_EXCLUSION_ENABLED = false
    const val DEFAULT_RAIL_HUE_DEG = 25f
    const val DEFAULT_RAIL_HUE_TOLERANCE_DEG = 30f
    const val DEFAULT_RAIL_SAT = 120
    const val DEFAULT_RAIL_SAT_TOLERANCE = 90
    const val DEFAULT_RAIL_VAL = 110
    const val DEFAULT_RAIL_VAL_TOLERANCE = 70

    // ---- Manual CUE / TARGET controller (feature request #1) ----
    // Ranges below are ported directly from the Manual app's AimPrefs.
    const val DEFAULT_MANUAL_CONTROLLER_ENABLED = false

    const val MANUAL_SENSITIVITY_MIN = 0.1f
    const val MANUAL_SENSITIVITY_MAX = 1.5f
    const val DEFAULT_MANUAL_SENSITIVITY = 1.0f

    const val MANUAL_LINE_WIDTH_MIN_PX = 1.0f
    const val MANUAL_LINE_WIDTH_MAX_PX = 6.0f
    const val DEFAULT_MANUAL_LINE_WIDTH_PX = 2.2f

    const val MANUAL_LINE_OPACITY_MIN = 60
    const val MANUAL_LINE_OPACITY_MAX = 255
    const val DEFAULT_MANUAL_LINE_OPACITY = 255

    val DEFAULT_MANUAL_LINE_COLOR = Color.WHITE

    const val DEFAULT_MANUAL_DOUBLE_LINE_ENABLED = true
    const val DEFAULT_MANUAL_DASHED_LINE_ENABLED = false
    const val DEFAULT_MANUAL_GHOST_RAIL_ENABLED = true

    // Offset from the shared ghost-ball radius (not an absolute width —
    // see the class doc comment above), exactly like the Manual app's
    // doubleLineWidthOffset: 0 keeps the double lines exactly ball-width
    // apart, negative pulls them in, positive pushes them out.
    const val MANUAL_DOUBLE_LINE_WIDTH_OFFSET_MIN_PX = -6f
    const val MANUAL_DOUBLE_LINE_WIDTH_OFFSET_MAX_PX = 6f
    const val DEFAULT_MANUAL_DOUBLE_LINE_WIDTH_OFFSET_PX = 0f

    // ---- Manual KISS / DEST controller (kiss-shot assist) ----
    const val DEFAULT_MANUAL_KISS_ENABLED = false
    // Defaults to active so turning the checkbox on behaves like it always
    // did before the tap-to-toggle existed — starts in kiss mode (green),
    // not silently parked off (red).
    const val DEFAULT_MANUAL_KISS_ACTIVE = true

    // Tweak 1 — ball-size calibration for the kiss solve only (percent of
    // the shared ghost-ball diameter). Kept separate from the shared
    // ghostBallDiameterPx slider since kiss geometry is far more sensitive
    // to a size mismatch than plain ghost-ball aiming is.
    const val MANUAL_KISS_RADIUS_SCALE_MIN_PERCENT = 80f
    const val MANUAL_KISS_RADIUS_SCALE_MAX_PERCENT = 120f
    const val DEFAULT_MANUAL_KISS_RADIUS_SCALE_PERCENT = 100f

    // Tweak 2 — residual correction, in degrees: covers ball_friction's
    // spin-transfer throw (not modeled — needs simulated spin/speed) plus
    // any engine/calibration slop. The elasticity (0.95) deviation itself
    // is now solved exactly in KissShot, so this only has to cover the
    // leftover — hence the narrower range than a plain guess would need.
    const val MANUAL_KISS_THROW_ANGLE_MIN_DEG = -8f
    const val MANUAL_KISS_THROW_ANGLE_MAX_DEG = 8f
    const val DEFAULT_MANUAL_KISS_THROW_ANGLE_DEG = 0f

    // Tweak 3 — which of the two mirror solutions to use.
    const val DEFAULT_MANUAL_KISS_SIDE_LOCK = KissShot.SIDE_AUTO

    // Tweak 4/5 — look of the two green kiss guide lines (CUE→ghost and
    // ghost→DEST). Separate from the CUE/TARGET manual line's own width/
    // opacity above, since the kiss lines are their own visual element and
    // people may want them thinner/fainter (or bolder) independently.
    const val MANUAL_KISS_LINE_WIDTH_MIN_PX = 1.0f
    const val MANUAL_KISS_LINE_WIDTH_MAX_PX = 6.0f
    const val DEFAULT_MANUAL_KISS_LINE_WIDTH_PX = 2.0f

    const val MANUAL_KISS_LINE_OPACITY_MIN = 60
    const val MANUAL_KISS_LINE_OPACITY_MAX = 255
    const val DEFAULT_MANUAL_KISS_LINE_OPACITY = 200

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

    fun getCircleDiameter() = prefs.getInt(KEY_CIRCLE_DIAMETER, DEFAULT_CIRCLE_DIAMETER)
    fun setCircleDiameter(v: Int) { prefs.edit().putInt(KEY_CIRCLE_DIAMETER, v).apply() }

    fun getCircleAlpha() = prefs.getInt(KEY_CIRCLE_ALPHA, DEFAULT_CIRCLE_ALPHA)
    fun setCircleAlpha(v: Int) { prefs.edit().putInt(KEY_CIRCLE_ALPHA, v).apply() }

    fun isRayMonitorEnabled() = prefs.getBoolean(KEY_RAY_MONITOR_ENABLED, DEFAULT_RAY_MONITOR_ENABLED)
    fun setRayMonitorEnabled(v: Boolean) { prefs.edit().putBoolean(KEY_RAY_MONITOR_ENABLED, v).apply() }

    fun getCaptureScale() = prefs.getFloat(KEY_CAPTURE_SCALE, DEFAULT_CAPTURE_SCALE)
    fun setCaptureScale(v: Float) { prefs.edit().putFloat(KEY_CAPTURE_SCALE, v).apply() }

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

    fun getGhostBallDiameterPx() = prefs.getFloat(KEY_GHOST_BALL_DIAMETER_PX, DEFAULT_GHOST_BALL_DIAMETER_PX)
    fun setGhostBallDiameterPx(v: Float) { prefs.edit().putFloat(KEY_GHOST_BALL_DIAMETER_PX, v).apply() }

    fun getBankCorrection(index: Int) = prefs.getFloat(KEY_BANK_CORRECTION_PREFIX + index, DEFAULT_BANK_CORRECTIONS[index])
    fun setBankCorrection(index: Int, v: Float) { prefs.edit().putFloat(KEY_BANK_CORRECTION_PREFIX + index, v).apply() }

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

    // ---- Detection: color strategy getters/setters ----

    fun getDetectionMode() = prefs.getInt(KEY_DETECTION_MODE, DEFAULT_DETECTION_MODE)
    fun setDetectionMode(v: Int) { prefs.edit().putInt(KEY_DETECTION_MODE, v).apply() }

    fun getFeltHueDeg() = prefs.getFloat(KEY_FELT_HUE_DEG, DEFAULT_FELT_HUE_DEG)
    fun setFeltHueDeg(v: Float) { prefs.edit().putFloat(KEY_FELT_HUE_DEG, v).apply() }

    fun getFeltHueToleranceDeg() = prefs.getFloat(KEY_FELT_HUE_TOLERANCE_DEG, DEFAULT_FELT_HUE_TOLERANCE_DEG)
    fun setFeltHueToleranceDeg(v: Float) { prefs.edit().putFloat(KEY_FELT_HUE_TOLERANCE_DEG, v).apply() }

    fun getFeltSat() = prefs.getInt(KEY_FELT_SAT, DEFAULT_FELT_SAT)
    fun setFeltSat(v: Int) { prefs.edit().putInt(KEY_FELT_SAT, v).apply() }

    fun getFeltSatTolerance() = prefs.getInt(KEY_FELT_SAT_TOLERANCE, DEFAULT_FELT_SAT_TOLERANCE)
    fun setFeltSatTolerance(v: Int) { prefs.edit().putInt(KEY_FELT_SAT_TOLERANCE, v).apply() }

    fun getFeltVal() = prefs.getInt(KEY_FELT_VAL, DEFAULT_FELT_VAL)
    fun setFeltVal(v: Int) { prefs.edit().putInt(KEY_FELT_VAL, v).apply() }

    fun getFeltValTolerance() = prefs.getInt(KEY_FELT_VAL_TOLERANCE, DEFAULT_FELT_VAL_TOLERANCE)
    fun setFeltValTolerance(v: Int) { prefs.edit().putInt(KEY_FELT_VAL_TOLERANCE, v).apply() }

    fun isRailExclusionEnabled() = prefs.getBoolean(KEY_RAIL_EXCLUSION_ENABLED, DEFAULT_RAIL_EXCLUSION_ENABLED)
    fun setRailExclusionEnabled(v: Boolean) { prefs.edit().putBoolean(KEY_RAIL_EXCLUSION_ENABLED, v).apply() }

    fun getRailHueDeg() = prefs.getFloat(KEY_RAIL_HUE_DEG, DEFAULT_RAIL_HUE_DEG)
    fun setRailHueDeg(v: Float) { prefs.edit().putFloat(KEY_RAIL_HUE_DEG, v).apply() }

    fun getRailHueToleranceDeg() = prefs.getFloat(KEY_RAIL_HUE_TOLERANCE_DEG, DEFAULT_RAIL_HUE_TOLERANCE_DEG)
    fun setRailHueToleranceDeg(v: Float) { prefs.edit().putFloat(KEY_RAIL_HUE_TOLERANCE_DEG, v).apply() }

    fun getRailSat() = prefs.getInt(KEY_RAIL_SAT, DEFAULT_RAIL_SAT)
    fun setRailSat(v: Int) { prefs.edit().putInt(KEY_RAIL_SAT, v).apply() }

    fun getRailSatTolerance() = prefs.getInt(KEY_RAIL_SAT_TOLERANCE, DEFAULT_RAIL_SAT_TOLERANCE)
    fun setRailSatTolerance(v: Int) { prefs.edit().putInt(KEY_RAIL_SAT_TOLERANCE, v).apply() }

    fun getRailVal() = prefs.getInt(KEY_RAIL_VAL, DEFAULT_RAIL_VAL)
    fun setRailVal(v: Int) { prefs.edit().putInt(KEY_RAIL_VAL, v).apply() }

    fun getRailValTolerance() = prefs.getInt(KEY_RAIL_VAL_TOLERANCE, DEFAULT_RAIL_VAL_TOLERANCE)
    fun setRailValTolerance(v: Int) { prefs.edit().putInt(KEY_RAIL_VAL_TOLERANCE, v).apply() }

    // ---- Manual CUE / TARGET controller getters/setters ----

    fun isManualControllerEnabled() = prefs.getBoolean(KEY_MANUAL_CONTROLLER_ENABLED, DEFAULT_MANUAL_CONTROLLER_ENABLED)
    fun setManualControllerEnabled(v: Boolean) { prefs.edit().putBoolean(KEY_MANUAL_CONTROLLER_ENABLED, v).apply() }

    fun getManualSensitivity() = prefs.getFloat(KEY_MANUAL_SENSITIVITY, DEFAULT_MANUAL_SENSITIVITY)
    fun setManualSensitivity(v: Float) { prefs.edit().putFloat(KEY_MANUAL_SENSITIVITY, v).apply() }

    fun getManualLineWidthPx() = prefs.getFloat(KEY_MANUAL_LINE_WIDTH_PX, DEFAULT_MANUAL_LINE_WIDTH_PX)
    fun setManualLineWidthPx(v: Float) { prefs.edit().putFloat(KEY_MANUAL_LINE_WIDTH_PX, v).apply() }

    fun getManualLineOpacity() = prefs.getInt(KEY_MANUAL_LINE_OPACITY, DEFAULT_MANUAL_LINE_OPACITY)
    fun setManualLineOpacity(v: Int) { prefs.edit().putInt(KEY_MANUAL_LINE_OPACITY, v).apply() }

    fun getManualLineColor() = prefs.getInt(KEY_MANUAL_LINE_COLOR, DEFAULT_MANUAL_LINE_COLOR)
    fun setManualLineColor(v: Int) { prefs.edit().putInt(KEY_MANUAL_LINE_COLOR, v).apply() }

    fun isManualDoubleLineEnabled() = prefs.getBoolean(KEY_MANUAL_DOUBLE_LINE_ENABLED, DEFAULT_MANUAL_DOUBLE_LINE_ENABLED)
    fun setManualDoubleLineEnabled(v: Boolean) { prefs.edit().putBoolean(KEY_MANUAL_DOUBLE_LINE_ENABLED, v).apply() }

    fun getManualDoubleLineWidthOffsetPx() =
        prefs.getFloat(KEY_MANUAL_DOUBLE_LINE_WIDTH_OFFSET_PX, DEFAULT_MANUAL_DOUBLE_LINE_WIDTH_OFFSET_PX)
    fun setManualDoubleLineWidthOffsetPx(v: Float) { prefs.edit().putFloat(KEY_MANUAL_DOUBLE_LINE_WIDTH_OFFSET_PX, v).apply() }

    fun isManualDashedLineEnabled() = prefs.getBoolean(KEY_MANUAL_DASHED_LINE_ENABLED, DEFAULT_MANUAL_DASHED_LINE_ENABLED)
    fun setManualDashedLineEnabled(v: Boolean) { prefs.edit().putBoolean(KEY_MANUAL_DASHED_LINE_ENABLED, v).apply() }

    fun isManualGhostRailEnabled() = prefs.getBoolean(KEY_MANUAL_GHOST_RAIL_ENABLED, DEFAULT_MANUAL_GHOST_RAIL_ENABLED)
    fun setManualGhostRailEnabled(v: Boolean) { prefs.edit().putBoolean(KEY_MANUAL_GHOST_RAIL_ENABLED, v).apply() }

    // ---- Manual KISS / DEST controller getters/setters ----

    fun isManualKissEnabled() = prefs.getBoolean(KEY_MANUAL_KISS_ENABLED, DEFAULT_MANUAL_KISS_ENABLED)
    fun setManualKissEnabled(v: Boolean) { prefs.edit().putBoolean(KEY_MANUAL_KISS_ENABLED, v).apply() }
    fun isManualKissActive() = prefs.getBoolean(KEY_MANUAL_KISS_ACTIVE, DEFAULT_MANUAL_KISS_ACTIVE)
    fun setManualKissActive(v: Boolean) { prefs.edit().putBoolean(KEY_MANUAL_KISS_ACTIVE, v).apply() }

    fun getManualKissRadiusScalePercent() =
        prefs.getFloat(KEY_MANUAL_KISS_RADIUS_SCALE_PERCENT, DEFAULT_MANUAL_KISS_RADIUS_SCALE_PERCENT)
    fun setManualKissRadiusScalePercent(v: Float) { prefs.edit().putFloat(KEY_MANUAL_KISS_RADIUS_SCALE_PERCENT, v).apply() }

    fun getManualKissThrowAngleDeg() = prefs.getFloat(KEY_MANUAL_KISS_THROW_ANGLE_DEG, DEFAULT_MANUAL_KISS_THROW_ANGLE_DEG)
    fun setManualKissThrowAngleDeg(v: Float) { prefs.edit().putFloat(KEY_MANUAL_KISS_THROW_ANGLE_DEG, v).apply() }

    fun getManualKissSideLock() = prefs.getInt(KEY_MANUAL_KISS_SIDE_LOCK, DEFAULT_MANUAL_KISS_SIDE_LOCK)
    fun setManualKissSideLock(v: Int) { prefs.edit().putInt(KEY_MANUAL_KISS_SIDE_LOCK, v).apply() }

    fun getManualKissLineWidthPx() = prefs.getFloat(KEY_MANUAL_KISS_LINE_WIDTH_PX, DEFAULT_MANUAL_KISS_LINE_WIDTH_PX)
    fun setManualKissLineWidthPx(v: Float) { prefs.edit().putFloat(KEY_MANUAL_KISS_LINE_WIDTH_PX, v).apply() }

    fun getManualKissLineOpacity() = prefs.getInt(KEY_MANUAL_KISS_LINE_OPACITY, DEFAULT_MANUAL_KISS_LINE_OPACITY)
    fun setManualKissLineOpacity(v: Int) { prefs.edit().putInt(KEY_MANUAL_KISS_LINE_OPACITY, v).apply() }

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
        Tunables.circleDiameter = getCircleDiameter()
        Tunables.circleAlpha = getCircleAlpha()
        Tunables.rayMonitorEnabled = isRayMonitorEnabled()
        Tunables.captureScale = getCaptureScale()

        Tunables.detectionMode = getDetectionMode()
        Tunables.feltHueDeg = getFeltHueDeg()
        Tunables.feltHueToleranceDeg = getFeltHueToleranceDeg()
        Tunables.feltSat = getFeltSat()
        Tunables.feltSatTolerance = getFeltSatTolerance()
        Tunables.feltVal = getFeltVal()
        Tunables.feltValTolerance = getFeltValTolerance()
        Tunables.railExclusionEnabled = isRailExclusionEnabled()
        Tunables.railHueDeg = getRailHueDeg()
        Tunables.railHueToleranceDeg = getRailHueToleranceDeg()
        Tunables.railSat = getRailSat()
        Tunables.railSatTolerance = getRailSatTolerance()
        Tunables.railVal = getRailVal()
        Tunables.railValTolerance = getRailValTolerance()

        Tunables.autoAimWidthPx = getAutoAimWidthPx()
        Tunables.autoAimOpacity = getAutoAimOpacity()
        Tunables.autoAimColor = getAutoAimColor()

        Tunables.maxLines = getMaxLines()
        Tunables.doubleLineEnabled = isDoubleLineEnabled()
        Tunables.dashedLineEnabled = isDashedLineEnabled()
        Tunables.doubleLineWidthPx = getDoubleLineWidthPx()
        Tunables.bankMarkerEnabled = isBankMarkerEnabled()
        Tunables.ghostBallDiameterPx = getGhostBallDiameterPx()

        Tunables.tableLeft = getTableLeft()
        Tunables.tableTop = getTableTop()
        Tunables.tableRight = getTableRight()
        Tunables.tableBottom = getTableBottom()

        Tunables.aimVisible = isAimVisible()
        Tunables.tweakPanelVisible = isTweakPanelVisible()

        Tunables.manualControllerEnabled = isManualControllerEnabled()
        Tunables.manualSensitivity = getManualSensitivity()
        Tunables.manualLineWidthPx = getManualLineWidthPx()
        Tunables.manualLineOpacity = getManualLineOpacity()
        Tunables.manualLineColor = getManualLineColor()
        Tunables.manualDoubleLineEnabled = isManualDoubleLineEnabled()
        Tunables.manualDoubleLineWidthOffsetPx = getManualDoubleLineWidthOffsetPx()
        Tunables.manualDashedLineEnabled = isManualDashedLineEnabled()
        Tunables.manualGhostRailEnabled = isManualGhostRailEnabled()

        Tunables.manualKissEnabled = isManualKissEnabled()
        Tunables.manualKissActive = isManualKissActive()
        Tunables.manualKissRadiusScalePercent = getManualKissRadiusScalePercent()
        Tunables.manualKissThrowAngleDeg = getManualKissThrowAngleDeg()
        Tunables.manualKissSideLock = getManualKissSideLock()
        Tunables.manualKissLineWidthPx = getManualKissLineWidthPx()
        Tunables.manualKissLineOpacity = getManualKissLineOpacity()

        pushBankCurve()
    }

    fun pushBankCurve() {
        val corrections = FloatArray(BANK_ANGLES.size) { getBankCorrection(it) }
        BankShot.updateCorrectionCurve(corrections)
    }
}
