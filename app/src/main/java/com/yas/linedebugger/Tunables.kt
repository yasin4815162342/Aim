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

    // --- Detection: color strategy (bug #2 — green/brown/yellow guideline
    //     recovery). Three selectable candidate-pixel classifiers, all
    //     feeding the same downstream ball-removal + line-fit pipeline
    //     above. See LineDetector for the actual classifiers. ---
    @Volatile var detectionMode: Int = AutoAimPrefs.DEFAULT_DETECTION_MODE
    // "Felt" reference color in HSV. A pixel close to this reference in
    // hue AND saturation AND value is background and gets discarded;
    // differing enough in *any one* of the three lets it through — that's
    // what lets a bright or desaturated green guideline survive even
    // though its hue matches the felt's.
    @Volatile var feltHueDeg: Float = AutoAimPrefs.DEFAULT_FELT_HUE_DEG
    @Volatile var feltHueToleranceDeg: Float = AutoAimPrefs.DEFAULT_FELT_HUE_TOLERANCE_DEG
    @Volatile var feltSat: Int = AutoAimPrefs.DEFAULT_FELT_SAT
    @Volatile var feltSatTolerance: Int = AutoAimPrefs.DEFAULT_FELT_SAT_TOLERANCE
    @Volatile var feltVal: Int = AutoAimPrefs.DEFAULT_FELT_VAL
    @Volatile var feltValTolerance: Int = AutoAimPrefs.DEFAULT_FELT_VAL_TOLERANCE
    // Optional second background reference for the wood rail/cushion ring
    // around the felt — same "close in all three -> discard" rule as felt
    // above. Off by default; turn on if a brown guideline near a rail is
    // getting contaminated by the cushion color.
    @Volatile var railExclusionEnabled: Boolean = AutoAimPrefs.DEFAULT_RAIL_EXCLUSION_ENABLED
    @Volatile var railHueDeg: Float = AutoAimPrefs.DEFAULT_RAIL_HUE_DEG
    @Volatile var railHueToleranceDeg: Float = AutoAimPrefs.DEFAULT_RAIL_HUE_TOLERANCE_DEG
    @Volatile var railSat: Int = AutoAimPrefs.DEFAULT_RAIL_SAT
    @Volatile var railSatTolerance: Int = AutoAimPrefs.DEFAULT_RAIL_SAT_TOLERANCE
    @Volatile var railVal: Int = AutoAimPrefs.DEFAULT_RAIL_VAL
    @Volatile var railValTolerance: Int = AutoAimPrefs.DEFAULT_RAIL_VAL_TOLERANCE

    // --- Ray Circle (the draggable detection-area controller) ---
    @Volatile var circleDiameter: Int = AutoAimPrefs.DEFAULT_CIRCLE_DIAMETER  // the draggable controller's size in px
    @Volatile var circleAlpha: Int = AutoAimPrefs.DEFAULT_CIRCLE_ALPHA       // 0-255, the controller circle's opacity

    // --- Ray Monitor: the raw-pixel debug preview + status text overlay ---
    @Volatile var rayMonitorEnabled: Boolean = AutoAimPrefs.DEFAULT_RAY_MONITOR_ENABLED

    // --- Capture resolution vs accuracy trade-off. Read once per capture
    //     session start (CaptureService) — changing this mid-session needs
    //     a Stop + Start to take effect, since it resizes the VirtualDisplay
    //     and ImageReader. See AutoAimPrefs for the full explanation. ---
    @Volatile var captureScale: Float = AutoAimPrefs.DEFAULT_CAPTURE_SCALE

    // --- Auto-aim line look. Manual now — no longer derived from the
    //     detected target ball's color/width. ---
    @Volatile var autoAimWidthPx: Float = AutoAimPrefs.DEFAULT_AUTO_AIM_WIDTH_PX
    @Volatile var autoAimOpacity: Int = AutoAimPrefs.DEFAULT_AUTO_AIM_OPACITY
    @Volatile var autoAimColor: Int = AutoAimPrefs.DEFAULT_AUTO_AIM_COLOR

    // --- Bank shot / multi-segment rendering (ported from the Manual app).
    //     Shared by BOTH the automatic and manual controllers, since a
    //     bank is the same physical event regardless of which controller
    //     is aiming it — see BankShot.kt and bug #3 (ghost-ball centering
    //     below). ---
    @Volatile var maxLines: Int = AutoAimPrefs.DEFAULT_MAX_LINES
    @Volatile var doubleLineEnabled: Boolean = AutoAimPrefs.DEFAULT_DOUBLE_LINE_ENABLED
    @Volatile var dashedLineEnabled: Boolean = AutoAimPrefs.DEFAULT_DASHED_LINE_ENABLED
    @Volatile var doubleLineWidthPx: Float = AutoAimPrefs.DEFAULT_DOUBLE_LINE_WIDTH_PX
    @Volatile var bankMarkerEnabled: Boolean = AutoAimPrefs.DEFAULT_BANK_MARKER_ENABLED
    // Bug #3 fix: the ball diameter used to (a) inset the table rect so a
    // rail bounce reflects off the ball's CENTER — flush against the rail,
    // not the table edge itself — and (b) size the ghost-ball marker ring.
    // One shared value so the automatic ray and the manual CUE/TARGET
    // balls bounce identically, per the bug report's "shared resource"
    // note.
    @Volatile var ghostBallDiameterPx: Float = AutoAimPrefs.DEFAULT_GHOST_BALL_DIAMETER_PX
    // Chipmunk / game-physics mode: pure optical reflection matching the
    // original pool game's rail e=1.0. When true, BankShot.reflect ignores
    // the entire correction curve and rebound-intensity slider and delegates
    // to BankShotChipmunk. See BankShotChipmunk.kt.
    @Volatile var chipmunkMode: Boolean = AutoAimPrefs.DEFAULT_CHIPMUNK_MODE

    // --- Table calibration (ported from the Manual app; -1 = uncalibrated) ---
    @Volatile var tableLeft: Float = -1f
    @Volatile var tableTop: Float = -1f
    @Volatile var tableRight: Float = -1f
    @Volatile var tableBottom: Float = -1f

    // --- Visibility toggles ---
    @Volatile var aimVisible: Boolean = AutoAimPrefs.DEFAULT_AIM_VISIBLE
    @Volatile var tweakPanelVisible: Boolean = AutoAimPrefs.DEFAULT_TWEAK_PANEL_VISIBLE

    // --- Manual CUE / TARGET controller (ported from the Manual app's
    //     Handle + LineOverlay — see feature request #1). Every field
    //     below is manual-controller-only: nothing in the automatic path
    //     reads any of it, so dialing it in from MainActivity's "Manual
    //     Controller" section never changes the automatic aim line. The
    //     controller still shares BankShot's reflection math, the table
    //     calibration above, and ghostBallDiameterPx above with the
    //     automatic path — only the manual line's *look* and drag feel
    //     are kept separate. ---
    @Volatile var manualControllerEnabled: Boolean = AutoAimPrefs.DEFAULT_MANUAL_CONTROLLER_ENABLED
    @Volatile var manualSensitivity: Float = AutoAimPrefs.DEFAULT_MANUAL_SENSITIVITY
    @Volatile var manualLineWidthPx: Float = AutoAimPrefs.DEFAULT_MANUAL_LINE_WIDTH_PX
    @Volatile var manualLineOpacity: Int = AutoAimPrefs.DEFAULT_MANUAL_LINE_OPACITY
    @Volatile var manualLineColor: Int = AutoAimPrefs.DEFAULT_MANUAL_LINE_COLOR
    @Volatile var manualDoubleLineEnabled: Boolean = AutoAimPrefs.DEFAULT_MANUAL_DOUBLE_LINE_ENABLED
    @Volatile var manualDoubleLineWidthOffsetPx: Float = AutoAimPrefs.DEFAULT_MANUAL_DOUBLE_LINE_WIDTH_OFFSET_PX
    @Volatile var manualDashedLineEnabled: Boolean = AutoAimPrefs.DEFAULT_MANUAL_DASHED_LINE_ENABLED
    @Volatile var manualGhostRailEnabled: Boolean = AutoAimPrefs.DEFAULT_MANUAL_GHOST_RAIL_ENABLED

    // --- Manual KISS / DEST controller (kiss-shot assist). KISS sits on
    // the ball being kissed off of, DEST on the pocket. Same "manual-only"
    // isolation as the CUE/TARGET section above. ---
    @Volatile var manualKissEnabled: Boolean = AutoAimPrefs.DEFAULT_MANUAL_KISS_ENABLED
    // Tweak 1: corrects a mismatch between the ghost-ball diameter and the
    // real in-game ball's collision size — shifts where along purple's
    // edge contact is solved for.
    @Volatile var manualKissRadiusScalePercent: Float = AutoAimPrefs.DEFAULT_MANUAL_KISS_RADIUS_SCALE_PERCENT
    // Tweak 2: fudge factor for non-ideal (non-spin-free) transfer physics
    // — rotates the solved contact point by a fixed angle.
    @Volatile var manualKissThrowAngleDeg: Float = AutoAimPrefs.DEFAULT_MANUAL_KISS_THROW_ANGLE_DEG
    // Tweak 3: the geometry has two mirror-image solutions; auto-pick can
    // flip unpredictably when Blue sits near the purple-to-pocket line —
    // this locks it to one side. 0=auto, 1=left, 2=right (KissShot.SIDE_*).
    @Volatile var manualKissSideLock: Int = AutoAimPrefs.DEFAULT_MANUAL_KISS_SIDE_LOCK
}
