package com.yas.linedebugger

import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class DetectionResult(
    val hasLine: Boolean,
    val angleRad: Double = 0.0,
    val offsetX: Float = 0f,   // crop-relative X of the fitted line's weighted centroid
    val offsetY: Float = 0f,   // crop-relative Y of the fitted line's weighted centroid
    val widthPx: Float = 0f,
    val colorArgb: Int = 0xFFFFFFFF.toInt(),
    val pixelCount: Int = 0,
    // Same size×size crop, recolored for debugging:
    // magenta = counted as line, blue = rejected as ball, yellow = non-green
    // but neither, dim green = felt. Filled in even when hasLine is false.
    val previewArgb: IntArray = IntArray(0)
)

object LineDetector {

    fun detect(pixels: IntArray, size: Int): DetectionResult {
        val n = size * size
        val notGreen = BooleanArray(n)
        val lineWeight = FloatArray(n)
        for (i in 0 until n) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val bright = maxOf(r, g, b)
            val isFelt = (g - r) > Tunables.greenDiff && (g - b) > Tunables.greenDiff
            notGreen[i] = !isFelt && bright > Tunables.minBrightness
            // Continuous "how line-like is this pixel" score, used later as a fit weight.
            // Anti-aliased edge pixels blend toward green and are dim, so they score low and
            // pull the fit less than solid, saturated line pixels do.
            val distFromGreen = (maxOf(r, b) - g).coerceAtLeast(0)
            lineWeight[i] = distFromGreen + bright * 0.5f
        }

        // Ball problem: erase anything thinner than a ball (the line), then grow
        // what survives back out to roughly the ball's true size, then subtract.
        val ballCore = erode(notGreen, size, Tunables.ballErodeRadius)
        val ballGrown = dilate(ballCore, size, Tunables.ballErodeRadius + Tunables.ballDilateGrow)
        val lineMask = BooleanArray(n) { notGreen[it] && !ballGrown[it] }

        val preview = IntArray(n)
        for (i in 0 until n) {
            preview[i] = when {
                lineMask[i] -> 0xFFFF00FF.toInt()
                ballGrown[i] -> 0xFF3060FF.toInt()
                notGreen[i] -> 0xFFFFFF00.toInt()
                else -> 0xFF104010.toInt()
            }
        }

        var xs = ArrayList<Int>()
        var ys = ArrayList<Int>()
        var ws = ArrayList<Float>()
        for (row in 0 until size) for (col in 0 until size) {
            val idx = row * size + col
            if (lineMask[idx]) { xs.add(col); ys.add(row); ws.add(lineWeight[idx].coerceAtLeast(1f)) }
        }

        if (xs.size < Tunables.minLinePixels) {
            return DetectionResult(hasLine = false, previewArgb = preview)
        }

        var angle = fitAngleWeighted(xs, ys, ws)
        repeat(2) {
            val (tx, ty, tw) = trimOutliers(xs, ys, ws, angle)
            if (tx.size >= Tunables.minLinePixels) {
                xs = tx; ys = ty; ws = tw
                angle = fitAngleWeighted(xs, ys, ws)
            }
        }

        val sumW = ws.sumOf { it.toDouble() }
        var meanX = 0.0; var meanY = 0.0
        for (i in xs.indices) { meanX += xs[i] * ws[i]; meanY += ys[i] * ws[i] }
        meanX /= sumW; meanY /= sumW

        val dirX = cos(angle)
        val dirY = sin(angle)
        var sqResidual = 0.0
        for (i in xs.indices) {
            val perp = -(xs[i] - meanX) * dirY + (ys[i] - meanY) * dirX
            sqResidual += perp * perp * ws[i]
        }
        val widthEstimate = (sqrt(sqResidual / sumW) * 3.46).toFloat().coerceAtLeast(1f)

        var rSum = 0; var gSum = 0; var bSum = 0
        for (i in xs.indices) {
            val p = pixels[ys[i] * size + xs[i]]
            rSum += (p shr 16) and 0xFF
            gSum += (p shr 8) and 0xFF
            bSum += p and 0xFF
        }
        val count = xs.size
        val avgColor = (0xFF shl 24) or ((rSum / count) shl 16) or ((gSum / count) shl 8) or (bSum / count)

        return DetectionResult(
            hasLine = true,
            angleRad = angle,
            offsetX = meanX.toFloat(),
            offsetY = meanY.toFloat(),
            widthPx = widthEstimate,
            colorArgb = avgColor,
            pixelCount = count,
            previewArgb = preview
        )
    }

    /** Weighted PCA angle fit. Points with a higher [ws] (more confidently "line", not a
     *  faded/anti-aliased edge) pull the fitted axis harder — this is what keeps the axis
     *  centered on the true line instead of drifting toward whichever side has more jagged
     *  edge pixels. */
    private fun fitAngleWeighted(xs: List<Int>, ys: List<Int>, ws: List<Float>): Double {
        val sumW = ws.sumOf { it.toDouble() }
        var mx = 0.0; var my = 0.0
        for (i in xs.indices) { mx += xs[i] * ws[i]; my += ys[i] * ws[i] }
        mx /= sumW; my /= sumW

        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - mx; val dy = ys[i] - my
            val w = ws[i]
            sxx += w * dx * dx; syy += w * dy * dy; sxy += w * dx * dy
        }
        return 0.5 * atan2(2 * sxy, sxx - syy)
    }

    /** Drop points whose perpendicular distance from the fitted line is a big outlier, then the
     *  caller refits — a cheap stand-in for a proper Huber/IRLS robust fit. The residual mean/std
     *  are themselves weighted so low-confidence edge pixels don't inflate the threshold and let
     *  more jaggedness survive the trim. */
    private fun trimOutliers(
        xs: List<Int>, ys: List<Int>, ws: List<Float>, angle: Double
    ): Triple<ArrayList<Int>, ArrayList<Int>, ArrayList<Float>> {
        val sumW = ws.sumOf { it.toDouble() }
        var mx = 0.0; var my = 0.0
        for (i in xs.indices) { mx += xs[i] * ws[i]; my += ys[i] * ws[i] }
        mx /= sumW; my /= sumW

        val dirX = cos(angle); val dirY = sin(angle)
        val residuals = DoubleArray(xs.size)
        for (i in xs.indices) {
            residuals[i] = abs(-(xs[i] - mx) * dirY + (ys[i] - my) * dirX)
        }
        var meanR = 0.0
        for (i in residuals.indices) meanR += residuals[i] * ws[i]
        meanR /= sumW
        var varR = 0.0
        for (i in residuals.indices) { val d = residuals[i] - meanR; varR += ws[i] * d * d }
        varR /= sumW
        val stdR = sqrt(varR)
        val threshold = stdR * Tunables.outlierTrimK

        val outXs = ArrayList<Int>(); val outYs = ArrayList<Int>(); val outWs = ArrayList<Float>()
        for (i in xs.indices) {
            if (stdR < 1e-6 || residuals[i] <= threshold) {
                outXs.add(xs[i]); outYs.add(ys[i]); outWs.add(ws[i])
            }
        }
        return Triple(outXs, outYs, outWs)
    }

    private fun erode(mask: BooleanArray, size: Int, radius: Int): BooleanArray {
        if (radius <= 0) return mask.copyOf()
        val out = BooleanArray(size * size)
        for (row in 0 until size) for (col in 0 until size) {
            var all = mask[row * size + col]
            if (all) {
                outer@ for (dy in -radius..radius) for (dx in -radius..radius) {
                    val ny = row + dy; val nx = col + dx
                    if (ny !in 0 until size || nx !in 0 until size || !mask[ny * size + nx]) {
                        all = false
                        break@outer
                    }
                }
            }
            out[row * size + col] = all
        }
        return out
    }

    private fun dilate(mask: BooleanArray, size: Int, radius: Int): BooleanArray {
        if (radius <= 0) return mask.copyOf()
        val out = BooleanArray(size * size)
        for (row in 0 until size) for (col in 0 until size) {
            var any = false
            outer@ for (dy in -radius..radius) for (dx in -radius..radius) {
                val ny = row + dy; val nx = col + dx
                if (ny in 0 until size && nx in 0 until size && mask[ny * size + nx]) {
                    any = true
                    break@outer
                }
            }
            out[row * size + col] = any
        }
        return out
    }
}
