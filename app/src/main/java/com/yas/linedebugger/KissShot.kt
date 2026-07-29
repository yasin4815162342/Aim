package com.yas.linedebugger

import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.hypot

// Rewritten from the game's real chipmunk.js collision solver
// (physicsData/PoolPhysics.js): ball-ball fixtures use
// ball_elasticity: 0.95, ball_friction: 0.1 (both identical on every
// ball, so they apply directly with no combine-rule ambiguity).
//
// Chipmunk resolves a circle-circle hit as a standard sequential-impulse
// collision: restitution e applied along the contact normal n, tangential
// component preserved. For equal masses, target initially at rest, that
// works out to a clean closed form for the incident ball's (Blue's)
// outgoing velocity:
//
//   v1' = v1 - n * (v1 . n) * (1 + e) / 2
//
// At e = 1 this reduces to v1' = v1 - n*(v1.n)*n, which is ALWAYS exactly
// perpendicular to n no matter what v1 is -- that's the old "ghost ball"
// assumption, and it's what let the previous version solve contact from
// Purple/Dest alone. At the real e = 0.95, v1' keeps a small residual
// component along n (~2.5% of the closing speed), so the true outgoing
// direction depends slightly on Blue's actual incoming direction too --
// it's no longer 100% angle-of-approach-independent. We fold that in
// below by seeding from the old e=1 closed form, then refining a few
// steps against the real formula using the CUE->TARGET line as Blue's
// approach direction, since that's the only estimate of it we have.
//
// NOT modeled: ball_friction's spin-transfer throw (needs simulated
// spin/speed we don't track for a static ghost-ball overlay). That,
// plus real-world/engine slop, is what the throw-correction tweak below
// is for.
object KissShot {

    const val SIDE_AUTO = 0
    const val SIDE_LEFT = 1
    const val SIDE_RIGHT = 2

    private const val BALL_ELASTICITY = 0.95f
    private const val REFINE_BRACKET_RAD = 0.5236f // 30 deg, generous vs. the ~1-16 deg real range
    private const val REFINE_STEPS = 24

    // [ghostCenterX, ghostCenterY, contactX, contactY], or null if the
    // destination is closer to purple's centre than one ball-diameter
    // (geometrically impossible).
    fun solve(
        purpleX: Float, purpleY: Float,
        destX: Float, destY: Float,
        blueX: Float, blueY: Float,
        cueX: Float, cueY: Float,
        ballDiameterPx: Float,
        radiusScalePercent: Float,
        throwAngleDeg: Float,
        sideLock: Int
    ): FloatArray? {
        val r = ballDiameterPx / 2f * (radiusScalePercent / 100f)
        val wx = destX - purpleX
        val wy = destY - purpleY
        val d = hypot(wx, wy)
        if (d < 2f * r) return null

        val cosT = (2f * r / d).coerceIn(-1f, 1f)
        val theta = acos(cosT)
        val phi = atan2(wy, wx)

        val sideRef = when (sideLock) {
            SIDE_LEFT -> -1f
            SIDE_RIGHT -> 1f
            else -> wx * (blueY - purpleY) - wy * (blueX - purpleX)
        }

        var alpha = phi + theta
        val cross0 = wx * sin(alpha) - wy * cos(alpha)
        val positive = sideRef >= 0f
        if ((cross0 >= 0f) != positive) alpha = phi - theta

        // Refine against the real e=0.95 formula using Blue's approach
        // direction (CUE->TARGET). Skipped if that line is degenerate.
        val ivx = blueX - cueX
        val ivy = blueY - cueY
        val ilen = hypot(ivx, ivy)
        if (ilen > 1f) {
            val v1x = ivx / ilen
            val v1y = ivy / ilen
            val k = (1f + BALL_ELASTICITY) / 2f

            fun residual(a: Float): Float {
                val nx = cos(a); val ny = sin(a)
                val dot = v1x * nx + v1y * ny
                val outX = v1x - nx * dot * k
                val outY = v1y - ny * dot * k
                val gx = purpleX + nx * 2f * r
                val gy = purpleY + ny * 2f * r
                val tx = destX - gx
                val ty = destY - gy
                return outX * ty - outY * tx
            }

            var lo = alpha - REFINE_BRACKET_RAD
            var hi = alpha + REFINE_BRACKET_RAD
            var fLo = residual(lo)
            val fHi = residual(hi)
            if (fLo * fHi < 0f) {
                repeat(REFINE_STEPS) {
                    val mid = (lo + hi) / 2f
                    val fMid = residual(mid)
                    if ((fMid >= 0f) == (fLo >= 0f)) { lo = mid; fLo = fMid } else { hi = mid }
                }
                alpha = (lo + hi) / 2f
            }
        }

        val throwRad = Math.toRadians(throwAngleDeg.toDouble()).toFloat()
        alpha += if (positive) throwRad else -throwRad

        val nx = cos(alpha)
        val ny = sin(alpha)
        return floatArrayOf(
            purpleX + nx * (2f * r), purpleY + ny * (2f * r),
            purpleX + nx * r, purpleY + ny * r
        )
    }
}
