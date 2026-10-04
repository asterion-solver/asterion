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

import me.champeau.asterion.index.StarIndex;
import me.champeau.asterion.math.Sphere;

/**
 * Decides if a hypothesis is true, by checking that catalog stars are found in the image where
 * the hypothesis predicts them. This is a bayesian decision: each catalog star which falls in the
 * image updates the odds ratio between "the alignment is right, so there must be a star of the
 * image close to this position" and "the alignment is wrong, so a star of the image can only
 * be here by chance".
 */
final class Verifier {
    /** Probability that a catalog star has no counterpart in the image, when the alignment is right. */
    private static final double MISS = 0.25;
    private static final double LOG_MISS = Math.log(MISS);
    /** Hypotheses are abandoned when their odds get this low. */
    private static final double BAIL = -Math.log(1e6);
    /** A hypothesis is accepted when it is a billion times more likely than a false match. */
    private static final double ACCEPT_LOG_ODDS = Math.log(1e9);
    /**
     * Stars are used to verify a hypothesis when the uncertainty of their predicted position is
     * less than this number of times the expected error of star positions.
     */
    private static final double MAX_SIGMA = 4;

    private final StarIndex index;
    private final PointGrid grid;
    private final int count;
    private final int width;
    private final int height;
    private final double density;
    private final double years;
    private final double[] center = new double[3];
    private final double[] vec = new double[3];
    private final double[] pixel = new double[2];
    private int[] ranges = new int[64];

    Verifier(StarIndex index, double[] x, double[] y, int count, int width, int height, double years) {
        this.index = index;
        this.count = count;
        this.grid = new PointGrid(x, y, count, width, height);
        this.width = width;
        this.height = height;
        this.density = count / ((double) width * height);
        this.years = years;
    }

    /** The deepest level of the star pyramid which is required to get a number of stars in an area of the sky. */
    static int levelFor(StarIndex index, double area, int target) {
        var last = index.levelCount() - 1;
        for (var level = 0; level < last; level++) {
            if (index.cumulativeStars(level) / Sphere.SKY_AREA * area >= target) {
                return level;
            }
        }
        return last;
    }

    boolean verify(Hypothesis h) {
        // Only the stars which are close to the quad are checked: the position of the others is too
        // uncertain to tell anything about the hypothesis, they will be matched when it is refined
        var reach = Math.sqrt(MAX_SIGMA * MAX_SIGMA - 1.25) * Math.sqrt(h.quadR2);
        var farthest = Math.hypot(Math.max(h.quadX, width - 1 - h.quadX), Math.max(h.quadY, height - 1 - h.quadY));
        var maxVariance = MAX_SIGMA * MAX_SIGMA * Hypothesis.POSITION_ERROR * Hypothesis.POSITION_ERROR;
        h.toVector(h.quadX, h.quadY, center);
        var ra = Sphere.ra(center[0], center[1]);
        var dec = Sphere.dec(center[2]);
        var radius = Math.atan(h.scale * Math.min(reach, farthest)) * 1.02;
        var maxLevel = levelFor(index, (double) width * height * h.scale * h.scale, count);
        var s0 = h.stars[0];
        var s1 = h.stars[1];
        var s2 = h.stars[2];
        var s3 = h.stars[3];
        // Levels are visited starting with the first one which has cells smaller than the image: the
        // few stars of coarser levels cost more to find than they are worth, so they come last,
        // once the hypothesis has resisted to the others
        var first = 0;
        while (first < maxLevel && index.grid(first).cellSize() > radius) {
            first++;
        }
        var logOdds = 0.0;
        var best = 0.0;
        var accepted = false;
        for (var i = 0; i <= maxLevel; i++) {
            var level = i <= maxLevel - first ? first + i : i - (maxLevel - first + 1);
            if (level == 0 && first > 0 && !accepted && logOdds <= 0) {
                // nothing supports the hypothesis: the stars of coarse levels won't change that
                return false;
            }
            var needed = index.grid(level).maxConeRangeInts(radius);
            if (ranges.length < needed) {
                ranges = new int[needed];
            }
            var nr = index.coneStarRanges(level, ra, dec, radius, ranges);
            for (var r = 0; r < nr; r += 2) {
                var to = ranges[r + 1];
                for (var s = ranges[r]; s < to; s++) {
                    if (s == s0 || s == s1 || s == s2 || s == s3) {
                        continue;
                    }
                    index.starVector(s, years, vec);
                    if (!h.toPixel(vec[0], vec[1], vec[2], pixel)) {
                        continue;
                    }
                    var px = pixel[0];
                    var py = pixel[1];
                    if (px < 0 || py < 0 || px > width - 1 || py > height - 1) {
                        continue;
                    }
                    var variance = h.variance(px, py);
                    if (variance > maxVariance) {
                        continue;
                    }
                    var j = grid.nearest(px, py, 3 * Math.sqrt(variance));
                    if (j >= 0) {
                        var foreground = Math.exp(-grid.distance2 / (2 * variance)) / (Sphere.TWO_PI * variance);
                        logOdds += Math.log(MISS + (1 - MISS) * foreground / density);
                    } else {
                        logOdds += LOG_MISS;
                    }
                    if (logOdds > best) {
                        best = logOdds;
                        accepted |= best >= ACCEPT_LOG_ODDS;
                    } else if (!accepted && logOdds < BAIL) {
                        return false;
                    }
                }
            }
        }
        h.logOdds = best;
        return accepted;
    }
}
