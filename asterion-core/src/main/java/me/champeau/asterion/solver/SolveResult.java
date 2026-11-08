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

import java.util.Optional;
import java.util.OptionalDouble;

/** The outcome of a solve. */
public final class SolveResult {
    private final Solution solution;
    private final StarList stars;
    private final Stats stats;
    private final double pixelSizeMicrons;
    private final int[] matchedStars;
    private final double fwhmPixels;

    /**
     * @param solution the solution, or null if none was found
     * @param pixelSizeMicrons the size of the pixels of the image in micrometers, or NaN if it isn't known
     * @param matchedStars the indices of the stars which the solution matched with the catalog
     * @param fwhmPixels the median width of the stars, see {@link StarWidths}
     */
    SolveResult(Solution solution, StarList stars, Stats stats, double pixelSizeMicrons, int[] matchedStars, double fwhmPixels) {
        this.solution = solution;
        this.stars = stars;
        this.stats = stats;
        this.pixelSizeMicrons = pixelSizeMicrons;
        this.matchedStars = matchedStars;
        this.fwhmPixels = fwhmPixels;
    }

    /** The indices of the stars which the solution matched with the catalog, in {@link #stars()}. */
    int[] matchedStars() {
        return matchedStars;
    }

    /** The solution, if one was found. */
    public Optional<Solution> solution() {
        return Optional.ofNullable(solution);
    }

    public boolean solved() {
        return solution != null;
    }

    /** The stars which were detected in the image, sorted by decreasing brightness. */
    public StarList stars() {
        return stars;
    }

    /**
     * The median full width at half maximum of the stars of the image, in pixels, or 0 without stars. Once the image is
     * solved, it is the one of the stars which were matched with the catalog, bright enough to be measured reliably, and
     * not saturated, fitted with a gaussian when the pixels of the image were solved. Otherwise, or with too few such
     * stars, it is the one of the stars which were detected.
     */
    public double fwhmPixels() {
        return fwhmPixels;
    }

    /** What the solver did. */
    public Stats stats() {
        return stats;
    }

    /** The size of the pixels of the image, in micrometers, if known. */
    public OptionalDouble pixelSizeMicrons() {
        return Double.isNaN(pixelSizeMicrons) ? OptionalDouble.empty() : OptionalDouble.of(pixelSizeMicrons);
    }

    /**
     * The focal length of the optics which took the image, in millimeters. It is known when the
     * image is solved, and the size of its pixels is known, from the image file or from
     * {@link SolverOptions.Builder#pixelSize(double)}.
     */
    public OptionalDouble focalLengthMm() {
        if (solution == null || Double.isNaN(pixelSizeMicrons)) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(solution.focalLengthMm(pixelSizeMicrons));
    }

    @Override
    public String toString() {
        return "SolveResult[solution=%s, stars=%d, stats=%s]".formatted(solution, stars.size(), stats);
    }

    /**
     * Statistics of a solve.
     *
     * @param quads the number of quads of the image which were searched in the index
     * @param candidates the number of index quads which matched
     * @param verifications the number of candidates which were verified against the catalog
     * @param loadMillis the time spent reading the image
     * @param detectMillis the time spent extracting stars
     * @param solveMillis the time spent searching for a solution
     * @param usedHints true if the solution was found with the hints of the image file
     */
    public record Stats(
            long quads,
            long candidates,
            long verifications,
            double loadMillis,
            double detectMillis,
            double solveMillis,
            boolean usedHints) {
        public double totalMillis() {
            return loadMillis + detectMillis + solveMillis;
        }
    }
}
