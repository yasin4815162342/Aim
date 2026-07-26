package com.yas.linedebugger

/**
 * Every knob the tweak panel exposes. Plain @Volatile fields, read from the
 * background frame-processing thread and written from the UI thread — good
 * enough for a debug tool where torn reads just mean "one stale frame."
 */
object Tunables {
    @Volatile var greenDiff: Int = 15          // how far G must exceed R and B to count as felt
    @Volatile var minBrightness: Int = 20      // reject near-black shadow pixels as "not line"
    @Volatile var ballErodeRadius: Int = 4     // erosion radius that erases the thin line, leaves ball cores
    @Volatile var ballDilateGrow: Int = 5      // extra growth to restore the ball's true footprint
    @Volatile var minLinePixels: Int = 15      // below this many surviving pixels, refuse to guess an angle
    @Volatile var outlierTrimK: Float = 2.5f   // outlier-rejection threshold, in std-devs of perpendicular residual
    @Volatile var circleDiameter: Int = 100    // the draggable controller's size in px
    @Volatile var widthMultiplier: Float = 1.0f
    @Volatile var circleAlpha: Int = 140       // 0-255, the controller circle's opacity
    @Volatile var showDebugPreview: Boolean = true
}
