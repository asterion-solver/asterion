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

import me.champeau.asterion.image.StarList;
import me.champeau.asterion.index.StarIndex;
import me.champeau.asterion.math.Sphere;
import me.champeau.asterion.wcs.Wcs;
import me.champeau.asterion.wcs.WcsFitter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Turns a verified hypothesis into an accurate solution: all the stars of the image are matched
 * with the catalog, and a WCS is fitted on the matches, the process being repeated as the solution
 * gets better.
 */
final class Refiner {
    private static final int MAX_AUTOMATIC_SIP_ORDER = 5;

    private final StarIndex index;
    private final double[] x;
    private final double[] y;
    private final int count;
    private final int width;
    private final int height;
    private final SolverOptions options;
    private final double years;
    private final double centerX;
    private final double centerY;
    private final double[] vec = new double[3];
    private final double[] pixel = new double[2];
    /** Catalog stars: their position in the image according to the hypothesis, and their direction. */
    private double[] refX = new double[1024];
    private double[] refY = new double[1024];
    private double[] refVec = new double[3 * 1024];
    private int refs;
    private PointGrid refGrid;
    private int[] bestStar;
    private double[] bestDistance;
    private final double[] pairX;
    private final double[] pairY;
    private final double[] pairVec;
    private final double[] residuals;
    private int pairs;

    Refiner(StarIndex index, StarList stars, int width, int height, SolverOptions options, double years) {
        this.index = index;
        this.x = stars.x();
        this.y = stars.y();
        this.count = stars.size();
        this.width = width;
        this.height = height;
        this.options = options;
        this.years = years;
        this.centerX = (width - 1) / 2.0;
        this.centerY = (height - 1) / 2.0;
        this.pairX = new double[count];
        this.pairY = new double[count];
        this.pairVec = new double[3 * count];
        this.residuals = new double[count];
    }

    Solution refine(Hypothesis h) {
        var center = new double[3];
        h.toVector(centerX, centerY, center);
        var positionError = Hypothesis.POSITION_ERROR;
        gather(h, center);

        match(h, null, 0);
        if (pairs < 4) {
            return null;
        }
        var wcs = WcsFitter.fitLinear(pairX, pairY, pairVec, pairs, centerX, centerY, center);
        if (wcs == null) {
            return null;
        }
        var matchRadius = 4 * positionError;
        for (var iter = 0; iter < 2; iter++) {
            var fitted = rematchAndFit(h, wcs, center, matchRadius, 0);
            if (fitted == null) {
                break;
            }
            wcs = fitted;
            matchRadius = Math.max(2 * positionError, Math.min(matchRadius, 3 * rms(wcs) + 0.5));
        }
        // distortion: polynomials of increasing order are fitted, and the simplest of the models
        // which describe the image well is kept
        var maxOrder = options.sipOrder() < 0 ? MAX_AUTOMATIC_SIP_ORDER : options.sipOrder();
        var models = new Wcs[maxOrder + 2];
        var rms = new double[maxOrder + 2];
        models[0] = wcs;
        rms[0] = rms(wcs);
        var current = wcs;
        var fittedOrder = 0;
        for (var order = 2; order <= maxOrder && pairs >= WcsFitter.minStarsForSip(order); order++) {
            var fitted = rematchAndFit(h, current, center, matchRadius, order);
            if (fitted == null) {
                break;
            }
            models[order] = fitted;
            rms[order] = rms(fitted);
            current = fitted;
            fittedOrder = order;
        }
        if (options.sipOrder() < 0) {
            var best = rms[0];
            for (var order = 2; order <= fittedOrder; order++) {
                best = Math.min(best, rms[order]);
            }
            for (var order = 0; order <= fittedOrder; order++) {
                if (models[order] != null && rms[order] <= 1.03 * best) {
                    wcs = models[order];
                    break;
                }
            }
        } else {
            wcs = models[fittedOrder];
        }
        // last pass, with stars which are close to their expected position only
        for (var iter = 0; iter < 2; iter++) {
            matchRadius = Math.max(1, Math.min(matchRadius, 3 * rms(wcs) + 0.5));
            var fitted = rematchAndFit(h, wcs, center, matchRadius, wcs.sipOrder());
            if (fitted == null) {
                break;
            }
            wcs = fitted;
        }
        // a true solution matches many more stars than the ones of the quad which found it
        if (pairs < Math.clamp(count / 2, 5, 8)) {
            return null;
        }
        // final residuals, on the sky
        var sum = 0.0;
        var matches = new ArrayList<Solution.Match>(pairs);
        for (var i = 0; i < pairs; i++) {
            wcs.pixelToVector(pairX[i], pairY[i], vec);
            var dx = vec[0] - pairVec[3 * i];
            var dy = vec[1] - pairVec[3 * i + 1];
            var dz = vec[2] - pairVec[3 * i + 2];
            var chord2 = dx * dx + dy * dy + dz * dz;
            sum += chord2;
            matches.add(new Solution.Match(pairX[i], pairY[i],
                    Math.toDegrees(Sphere.ra(pairVec[3 * i], pairVec[3 * i + 1])), Math.toDegrees(Sphere.dec(pairVec[3 * i + 2])),
                    Math.sqrt(chord2) / Sphere.ARCSEC));
        }
        var rmsArcsec = Math.sqrt(sum / pairs) / Sphere.ARCSEC;
        wcs = WcsFitter.withInverse(wcs, width, height);
        var sky = wcs.pixelToSky(centerX, centerY);
        var scale = wcs.pixelScale();
        return new Solution(wcs, sky[0], sky[1], scale, wcs.rotation(), wcs.flipped(),
                width * scale / 3600, height * scale / 3600, pairs, rmsArcsec, h.logOdds, index.name(), List.copyOf(matches));
    }

    private Wcs rematchAndFit(Hypothesis h, Wcs wcs, double[] center, double matchRadius, int sipOrder) {
        wcs.pixelToVector(centerX, centerY, center);
        match(h, wcs, matchRadius);
        var current = wcs;
        for (var round = 0; round < 3; round++) {
            if (pairs < 4) {
                return null;
            }
            var fitted = WcsFitter.fitLinear(pairX, pairY, pairVec, pairs, centerX, centerY, center);
            if (fitted != null && sipOrder >= 2) {
                var sip = WcsFitter.fitSip(pairX, pairY, pairVec, pairs, fitted, sipOrder, width, height);
                if (sip != null) {
                    fitted = sip;
                }
            }
            if (fitted == null) {
                return null;
            }
            current = fitted;
            if (!clip(current)) {
                break;
            }
        }
        return current;
    }

    /**
     * Collects the catalog stars which are in the image, or close to it. This is done once: the
     * stars are located in the image with the hypothesis, which is good enough to find them again
     * when the solution gets better.
     */
    private void gather(Hypothesis h, double[] center) {
        var ra = Sphere.ra(center[0], center[1]);
        var dec = Sphere.dec(center[2]);
        var margin = 0.05 * Math.hypot(width, height);
        var radius = Math.atan(h.scale * (Math.hypot(width, height) / 2 + margin)) * 1.02;
        // as many catalog stars as possible, without having them outnumber the stars of the image too much
        var maxLevel = Verifier.levelFor(index, (double) width * height * h.scale * h.scale, 2 * count);
        refs = 0;
        for (var level = 0; level <= maxLevel; level++) {
            var ranges = new int[index.grid(level).maxConeRangeInts(radius)];
            var nr = index.coneStarRanges(level, ra, dec, radius, ranges);
            for (var r = 0; r < nr; r += 2) {
                for (var s = ranges[r]; s < ranges[r + 1]; s++) {
                    index.starVector(s, years, vec);
                    if (!h.toPixel(vec[0], vec[1], vec[2], pixel)) {
                        continue;
                    }
                    if (pixel[0] < -margin || pixel[1] < -margin || pixel[0] > width - 1 + margin || pixel[1] > height - 1 + margin) {
                        continue;
                    }
                    if (refs == refX.length) {
                        refX = Arrays.copyOf(refX, 2 * refs);
                        refY = Arrays.copyOf(refY, 2 * refs);
                        refVec = Arrays.copyOf(refVec, 6 * refs);
                    }
                    refX[refs] = pixel[0];
                    refY[refs] = pixel[1];
                    refVec[3 * refs] = vec[0];
                    refVec[3 * refs + 1] = vec[1];
                    refVec[3 * refs + 2] = vec[2];
                    refs++;
                }
            }
        }
        refGrid = new PointGrid(refX, refY, refs, width, height);
        bestStar = new int[refs];
        bestDistance = new double[refs];
    }

    /**
     * Pairs each catalog star with the closest star of the image. Stars of the image are moved to
     * where the hypothesis would have put them, given their position on the sky according to the
     * current solution: this is where catalog stars are indexed.
     *
     * @param wcs the current solution, or null to match with the hypothesis itself, in which case
     * the matching radius depends on the uncertainty of the hypothesis
     */
    private void match(Hypothesis h, Wcs wcs, double matchRadius) {
        Arrays.fill(bestStar, -1);
        Arrays.fill(bestDistance, Double.MAX_VALUE);
        for (var j = 0; j < count; j++) {
            var px = x[j];
            var py = y[j];
            var radius = matchRadius;
            if (wcs == null) {
                radius = 3 * Math.sqrt(h.variance(px, py));
            } else {
                wcs.pixelToVector(px, py, vec);
                if (!h.toPixel(vec[0], vec[1], vec[2], pixel)) {
                    continue;
                }
                px = pixel[0];
                py = pixel[1];
            }
            var k = refGrid.nearest(px, py, radius);
            if (k >= 0 && refGrid.distance2 < bestDistance[k]) {
                bestDistance[k] = refGrid.distance2;
                bestStar[k] = j;
            }
        }
        pairs = 0;
        for (var k = 0; k < refs && pairs < count; k++) {
            var j = bestStar[k];
            if (j >= 0) {
                pairX[pairs] = x[j];
                pairY[pairs] = y[j];
                pairVec[3 * pairs] = refVec[3 * k];
                pairVec[3 * pairs + 1] = refVec[3 * k + 1];
                pairVec[3 * pairs + 2] = refVec[3 * k + 2];
                pairs++;
            }
        }
    }

    /** Computes the distance in pixels between each star and its catalog position. */
    private void computeResiduals(Wcs wcs) {
        var scale = wcs.pixelScale() * Sphere.ARCSEC;
        for (var i = 0; i < pairs; i++) {
            wcs.pixelToVector(pairX[i], pairY[i], vec);
            var dx = vec[0] - pairVec[3 * i];
            var dy = vec[1] - pairVec[3 * i + 1];
            var dz = vec[2] - pairVec[3 * i + 2];
            residuals[i] = Math.sqrt(dx * dx + dy * dy + dz * dz) / scale;
        }
    }

    private double rms(Wcs wcs) {
        computeResiduals(wcs);
        var sum = 0.0;
        for (var i = 0; i < pairs; i++) {
            sum += residuals[i] * residuals[i];
        }
        return pairs == 0 ? 0 : Math.sqrt(sum / pairs);
    }

    /**
     * Removes the pairs which don't agree with the others.
     *
     * @return true if pairs were removed
     */
    private boolean clip(Wcs wcs) {
        if (pairs < 8) {
            return false;
        }
        computeResiduals(wcs);
        var sorted = Arrays.copyOf(residuals, pairs);
        Arrays.sort(sorted);
        // the median of a Rayleigh distribution is 1.177 sigma
        var sigma = sorted[pairs / 2] / 1.1774;
        var limit = Math.max(3 * sigma, 0.2);
        var kept = 0;
        for (var i = 0; i < pairs; i++) {
            if (residuals[i] <= limit) {
                pairX[kept] = pairX[i];
                pairY[kept] = pairY[i];
                pairVec[3 * kept] = pairVec[3 * i];
                pairVec[3 * kept + 1] = pairVec[3 * i + 1];
                pairVec[3 * kept + 2] = pairVec[3 * i + 2];
                kept++;
            }
        }
        var removed = kept < pairs;
        pairs = kept;
        return removed;
    }
}
