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

import me.champeau.asterion.math.LinearSolver;

import java.util.Arrays;

/**
 * Fits a two dimensional gaussian over a constant background to a star, as the PSF tools of image processing software
 * do. Its width is measured from the pixels around the star, which noise and sampling bias much less than the area
 * above half its peak, which {@link StarDetector} measures.
 */
public final class GaussianFit {
    private static final double FWHM_PER_SIGMA = 2 * Math.sqrt(2 * Math.log(2));
    /** The parameters of the model: amplitude, center, inverse covariance (a, b, c), background. */
    private static final int PARAMETERS = 7;
    private static final int MAX_ITERATIONS = 50;
    /** The fit stops once an iteration reduces the squared residuals by less than this fraction. */
    private static final double CONVERGENCE = 1e-7;
    private static final double MAX_DAMPING = 1e10;
    /** The fitted window extends to this many times the estimated width around the star, within these bounds. */
    private static final double WINDOW_PER_FWHM = 1.5;
    private static final int MIN_RADIUS = 4;
    private static final int MAX_RADIUS = 32;
    /**
     * Wider windows are sampled, a pixel every few ones, so that they have at most this many samples from their center
     * to their border: the cost of a fit is bounded, and a star still has several samples across its width.
     */
    private static final int MAX_SAMPLED_RADIUS = 8;

    private GaussianFit() {
    }

    /**
     * The full width at half maximum of a star, in pixels of the source image, or NaN if no gaussian fits it: the
     * geometric mean of the widths along the axes of the fitted gaussian, which may be elongated.
     *
     * @param x the abscissa of the star, in pixels of the source image, as the stars of {@link StarList}
     * @param y the ordinate of the star
     * @param fwhm an estimate of the width of the star, in pixels of the source image, which sets the fitted window, or
     * 0 if unknown
     */
    public static double fwhm(GrayImage image, double x, double y, double fwhm) {
        var binning = image.binning();
        var cx = (x + 0.5) / binning - 0.5;
        var cy = (y + 0.5) / binning - 0.5;
        var estimate = fwhm > 0 ? fwhm / binning : 0;
        var sigma = Math.max(0.5, estimate / FWHM_PER_SIGMA);
        var radius = (int) Math.clamp(Math.ceil(WINDOW_PER_FWHM * estimate), MIN_RADIUS, MAX_RADIUS);
        var step = Math.ceilDiv(radius, MAX_SAMPLED_RADIUS);
        var samples = Math.ceilDiv(radius, step);
        radius = samples * step;
        var x0 = (int) Math.round(cx) - radius;
        var y0 = (int) Math.round(cy) - radius;
        if (x0 < 0 || y0 < 0 || x0 + 2 * radius >= image.width() || y0 + 2 * radius >= image.height()) {
            return Double.NaN;
        }
        // the gaussian is fitted in samples, then measured in pixels
        var window = new Window(image, x0, y0, 2 * samples + 1, step);
        var p = window.initial((cx - x0) / step, (cy - y0) / step, sigma / step);
        var fitted = window.fit(p);
        if (fitted == null || !Window.valid(fitted)) {
            return Double.NaN;
        }
        // the inverse covariance is [[a, b], [b, c]]: the product of the sigmas along the axes is 1 / sqrt(ac - b²)
        var s = Math.pow(fitted[3] * fitted[5] - fitted[4] * fitted[4], -0.25) * step;
        var moved = Math.hypot(fitted[1] * step - (cx - x0), fitted[2] * step - (cy - y0));
        if (s < 0.25 || s > radius || moved > Math.max(2, s)) {
            return Double.NaN;
        }
        return FWHM_PER_SIGMA * s * binning;
    }

    /** The samples of the pixels around a star, and the gaussian fitted to them by Levenberg-Marquardt. */
    private static final class Window {
        private final int size;
        private final float[] values;
        private final double[] jacobian = new double[PARAMETERS];

        /** A window of size × size samples, one every step pixels from (x0, y0). */
        Window(GrayImage image, int x0, int y0, int size, int step) {
            this.size = size;
            this.values = new float[size * size];
            var data = image.data();
            for (var y = 0; y < size; y++) {
                var row = (y0 + y * step) * image.width() + x0;
                for (var x = 0; x < size; x++) {
                    values[y * size + x] = data[row + x * step];
                }
            }
        }

        /** A round gaussian at the position of the star, over the median of the border of the window. */
        double[] initial(double cx, double cy, double sigma) {
            var border = new float[4 * (size - 1)];
            var k = 0;
            for (var i = 0; i < size - 1; i++) {
                border[k++] = values[i];
                border[k++] = values[i * size + size - 1];
                border[k++] = values[(size - 1) * size + i + 1];
                border[k++] = values[(i + 1) * size];
            }
            Arrays.sort(border);
            var background = border[border.length / 2];
            var peak = Float.NEGATIVE_INFINITY;
            for (var v : values) {
                peak = Math.max(peak, v);
            }
            var inverse = 1 / (sigma * sigma);
            return new double[]{peak - background, cx, cy, inverse, 0, inverse, background};
        }

        /** The fitted parameters, or null if the fit failed. */
        double[] fit(double[] p) {
            var normal = new double[PARAMETERS * PARAMETERS];
            var gradient = new double[PARAMETERS];
            var nextNormal = new double[PARAMETERS * PARAMETERS];
            var nextGradient = new double[PARAMETERS];
            var chi2 = evaluate(p, normal, gradient);
            var damping = 1e-3;
            for (var iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
                var a = normal.clone();
                var step = gradient.clone();
                for (var i = 0; i < PARAMETERS; i++) {
                    a[i * PARAMETERS + i] *= 1 + damping;
                }
                if (!LinearSolver.solve(a, step, PARAMETERS)) {
                    return null;
                }
                var q = p.clone();
                for (var i = 0; i < PARAMETERS; i++) {
                    q[i] += step[i];
                }
                // most steps are taken: the normal equations of the next one are computed with the residuals
                var next = valid(q) ? evaluate(q, nextNormal, nextGradient) : Double.POSITIVE_INFINITY;
                if (next < chi2) {
                    var converged = chi2 - next < CONVERGENCE * chi2;
                    p = q;
                    chi2 = next;
                    var swap = normal;
                    normal = nextNormal;
                    nextNormal = swap;
                    var swapGradient = gradient;
                    gradient = nextGradient;
                    nextGradient = swapGradient;
                    damping /= 10;
                    if (converged) {
                        return p;
                    }
                } else {
                    damping *= 10;
                    if (damping > MAX_DAMPING) {
                        return p;
                    }
                }
            }
            return p;
        }

        /** Whether the parameters describe a star: a positive amplitude and a positive definite covariance. */
        private static boolean valid(double[] p) {
            return p[0] > 0 && p[3] > 0 && p[5] > 0 && p[3] * p[5] > p[4] * p[4];
        }

        /**
         * The sum of the squared residuals of a model, and the normal equations of the next step: JᵀJ and Jᵀr, where J
         * is the jacobian of the model and r the residuals.
         */
        private double evaluate(double[] p, double[] normal, double[] gradient) {
            Arrays.fill(normal, 0);
            Arrays.fill(gradient, 0);
            var amplitude = p[0];
            var chi2 = 0.0;
            for (var y = 0; y < size; y++) {
                var dy = y - p[2];
                for (var x = 0; x < size; x++) {
                    var dx = x - p[1];
                    var e = Math.exp(-0.5 * (p[3] * dx * dx + 2 * p[4] * dx * dy + p[5] * dy * dy));
                    var r = values[y * size + x] - (amplitude * e + p[6]);
                    chi2 += r * r;
                    var ae = amplitude * e;
                    jacobian[0] = e;
                    jacobian[1] = ae * (p[3] * dx + p[4] * dy);
                    jacobian[2] = ae * (p[4] * dx + p[5] * dy);
                    jacobian[3] = -0.5 * ae * dx * dx;
                    jacobian[4] = -ae * dx * dy;
                    jacobian[5] = -0.5 * ae * dy * dy;
                    jacobian[6] = 1;
                    for (var i = 0; i < PARAMETERS; i++) {
                        var ji = jacobian[i];
                        gradient[i] += ji * r;
                        for (var j = i; j < PARAMETERS; j++) {
                            normal[i * PARAMETERS + j] += ji * jacobian[j];
                        }
                    }
                }
            }
            for (var i = 0; i < PARAMETERS; i++) {
                for (var j = 0; j < i; j++) {
                    normal[i * PARAMETERS + j] = normal[j * PARAMETERS + i];
                }
            }
            return chi2;
        }
    }
}
