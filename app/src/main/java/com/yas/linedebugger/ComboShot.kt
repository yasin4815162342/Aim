package com.yas.linedebugger

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Combo Shot: the mirror-image sibling of [KissShot]. Kiss Shot solves for
 * where the STRIKING ball (CUE) ends up after it kisses off TARGET. Combo
 * Shot doesn't care about CUE at all -- it isn't even part of this solve
 * (see OverlayController's updateCueSuppression, which hides CUE's handle
 * entirely while combo mode is active; aiming is done for real with a
 * separate, unrelated auto-line feature). Combo solves for which point of
 * TARGET needs to be hit so TARGET ITSELF (the money ball) ends up at
 * DEST -- same TARGET/DEST pair, same ghost ball, opposite question from
 * Kiss Shot -- see OverlayController's DEST tap-cycle (off -> kiss ->
 * combo).
 *
 * The physics fact that makes this a plain closed form: regardless of the
 * real elasticity (0.95, see KissShot's header doc) or how hard the ball
 * is hit, a struck ball that starts at rest always departs along the line
 * from the ghost-ball centre through its own centre, extended -- the
 * classic "ghost-ball line" every pool player already uses to aim a plain
 * cut shot. That direction never depends on speed or e (only the
 * *distance* TARGET travels does, and this is a static overlay with no
 * notion of speed anyway). So sending TARGET straight at DEST has exactly
 * one solution: the contact normal must point directly away from DEST --
 * no bisection, no approach-direction check, nothing else needed.
 *
 * Scope, on purpose: DEST is assumed to be somewhere in front of TARGET --
 * reachable in a straight line. [solve] itself never banks TARGET off a
 * rail; if DEST is placed hugging a rail, OverlayController separately
 * continues the line past DEST using the exact same rail-reflection code
 * the plain Bank Shot walk uses (DEST standing in for TARGET's usual role
 * in that walk) -- see drawManualController. That's a forward walk with no
 * solving involved, unlike this file.
 */
object ComboShot {

    class Result(
        val ghostX: Float, val ghostY: Float,
        val contactX: Float, val contactY: Float
    )

    fun solve(
        purpleX: Float, purpleY: Float,   // TARGET / the money ball
        destX: Float, destY: Float,       // DEST -- where TARGET must end up
        ballDiameterPx: Float,
        radiusScalePercent: Float
    ): Result? {
        val r = ballDiameterPx / 2f * (radiusScalePercent / 100f)

        val wx = destX - purpleX
        val wy = destY - purpleY
        if (hypot(wx, wy) < 2f * r) return null // DEST sits inside TARGET's own footprint

        // TARGET's post-collision direction is always exactly along the
        // line from the ghost-ball centre through TARGET's own centre, so
        // the contact normal must point directly away from DEST.
        val alpha = atan2(purpleY - destY, purpleX - destX)
        val nx = cos(alpha)
        val ny = sin(alpha)
        return Result(
            purpleX + nx * (2f * r), purpleY + ny * (2f * r),
            purpleX + nx * r, purpleY + ny * r
        )
    }
}
