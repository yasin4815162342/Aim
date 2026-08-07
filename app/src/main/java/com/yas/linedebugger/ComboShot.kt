package com.yas.linedebugger

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Combo Shot: the mirror-image sibling of [KissShot], but solved for an
 * independent ball rather than TARGET. Kiss Shot solves for where the
 * STRIKING ball (CUE) ends up after it kisses off TARGET. Combo Shot
 * doesn't touch CUE or TARGET at all -- they keep running their own plain
 * Bank Shot underneath, completely unrelated (see OverlayController's
 * drawManualController). Combo gets its own ball, the yellow Octagon, and
 * solves for which point of IT needs to be hit so the Octagon ITSELF ends
 * up at DEST -- same DEST, same ghost-ball math, opposite question from
 * Kiss Shot -- see OverlayController's DEST tap-cycle (off -> kiss ->
 * combo).
 *
 * The physics fact that makes this a plain closed form: regardless of the
 * real elasticity (0.95, see KissShot's header doc) or how hard the ball
 * is hit, a struck ball that starts at rest always departs along the line
 * from the ghost-ball centre through its own centre, extended -- the
 * classic "ghost-ball line" every pool player already uses to aim a plain
 * cut shot. That direction never depends on speed or e (only the
 * *distance* the Octagon travels does, and this is a static overlay with
 * no notion of speed anyway). So sending it straight at DEST has exactly
 * one solution: the contact normal must point directly away from DEST --
 * no bisection, no approach-direction check, nothing else needed.
 *
 * Scope, on purpose: DEST is assumed to be somewhere in front of the
 * Octagon -- reachable in a straight line. [solve] itself never banks it
 * off a rail; if DEST is placed hugging a rail, OverlayController
 * separately continues the line past DEST using the exact same
 * rail-reflection code the plain Bank Shot walk uses (DEST standing in
 * for the Octagon's usual role in that walk) -- see drawManualController.
 * That's a forward walk with no solving involved, unlike this file.
 *
 * [gapOffsetPx] is a purely visual nudge, not a physics correction: it
 * shifts the Collision Ghost Ball a few pixels closer to or further from
 * the Octagon than the true one-ball-diameter distance, for matching the
 * on-screen ring spacing to how it actually looks over the camera feed.
 * The Octagon's own ghost ball is unaffected -- it's anchored to wherever
 * the handle itself is dragged, since that's meant to track the real
 * ball's position.
 */
object ComboShot {

    class Result(
        val ghostX: Float, val ghostY: Float,
        val contactX: Float, val contactY: Float
    )

    fun solve(
        purpleX: Float, purpleY: Float,   // the Octagon / Combo Ball
        destX: Float, destY: Float,       // DEST -- where the Octagon must end up
        ballDiameterPx: Float,
        radiusScalePercent: Float,
        gapOffsetPx: Float = 0f
    ): Result? {
        val r = ballDiameterPx / 2f * (radiusScalePercent / 100f)

        val wx = destX - purpleX
        val wy = destY - purpleY
        if (hypot(wx, wy) < 2f * r) return null // DEST sits inside the Octagon's own footprint

        // The Octagon's post-collision direction is always exactly along
        // the line from the ghost-ball centre through its own centre, so
        // the contact normal must point directly away from DEST.
        val alpha = atan2(purpleY - destY, purpleX - destX)
        val nx = cos(alpha)
        val ny = sin(alpha)
        val ghostDist = 2f * r - gapOffsetPx
        val contactDist = r - gapOffsetPx / 2f
        return Result(
            purpleX + nx * ghostDist, purpleY + ny * ghostDist,
            purpleX + nx * contactDist, purpleY + ny * contactDist
        )
    }
}
