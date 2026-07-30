package com.yas.linedebugger

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Bank-shot reflection using the **exact** Chipmunk contact solver from the
 * original pool game — not a hand-written approximation.
 *
 * Source material (game code):
 *
 *   PoolPhysics.js
 *     ball_elasticity = 0.95
 *     ball_friction   = 0.1
 *     ball mass       = 1
 *
 *   physicsData.js  (end_rail / side_rail / side_rail_r)
 *     rail elasticity = 1.0
 *     rail friction   = 0.8
 *     rails are static (infinite mass)
 *
 *   chipmunk.js  Arbiter.update + Arbiter.applyImpulse
 *     e = a.e * b.e          →  0.95 * 1.0 = 0.95
 *     u = a.u * b.u          →  0.1  * 0.8 = 0.08
 *     nMass = 1 / k_scalar   →  1 for m=1 vs static
 *     tMass = 1
 *     bounce = vrn * e
 *     jn    = -(bounce + vrn) * nMass = -vrn * (1 + e)
 *     jnAcc = max(0, jn)
 *     jtMax = u * jnAcc
 *     jt    = clamp(-vrt * tMass, -jtMax, jtMax)
 *     apply impulse to ball velocity
 *
 * Direction after the bounce is independent of speed (both jn and jtMax
 * scale linearly with |v|), matching in-game tests at any power.
 *
 * Used only when Tunables.chipmunkMode is true. BankShot's correction curve
 * and rebound-intensity are fully bypassed.
 */
object BankShotChipmunk {

    // ---- exact game constants (PoolPhysics.js + physicsData.js) ----
    private const val BALL_E = 0.95f
    private const val BALL_U = 0.1f
    private const val RAIL_E = 1.0f
    private const val RAIL_U = 0.8f

    // Chipmunk multiplies the pair coefficients (Arbiter.update)
    private const val E = BALL_E * RAIL_E   // 0.95
    private const val U = BALL_U * RAIL_U   // 0.08

    /**
     * Reflect incoming unit (or any-scale) direction off an axis-aligned rail
     * using Chipmunk's impulse solver for circle vs static plane.
     *
     * @param dx, dy       incoming travel direction
     * @param hitVertical  true  = left/right rail (normal along X)
     *                     false = top/bottom rail (normal along Y)
     * @return outgoing unit direction, or null if degenerate
     */
    fun reflect(dx: Float, dy: Float, hitVertical: Boolean): FloatArray? {
        // Wall normal pointing *into the table* (away from the cushion).
        // For axis-aligned rails the sign of the incoming normal component
        // tells us which side we hit; we orient n so the ball is approaching
        // (vrn < 0), which is what Chipmunk expects before applying jn.
        val nx: Float
        val ny: Float
        if (hitVertical) {
            // left or right rail — normal is ±X
            nx = if (dx > 0f) -1f else 1f   // oppose the inbound X motion
            ny = 0f
        } else {
            // top or bottom rail — normal is ±Y
            nx = 0f
            ny = if (dy > 0f) -1f else 1f
        }

        return reflectAgainstNormal(dx, dy, nx, ny)
    }

    /**
     * Core: one Chipmunk contact resolution against a static plane.
     *
     * Port of chipmunk.js Arbiter.preStep (bounce/nMass/tMass) +
     * Arbiter.applyImpulse (jnAcc / jtAcc) for infinite-mass body A
     * and mass-1 body B.
     *
     * n must point from the wall toward the ball (into the playable area)
     * so that an approaching ball has vrn < 0.
     */
    fun reflectAgainstNormal(vx: Float, vy: Float, nx: Float, ny: Float): FloatArray? {
        // Relative velocity at contact (wall velocity = 0)
        // vr = v_ball
        val vrn = vx * nx + vy * ny                // normal component
        val tx = -ny
        val ty = nx
        val vrt = vx * tx + vy * ty                // tangential component

        // Separating or grazing with no penetration velocity → no impulse
        if (vrn >= 0f) {
            val len = hypot(vx.toDouble(), vy.toDouble()).toFloat()
            if (len < 1e-4f) return null
            return floatArrayOf(vx / len, vy / len)
        }

        // --- normal impulse (chipmunk.js applyImpulse) ---
        // nMass = 1 for m=1 vs static
        // bounce = vrn * e
        // jn = -(bounce + vrn) * nMass = -vrn * (1 + e)
        var jn = -vrn * (1f + E)
        if (jn < 0f) jn = 0f                       // jnAcc = max(0, …)

        // --- friction impulse (Coulomb clamp) ---
        // tMass = 1
        // jtMax = u * jnAcc
        // jt = clamp(-vrt * tMass, -jtMax, jtMax)
        val jtMax = U * jn
        var jt = -vrt
        if (jt > jtMax) jt = jtMax
        if (jt < -jtMax) jt = -jtMax

        // Apply impulse to the ball.
        // Chipmunk applies +j to body B along (jn * n + jt * t) when the
        // normal points from A→B. Our n points wall→ball (= A→B), so:
        //   Δv = jn * n + jt * t
        val outVx = vx + jn * nx + jt * tx
        val outVy = vy + jn * ny + jt * ty

        val len = hypot(outVx.toDouble(), outVy.toDouble()).toFloat()
        if (len < 1e-4f) return null
        return floatArrayOf(outVx / len, outVy / len)
    }

    /** Diagnostic: effective pair coefficients the game actually uses. */
    fun effectiveElasticity(): Float = E
    fun effectiveFriction(): Float = U
}
