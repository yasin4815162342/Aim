package com.yas.linedebugger

import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.hypot

// Ball-to-ball kiss geometry, reusing the existing CUE/TARGET pair:
// TARGET = the ball being kissed off of (purple), CUE = the moving
// ball's approach point (blue's start / aim anchor) -- same "not
// necessarily the real cue ball" idea the app already uses for bank
// shots. DEST is the only dedicated new point (pocket target).
//
// Collision physics extracted from the game's real chipmunk.js solver
// (physicsData/PoolPhysics.js): ball_elasticity: 0.95, identical on
// every ball. A circle-circle hit resolves as restitution e along the
// contact normal n, tangential component preserved -- for equal masses,
// target initially at rest, that's:
//
//   v1' = v1 - n * (v1 . n) * (1 + e) / 2
//
// At e=1 this is always exactly perpendicular to n regardless of v1 --
// that's the classic ghost-ball assumption, and it's what lets the base
// solution come from TARGET/DEST alone. At the real e=0.95 there's a
// small residual along n, so we seed from the e=1 closed form and
// refine a few steps against the real formula using CUE->TARGET as
// blue's approach direction (the only estimate of it available).
//
// Not modeled: ball_friction's spin-transfer throw (needs simulated
// spin/speed a static overlay doesn't have). That, plus engine/
// calibration slop, is what the throw-correction tweak is for.
//
// Impossibility check: a ball can only be struck from the side it's
// actually approaching from. Of the two geometrically valid contact
// points (mirror images across the TARGET-DEST line), if NEITHER is on
// the side CUE's direction actually reaches, the kiss is not physically
// achievable -- solve() returns null rather than a wrong answer.
object KissShot {

    const val SIDE_AUTO = 0
    const val SIDE_LEFT = 1
    const val SIDE_RIGHT = 2

    private const val BALL_ELASTICITY = 0.95f
    private const val REFINE_BRACKET_RAD = 0.5236f // 30 deg, generous vs. the ~1-16 deg real range
    private const val REFINE_STEPS = 24

    // [ghostCenterX, ghostCenterY, contactX, contactY], or null if
    // impossible (destination unreachably close to purple's centre, or
    // no valid contact point is on CUE's approach side).
    fun solve(
        purpleX: Float, purpleY: Float,   // TARGET
        destX: Float, destY: Float,       // DEST
        cueX: Float, cueY: Float,         // CUE
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
        val alphaA = phi + theta
        val alphaB = phi - theta

        val apx = purpleX - cueX
        val apy = purpleY - cueY
        val alen = hypot(apx, apy)
        val haveApproach = alen > 1f
        val v1x = if (haveApproach) apx / alen else 0f
        val v1y = if (haveApproach) apy / alen else 0f

        // n = (cos a, sin a) points from purple's centre toward the
        // contact/ghost position. Reachable only if blue is actually
        // heading into -n (i.e. approaching from that side).
        fun reachable(a: Float) = !haveApproach || (v1x * cos(a) + v1y * sin(a)) < 0f

        var alpha: Float
        var usingA: Boolean
        when (sideLock) {
            SIDE_LEFT -> { alpha = alphaA; usingA = true }
            SIDE_RIGHT -> { alpha = alphaB; usingA = false }
            else -> {
                val okA = reachable(alphaA)
                val okB = reachable(alphaB)
                when {
                    okA && !okB -> { alpha = alphaA; usingA = true }
                    okB && !okA -> { alpha = alphaB; usingA = false }
                    !okA && !okB -> return null // impossible from this approach
                    else -> {
                        val sideRef = wx * (cueY - purpleY) - wy * (cueX - purpleX)
                        val crossA = wx * sin(alphaA) - wy * cos(alphaA)
                        if ((crossA >= 0f) == (sideRef >= 0f)) {
                            alpha = alphaA; usingA = true
                        } else {
                            alpha = alphaB; usingA = false
                        }
                    }
                }
            }
        }

        if (haveApproach) {
            val k = (1f + BALL_ELASTICITY) / 2f
            fun residual(a: Float): Float {
                val nx = cos(a); val ny = sin(a)
                val dot = v1x * nx + v1y * ny
                val outX = v1x - nx * dot * k
                val outY = v1y - ny * dot * k
                val gx = purpleX + nx * 2f * r
                val gy = purpleY + ny * 2f * r
                return outX * (destY - gy) - outY * (destX - gx)
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
        alpha += if (usingA) throwRad else -throwRad

        val nx = cos(alpha)
        val ny = sin(alpha)
        return floatArrayOf(
            purpleX + nx * (2f * r), purpleY + ny * (2f * r),
            purpleX + nx * r, purpleY + ny * r
        )
    }
}
