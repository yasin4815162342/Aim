package com.yas.linedebugger

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Rail-reflection math — shared by automatic ray and manual CUE/TARGET.
 *
 * Model (deliberately simple so slider values are never "swallowed"):
 *  1. Pure geometric reflection (flip the wall-normal component).
 *  2. Rotate that pure-reflected unit vector by `correctionDegrees`
 *     (looked up from the user's curve at the impact angle, then scaled
 *     by rebound intensity). Positive rotation opens the bounce toward
 *     the tangent; negative closes it toward the normal (mirror-steep).
 *
 * Impact angle convention: 0° = grazing along the rail, 90° = dead-on
 * into the cushion. Matches the slider labels ("Correction @ 45°" etc.).
 */
object BankShot {

    // 90 deg (dead-on) is always 0 correction and not user-adjustable.
    // Remaining 17 control points match AutoAimPrefs.BANK_ANGLES order.
    private val angles = floatArrayOf(
        90f, 85f, 80f, 75f, 70f, 65f, 60f, 55f, 50f,
        45f, 40f, 35f, 30f, 25f, 20f, 15f, 10f, 5f
    )
    // Live curve, already scaled by rebound intensity. Index 0 (90°) stays 0.
    private val corrections = FloatArray(angles.size)

    /**
     * @param rawCorrections length = angles.size - 1, ordered 85° → 5°
     * @param reboundIntensity -100..+100 → scale = intensity/100
     *        (0 = pure reflection, negative inverts the whole curve)
     */
    fun updateCorrectionCurve(rawCorrections: FloatArray, reboundIntensity: Float) {
        val scale = reboundIntensity / 100f
        corrections[0] = 0f
        val n = minOf(rawCorrections.size, corrections.size - 1)
        for (i in 0 until n) {
            corrections[i + 1] = rawCorrections[i] * scale
        }
        // Clear any leftover slots if raw was shorter than expected.
        for (i in (n + 1) until corrections.size) {
            corrections[i] = 0f
        }
    }

    /** Piecewise-smooth lookup on the live curve. */
    private fun correctionDegrees(impactAngleDeg: Float): Float {
        if (impactAngleDeg >= angles[0]) return corrections[0]
        val last = angles.size - 1
        if (impactAngleDeg <= angles[last]) return corrections[last]
        for (i in 0 until last) {
            val a0 = angles[i]
            val a1 = angles[i + 1]
            if (impactAngleDeg <= a0 && impactAngleDeg >= a1) {
                val t = (a0 - impactAngleDeg) / (a0 - a1)
                // Smoothstep so the curve doesn't kink at control points.
                val eased = t * t * (3f - 2f * t)
                return corrections[i] + (corrections[i + 1] - corrections[i]) * eased
            }
        }
        return corrections[last]
    }

    /**
     * Impact angle from the rail surface: 0° = grazing, 90° = head-on.
     * Uses the *incoming* direction and which wall was hit.
     */
    private fun impactAngleDeg(dx: Float, dy: Float, hitVertical: Boolean): Float {
        val normalComp = if (hitVertical) abs(dx) else abs(dy)
        val tangentComp = if (hitVertical) abs(dy) else abs(dx)
        if (normalComp + tangentComp < 1e-8f) return 90f
        return Math.toDegrees(atan2(normalComp.toDouble(), tangentComp.toDouble())).toFloat()
    }

    /**
     * Returns the corrected outgoing unit direction [dx, dy], or null if
     * the incoming direction is degenerate.
     *
     * Step 1 — pure reflection (flip the normal component of the wall).
     * Step 2 — rotate that result by the curve's correction (degrees).
     *          Rotation sense is chosen so positive correction increases
     *          the angle between the outgoing ray and the wall normal
     *          (opens the bank); negative decreases it (toward mirror).
     */
    fun reflect(dx: Float, dy: Float, hitVertical: Boolean): FloatArray? {
        val lenIn = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (lenIn < 1e-4f) return null

        // --- 1. Pure geometric reflection ---
        val pureDx: Float
        val pureDy: Float
        if (hitVertical) {
            pureDx = -dx
            pureDy = dy
        } else {
            pureDx = dx
            pureDy = -dy
        }
        val pureLen = hypot(pureDx.toDouble(), pureDy.toDouble()).toFloat()
        if (pureLen < 1e-4f) return null
        val ux = pureDx / pureLen
        val uy = pureDy / pureLen

        // --- 2. Curve correction as an explicit 2D rotation ---
        val impact = impactAngleDeg(dx, dy, hitVertical)
        val corrDeg = correctionDegrees(impact)
        if (abs(corrDeg) < 1e-4f) {
            // Pure reflection, no curve applied.
            return floatArrayOf(ux, uy)
        }

        // Rotation direction: we want +corr to open the angle from the
        // wall normal. The wall normal pointing *into the table* is:
        //   vertical hit from the right (dx>0 before bounce) → normal = (-1,0)
        //   after pure reflect, outgoing is leftward.
        // A positive (counter-clockwise) rotation of the outgoing vector
        // increases the tangent component relative to that inward normal
        // when the tangent sign matches the incoming tangent. Using a
        // signed rotation based on the incoming tangent keeps the
        // "open/close" sense consistent on every wall.
        val incomingTangent = if (hitVertical) dy else dx
        val sense = if (incomingTangent >= 0f) 1f else -1f
        val rad = Math.toRadians((corrDeg * sense).toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        // Standard 2D rotation: [c -s; s c]
        val outDx = ux * c - uy * s
        val outDy = ux * s + uy * c

        val outLen = hypot(outDx.toDouble(), outDy.toDouble()).toFloat()
        if (outLen < 1e-4f) return null
        return floatArrayOf(outDx / outLen, outDy / outLen)
    }

    /** Debug/test helper: current live correction at a given impact angle. */
    fun debugCorrectionAt(impactAngleDeg: Float): Float = correctionDegrees(impactAngleDeg)
}
