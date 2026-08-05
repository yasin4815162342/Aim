package com.yas.linedebugger

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Combo Shot: the mirror-image sibling of [KissShot]. Kiss Shot solves for
 * where the STRIKING ball (CUE) ends up after it kisses off TARGET. Combo
 * Shot doesn't care about CUE at all -- it isn't even part of this solve
 * (aiming the real shot is done separately, with the unrelated auto-line
 * feature). Combo solves for which point of TARGET needs to be hit so
 * TARGET ITSELF (the money ball) ends up at its own destination handle --
 * a yellow octagon (ManualRole.COMBO_DEST), fully independent of Kiss
 * Shot's DEST marker. Same ghost ball, opposite question from Kiss Shot.
 *
 * The physics fact that makes this a plain closed form: regardless of the
 * real elasticity (0.95, see KissShot's header doc) or how hard the ball
 * is hit, a struck ball that starts at rest always departs along the line
 * from the ghost-ball centre through its own centre, extended -- the
 * classic "ghost-ball line" every pool player already uses to aim a plain
 * cut shot. That direction never depends on speed or e (only the
 * *distance* TARGET travels does, and this is a static overlay with no
 * notion of speed anyway). So sending TARGET straight at its destination
 * has exactly one solution: the contact normal must point directly away
 * from the destination -- no bisection, no approach-direction check,
 * nothing else needed.
 *
 * Scope, on purpose: the destination is assumed to be somewhere in front
 * of TARGET -- reachable in a straight line. [solve] itself never banks
 * TARGET off a rail; if the destination is placed hugging a rail,
 * OverlayController separately continues the line past it using the exact
 * same rail-reflection code the plain Bank Shot walk uses (the destination
 * standing in for TARGET's usual role in that walk) -- see
 * drawManualController. That's a forward walk with no solving involved,
 * unlike this file.
 */
object ComboShot {

    class Result(
        val ghostX: Float, val ghostY: Float,
        val contactX: Float, val contactY: Float
    )

    fun solve(
        purpleX: Float, purpleY: Float,   // TARGET / the money ball
        destX: Float, destY: Float,       // COMBO_DEST -- where TARGET must end up
        ballDiameterPx: Float,
        radiusScalePercent: Float,
        offsetPx: Float
    ): Result? {
        val r = ballDiameterPx / 2f * (radiusScalePercent / 100f)
        // How far apart the two ghost-ball centres sit at the solved
        // contact point. 0 offset = the geometric 2*r "rings exactly hug"
        // case. Negative lets the rings overlap slightly; positive leaves
        // a small gap -- whichever matches the game's actual collision
        // feel. Clamped so a large negative offset can never collapse the
        // separation to zero or flip its sign.
        val sep = (2f * r + offsetPx).coerceAtLeast(1f)

        val wx = destX - purpleX
        val wy = destY - purpleY
        if (hypot(wx, wy) < sep) return null // destination sits inside TARGET's own footprint

        // TARGET's post-collision direction is always exactly along the
        // line from the ghost-ball centre through TARGET's own centre, so
        // the contact normal must point directly away from the destination.
        val alpha = atan2(purpleY - destY, purpleX - destX)
        val nx = cos(alpha)
        val ny = sin(alpha)
        return Result(
            purpleX + nx * sep, purpleY + ny * sep,
            purpleX + nx * r, purpleY + ny * r
        )
    }
}
