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
 */
object BankShot {

    // 90 deg (dead-on hit) is always 0 correction and not user-adjustable.
    // The remaining 17 control points are user-tunable via the sliders.
    private val angles = floatArrayOf(
        90f, 85f, 80f, 75f, 70f, 65f, 60f, 55f, 50f,
        45f, 40f, 35f, 30f, 25f, 20f, 15f, 10f, 5f
    )
    private val corrections = FloatArray(angles.size)

    /** rawCorrections must have angles.size - 1 entries, ordered to match
     * angles[1..] (85 down to 5), already scaled by rebound intensity. */
    fun updateCorrectionCurve(rawCorrections: FloatArray, reboundIntensityPercent: Float) {
        val scale = reboundIntensityPercent / 100f
        corrections[0] = 0f
        for (i in rawCorrections.indices) {
            if (i + 1 < corrections.size) {
                corrections[i + 1] = rawCorrections[i] * scale
            }
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
