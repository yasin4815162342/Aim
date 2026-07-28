package com.yas.linedebugger

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Rail-reflection math — ported directly from the Manual app's
 * (AimOverlay) bank-shot correction curve so both apps behave identically
 * on a bank. Given an incoming travel direction and which pair of walls it
 * hit (vertical rail = left/right, horizontal rail = top/bottom), returns
 * the outgoing direction after applying the user's per-angle correction
 * curve, scaled by the rebound-intensity master slider.
 *
 * Restored from the old (Aim-8) implementation: correction is added
 * directly to the theta-from-normal angle and the vector is rebuilt from
 * that angle, rather than applied as a raw 2D rotation of the reflected
 * vector. Adding degrees straight to theta is what makes a slider's
 * number mean what it says — a rotation-based version drifts because a
 * rotation of the vector doesn't equal the same change in this abs-value
 * theta once you're off the first quadrant, so the same slider value
 * could open a bank on one rail and close it on another. That mismatch is
 * what "buggy" was: correction @ 45° behaved differently depending on
 * which rail and which incoming quadrant hit it.
 */
object BankShot {

    // Both ends are physically fixed at 0 correction and not user-adjustable:
    // 90° (dead-on) can't bank at all, and 0° (pure graze) has nothing to
    // bank either. The 22 real control points, 88° down to 4°, cover every
    // angle where a bank correction actually means something.
    private val angles = floatArrayOf(
        90f, 88f, 84f, 80f, 76f, 72f, 68f, 64f, 60f, 56f, 52f, 48f,
        44f, 40f, 36f, 32f, 28f, 24f, 20f, 16f, 12f, 8f, 4f, 0f
    )
    // Live curve, already scaled by rebound intensity. Index 0 (90°) and the
    // last index (0°) are never written by updateCorrectionCurve below, so
    // they stay at FloatArray's zero default permanently — both endpoints
    // are hard-locked to 0 by construction, not just by convention.
    private val corrections = FloatArray(angles.size)

    /** rawCorrections must have angles.size - 2 entries, ordered to match
     * angles[1..angles.size-2] (88° down to 4°), already scaled by rebound
     * intensity. The first and last slots (90°, 0°) are left untouched —
     * they're locked at 0. */
    fun updateCorrectionCurve(rawCorrections: FloatArray, reboundIntensityPercent: Float) {
        val scale = reboundIntensityPercent / 100f
        val last = corrections.size - 1
        corrections[0] = 0f      // 90° — locked
        corrections[last] = 0f   // 0°  — locked
        val n = minOf(rawCorrections.size, last - 1)
        for (i in 0 until n) {
            corrections[i + 1] = rawCorrections[i] * scale
        }
    }

    private fun correctionDegrees(impactAngleDeg: Float): Float {
        if (impactAngleDeg >= angles[0]) return corrections[0]
        val last = angles.size - 1
        if (impactAngleDeg <= angles[last]) return corrections[last]
        for (i in 0 until last) {
            val a0 = angles[i]
            val a1 = angles[i + 1]
            if (impactAngleDeg <= a0 && impactAngleDeg >= a1) {
                val t = (a0 - impactAngleDeg) / (a0 - a1)
                val eased = t * t * (3f - 2f * t)
                return corrections[i] + (corrections[i + 1] - corrections[i]) * eased
            }
        }
        return corrections[last]
    }

    private fun impactAngleDeg(dx: Float, dy: Float, hitVertical: Boolean): Float {
        val normalComp = if (hitVertical) abs(dx) else abs(dy)
        val tangentComp = if (hitVertical) abs(dy) else abs(dx)
        return Math.toDegrees(atan2(normalComp.toDouble(), tangentComp.toDouble())).toFloat()
    }

    /**
     * Returns the reflected outgoing unit direction as [dx, dy], or null if
     * the incoming direction was degenerate (shouldn't happen in practice —
     * callers should just stop drawing that direction in that case).
     */
    fun reflect(dx: Float, dy: Float, hitVertical: Boolean): FloatArray? {
        val impactAngle = impactAngleDeg(dx, dy, hitVertical)

        val normalComp = if (hitVertical) dx else dy
        val tangentComp = if (hitVertical) dy else dx

        val normalMag = abs(normalComp)
        val tangentMag = abs(tangentComp)

        val thetaFromNormal = Math.toDegrees(atan2(tangentMag.toDouble(), normalMag.toDouble())).toFloat()

        val correction = correctionDegrees(impactAngle)
        var correctedTheta = thetaFromNormal + correction
        if (correctedTheta > 90f) correctedTheta = 90f
        if (correctedTheta < 0f) correctedTheta = 0f

        val correctedThetaRad = Math.toRadians(correctedTheta.toDouble())
        val newNormalMag = cos(correctedThetaRad).toFloat()
        val newTangentMag = sin(correctedThetaRad).toFloat()

        val normalSign = if (normalComp < 0f) 1f else -1f
        val tangentSign = if (tangentComp < 0f) -1f else 1f

        val newNormalComp = normalSign * newNormalMag
        val newTangentComp = tangentSign * newTangentMag

        val newDx = if (hitVertical) newNormalComp else newTangentComp
        val newDy = if (hitVertical) newTangentComp else newNormalComp

        val len = hypot(newDx.toDouble(), newDy.toDouble()).toFloat()
        if (len < 1e-4f) return null
        return floatArrayOf(newDx / len, newDy / len)
    }
}
