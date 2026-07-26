// ============================================================
// FILE: app/src/main/java/com/yas/linedebugger/LineDetector.kt
// (FULL REPLACEMENT – same as previous + weighted fit)
// ============================================================
package com.yas.linedebugger

import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class DetectionResult(
    val hasLine: Boolean,
    val angleRad: Double = 0.0,
    val widthPx: Float = 0f,
    val colorArgb: Int = 0xFFFFFFFF.toInt(),
    val pixelCount: Int = 0,
    /** Crop-relative X of the intensity-weighted medial-axis reference point. */
    val offsetX: Float = 0f,
    /** Crop-relative Y of the intensity-weighted medial-axis reference point. */
    val offsetY: Float = 0f,
    val previewArgb: IntArray = IntArray(0)
)

object LineDetector {

    fun detect(pixels: IntArray, size: Int): DetectionResult {
        val n = size * size
        val notGreen = BooleanArray(n)
        val brightness = IntArray(n)
        for (i in 0 until n) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val bright = maxOf(r, g, b)
            brightness[i] = bright
            val isFelt = (g - r) > Tunables.greenDiff && (g - b) > Tunables.greenDiff
            notGreen[i] = !isFelt && bright > Tunables.minBrightness
        }

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

        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        val ws = ArrayList<Float>()
        var totalW = 0f
        for (row in 0 until size) for (col in 0 until size) {
            val i = row * size + col
            if (lineMask[i]) {
                val w = brightness[i].toFloat().coerceAtLeast(1f)
                xs.add(col)
                ys.add(row)
                ws.add(w)
                totalW += w
            }
        }

        if (xs.size < Tunables.minLinePixels || totalW < 1f) {
            return DetectionResult(hasLine = false, previewArgb = preview)
        }

        var meanX = 0.0
        var meanY = 0.0
        for (i in xs.indices) {
            meanX += xs[i] * ws[i]
            meanY += ys[i] * ws[i]
        }
        meanX /= totalW
        meanY /= totalW

        var angle = weightedFitAngle(xs, ys, ws, meanX, meanY)

        repeat(2) {
            val (tx, ty, tw) = trimOutliersWeighted(xs, ys, ws, angle, meanX, meanY)
            if (tx.size >= Tunables.minLinePixels) {
                xs.clear(); ys.clear(); ws.clear()
                xs.addAll(tx); ys.addAll(ty); ws.addAll(tw)
                totalW = ws.sum()
                meanX = 0.0; meanY = 0.0
                for (i in xs.indices) {
                    meanX += xs[i] * ws[i]
                    meanY += ys[i] * ws[i]
                }
                meanX /= totalW
                meanY /= totalW
                angle = weightedFitAngle(xs, ys, ws, meanX, meanY)
            }
        }

        val dirX = cos(angle)
        val dirY = sin(angle)
        var sqResidual = 0.0
        for (i in xs.indices) {
            val perp = -(xs[i] - meanX) * dirY + (ys[i] - meanY) * dirX
            sqResidual += perp * perp
        }
        val widthEstimate = (sqrt(sqResidual / xs.size) * 3.46).toFloat().coerceAtLeast(1f)

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
            widthPx = widthEstimate,
            colorArgb = avgColor,
            pixelCount = count,
            offsetX = meanX.toFloat(),
            offsetY = meanY.toFloat(),
            previewArgb = preview
        )
    }

    private fun weightedFitAngle(
        xs: List<Int>, ys: List<Int>, ws: List<Float>,
        mx: Double, my: Double
    ): Double {
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - mx
            val dy = ys[i] - my
            val w = ws[i].toDouble()
            sxx += w * dx * dx
            syy += w * dy * dy
            sxy += w * dx * dy
        }
        return 0.5 * atan2(2 * sxy, sxx - syy)
    }

    private fun trimOutliersWeighted(
        xs: List<Int>, ys: List<Int>, ws: List<Float>,
        angle: Double, mx: Double, my: Double
    ): Triple<ArrayList<Int>, ArrayList<Int>, ArrayList<Float>> {
        val dirX = cos(angle); val dirY = sin(angle)
        val residuals = DoubleArray(xs.size)
        for (i in xs.indices) {
            residuals[i] = abs(-(xs[i] - mx) * dirY + (ys[i] - my) * dirX)
        }
        val meanR = residuals.average()
        var varR = 0.0
        for (v in residuals) { val d = v - meanR; varR += d * d }
        varR /= residuals.size
        val stdR = sqrt(varR)
        val threshold = stdR * Tunables.outlierTrimK

        val outXs = ArrayList<Int>()
        val outYs = ArrayList<Int>()
        val outWs = ArrayList<Float>()
        for (i in xs.indices) {
            if (stdR < 1e-6 || residuals[i] <= threshold) {
                outXs.add(xs[i])
                outYs.add(ys[i])
                outWs.add(ws[i])
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
