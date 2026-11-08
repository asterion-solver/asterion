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

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GaussianFitTest {
    private static final double FWHM_PER_SIGMA = 2 * Math.sqrt(2 * Math.log(2));
    private static final int WIDTH = 120;
    private static final int HEIGHT = 100;

    @Test
    void measuresRoundStars() {
        var image = star(60.3, 50.7, 1.4, 1.4, 0, 2000, 1);
        assertEquals(1.4 * FWHM_PER_SIGMA, GaussianFit.fwhm(image, 60.3, 50.7, 3), 0.02 * 1.4 * FWHM_PER_SIGMA);
    }

    @Test
    void measuresStarsWhateverTheEstimateOfTheirWidth() {
        var image = star(60.3, 50.7, 2.5, 2.5, 0, 2000, 1);
        var fwhm = 2.5 * FWHM_PER_SIGMA;
        // an unknown width gives the smallest window, which covers the core of the star still
        for (var estimate : new double[]{0, Double.NaN, 2, 4, 8}) {
            assertEquals(fwhm, GaussianFit.fwhm(image, 60.6, 50.2, estimate), 0.02 * fwhm, "estimated " + estimate);
        }
    }

    @Test
    void measuresElongatedStarsAsTheMeanOfTheirAxes() {
        var image = star(60.3, 50.7, 1.2, 2, Math.toRadians(30), 2000, 1);
        var fwhm = Math.sqrt(1.2 * 2) * FWHM_PER_SIGMA;
        assertEquals(fwhm, GaussianFit.fwhm(image, 60.3, 50.7, 3), 0.02 * fwhm);
    }

    @Test
    void measuresUndersampledStars() {
        var image = star(60.3, 50.7, 0.7, 0.7, 0, 2000, 1);
        assertEquals(0.7 * FWHM_PER_SIGMA, GaussianFit.fwhm(image, 60.3, 50.7, 2), 0.03 * 0.7 * FWHM_PER_SIGMA);
    }

    @Test
    void measuresInPixelsOfTheSourceImage() {
        // the image is the 2x2 binning of the source: the star is at (60.3, 50.7) in binned pixels
        var image = star(60.3, 50.7, 1.4, 1.4, 0, 2000, 2);
        var fwhm = 2 * 1.4 * FWHM_PER_SIGMA;
        assertEquals(fwhm, GaussianFit.fwhm(image, (60.3 + 0.5) * 2 - 0.5, (50.7 + 0.5) * 2 - 0.5, 6), 0.02 * fwhm);
    }

    @Test
    void measuresNothingAtTheBorderOrWithoutAStar() {
        var image = star(2.5, 50.7, 1.4, 1.4, 0, 2000, 1);
        assertTrue(Double.isNaN(GaussianFit.fwhm(image, 2.5, 50.7, 3)));
        var flat = new GrayImage(WIDTH, HEIGHT, new float[WIDTH * HEIGHT]);
        assertTrue(Double.isNaN(GaussianFit.fwhm(flat, 60, 50, 3)));
    }

    /** An image with a gaussian star, whose axes are rotated by an angle, over a noisy background. */
    private static GrayImage star(double cx, double cy, double sigmaX, double sigmaY, double angle, double peak, int binning) {
        var data = new float[WIDTH * HEIGHT];
        var random = new Random(31);
        var cos = Math.cos(angle);
        var sin = Math.sin(angle);
        for (var y = 0; y < HEIGHT; y++) {
            for (var x = 0; x < WIDTH; x++) {
                var u = (x - cx) * cos + (y - cy) * sin;
                var v = -(x - cx) * sin + (y - cy) * cos;
                var g = Math.exp(-0.5 * (u * u / (sigmaX * sigmaX) + v * v / (sigmaY * sigmaY)));
                data[y * WIDTH + x] = (float) (500 + peak * g + 5 * random.nextGaussian());
            }
        }
        return new GrayImage(WIDTH, HEIGHT, data, binning, binning * WIDTH, binning * HEIGHT);
    }
}
