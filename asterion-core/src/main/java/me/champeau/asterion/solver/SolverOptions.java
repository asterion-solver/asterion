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

import me.champeau.asterion.image.DetectionOptions;

import java.time.Duration;

/**
 * Parameters of the solver. Instances are immutable, and created with a {@link #builder() builder},
 * which documents each parameter. By default, the solver is fully blind: it knows nothing about the image.
 *
 * @param raDeg the approximate right ascension of the image in degrees, or NaN if unknown
 * @param decDeg the approximate declination of the image in degrees, or NaN if unknown
 * @param searchRadiusDeg the radius of the region of the sky which is searched around the approximate position, in degrees
 * @param minScale the minimum scale of the image, in arcseconds per pixel, or 0 if unknown
 * @param maxScale the maximum scale of the image, in arcseconds per pixel, or 0 if unknown
 * @param minFieldWidthDeg the minimum width of the field of view, in degrees, or 0 if unknown
 * @param maxFieldWidthDeg the maximum width of the field of view, in degrees, or 0 if unknown
 * @param useImageHints true to use the position and scale found in image files for a first attempt
 * @param blindFallback true to try a blind solve when solving with the hints of the image file fails
 * @param maxQuadStars the number of stars of the image, starting with the brightest, which are used to build quads
 * @param codeTolerance the maximum distance between the geometric hash codes of matching quads
 * @param sipOrder the order of the SIP distortion polynomials: 0 to disable them, -1 to choose automatically
 * @param timeout the time after which the solver gives up
 * @param parity the handedness of the image
 * @param epoch the date of the image as a Julian year, used to apply proper motions, or NaN for the date of the image file
 * @param binning the binning applied to image files before detecting stars, 0 to choose automatically
 * @param pixelSizeMicrons the size of the pixels of the image in micrometers, binning included, or 0 if unknown
 * @param detection the parameters of the star detector
 */
public record SolverOptions(
        double raDeg,
        double decDeg,
        double searchRadiusDeg,
        double minScale,
        double maxScale,
        double minFieldWidthDeg,
        double maxFieldWidthDeg,
        boolean useImageHints,
        boolean blindFallback,
        int maxQuadStars,
        double codeTolerance,
        int sipOrder,
        Duration timeout,
        Parity parity,
        double epoch,
        int binning,
        double pixelSizeMicrons,
        DetectionOptions detection) {
    public static Builder builder() {
        return new Builder();
    }

    public static SolverOptions defaults() {
        return new Builder().build();
    }

    public Builder toBuilder() {
        var b = new Builder();
        b.raDeg = raDeg;
        b.decDeg = decDeg;
        b.searchRadiusDeg = searchRadiusDeg;
        b.minScale = minScale;
        b.maxScale = maxScale;
        b.minFieldWidthDeg = minFieldWidthDeg;
        b.maxFieldWidthDeg = maxFieldWidthDeg;
        b.useImageHints = useImageHints;
        b.blindFallback = blindFallback;
        b.maxQuadStars = maxQuadStars;
        b.codeTolerance = codeTolerance;
        b.sipOrder = sipOrder;
        b.timeout = timeout;
        b.parity = parity;
        b.epoch = epoch;
        b.binning = binning;
        b.pixelSizeMicrons = pixelSizeMicrons;
        b.detection = detection;
        return b;
    }

    /** True if the search is restricted to a region of the sky. */
    public boolean hasPosition() {
        return !Double.isNaN(raDeg) && !Double.isNaN(decDeg);
    }

    public static final class Builder {
        private double raDeg = Double.NaN;
        private double decDeg = Double.NaN;
        private double searchRadiusDeg = 10;
        private double minScale;
        private double maxScale;
        private double minFieldWidthDeg;
        private double maxFieldWidthDeg;
        private boolean useImageHints = true;
        private boolean blindFallback = true;
        private int maxQuadStars = 100;
        private double codeTolerance = 0.01;
        private int sipOrder = -1;
        private Duration timeout = Duration.ofSeconds(30);
        private Parity parity = Parity.BOTH;
        private double epoch = Double.NaN;
        private int binning;
        private double pixelSizeMicrons;
        private DetectionOptions detection = DetectionOptions.defaults();

        private Builder() {
        }

        /** Restricts the search to a cone around a position, in degrees. */
        public Builder position(double raDeg, double decDeg, double radiusDeg) {
            this.raDeg = raDeg;
            this.decDeg = decDeg;
            this.searchRadiusDeg = radiusDeg;
            return this;
        }

        /** Restricts the scale of the image, in arcseconds per pixel. Use 0 for an unknown bound. */
        public Builder scale(double minArcsecPerPixel, double maxArcsecPerPixel) {
            this.minScale = minArcsecPerPixel;
            this.maxScale = maxArcsecPerPixel;
            return this;
        }

        /**
         * Restricts the width of the field of view, in degrees. Use 0 for an unknown bound. This is
         * an alternative to {@link #scale(double, double)}.
         */
        public Builder fieldOfView(double minWidthDeg, double maxWidthDeg) {
            this.minFieldWidthDeg = minWidthDeg;
            this.maxFieldWidthDeg = maxWidthDeg;
            return this;
        }

        /**
         * Tells if the position and scale found in image files should be used to solve faster.
         * These hints are only used for a first attempt, when no explicit position or scale is set.
         */
        public Builder useImageHints(boolean value) {
            this.useImageHints = value;
            return this;
        }

        /** Tells if a blind solve must be attempted when solving with the hints of the image file fails. */
        public Builder blindFallback(boolean value) {
            this.blindFallback = value;
            return this;
        }

        /** The number of stars of the image, starting with the brightest, which are used to build quads. */
        public Builder maxQuadStars(int value) {
            this.maxQuadStars = Math.clamp(value, 4, 512);
            return this;
        }

        /** The maximum distance between the geometric hash codes of matching quads. */
        public Builder codeTolerance(double value) {
            this.codeTolerance = value;
            return this;
        }

        /** The order of SIP distortion polynomials: 0 to disable them, -1 to choose automatically. */
        public Builder sipOrder(int value) {
            this.sipOrder = value;
            return this;
        }

        public Builder timeout(Duration value) {
            this.timeout = value;
            return this;
        }

        public Builder parity(Parity value) {
            this.parity = value;
            return this;
        }

        /** The date of the image, as a Julian year, used to apply proper motions. */
        public Builder epoch(double value) {
            this.epoch = value;
            return this;
        }

        /** The binning applied to image files before detecting stars, 0 to choose automatically. */
        public Builder binning(int value) {
            this.binning = value;
            return this;
        }

        /**
         * The size of the pixels of the image in micrometers, binning included, which gives the
         * focal length of the optics. It overrides the one found in image files.
         */
        public Builder pixelSize(double micrometers) {
            this.pixelSizeMicrons = micrometers;
            return this;
        }

        public Builder detection(DetectionOptions value) {
            this.detection = value;
            return this;
        }

        public SolverOptions build() {
            return new SolverOptions(raDeg, decDeg, searchRadiusDeg, minScale, maxScale, minFieldWidthDeg, maxFieldWidthDeg, useImageHints,
                    blindFallback, maxQuadStars, codeTolerance, sipOrder, timeout, parity, epoch, binning, pixelSizeMicrons, detection);
        }
    }
}
