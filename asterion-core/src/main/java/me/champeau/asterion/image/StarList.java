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

import java.util.Arrays;

/**
 * A list of stars detected in an image, sorted by decreasing brightness. Positions are expressed
 * in pixels of the source image, the center of the first pixel being at (0, 0).
 *
 * @param x the abscissas of the stars
 * @param y the ordinates of the stars
 * @param flux the brightness of the stars, in arbitrary units
 * @param fwhm the full width at half maximum of the stars, in pixels, or 0 if unknown
 */
@SuppressWarnings("ArrayRecordComponent") // star lists are large, and never modified once created
public record StarList(
        double[] x,
        double[] y,
        float[] flux,
        float[] fwhm) {
    private static final StarList EMPTY = new StarList(new double[0], new double[0], new float[0], new float[0]);

    public StarList {
        if (y.length != x.length || flux.length != x.length || fwhm.length != x.length) {
            throw new IllegalArgumentException("All the arrays must have the same length");
        }
    }

    public static StarList empty() {
        return EMPTY;
    }

    /**
     * Creates a list from positions which must already be sorted by decreasing brightness.
     */
    public static StarList of(double[] x, double[] y) {
        var flux = new float[x.length];
        for (var i = 0; i < flux.length; i++) {
            flux[i] = flux.length - i;
        }
        return new StarList(x.clone(), y.clone(), flux, new float[x.length]);
    }

    public int size() {
        return x.length;
    }

    public double x(int i) {
        return x[i];
    }

    public double y(int i) {
        return y[i];
    }

    public float flux(int i) {
        return flux[i];
    }

    /** The median full width at half maximum of the stars, in pixels, or 0 if unknown. */
    public float medianFwhm() {
        if (fwhm.length == 0) {
            return 0;
        }
        var sorted = fwhm.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
