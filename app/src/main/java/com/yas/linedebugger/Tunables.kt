package com.yas.linedebugger

/**
 * Every knob the tweak panel (and the in-app settings screen) exposes.
 * Plain @Volatile fields, read from the background frame-processing thread
 * and written from the UI thread — good enough for a debug tool where a
 * torn read just means "one stale frame."
 *
 * These are just the live cache. Persisted values live in [AutoAimPrefs];
 * call [AutoAimPrefs.loadIntoTunables] once at startup (MainActivity and
 * CaptureService both do this) to populate everything below from the last
 * saved settings.
 */
object Tunables {
    // --- Detection ---
    @Volatile var greenDiff: Int = AutoAimPrefs.DEFAULT_GREEN_DIFF          // how far G must exceed R and B to count as felt
    @Volatile var greenLineBrightness: Int = AutoAimPrefs.DEFAULT_GREEN_LINE_BRIGHTNESS  // green pixels this bright or brighter are treated as guideline, not felt
    @Volatile var minBrightness: Int = AutoAimPrefs.DEFAULT_MIN_BRIGHTNESS  // reject near-black shadow pixels as "not line"
    @Volatile var ballErodeRadius: Int = AutoAimPrefs.DEFAULT_BALL_ERODE_RADIUS   // erosion radius that erases the thin line, leaves ball cores
    @Volatile var ballDilateGrow: Int = AutoAimPrefs.DEFAULT_BALL_DILATE_GROW     // extra growth to restore the ball's true footprint
    @Volatile var minLinePixels: Int = AutoAimPrefs.DEFAULT_MIN_LINE_PIXELS       // below this many surviving pixels, refuse to guess an angle
    @Volatile var outlierTrimK: Float = AutoAimPrefs.DEFAULT_OUTLIER_TRIM_K       // outlier-rejection threshold, in std-devs of perpendicular residual

    // --- Color detection mode (green/brown/yellow guideline recovery) ---
    // 0 = Legacy Green-Diff (original algorithm, hardcoded green-felt hue check)
    // 1 = Adaptive Felt Sample (primary/default) — samples the actual felt
    //     hue+brightness out of each frame, so it isn't hardcoded to green
    //     felt and doesn't special-case any one guideline color.
    // 2 = Manual Hue Reference — same classifier as mode 1, but the felt hue
    //     is a fixed slider instead of auto-sampled, for when auto-sampling
    //     misfires (e.g. the line dominates the capture circle).
    @Volatile var colorDetectionMode: Int = AutoAimPrefs.DEFAULT_COLOR_DETECTION_MODE
    @Volatile var feltHueToleranceDeg: Float = AutoAimPrefs.DEFAULT_FELT_HUE_TOLERANCE_DEG
    @Volatile var feltBrightnessDiff: Int = AutoAimPrefs.DEFAULT_FELT_BRIGHTNESS_DIFF
    @Volatile var feltSaturationMin: Int = AutoAimPrefs.DEFAULT_FELT_SATURATION_MIN
    @Volatile var manualFeltHueDeg: Float = AutoAimPrefs.DEFAULT_MANUAL_FELT_HUE_DEG

    // --- Ray Circle (the draggable detection-area controller) ---
    @Volatile var circleDiameter: Int = AutoAimPrefs.DEFAULT_CIRCLE_DIAMETER  // the draggable controller's size in px
    @Volatile var circleAlpha: Int = AutoAimPrefs.DEFAULT_CIRCLE_ALPHA       // 0-255, the controller circle's opacity

    // --- Ray Monitor: the raw-pixel debug preview + status text overlay ---
    @Volatile var rayMonitorEnabled: Boolean = AutoAimPrefs.DEFAULT_RAY_MONITOR_ENABLED

    // --- Auto-aim line look. Manual now — no longer derived from the
    //     detected target ball's color/width. ---
    @Volatile var autoAimWidthPx: Float = AutoAimPrefs.DEFAULT_AUTO_AIM_WIDTH_PX
    @Volatile var autoAimOpacity: Int = AutoAimPrefs.DEFAULT_AUTO_AIM_OPACITY
    @Volatile var autoAimColor: Int = AutoAimPrefs.DEFAULT_AUTO_AIM_COLOR

    // --- Bank shot / multi-segment rendering (ported from the Manual app) ---
    @Volatile var maxLines: Int = AutoAimPrefs.DEFAULT_MAX_LINES
    @Volatile var doubleLineEnabled: Boolean = AutoAimPrefs.DEFAULT_DOUBLE_LINE_ENABLED
    @Volatile var dashedLineEnabled: Boolean = AutoAimPrefs.DEFAULT_DASHED_LINE_ENABLED
    @Volatile var doubleLineWidthPx: Float = AutoAimPrefs.DEFAULT_DOUBLE_LINE_WIDTH_PX
    @Volatile var bankMarkerEnabled: Boolean = AutoAimPrefs.DEFAULT_BANK_MARKER_ENABLED

    // --- Rail ghost ball (shared bank-shot geometry — bug #3 fix) ---
    // The object-ball diameter used to inset the playable rail boundary so
    // a bank-shot trajectory line ends where the ball's CENTER would rest
    // flush against the rail, not where the ball's edge (or the raw table
    // edge) would. Shared identically by both the automatic and manual
    // controllers so their ghost-ball placement and angle-line centering
    // always agree — this is the "shared component" bug #3 asks for.
    @Volatile var railGhostBallDiameterPx: Float = AutoAimPrefs.DEFAULT_RAIL_GHOST_BALL_DIAMETER_PX

    // --- Controller mode ---
    // false = Automatic (Ray Circle / auto-detected guideline), matching
    // the app's existing sole behavior. true = Manual (legacy CUE/TARGET
    // controllers, ported from the AimOverlay project). Mutually exclusive:
    // exactly one controller's handles + line are visible/touchable at a
    // time, so they never fight over the same screen input.
    @Volatile var manualModeEnabled: Boolean = AutoAimPrefs.DEFAULT_MANUAL_MODE_ENABLED

    // --- Manual controller (ported from the Manual app / AimOverlay project).
    //     Table calibration, maxLines, doubleLineEnabled, the bank
    //     correction curve, and railGhostBallDiameterPx above are all
    //     SHARED with the automatic controller (same physical table, same
    //     ball). Only the manual-controller-only tweaks the bug report
    //     calls out — Dashed Line, Double Line Width Offset, Show Rail
    //     Ghost Ball — plus the manual controller's own drag/look settings,
    //     live here and never touch the automatic path. ---
    @Volatile var manualSensitivity: Float = AutoAimPrefs.DEFAULT_MANUAL_SENSITIVITY
    @Volatile var manualOpacity: Int = AutoAimPrefs.DEFAULT_MANUAL_OPACITY
    @Volatile var manualLineWidthPx: Float = AutoAimPrefs.DEFAULT_MANUAL_LINE_WIDTH_PX
    @Volatile var manualLineColor: Int = AutoAimPrefs.DEFAULT_MANUAL_LINE_COLOR
    @Volatile var manualDashedLineEnabled: Boolean = AutoAimPrefs.DEFAULT_MANUAL_DASHED_LINE_ENABLED
    @Volatile var manualDoubleLineWidthOffsetPx: Float = AutoAimPrefs.DEFAULT_MANUAL_DOUBLE_LINE_WIDTH_OFFSET_PX
    @Volatile var manualShowRailGhostBall: Boolean = AutoAimPrefs.DEFAULT_MANUAL_SHOW_RAIL_GHOST_BALL

    // --- Table calibration (ported from the Manual app; -1 = uncalibrated) ---
    @Volatile var tableLeft: Float = -1f
    @Volatile var tableTop: Float = -1f
    @Volatile var tableRight: Float = -1f
    @Volatile var tableBottom: Float = -1f

    // --- Visibility toggles ---
    @Volatile var aimVisible: Boolean = AutoAimPrefs.DEFAULT_AIM_VISIBLE
    @Volatile var tweakPanelVisible: Boolean = AutoAimPrefs.DEFAULT_TWEAK_PANEL_VISIBLE
}
