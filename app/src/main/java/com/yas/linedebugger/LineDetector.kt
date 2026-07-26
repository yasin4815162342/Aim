// ============================================================
// FILE: app/src/main/java/com/yas/linedebugger/LineDetector.kt
// FULL REPLACEMENT – type-fixed + aggressive circle kill + stable elongated-line only
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
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val previewArgb: IntArray = IntArray(0),
    /** Quality score used by the lock (higher = better). */
    val score: Float = 0f
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

        // --- Stage 1: classic morphological ball removal ---
        val ballCore = erode(notGreen, size, Tunables.ballErodeRadius)
        val ballGrown = dilate(ballCore, size, Tunables.ballErodeRadius + Tunables.ballDilateGrow)
        var lineMask = BooleanArray(n) { notGreen[it] && !ballGrown[it] }

        // --- Stage 2: kill remaining circle-like blobs ---
        lineMask = killCircularBlobs(lineMask, size)

        // Preview
        val preview = IntArray(n)
        for (i in 0 until n) {
            preview[i] = when {
                lineMask[i] -> 0xFFFF00FF.toInt()          // magenta = accepted line
                ballGrown[i] -> 0xFF3060FF.toInt()         // blue   = morphological ball
                notGreen[i] -> 0xFFFFFF00.toInt()          // yellow = rejected non-green
                else -> 0xFF104010.toInt()                 // dim green = felt
            }
        }

        val xs = ArrayList<Int>(n / 4)
        val ys = ArrayList<Int>(n / 4)
        val ws = ArrayList<Float>(n / 4)
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

        // Weighted centroid + PCA angle
        var meanX = 0.0
        var meanY = 0.0
        for (i in xs.indices) {
            meanX += xs[i] * ws[i]
            meanY += ys[i] * ws[i]
        }
        meanX /= totalW
        meanY /= totalW

        var angle = weightedFitAngle(xs, ys, ws, meanX, meanY)

        // Two rounds of outlier trim + refit
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

        // Final quality metrics
        val dirX = cos(angle)
        val dirY = sin(angle)
        var sqResidual = 0.0
        for (i in xs.indices) {
            val perp = -(xs[i] - meanX) * dirY + (ys[i] - meanY) * dirX
            sqResidual += perp * perp
        }
        val rms = sqrt(sqResidual / xs.size)                 // Double
        val widthEstimate = (rms * 3.46).toFloat().coerceAtLeast(1f)

        // Score: prefer many inliers + low residual (higher is better)
        // Explicit Float conversion to avoid type mismatch
        val score = (xs.size.toFloat() / (1f + rms.toFloat() * 2f)).coerceAtLeast(0f)

        // Average colour of surviving pixels
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
            previewArgb = preview,
            score = score
        )
    }

    // ------------------------------------------------------------------
    // Circle / blob killer
    // ------------------------------------------------------------------
    private fun killCircularBlobs(mask: BooleanArray, size: Int): BooleanArray {
        val n = size * size
        val visited = BooleanArray(n)
        val out = mask.copyOf()
        val qx = IntArray(n)
        val qy = IntArray(n)

        for (y in 0 until size) {
            for (x in 0 until size) {
                val start = y * size + x
                if (!mask[start] || visited[start]) continue

                var head = 0
                var tail = 0
                qx[tail] = x; qy[tail] = y; tail++
                visited[start] = true

                var minX = x; var maxX = x
                var minY = y; var maxY = y
                var area = 0

                while (head < tail) {
                    val cx = qx[head]
                    val cy = qy[head]
                    head++
                    area++
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy

                    for (d in 0 until 4) {
                        val nx = cx + when (d) { 0 -> 1; 1 -> -1; else -> 0 }
                        val ny = cy + when (d) { 2 -> 1; 3 -> -1; else -> 0 }
                        if (nx !in 0 until size || ny !in 0 until size) continue
                        val ni = ny * size + nx
                        if (mask[ni] && !visited[ni]) {
                            visited[ni] = true
                            qx[tail] = nx; qy[tail] = ny; tail++
                        }
                    }
                }

                if (area < 6) {
                    for (i in 0 until tail) {
                        out[qy[i] * size + qx[i]] = false
                    }
                    continue
                }

                val bw = (maxX - minX + 1).toFloat()
                val bh = (maxY - minY + 1).toFloat()
                val aspect = maxOf(bw, bh) / minOf(bw, bh).coerceAtLeast(1f)
                val fill = area / (bw * bh)

                val isCircleLike = aspect < 1.85f && fill > 0.42f

                if (isCircleLike) {
                    for (i in 0 until tail) {
                        out[qy[i] * size + qx[i]] = false
                    }
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------
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
