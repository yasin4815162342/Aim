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
    @Volatile var minBrightness: Int = AutoAimPrefs.DEFAULT_MIN_BRIGHTNESS  // reject near-black shadow pixels as "not line"
    @Volatile var ballErodeRadius: Int = AutoAimPrefs.DEFAULT_BALL_ERODE_RADIUS   // erosion radius that erases the thin line, leaves ball cores
    @Volatile var ballDilateGrow: Int = AutoAimPrefs.DEFAULT_BALL_DILATE_GROW     // extra growth to restore the ball's true footprint
    @Volatile var minLinePixels: Int = AutoAimPrefs.DEFAULT_MIN_LINE_PIXELS       // below this many surviving pixels, refuse to guess an angle
    @Volatile var outlierTrimK: Float = AutoAimPrefs.DEFAULT_OUTLIER_TRIM_K       // outlier-rejection threshold, in std-devs of perpendicular residual

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

    // --- Table calibration (ported from the Manual app; -1 = uncalibrated) ---
    @Volatile var tableLeft: Float = -1f
    @Volatile var tableTop: Float = -1f
    @Volatile var tableRight: Float = -1f
    @Volatile var tableBottom: Float = -1f

    // --- Visibility toggles ---
    @Volatile var aimVisible: Boolean = AutoAimPrefs.DEFAULT_AIM_VISIBLE
    @Volatile var tweakPanelVisible: Boolean = AutoAimPrefs.DEFAULT_TWEAK_PANEL_VISIBLE
}
