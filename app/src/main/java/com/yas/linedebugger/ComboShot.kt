package com.yas.linedebugger

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Combo Shot: the mirror-image sibling of [KissShot]. Kiss Shot solves for
 * where the STRIKING ball (CUE) ends up after it kisses off TARGET. Combo
 * Shot doesn't care about CUE's own path at all -- it solves for which
 * point of TARGET needs to be hit so TARGET ITSELF (the money ball) ends
 * up at DEST. Same CUE/TARGET/DEST trio, same ghost ball, opposite
 * question -- see OverlayController's DEST tap-cycle (off -> kiss -> combo).
 *
 * The physics fact that makes this a plain closed form rather than a
 * KissShot-style residual/bisection solve: regardless of the real
 * elasticity (0.95, see KissShot's header doc) or how hard the ball is
 * hit, a struck ball that starts at rest always departs along the line
 * from the ghost-ball centre through its own centre, extended -- the
 * classic "ghost-ball line" every pool player already uses to aim a plain
 * cut shot. That direction never depends on speed or e (only the
 * *distance* TARGET travels does, and this is a static overlay with no
 * notion of speed anyway). So sending TARGET straight at DEST has exactly
 * one solution: the contact normal must point directly away from DEST.
 *
 * Scope, on purpose: DEST is assumed to be somewhere in front of TARGET --
 * reachable in a straight line. This does NOT attempt to bank TARGET off a
 * rail when DEST is behind it from CUE's approach; that needs picking
 * which rail to aim off of and refining against the real (non-mirror)
 * rail-reflection curve, which isn't reliable to get right without being
 * able to build and test it. If the straight-line contact point is on the
 * wrong side of TARGET for CUE to reach (CUE would have to hit through the
 * ball), [solve] returns null rather than a guess -- same "no false
 * positives" rule as KissShot.
 *
 * Not modeled: ball_friction's spin-transfer throw -- same caveat as
 * KissShot, same throw-angle tweak reused here to mop it up.
 */
object ComboShot {

    class Result(
        val ghostX: Float, val ghostY: Float,
        val contactX: Float, val contactY: Float
    )

    fun solve(
        purpleX: Float, purpleY: Float,   // TARGET / the money ball
        destX: Float, destY: Float,       // DEST -- where TARGET must end up
        cueX: Float, cueY: Float,         // CUE / the striking ball
        ballDiameterPx: Float,
        radiusScalePercent: Float,
        throwAngleDeg: Float
    ): Result? {
        val r = ballDiameterPx / 2f * (radiusScalePercent / 100f)

        val wx = destX - purpleX
        val wy = destY - purpleY
        if (hypot(wx, wy) < 2f * r) return null // DEST sits inside TARGET's own footprint

        val apx = purpleX - cueX
        val apy = purpleY - cueY
        val alen = hypot(apx, apy)
        val haveApproach = alen > 1f
        val v1x = if (haveApproach) apx / alen else 0f
        val v1y = if (haveApproach) apy / alen else 0f

        // TARGET's post-collision direction is always exactly along the
        // line from the ghost-ball centre through TARGET's own centre, so
        // the contact normal must point directly away from DEST.
        val alpha = atan2(purpleY - destY, purpleX - destX)

        // Same reachability idea as KissShot: TARGET can only be struck
        // from the side CUE is actually approaching from.
        val reachable = !haveApproach || (v1x * cos(alpha) + v1y * sin(alpha)) < 0f
        if (!reachable) return null

        val throwRad = Math.toRadians(throwAngleDeg.toDouble()).toFloat()
        // Handedness for the throw nudge -- which side of the TARGET->DEST
        // line CUE is approaching from. Keeps the throw's real-world
        // direction consistent no matter where the geometry lands the
        // contact point, same shape as KissShot's usingA tie-break, just
        // against a single already-solved alpha instead of choosing
        // between two mirror candidates.
        val sideRef = wx * (cueY - purpleY) - wy * (cueX - purpleX)
        val finalAlpha = alpha + if (sideRef >= 0f) throwRad else -throwRad

        val nx = cos(finalAlpha)
        val ny = sin(finalAlpha)
        return Result(
            purpleX + nx * (2f * r), purpleY + ny * (2f * r),
            purpleX + nx * r, purpleY + ny * r
        )
    }
}
