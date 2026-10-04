/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package me.champeau.asterion.solver;

import me.champeau.asterion.index.QuadCode;
import me.champeau.asterion.index.StarIndex;
import me.champeau.asterion.math.Sphere;
import me.champeau.asterion.math.TangentPlane;

import java.util.ArrayList;
import java.util.List;

/**
 * Searches the quads of an image in indexes. Quads are enumerated starting with the ones made
 * of the brightest stars, each one is looked up in the hash grid of the index, and the alignments
 * suggested by the index quads which have the same shape are verified.
 */
final class QuadSearch {
    private static final double Q = QuadCode.QUANTUM;
    /**
     * The number of stars which are considered inside of the circle of a pair of stars, starting
     * with the brightest. Index quads are built with the brightest stars of the circle too.
     */
    private static final int MAX_MEMBERS = 10;

    /** An index to search, and what's needed to search it. */
    private static final class Target {
        final StarIndex index;
        final Verifier verifier;
        final int bins;
        final double years;
        final double minPixels;
        final double maxPixels;

        Target(StarIndex index, Verifier verifier, double years, double minPixels, double maxPixels) {
            this.index = index;
            this.verifier = verifier;
            this.bins = index.codeBins();
            this.years = years;
            this.minPixels = minPixels;
            this.maxPixels = maxPixels;
        }
    }

    /** The number of stars of the image, starting with the brightest, among which check stars are searched. */
    private static final int CHECK_STARS = 250;

    private final List<Target> targets = new ArrayList<>();
    private final PointGrid checkGrid;
    private final int width;
    private final int height;
    private final double[] x;
    private final double[] y;
    private final int n;
    private final double tolerance;
    private final double toleranceQ;
    private final double minScale;
    private final double maxScale;
    private final double minPixels2;
    private final double maxPixels2;
    private final boolean tryFlipped;
    private final boolean tryNormal;
    private final double[] hint;
    private final double hintRadius;
    private final double diagonal;
    private final double maxResidualFactor;
    private final long deadline;
    private final Hypothesis hypothesis = new Hypothesis();
    private final double[] starVec = new double[12];
    private final double[] tmp = new double[3];
    private final double[] xi = new double[4];
    private final double[] eta = new double[4];
    private final int[] quad = new int[4];
    long quads;
    long candidates;
    long verifications;
    boolean timedOut;

    /**
     * @param epoch the date of the image, as a Julian year
     * @param maxStars the number of stars, starting with the brightest, which are used to build quads
     * @param tolerance the maximum distance between the codes of matching quads
     * @param minScale the minimum scale of the image in radians per pixel, or 0
     * @param maxScale the maximum scale of the image in radians per pixel, or 0
     * @param hint the unit vector of the center of the region to search, or null
     * @param hintRadius the radius of the region to search, in radians
     */
    QuadSearch(List<StarIndex> indexes, double[] x, double[] y, int n, int width, int height, SolverOptions options, double epoch,
               int maxStars, double tolerance, double minScale, double maxScale, double[] hint, double hintRadius, long deadline) {
        this.x = x;
        this.y = y;
        this.n = Math.min(n, maxStars);
        this.width = width;
        this.height = height;
        this.checkGrid = new PointGrid(x, y, Math.min(n, CHECK_STARS), width, height);
        this.tolerance = tolerance;
        this.toleranceQ = tolerance * Q;
        this.minScale = minScale;
        this.maxScale = maxScale > 0 ? maxScale : Double.MAX_VALUE;
        this.diagonal = Math.hypot(width, height);
        var smallest = Double.MAX_VALUE;
        var largest = 0.0;
        for (var index : indexes) {
            // the size in pixels of the quads of this index, given what is known of the scale
            var minPixels = Math.max(10, 0.03 * diagonal);
            var maxPixels = diagonal;
            if (maxScale > 0) {
                minPixels = Math.max(minPixels, 0.98 * index.minQuadDiam() / maxScale);
            }
            if (minScale > 0) {
                maxPixels = Math.min(maxPixels, 1.02 * index.maxQuadDiam() / minScale);
            }
            if (minPixels <= maxPixels) {
                var years = epoch - index.epoch();
                var verifier = new Verifier(index, x, y, Math.min(n, 200), width, height, years);
                targets.add(new Target(index, verifier, years, minPixels, maxPixels));
                smallest = Math.min(smallest, minPixels);
                largest = Math.max(largest, maxPixels);
            }
        }
        this.minPixels2 = smallest * smallest;
        this.maxPixels2 = largest * largest;
        this.tryFlipped = options.parity() != Parity.NORMAL;
        this.tryNormal = options.parity() != Parity.FLIPPED;
        this.hint = hint;
        this.hintRadius = hintRadius;
        this.maxResidualFactor = 2 * tolerance;
        this.deadline = deadline;
    }

    /**
     * @return a verified hypothesis, or null if none was found
     */
    Hypothesis run() {
        if (n < 4 || targets.isEmpty()) {
            return null;
        }
        var words = (n + 63) >>> 6;
        var pairs = n * (n - 1) / 2;
        var members = new long[pairs * words];
        var dist2 = new double[pairs];
        var count = new byte[pairs];
        var slack = tolerance * (1 + tolerance);
        for (var nn = 1; nn < n; nn++) {
            if (System.nanoTime() > deadline) {
                timedOut = true;
                return null;
            }
            var px = x[nn];
            var py = y[nn];
            // the new star is C or D of the pairs made of brighter stars
            for (var j = 1; j < nn; j++) {
                var bx = x[j];
                var by = y[j];
                var row = j * (j - 1) / 2;
                for (var i = 0; i < j; i++) {
                    var p = row + i;
                    var d2 = dist2[p];
                    if (d2 == 0 || count[p] >= MAX_MEMBERS) {
                        continue;
                    }
                    var ax = x[i];
                    var ay = y[i];
                    if ((px - ax) * (px - bx) + (py - ay) * (py - by) > slack * d2) {
                        continue;
                    }
                    var base = p * words;
                    for (var wd = 0; wd < words; wd++) {
                        for (var mem = members[base + wd]; mem != 0; mem &= mem - 1) {
                            var m = (wd << 6) + Long.numberOfTrailingZeros(mem);
                            if (tryQuad(i, j, m, nn, d2)) {
                                return hypothesis;
                            }
                        }
                    }
                    members[base + (nn >>> 6)] |= 1L << nn;
                    count[p]++;
                }
            }
            // the new star is A or B
            var row = nn * (nn - 1) / 2;
            for (var i = 0; i < nn; i++) {
                var ax = x[i];
                var ay = y[i];
                var d2 = (px - ax) * (px - ax) + (py - ay) * (py - ay);
                if (d2 < minPixels2 || d2 > maxPixels2) {
                    continue;
                }
                var p = row + i;
                dist2[p] = d2;
                var base = p * words;
                var inside = 0;
                for (var m = 0; m < nn && inside < MAX_MEMBERS; m++) {
                    if (m != i && (x[m] - ax) * (x[m] - px) + (y[m] - ay) * (y[m] - py) <= slack * d2) {
                        members[base + (m >>> 6)] |= 1L << m;
                        inside++;
                    }
                }
                count[p] = (byte) inside;
                for (var w1 = 0; w1 < words; w1++) {
                    for (var mem1 = members[base + w1]; mem1 != 0; mem1 &= mem1 - 1) {
                        var m1 = (w1 << 6) + Long.numberOfTrailingZeros(mem1);
                        for (var w2 = w1; w2 < words; w2++) {
                            var mem2 = members[base + w2];
                            if (w2 == w1) {
                                mem2 = mem1 & (mem1 - 1);
                            }
                            for (; mem2 != 0; mem2 &= mem2 - 1) {
                                var m2 = (w2 << 6) + Long.numberOfTrailingZeros(mem2);
                                if (tryQuad(i, nn, m1, m2, d2)) {
                                    return hypothesis;
                                }
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private boolean tryQuad(int a, int b, int c, int d, double d2) {
        quads++;
        var ax = x[a];
        var ay = y[a];
        var abx = x[b] - ax;
        var aby = y[b] - ay;
        var pcx = x[c] - ax;
        var pcy = y[c] - ay;
        var pdx = x[d] - ax;
        var pdy = y[d] - ay;
        var cr = (pcx * abx + pcy * aby) / d2;
        var ci = (pcy * abx - pcx * aby) / d2;
        var dr = (pdx * abx + pdy * aby) / d2;
        var di = (pdy * abx - pdx * aby) / d2;
        var pixels = Math.sqrt(d2);
        // all the orderings of the stars which are canonical, within tolerance
        for (var swapAb = 0; swapAb < 2; swapAb++) {
            if (swapAb == 1) {
                cr = 1 - cr;
                ci = -ci;
                dr = 1 - dr;
                di = -di;
            }
            if (cr + dr > 1 + tolerance) {
                continue;
            }
            var qa = swapAb == 0 ? a : b;
            var qb = swapAb == 0 ? b : a;
            if (cr <= dr + tolerance && lookup(cr, ci, dr, di, qa, qb, c, d, pixels)) {
                return true;
            }
            if (dr <= cr + tolerance && lookup(dr, di, cr, ci, qa, qb, d, c, pixels)) {
                return true;
            }
        }
        return false;
    }

    private boolean lookup(double cx, double cy, double dx, double dy, int a, int b, int c, int d, double pixels) {
        for (var target : targets) {
            if (pixels < target.minPixels || pixels > target.maxPixels) {
                continue;
            }
            if (tryFlipped && lookup(target, cx, cy, dx, dy, a, b, c, d, pixels, 1)) {
                return true;
            }
            if (tryNormal && lookup(target, cx, -cy, dx, -dy, a, b, c, d, pixels, -1)) {
                return true;
            }
        }
        return false;
    }

    private int binLow(double q, int bins) {
        return QuadCode.bin((int) Math.max(0, Math.floor(q - toleranceQ)), bins);
    }

    private int binHigh(double q, int bins) {
        return QuadCode.bin((int) Math.min(Q, Math.ceil(q + toleranceQ)), bins);
    }

    private boolean lookup(Target target, double cx, double cy, double dx, double dy, int a, int b, int c, int d, double pixels, int sign) {
        var index = target.index;
        var bins = target.bins;
        var q0 = cx * Q;
        var q1 = (cy + 0.5) * Q;
        var q2 = dx * Q;
        var q3 = (dy + 0.5) * Q;
        var min = Math.min(Math.min(q0, q1), Math.min(q2, q3));
        var max = Math.max(Math.max(q0, q1), Math.max(q2, q3));
        if (min < -toleranceQ || max > Q + toleranceQ) {
            return false;
        }
        var l0 = binLow(q0, bins);
        var h0 = binHigh(q0, bins);
        var l1 = binLow(q1, bins);
        var h1 = binHigh(q1, bins);
        var l2 = binLow(q2, bins);
        var h2 = binHigh(q2, bins);
        var l3 = binLow(q3, bins);
        var h3 = binHigh(q3, bins);
        var limit = toleranceQ * toleranceQ;
        for (var i0 = l0; i0 <= h0; i0++) {
            for (var i1 = l1; i1 <= h1; i1++) {
                for (var i2 = l2; i2 <= h2; i2++) {
                    var base = ((i0 * bins + i1) * bins + i2) * bins;
                    var from = index.quadCellStart(base + l3);
                    var to = index.quadCellStart(base + h3 + 1);
                    for (var q = from; q < to; q++) {
                        var code = index.quadCode(q);
                        var e0 = (code & 0xFFFF) - q0;
                        var e1 = ((code >>> 16) & 0xFFFF) - q1;
                        var e2 = ((code >>> 32) & 0xFFFF) - q2;
                        var e3 = (code >>> 48) - q3;
                        if (e0 * e0 + e1 * e1 + e2 * e2 + e3 * e3 <= limit && evaluate(target, q, a, b, c, d, pixels, sign)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Evaluates the alignment suggested by an index quad which has the same shape as a quad of
     * the image.
     *
     * @param sign 1 if the image has the same handedness as the tangent plane, -1 otherwise
     */
    private boolean evaluate(Target target, int q, int a, int b, int c, int d, double pixels, int sign) {
        var index = target.index;
        var years = target.years;
        var scale = index.quadDiam(q) / pixels;
        if (scale < minScale || scale > maxScale) {
            return false;
        }
        candidates++;
        if (!hasCheckStars(index.quadChecks(q), a, b, pixels, sign)) {
            return false;
        }
        var h = hypothesis;
        var sx = 0.0;
        var sy = 0.0;
        var sz = 0.0;
        for (var k = 0; k < 4; k++) {
            var star = index.quadStar(q, k);
            h.stars[k] = star;
            index.starVector(star, years, tmp);
            starVec[3 * k] = tmp[0];
            starVec[3 * k + 1] = tmp[1];
            starVec[3 * k + 2] = tmp[2];
            sx += tmp[0];
            sy += tmp[1];
            sz += tmp[2];
        }
        if (hint != null) {
            var limit = Math.cos(Math.min(Math.PI, hintRadius + scale * diagonal));
            if (starVec[0] * hint[0] + starVec[1] * hint[1] + starVec[2] * hint[2] < limit) {
                return false;
            }
        }
        var plane = TangentPlane.at(sx, sy, sz);
        quad[0] = a;
        quad[1] = b;
        quad[2] = c;
        quad[3] = d;
        var pmx = 0.0;
        var pmy = 0.0;
        var qmx = 0.0;
        var qmy = 0.0;
        for (var k = 0; k < 4; k++) {
            xi[k] = plane.xi(starVec[3 * k], starVec[3 * k + 1], starVec[3 * k + 2]);
            eta[k] = plane.eta(starVec[3 * k], starVec[3 * k + 1], starVec[3 * k + 2]);
            pmx += x[quad[k]];
            pmy += sign * y[quad[k]];
            qmx += xi[k];
            qmy += eta[k];
        }
        pmx /= 4;
        pmy /= 4;
        qmx /= 4;
        qmy /= 4;
        // least squares similarity, as complex numbers: q = s.p + t
        var sr = 0.0;
        var si = 0.0;
        var den = 0.0;
        for (var k = 0; k < 4; k++) {
            var dpx = x[quad[k]] - pmx;
            var dpy = sign * y[quad[k]] - pmy;
            var dqx = xi[k] - qmx;
            var dqy = eta[k] - qmy;
            sr += dqx * dpx + dqy * dpy;
            si += dqy * dpx - dqx * dpy;
            den += dpx * dpx + dpy * dpy;
        }
        sr /= den;
        si /= den;
        var residual = 0.0;
        for (var k = 0; k < 4; k++) {
            var dpx = x[quad[k]] - pmx;
            var dpy = sign * y[quad[k]] - pmy;
            var ex = xi[k] - qmx - (sr * dpx - si * dpy);
            var ey = eta[k] - qmy - (si * dpx + sr * dpy);
            residual += ex * ex + ey * ey;
        }
        var s2 = sr * sr + si * si;
        var maxResidual = maxResidualFactor * pixels + 2 * Hypothesis.POSITION_ERROR;
        if (residual / 4 > maxResidual * maxResidual * s2) {
            return false;
        }
        verifications++;
        h.plane = plane;
        h.m00 = sr;
        h.m01 = -si * sign;
        h.m10 = si;
        h.m11 = sr * sign;
        h.tx = qmx - (sr * pmx - si * pmy);
        h.ty = qmy - (si * pmx + sr * pmy);
        h.scale = Math.sqrt(s2);
        h.quadX = pmx;
        h.quadY = sign * pmy;
        h.quadR2 = den;
        h.index = index;
        h.years = years;
        return target.verifier.verify(h);
    }

    /**
     * Tells if the check stars of an index quad are in the image, where the match of the quad with
     * a quad of the image predicts them. This requires nothing but the image, so false matches are
     * rejected before reading anything else from the index.
     *
     * @return false if check stars are expected in the image, but none of them is there
     */
    private boolean hasCheckStars(long checks, int a, int b, double pixels, int sign) {
        var ax = x[a];
        var ay = y[a];
        var abx = x[b] - ax;
        var aby = y[b] - ay;
        var radius = 2 * Hypothesis.POSITION_ERROR + tolerance * pixels;
        var expected = 0;
        for (var k = 0; k < 2; k++) {
            var ex = (short) (checks >>> (32 * k));
            if (ex == QuadCode.NO_CHECK) {
                continue;
            }
            var zr = ex / QuadCode.CHECK_SCALE;
            var zi = sign * (short) (checks >>> (32 * k + 16)) / QuadCode.CHECK_SCALE;
            var px = ax + abx * zr - aby * zi;
            var py = ay + abx * zi + aby * zr;
            if (px < 0 || py < 0 || px > width - 1 || py > height - 1) {
                continue;
            }
            if (checkGrid.nearest(px, py, radius) >= 0) {
                return true;
            }
            expected++;
        }
        return expected == 0;
    }

    static double[] vector(double raDeg, double decDeg) {
        var v = new double[3];
        Sphere.toVector(Math.toRadians(raDeg), Math.toRadians(decDeg), v);
        return v;
    }
}
