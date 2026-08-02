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
import kotlin.math.roundToInt

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
    private val EMPTY_PREVIEW = IntArray(0)

    // Weight = brightness ABOVE the inclusion threshold, not raw brightness.
    // The candidate mask is a hard cut (bright > minBrightness), so a pixel
    // one unit above the line and one unit below it get weight ~full vs 0 —
    // a discontinuity that isn't symmetric across the true (sub-pixel) line
    // when the two sides' backgrounds composite differently under AA (e.g.
    // rail vs felt), biasing the centroid toward whichever side crosses the
    // threshold at a smaller true coverage fraction. Weighting by distance
    // above the threshold instead makes near-threshold pixels contribute
    // almost nothing — approximating true coverage — so that bias shrinks
    // instead of being carried at nearly full strength into the fit.
    private fun edgeWeight(bright: Int): Float =
        (bright - Tunables.minBrightness).toFloat().coerceAtLeast(0.05f)

    // Tunables.minLinePixels, ballErodeRadius and ballDilateGrow are all
    // dialed in against a capture buffer, but the buffer's resolution
    // changes with Tunables.captureScale while the on-screen Ray Zone
    // (Tunables.circleDiameter) does not. A guideline of fixed on-screen
    // width and length maps onto a crop whose side (diamCap) shrinks
    // linearly with captureScale — so the raw candidate pixel COUNT for
    // that same on-screen line shrinks with AREA, i.e. ~captureScale².
    // A detection that clears minLinePixels comfortably at 1.0x can fall
    // under that same fixed floor at 0.8x purely from the resolution
    // drop, with nothing on screen actually changing — read from the
    // outside as "doesn't react to small movements" / "useless below
    // 1.0x", because the detector was silently returning hasLine=false
    // (or trimming a marginal component below the floor) on those frames.
    // Scaling the floor by the same area ratio keeps it representing a
    // constant on-screen requirement at every capture resolution instead
    // of a constant buffer-pixel one. Radii (erode/dilate) are linear
    // measures, so they're scaled by captureScale itself, not its square.
    private fun currentCaptureScale(): Float =
        Tunables.captureScale.coerceIn(AutoAimPrefs.CAPTURE_SCALE_MIN, AutoAimPrefs.CAPTURE_SCALE_MAX)

    private fun scaledMinLinePixels(): Int {
        val s = currentCaptureScale()
        return (Tunables.minLinePixels * s * s).roundToInt().coerceAtLeast(12)
    }

    private fun scaledBallRadii(): Pair<Int, Int> {
        val s = currentCaptureScale()
        val erode = (Tunables.ballErodeRadius * s).roundToInt().coerceAtLeast(1)
        val grow = (Tunables.ballDilateGrow * s).roundToInt().coerceAtLeast(1)
        return erode to grow
    }

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
        if (size <= 0 || pixels.size < size * size) {
            return DetectionResult(hasLine = false)
        }
        return try {
            detectInner(pixels, size)
        } catch (t: Throwable) {
            android.util.Log.e("LineDetector", "detect failed size=$size", t)
            DetectionResult(hasLine = false)
        }
    }

    private fun detectInner(pixels: IntArray, size: Int): DetectionResult {
        val n = size * size
        ensureScratch(n)
        val isCandidate = candidateBuf
        val rejectedAsRail = rejectedRailBuf
        val brightness = brightnessBuf
        java.util.Arrays.fill(isCandidate, 0, n, false)
        java.util.Arrays.fill(rejectedAsRail, 0, n, false)

        val mode = Tunables.detectionMode

        // The Ray Zone the user drags onto the guideline used to be a
        // CIRCLE inscribed in the square crop extractCrop() hands us, so
        // this loop masked out the square's four corners (up to ~21% of
        // its area) to keep candidates confined to that circle — a
        // guideline running close to the edge could otherwise pull the
        // weighted centroid past the circle's boundary, and a centroid
        // flickering across that boundary flipped which branch
        // OverlayController's zone clip took frame to frame (the
        // "throbbing on the edges" bug).
        //
        // Now that the Ray Zone itself is a SQUARE — the same shape as
        // the crop — there's nothing to mask: the entire crop IS the
        // zone, corners included. The convexity argument that motivated
        // the circular mask still holds and is now free: a weighted
        // average of points confined to a square can never leave that
        // square (squares are convex too), so the centroid is still
        // geometrically guaranteed to stay inside the zone, with no
        // pixels thrown away and no per-pixel distance test to pay for.
        for (i in 0 until n) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val bright = maxOf(r, g, b)
            brightness[i] = bright

            var legacyCandidate = false
            if (mode == AutoAimPrefs.DETECTION_MODE_LEGACY) {
                val isGreenHue = (g - r) > Tunables.greenDiff && (g - b) > Tunables.greenDiff
                val isFelt = isGreenHue && bright < Tunables.greenLineBrightness
                legacyCandidate = !isFelt && bright > Tunables.minBrightness
            }

            var hsvCandidate = false
            var railRejected = false
            if (mode == AutoAimPrefs.DETECTION_MODE_HSV) {
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

            // White mode: purpose-built for "bright white line on dim green
            // felt" and nothing else. No hue conversion, no division, no
            // reference-color distance check — just two integer ops reused
            // against the brightness this loop already computed above.
            // A saturated color (including the green felt, and colored
            // balls) has R/G/B spread apart; white/gray does not. That
            // alone is enough to separate "white highlight" from "anything
            // else on the table" without needing to know what that
            // anything-else's color actually is.
            var whiteCandidate = false
            if (mode == AutoAimPrefs.DETECTION_MODE_WHITE) {
                val minC = minOf(r, g, b)
                val spread = bright - minC
                whiteCandidate = bright > Tunables.minBrightness && spread <= Tunables.whiteMaxSpread
            }

            isCandidate[i] = when (mode) {
                AutoAimPrefs.DETECTION_MODE_LEGACY -> legacyCandidate
                AutoAimPrefs.DETECTION_MODE_WHITE -> whiteCandidate
                else -> hsvCandidate
            }
            rejectedAsRail[i] = !isCandidate[i] && railRejected
        }

        // --- Stage 1: separable morphological ball removal (O(n·r) not O(n·r²)) ---
        val (erodeR, dilateGrow) = scaledBallRadii()
        val ballCore = erodeSeparable(isCandidate, size, erodeR, scratchB)
        val growR = erodeR + dilateGrow
        val ballGrown = dilateSeparable(ballCore, size, growR, scratchA)

        // lineMask into scratchB (ballCore no longer needed)
        val lineMask = scratchB
        for (i in 0 until n) {
            lineMask[i] = isCandidate[i] && !ballGrown[i]
        }

        // --- Stage 2: kill remaining circle-like blobs ---
        killCircularBlobsInPlace(lineMask, size)

        // Preview — only built when the Ray Monitor debug thumbnail is
        // actually on screen to read it. Otherwise this is a full O(n)
        // pass plus an IntArray(n) allocation every single detection
        // frame for a result nobody looks at.
        val preview: IntArray
        if (Tunables.rayMonitorEnabled) {
            preview = IntArray(n)
            for (i in 0 until n) {
                preview[i] = when {
                    lineMask[i] -> 0xFFFF00FF.toInt()
                    ballGrown[i] -> 0xFF3060FF.toInt()
                    isCandidate[i] -> 0xFFFFFF00.toInt()
                    rejectedAsRail[i] -> 0xFF6B4423.toInt()
                    else -> 0xFF104010.toInt()
                }
            }
        } else {
            preview = EMPTY_PREVIEW
        }

        // --- Stage 3: prefer strongest elongated component (Scenario A);
        //     fall back to all surviving line pixels if none pass filters
        //     (Scenario B / thin short guidelines must still lock). ---
        val best = selectBestLineComponent(lineMask, brightness, size)
        val xs: ArrayList<Int>
        val ys: ArrayList<Int>
        val ws: ArrayList<Float>
        var totalW: Float
        var meanX: Double
        var meanY: Double
        var angle: Double

        if (best != null) {
            xs = best.xs; ys = best.ys; ws = best.ws
            totalW = best.totalW; meanX = best.meanX; meanY = best.meanY; angle = best.angle
        } else {
            // Fallback: collect every surviving line pixel (original behaviour)
            xs = ArrayList(n / 4); ys = ArrayList(n / 4); ws = ArrayList(n / 4)
            totalW = 0f
            for (row in 0 until size) for (col in 0 until size) {
                val i = row * size + col
                if (lineMask[i]) {
                    val w = edgeWeight(brightness[i])
                    xs.add(col); ys.add(row); ws.add(w)
                    totalW += w
                }
            }
            if (xs.size < scaledMinLinePixels() || totalW < 1f) {
                return DetectionResult(hasLine = false, previewArgb = preview)
            }
            meanX = 0.0; meanY = 0.0
            for (i in xs.indices) {
                meanX += xs[i] * ws[i]
                meanY += ys[i] * ws[i]
            }
            meanX /= totalW
            meanY /= totalW
            angle = weightedFitAngle(xs, ys, ws, meanX, meanY)
        }

        // --- Robust refinement on the chosen component ---
        // The fit above is only a rough starting point. If a ball's edge is
        // 4-connected to the real line — a partial-ball sliver too thin to
        // survive Stage-1 erosion, or shaped so the WHOLE component's
        // aspect/fill still reads "line-like" to Stage 2/selectBest — that
        // contamination is already baked into this angle/mean, and no
        // amount of "distance from THIS fit" trimming can fully undo a bias
        // it was used to create. Bootstrap it out in stages instead:
        var fit = FitState(totalW, meanX, meanY, angle)

        // Stage A (x2): local width-consistency filter. A cue line has a
        // near-constant cross-section width (that's what widthPx measures);
        // a ball's edge locally balloons it because it's curving away from
        // the line. Bin the mask along the rough direction and drop any bin
        // whose perpendicular spread blows past the median bin's — this
        // catches contamination by its LOCAL shape, which is exactly what
        // the global aspect/fill checks upstream can't see.
        repeat(2) {
            val (fx, fy, fw) = filterByLocalWidth(xs, ys, ws, fit.angle, fit.meanX, fit.meanY)
            if (fx.size >= scaledMinLinePixels()) {
                xs.clear(); ys.clear(); ws.clear()
                xs.addAll(fx); ys.addAll(fy); ws.addAll(fw)
                fit = computeFit(xs, ys, ws)
            }
        }

        // Stage B: weighted RANSAC. Catches whatever slipped past the width
        // filter (e.g. a short arc briefly near-tangent to the true line).
        // Unlike a single least-squares fit, RANSAC never uses a
        // contamination-biased fit as its own outlier yardstick — each
        // candidate line is scored by direct pixel agreement, so it stays
        // correct even with a large minority of junk pixels in the set.
        val inlierThreshold = (residualStd(xs, ys, ws, fit.angle, fit.meanX, fit.meanY) * 1.8)
            .coerceIn(1.2, 6.0).toFloat()
        val inlierIdx = ransacLineFit(xs, ys, ws, inlierDistPx = inlierThreshold)
        if (inlierIdx.size >= scaledMinLinePixels()) {
            val rx = ArrayList<Int>(inlierIdx.size)
            val ry = ArrayList<Int>(inlierIdx.size)
            val rw = ArrayList<Float>(inlierIdx.size)
            for (idx in inlierIdx) { rx.add(xs[idx]); ry.add(ys[idx]); rw.add(ws[idx]) }
            xs.clear(); ys.clear(); ws.clear()
            xs.addAll(rx); ys.addAll(ry); ws.addAll(rw)
            fit = computeFit(xs, ys, ws)
        }

        // Stage C: one final light std-dev trim — now that gross
        // contamination is gone, this only shaves anti-aliasing /
        // quantization noise, which is what it was originally designed for.
        run {
            val (tx, ty, tw) = trimOutliersWeighted(xs, ys, ws, fit.angle, fit.meanX, fit.meanY)
            if (tx.size >= scaledMinLinePixels()) {
                xs.clear(); ys.clear(); ws.clear()
                xs.addAll(tx); ys.addAll(ty); ws.addAll(tw)
                fit = computeFit(xs, ys, ws)
            }
        }

        totalW = fit.totalW
        meanX = fit.meanX
        meanY = fit.meanY
        angle = fit.angle

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
            // xs/ys are integer column/row indices, i.e. pixel *corners*.
            // meanX/meanY is therefore biased half a pixel toward the
            // top-left corner convention. This bias is invisible in the
            // angle (PCA is translation-invariant) and cancels out along a
            // 45° diagonal, but doesn't cancel for near-horizontal/vertical
            // lines — which is exactly the "diagonal is perfect, axis-
            // aligned is off" pattern. +0.5 recenters to pixel centers.
            offsetX = (meanX + 0.5).toFloat(),
            offsetY = (meanY + 0.5).toFloat(),
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

                if (area < scaledMinLinePixels()) continue

                val bw = (maxX - minX + 1).toFloat()
                val bh = (maxY - minY + 1).toFloat()
                val aspect = maxOf(bw, bh) / minOf(bw, bh).coerceAtLeast(1f)
                val fill = area / (bw * bh)

                // Reject only clearly circular/compact blobs (partial balls).
                // Thin guidelines (even short ones in a small crop) often have
                // aspect ~1.3–2.0 — do NOT hard-reject those; score ranking
                // still prefers the more elongated component when several exist.
                if (aspect < 1.8f && fill > 0.45f) continue

                val xs = ArrayList<Int>(area)
                val ys = ArrayList<Int>(area)
                val ws = ArrayList<Float>(area)
                var totalW = 0f
                for (i in 0 until tail) {
                    val px = qx[i]; val py = qy[i]
                    val w = edgeWeight(brightness[py * size + px])
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

                val isCircleLike = aspect < 1.9f && fill > 0.45f
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
    // Robust refit helpers (local width filter + weighted RANSAC)
    // ------------------------------------------------------------------

    private data class FitState(
        val totalW: Float,
        val meanX: Double,
        val meanY: Double,
        val angle: Double
    )

    /** Recomputes totalW/meanX/meanY/angle from scratch after a filter pass. */
    private fun computeFit(xs: List<Int>, ys: List<Int>, ws: List<Float>): FitState {
        var totalW = 0f
        for (w in ws) totalW += w
        var meanX = 0.0; var meanY = 0.0
        if (totalW > 0f) {
            for (i in xs.indices) {
                meanX += xs[i] * ws[i]
                meanY += ys[i] * ws[i]
            }
            meanX /= totalW
            meanY /= totalW
        }
        val angle = if (xs.size >= 2) weightedFitAngle(xs, ys, ws, meanX, meanY) else 0.0
        return FitState(totalW, meanX, meanY, angle)
    }

    /** Weighted perpendicular-residual std relative to a given fit — used to size the RANSAC inlier band to the actual line thickness instead of a fixed guess. */
    private fun residualStd(
        xs: List<Int>, ys: List<Int>, ws: List<Float>,
        angle: Double, mx: Double, my: Double
    ): Double {
        val dirX = cos(angle); val dirY = sin(angle)
        var sw = 0.0; var swr2 = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - mx; val dy = ys[i] - my
            val r = -dx * dirY + dy * dirX
            val w = ws[i].toDouble()
            sw += w
            swr2 += w * r * r
        }
        return if (sw > 1e-6) sqrt(swr2 / sw) else 1.0
    }

    /**
     * Bins the point set along [angle] and drops any bin whose perpendicular
     * (cross-line) spread exceeds [widthFactor] times the median bin width.
     * Meant to run on a rough direction estimate — a ball's edge fused to
     * the real line by 4-connectivity locally balloons the mask's
     * cross-section (it's curving away from the line), which this catches
     * by LOCAL shape even when the whole component's aspect/fill still
     * look line-like to the upstream circularity checks.
     */
    private fun filterByLocalWidth(
        xs: List<Int>, ys: List<Int>, ws: List<Float>,
        angle: Double, mx: Double, my: Double,
        binPx: Float = 6f, widthFactor: Float = 2.2f
    ): Triple<ArrayList<Int>, ArrayList<Int>, ArrayList<Float>> {
        val n = xs.size
        if (n < 4) return Triple(ArrayList(xs), ArrayList(ys), ArrayList(ws))
        val dirX = cos(angle); val dirY = sin(angle)
        val along = FloatArray(n)
        val perp = FloatArray(n)
        var minA = Float.MAX_VALUE; var maxA = -Float.MAX_VALUE
        for (i in xs.indices) {
            val dx = xs[i] - mx; val dy = ys[i] - my
            val a = (dx * dirX + dy * dirY).toFloat()
            val p = (-dx * dirY + dy * dirX).toFloat()
            along[i] = a; perp[i] = p
            if (a < minA) minA = a
            if (a > maxA) maxA = a
        }
        val numBins = (((maxA - minA) / binPx).toInt() + 1).coerceAtLeast(1)
        val binMin = FloatArray(numBins) { Float.MAX_VALUE }
        val binMax = FloatArray(numBins) { -Float.MAX_VALUE }
        val binOf = IntArray(n)
        for (i in 0 until n) {
            val bin = (((along[i] - minA) / binPx).toInt()).coerceIn(0, numBins - 1)
            binOf[i] = bin
            if (perp[i] < binMin[bin]) binMin[bin] = perp[i]
            if (perp[i] > binMax[bin]) binMax[bin] = perp[i]
        }
        val widths = ArrayList<Float>(numBins)
        for (bin in 0 until numBins) {
            if (binMax[bin] >= binMin[bin]) widths.add(binMax[bin] - binMin[bin])
        }
        if (widths.isEmpty()) return Triple(ArrayList(xs), ArrayList(ys), ArrayList(ws))
        widths.sort()
        val medianWidth = widths[widths.size / 2].coerceAtLeast(1f)
        // Small additive slack (+1.5px) so a naturally clean, uniform-width
        // line doesn't get bins shaved off by pixel-quantization jitter.
        val maxAllowed = medianWidth * widthFactor + 1.5f

        val outXs = ArrayList<Int>(n); val outYs = ArrayList<Int>(n); val outWs = ArrayList<Float>(n)
        for (i in 0 until n) {
            val bin = binOf[i]
            val bw = binMax[bin] - binMin[bin]
            if (bw <= maxAllowed) {
                outXs.add(xs[i]); outYs.add(ys[i]); outWs.add(ws[i])
            }
        }
        return Triple(outXs, outYs, outWs)
    }

    /**
     * Weighted RANSAC line fit. Returns the indices (into [xs]/[ys]) of the
     * inlier set for the best-scoring line. Robust to a large minority of
     * outlier pixels because it never uses a contamination-biased least-
     * squares fit as its own reference — each candidate line is scored by
     * direct pixel agreement instead. Fixed seed => deterministic, no
     * frame-to-frame jitter from RNG alone.
     */
    private fun ransacLineFit(
        xs: List<Int>, ys: List<Int>, ws: List<Float>,
        iterations: Int = 50,
        inlierDistPx: Float = 1.6f
    ): IntArray {
        val n = xs.size
        if (n < 2) return IntArray(0)
        val rnd = java.util.Random(0x5EEDL)
        var bestCount = -1
        var bestA = 0f; var bestB = 1f; var bestC = 0f
        repeat(iterations) {
            val i1 = rnd.nextInt(n)
            var i2 = rnd.nextInt(n)
            var guard = 0
            while (i2 == i1 && guard < 5) { i2 = rnd.nextInt(n); guard++ }
            if (i2 == i1) return@repeat
            val x1 = xs[i1].toFloat(); val y1 = ys[i1].toFloat()
            val x2 = xs[i2].toFloat(); val y2 = ys[i2].toFloat()
            val dx = x2 - x1; val dy = y2 - y1
            val len = sqrt(dx * dx + dy * dy)
            if (len < 3f) return@repeat // too close together => unstable sample
            val a = -dy / len
            val b = dx / len
            val c = a * x1 + b * y1
            var count = 0
            for (i in 0 until n) {
                val d = abs(a * xs[i] + b * ys[i] - c)
                if (d <= inlierDistPx) count++
            }
            if (count > bestCount) {
                bestCount = count; bestA = a; bestB = b; bestC = c
            }
        }
        if (bestCount < 0) return IntArray(0)
        val inliers = ArrayList<Int>(bestCount)
        for (i in 0 until n) {
            val d = abs(bestA * xs[i] + bestB * ys[i] - bestC)
            if (d <= inlierDistPx) inliers.add(i)
        }
        return inliers.toIntArray()
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
