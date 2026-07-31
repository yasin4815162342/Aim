package com.yas.linedebugger

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Rail-reflection math for the Bank Shot ecosystem — rewritten to follow
 * the real game's Chipmunk physics engine (PoolPhysics.js / chipmunk.js)
 * for anything bank-shot related. This is NOT a plain mirror bounce: a
 * rail-vs-ball collision combines the ball's own elasticity (0.95) with
 * the rail's (1) — giving 0.95 net restitution, not 1 — and friction
 * (ball 0.1 × rail 0.8 = 0.08) acts on the tangential contact velocity.
 * Because that friction impulse is applied at the contact point (offset
 * from the ball's center), it also imparts torque, converting some
 * tangential motion into spin — an effect that is NOT uniform across
 * incoming angles. Net result: replaying Chipmunk's own sequential-impulse
 * formulas for a clean no-spin first bounce shows the ball comes off
 * tighter than a mirror almost everywhere (biggest gap around 64°), true
 * mirror only around 18°, then slightly wider than a mirror from about
 * 16° down to 4°. See AutoAimPrefs.DEFAULT_BANK_CORRECTIONS for the
 * derived per-angle values that encode this curve by default.
 *
 * The old rebound-intensity master slider is gone — there's no longer a
 * global scale knob sitting between the correction curve and the
 * reflection, since Chipmunk doesn't have one either. The per-angle
 * correction curve is still a slider a user can nudge further (e.g. to
 * compensate for a specific physical table), but it now starts from the
 * real Chipmunk-derived shape instead of a hand-tuned guess or a flat
 * zero.
 *
 * Correction is added directly to the theta-from-normal angle and the
 * vector is rebuilt from that angle, rather than applied as a raw 2D
 * rotation of the reflected vector. Adding degrees straight to theta is
 * what makes a slider's number mean what it says — a rotation-based
 * version drifts because a rotation of the vector doesn't equal the same
 * change in this abs-value theta once you're off the first quadrant, so
 * the same slider value could open a bank on one rail and close it on
 * another.
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
    // Live curve. Index 0 (90°) and the last index (0°) are never written by
    // updateCorrectionCurve below, so they stay at FloatArray's zero default
    // permanently — both endpoints are hard-locked to 0 by construction, not
    // just by convention. Every other slot is seeded from the Chipmunk-derived
    // curve in AutoAimPrefs.DEFAULT_BANK_CORRECTIONS on first load.
    private val corrections = FloatArray(angles.size)

    /** rawCorrections must have angles.size - 2 entries, ordered to match
     * angles[1..angles.size-2] (88° down to 4°). The first and last slots
     * (90°, 0°) are left untouched — they're locked at 0. */
    fun updateCorrectionCurve(rawCorrections: FloatArray) {
        val last = corrections.size - 1
        corrections[0] = 0f      // 90° — locked
        corrections[last] = 0f   // 0°  — locked
        val n = minOf(rawCorrections.size, last - 1)
        for (i in 0 until n) {
            corrections[i + 1] = rawCorrections[i]
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
     * Pure mirror reflection — no correction curve applied. Bug fix (Double
     * Bank Shots): the correction curve above is derived from replaying
     * Chipmunk's impulse solver for a clean, no-spin FIRST bounce only (see
     * the class doc). The rail-friction impulse on that first bounce imparts
     * torque, so the ball carries real spin into any second (or third)
     * bounce — spin the curve was never derived for. Re-applying the same
     * no-spin curve to every segment of a walked bank path compounds a few
     * degrees of error per extra bounce, which on a long second segment is
     * enough to flip which rail gets hit entirely. A single bank shot only
     * ever hits one rail, so it was never affected — this only matters from
     * the second bounce of a double (or later bounces of a triple) onward.
     * Callers should use [reflect] for a path's first bounce and this for
     * every bounce after it.
     */
    fun reflectMirror(dx: Float, dy: Float, hitVertical: Boolean): FloatArray? {
        val len = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (len < 1e-4f) return null
        return if (hitVertical) {
            floatArrayOf(-dx / len, dy / len)
        } else {
            floatArrayOf(dx / len, -dy / len)
        }
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
