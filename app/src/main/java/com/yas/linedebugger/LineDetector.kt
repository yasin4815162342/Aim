// ============================================================
// FILE: app/src/main/java/com/yas/linedebugger/LineDetector.kt
// Optimized: separable O(n·r) erode/dilate, best-component selection
// (Scenario A partial-ball rejection), reduced allocations.
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

    // Reusable scratch buffers — detect() is single-threaded on bgHandler.
    private var candidateBuf: BooleanArray = BooleanArray(0)
    private var scratchA: BooleanArray = BooleanArray(0)
    private var scratchB: BooleanArray = BooleanArray(0)
    private var scratchTmp: BooleanArray = BooleanArray(0)
    private var scratchVisited: BooleanArray = BooleanArray(0)
    private var qxBuf: IntArray = IntArray(0)
    private var qyBuf: IntArray = IntArray(0)
    private var brightnessBuf: IntArray = IntArray(0)
    private var rejectedRailBuf: BooleanArray = BooleanArray(0)

    private fun ensureScratch(n: Int) {
        if (candidateBuf.size < n) {
            candidateBuf = BooleanArray(n)
            scratchA = BooleanArray(n)
            scratchB = BooleanArray(n)
            scratchTmp = BooleanArray(n)
            scratchVisited = BooleanArray(n)
            qxBuf = IntArray(n)
            qyBuf = IntArray(n)
            brightnessBuf = IntArray(n)
            rejectedRailBuf = BooleanArray(n)
        }
    }

    fun detect(pixels: IntArray, size: Int): DetectionResult {
        val n = size * size
        ensureScratch(n)
        val isCandidate = candidateBuf
        val rejectedAsRail = rejectedRailBuf
        val brightness = brightnessBuf
        java.util.Arrays.fill(isCandidate, 0, n, false)
        java.util.Arrays.fill(rejectedAsRail, 0, n, false)

        val mode = Tunables.detectionMode

        for (i in 0 until n) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val bright = maxOf(r, g, b)
            brightness[i] = bright

            var legacyCandidate = false
            if (mode == AutoAimPrefs.DETECTION_MODE_LEGACY || mode == AutoAimPrefs.DETECTION_MODE_HYBRID) {
                val isGreenHue = (g - r) > Tunables.greenDiff && (g - b) > Tunables.greenDiff
                val isFelt = isGreenHue && bright < Tunables.greenLineBrightness
                legacyCandidate = !isFelt && bright > Tunables.minBrightness
            }

            var hsvCandidate = false
            var railRejected = false
            if (mode == AutoAimPrefs.DETECTION_MODE_HSV || mode == AutoAimPrefs.DETECTION_MODE_HYBRID) {
                val hsv = rgbToHsv(r, g, b)
                val h = hsv[0]; val s = hsv[1]; val v = hsv[2]

                val looksLikeFelt = closeToReference(
                    h, s, v,
                    Tunables.feltHueDeg, Tunables.feltHueToleranceDeg,
                    Tunables.feltSat.toFloat(), Tunables.feltSatTolerance.toFloat(),
                    Tunables.feltVal.toFloat(), Tunables.feltValTolerance.toFloat()
                )
                val looksLikeRail = Tunables.railExclusionEnabled && closeToReference(
                    h, s, v,
                    Tunables.railHueDeg, Tunables.railHueToleranceDeg,
                    Tunables.railSat.toFloat(), Tunables.railSatTolerance.toFloat(),
                    Tunables.railVal.toFloat(), Tunables.railValTolerance.toFloat()
                )
                railRejected = !looksLikeFelt && looksLikeRail
                hsvCandidate = !looksLikeFelt && !looksLikeRail && bright > Tunables.minBrightness
            }

            isCandidate[i] = when (mode) {
                AutoAimPrefs.DETECTION_MODE_LEGACY -> legacyCandidate
                AutoAimPrefs.DETECTION_MODE_HYBRID -> legacyCandidate || hsvCandidate
                else -> hsvCandidate
            }
            rejectedAsRail[i] = !isCandidate[i] && railRejected
        }

        // --- Stage 1: separable morphological ball removal (O(n·r) not O(n·r²)) ---
        val ballCore = erodeSeparable(isCandidate, size, Tunables.ballErodeRadius, scratchB)
        val growR = Tunables.ballErodeRadius + Tunables.ballDilateGrow
        val ballGrown = dilateSeparable(ballCore, size, growR, scratchA)

        // lineMask into scratchB (ballCore no longer needed)
        val lineMask = scratchB
        for (i in 0 until n) {
            lineMask[i] = isCandidate[i] && !ballGrown[i]
        }

        // --- Stage 2: kill remaining circle-like blobs ---
        killCircularBlobsInPlace(lineMask, size)

        // Preview
        val preview = IntArray(n)
        for (i in 0 until n) {
            preview[i] = when {
                lineMask[i] -> 0xFFFF00FF.toInt()
                ballGrown[i] -> 0xFF3060FF.toInt()
                isCandidate[i] -> 0xFFFFFF00.toInt()
                rejectedAsRail[i] -> 0xFF6B4423.toInt()
                else -> 0xFF104010.toInt()
            }
        }

        // --- Stage 3: pick the single strongest elongated component (Scenario A) ---
        val best = selectBestLineComponent(lineMask, brightness, size)
        if (best == null) {
            return DetectionResult(hasLine = false, previewArgb = preview)
        }

        val xs = best.xs
        val ys = best.ys
        val ws = best.ws
        var totalW = best.totalW
        var meanX = best.meanX
        var meanY = best.meanY
        var angle = best.angle

        // Two rounds of outlier trim + refit on the chosen component only
        repeat(2) {
            val (tx, ty, tw) = trimOutliersWeighted(xs, ys, ws, angle, meanX, meanY)
            if (tx.size >= Tunables.minLinePixels) {
                xs.clear(); ys.clear(); ws.clear()
                xs.addAll(tx); ys.addAll(ty); ws.addAll(tw)
                totalW = 0f
                for (w in ws) totalW += w
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
        val rms = sqrt(sqResidual / xs.size)
        val widthEstimate = (rms * 3.46).toFloat().coerceAtLeast(1f)
        val score = (xs.size.toFloat() / (1f + rms.toFloat() * 2f)).coerceAtLeast(0f)

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
    // Best elongated component (Scenario A)
    // ------------------------------------------------------------------

    private data class CompResult(
        val xs: ArrayList<Int>,
        val ys: ArrayList<Int>,
        val ws: ArrayList<Float>,
        val totalW: Float,
        val meanX: Double,
        val meanY: Double,
        val angle: Double,
        val score: Float
    )

    /**
     * Flood-fill every connected component in [mask], score by elongation
     * + residual of a quick weighted fit, return the single best elongated
     * line. Rejects compact/circular remnants left by partial balls.
     */
    private fun selectBestLineComponent(
        mask: BooleanArray,
        brightness: IntArray,
        size: Int
    ): CompResult? {
        val n = size * size
        val visited = scratchVisited
        java.util.Arrays.fill(visited, 0, n, false)
        val qx = qxBuf
        val qy = qyBuf

        var best: CompResult? = null
        var bestScore = -1f

        for (y0 in 0 until size) {
            for (x0 in 0 until size) {
                val start = y0 * size + x0
                if (!mask[start] || visited[start]) continue

                var head = 0
                var tail = 0
                qx[tail] = x0; qy[tail] = y0; tail++
                visited[start] = true

                var minX = x0; var maxX = x0
                var minY = y0; var maxY = y0
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

                    if (cx + 1 < size) {
                        val ni = cy * size + (cx + 1)
                        if (mask[ni] && !visited[ni]) {
                            visited[ni] = true; qx[tail] = cx + 1; qy[tail] = cy; tail++
                        }
                    }
                    if (cx - 1 >= 0) {
                        val ni = cy * size + (cx - 1)
                        if (mask[ni] && !visited[ni]) {
                            visited[ni] = true; qx[tail] = cx - 1; qy[tail] = cy; tail++
                        }
                    }
                    if (cy + 1 < size) {
                        val ni = (cy + 1) * size + cx
                        if (mask[ni] && !visited[ni]) {
                            visited[ni] = true; qx[tail] = cx; qy[tail] = cy + 1; tail++
                        }
                    }
                    if (cy - 1 >= 0) {
                        val ni = (cy - 1) * size + cx
                        if (mask[ni] && !visited[ni]) {
                            visited[ni] = true; qx[tail] = cx; qy[tail] = cy - 1; tail++
                        }
                    }
                }

                if (area < Tunables.minLinePixels) continue

                val bw = (maxX - minX + 1).toFloat()
                val bh = (maxY - minY + 1).toFloat()
                val aspect = maxOf(bw, bh) / minOf(bw, bh).coerceAtLeast(1f)
                val fill = area / (bw * bh)

                // Hard reject compact/circular blobs (partial balls)
                if (aspect < 2.0f && fill > 0.35f) continue
                if (aspect < 1.6f) continue

                val xs = ArrayList<Int>(area)
                val ys = ArrayList<Int>(area)
                val ws = ArrayList<Float>(area)
                var totalW = 0f
                for (i in 0 until tail) {
                    val px = qx[i]; val py = qy[i]
                    val w = brightness[py * size + px].toFloat().coerceAtLeast(1f)
                    xs.add(px); ys.add(py); ws.add(w)
                    totalW += w
                }
                if (totalW < 1f) continue

                var meanX = 0.0; var meanY = 0.0
                for (i in xs.indices) {
                    meanX += xs[i] * ws[i]
                    meanY += ys[i] * ws[i]
                }
                meanX /= totalW
                meanY /= totalW

                val angle = weightedFitAngle(xs, ys, ws, meanX, meanY)
                val dirX = cos(angle); val dirY = sin(angle)
                var sqR = 0.0
                for (i in xs.indices) {
                    val perp = -(xs[i] - meanX) * dirY + (ys[i] - meanY) * dirX
                    sqR += perp * perp
                }
                val rms = sqrt(sqR / xs.size).toFloat().coerceAtLeast(0.01f)

                val score = (area.toFloat() / (1f + rms * 2f)) * (1f + (aspect - 1f) * 0.35f)

                if (score > bestScore) {
                    bestScore = score
                    best = CompResult(xs, ys, ws, totalW, meanX, meanY, angle, score)
                }
            }
        }
        return best
    }

    // ------------------------------------------------------------------
    // Color helpers
    // ------------------------------------------------------------------

    private fun rgbToHsv(r: Int, g: Int, b: Int): FloatArray {
        val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
        val maxC = maxOf(rf, gf, bf)
        val minC = minOf(rf, gf, bf)
        val delta = maxC - minC

        var hue = when {
            delta < 1e-6f -> 0f
            maxC == rf -> 60f * ((gf - bf) / delta)
            maxC == gf -> 60f * (((bf - rf) / delta) + 2f)
            else -> 60f * (((rf - gf) / delta) + 4f)
        }
        if (hue < 0f) hue += 360f

        val sat = if (maxC < 1e-6f) 0f else (delta / maxC)
        val value = maxC
        return floatArrayOf(hue, sat * 255f, value * 255f)
    }

    private fun circularHueDist(a: Float, b: Float): Float {
        var d = abs(a - b) % 360f
        if (d > 180f) d = 360f - d
        return d
    }

    private fun closeToReference(
        h: Float, s: Float, v: Float,
        refHueDeg: Float, hueToleranceDeg: Float,
        refSat: Float, satTolerance: Float,
        refVal: Float, valTolerance: Float
    ): Boolean {
        val closeHue = circularHueDist(h, refHueDeg) <= hueToleranceDeg
        val closeSat = abs(s - refSat) <= satTolerance
        val closeVal = abs(v - refVal) <= valTolerance
        return closeHue && closeSat && closeVal
    }

    // ------------------------------------------------------------------
    // Circle / blob killer (in-place, tighter thresholds for partial balls)
    // ------------------------------------------------------------------
    private fun killCircularBlobsInPlace(mask: BooleanArray, size: Int) {
        val n = size * size
        val visited = scratchVisited
        java.util.Arrays.fill(visited, 0, n, false)
        val qx = qxBuf
        val qy = qyBuf

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
                    for (i in 0 until tail) mask[qy[i] * size + qx[i]] = false
                    continue
                }

                val bw = (maxX - minX + 1).toFloat()
                val bh = (maxY - minY + 1).toFloat()
                val aspect = maxOf(bw, bh) / minOf(bw, bh).coerceAtLeast(1f)
                val fill = area / (bw * bh)

                val isCircleLike = aspect < 2.1f && fill > 0.38f
                if (isCircleLike) {
                    for (i in 0 until tail) mask[qy[i] * size + qx[i]] = false
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // PCA / outlier helpers
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

    // ------------------------------------------------------------------
    // Separable morphology — rectangular SE, O(n·r)
    // Boundary: OOB treated as false (same as original 2-D loops).
    // ------------------------------------------------------------------

    private fun erodeSeparable(mask: BooleanArray, size: Int, radius: Int, out: BooleanArray): BooleanArray {
        if (radius <= 0) {
            System.arraycopy(mask, 0, out, 0, size * size)
            return out
        }
        val tmp = scratchTmp
        for (row in 0 until size) {
            val rowOff = row * size
            for (col in 0 until size) {
                var all = true
                val c0 = col - radius
                val c1 = col + radius
                if (c0 < 0 || c1 >= size) {
                    all = false
                } else {
                    for (c in c0..c1) {
                        if (!mask[rowOff + c]) { all = false; break }
                    }
                }
                tmp[rowOff + col] = all
            }
        }
        for (col in 0 until size) {
            for (row in 0 until size) {
                var all = true
                val r0 = row - radius
                val r1 = row + radius
                if (r0 < 0 || r1 >= size) {
                    all = false
                } else {
                    for (r in r0..r1) {
                        if (!tmp[r * size + col]) { all = false; break }
                    }
                }
                out[row * size + col] = all
            }
        }
        return out
    }

    private fun dilateSeparable(mask: BooleanArray, size: Int, radius: Int, out: BooleanArray): BooleanArray {
        if (radius <= 0) {
            System.arraycopy(mask, 0, out, 0, size * size)
            return out
        }
        val tmp = scratchTmp
        for (row in 0 until size) {
            val rowOff = row * size
            for (col in 0 until size) {
                var any = false
                val c0 = (col - radius).coerceAtLeast(0)
                val c1 = (col + radius).coerceAtMost(size - 1)
                for (c in c0..c1) {
                    if (mask[rowOff + c]) { any = true; break }
                }
                tmp[rowOff + col] = any
            }
        }
        for (col in 0 until size) {
            for (row in 0 until size) {
                var any = false
                val r0 = (row - radius).coerceAtLeast(0)
                val r1 = (row + radius).coerceAtMost(size - 1)
                for (r in r0..r1) {
                    if (tmp[r * size + col]) { any = true; break }
                }
                out[row * size + col] = any
            }
        }
        return out
    }
}
