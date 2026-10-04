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
package me.champeau.asterion.image;

import java.util.OptionalDouble;

/**
 * What an image file tells about its content, which may help solving it faster. Hints are created
 * with a {@link #builder() builder}: the values which aren't set are unknown.
 */
public final class ImageHints {
    private static final ImageHints NONE = builder().build();

    // unknown values are NaN
    private final double raDeg;
    private final double decDeg;
    private final double pixelScale;
    private final double epoch;
    private final double pixelSizeMicrons;
    private final boolean bayer;

    private ImageHints(Builder builder) {
        this.raDeg = builder.raDeg;
        this.decDeg = builder.decDeg;
        this.pixelScale = builder.pixelScale;
        this.epoch = builder.epoch;
        this.pixelSizeMicrons = builder.pixelSizeMicrons;
        this.bayer = builder.bayer;
    }

    /** Hints which tell nothing. */
    public static ImageHints none() {
        return NONE;
    }

    /** A builder with the values of these hints, to change some of them. */
    public Builder toBuilder() {
        return builder()
                .raDeg(raDeg)
                .decDeg(decDeg)
                .pixelScale(pixelScale)
                .epoch(epoch)
                .pixelSizeMicrons(pixelSizeMicrons)
                .bayer(bayer);
    }

    public static Builder builder() {
        return new Builder();
    }

    private static OptionalDouble optional(double value) {
        return Double.isNaN(value) ? OptionalDouble.empty() : OptionalDouble.of(value);
    }

    /** The approximate right ascension of the image, in degrees. */
    public OptionalDouble raDeg() {
        return optional(raDeg);
    }

    /** The approximate declination of the image, in degrees. */
    public OptionalDouble decDeg() {
        return optional(decDeg);
    }

    /** The approximate scale of the image, in arcseconds per pixel. */
    public OptionalDouble pixelScale() {
        return optional(pixelScale);
    }

    /** The date of the observation, as a Julian year. */
    public OptionalDouble epoch() {
        return optional(epoch);
    }

    /** The size of the pixels of the image, in micrometers, binning included. */
    public OptionalDouble pixelSizeMicrons() {
        return optional(pixelSizeMicrons);
    }

    /** True if the image is the raw frame of a color sensor. */
    public boolean bayer() {
        return bayer;
    }

    public boolean hasPosition() {
        return !Double.isNaN(raDeg) && !Double.isNaN(decDeg);
    }

    @Override
    public String toString() {
        return "ImageHints[raDeg=%s, decDeg=%s, pixelScale=%s, epoch=%s, pixelSizeMicrons=%s, bayer=%s]"
                .formatted(raDeg(), decDeg(), pixelScale(), epoch(), pixelSizeMicrons(), bayer);
    }

    public static final class Builder {
        private double raDeg = Double.NaN;
        private double decDeg = Double.NaN;
        private double pixelScale = Double.NaN;
        private double epoch = Double.NaN;
        private double pixelSizeMicrons = Double.NaN;
        private boolean bayer;

        private Builder() {
        }

        /** The approximate position of the center of the image, in degrees. */
        public Builder position(double raDeg, double decDeg) {
            return raDeg(raDeg).decDeg(decDeg);
        }

        public Builder raDeg(double value) {
            this.raDeg = value;
            return this;
        }

        public Builder decDeg(double value) {
            this.decDeg = value;
            return this;
        }

        /** The approximate scale of the image, in arcseconds per pixel. */
        public Builder pixelScale(double value) {
            this.pixelScale = value;
            return this;
        }

        /** The date of the observation, as a Julian year. */
        public Builder epoch(double value) {
            this.epoch = value;
            return this;
        }

        /** The size of the pixels of the image, in micrometers, binning included. */
        public Builder pixelSizeMicrons(double value) {
            this.pixelSizeMicrons = value;
            return this;
        }

        /** Tells if the image is the raw frame of a color sensor. */
        public Builder bayer(boolean value) {
            this.bayer = value;
            return this;
        }

        public ImageHints build() {
            return new ImageHints(this);
        }
    }
}
