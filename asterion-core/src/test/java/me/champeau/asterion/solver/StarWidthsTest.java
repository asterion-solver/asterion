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

import me.champeau.asterion.image.GrayImage;
import me.champeau.asterion.image.StarList;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StarWidthsTest {
    /** The width of a gaussian whose sigma is 1.4 pixels. */
    private static final double FWHM = 1.4 * 2 * Math.sqrt(2 * Math.log(2));

    @Test
    void measuresTheMatchedStarsWhichAreBrightAndNotSaturated() {
        // the stars 0 to 6 are matched: 3 is saturated and 4 is faint, the others are a fraction of a pixel narrower
        // than 3, and the unmatched 7 to 15 much narrower, like the noise and hot pixels of images with little signal
        var fwhm = new float[]{3.1f, 3.2f, 3.3f, 6f, 1.5f, 3.4f, 3.5f, 1.2f, 1.2f, 1.2f, 1.2f, 1.2f, 1.2f, 1.2f, 1.2f, 1.2f};
        var snr = filled(fwhm.length, 100);
        snr[4] = 10;
        var saturated = new boolean[fwhm.length];
        saturated[3] = true;
        var stars = stars(new double[fwhm.length], new double[fwhm.length], fwhm, snr, saturated);
        assertEquals(3.3, StarWidths.fwhm(stars, new int[]{0, 1, 2, 3, 4, 5, 6}, null), 1e-6);
        assertEquals(1.2, stars.medianFwhm(), 1e-6);
    }

    @Test
    void measuresTheDetectedStarsWithoutEnoughMatchedOnes() {
        var fwhm = new float[]{3, 3, 3, 3, 3, 2, 2, 2, 2, 2, 2};
        var n = fwhm.length;
        var stars = stars(new double[n], new double[n], fwhm, filled(n, 100), new boolean[n]);
        // 5 measurable stars are enough, 4 aren't
        assertEquals(3, StarWidths.fwhm(stars, new int[]{0, 1, 2, 3, 4}, null), 1e-6);
        assertEquals(2, StarWidths.fwhm(stars, new int[]{0, 1, 2, 3}, null), 1e-6);
        // without a solution, or when the signal-to-noise ratio of the stars is unknown
        assertEquals(2, StarWidths.fwhm(stars, new int[0], null), 1e-6);
        var unknown = new StarList(stars.x(), stars.y(), stars.flux(), fwhm, stars.concentration());
        assertEquals(2, StarWidths.fwhm(unknown, new int[]{0, 1, 2, 3, 4}, null), 1e-6);
    }

    @Test
    void measuresNothingWithoutStars() {
        assertEquals(0, StarWidths.fwhm(StarList.of(new double[0], new double[0]), new int[0], null));
    }

    @Test
    void fitsTheMatchedStarsInTheImage() {
        var width = 400;
        var height = 300;
        var data = new float[width * height];
        var random = new Random(23);
        for (var i = 0; i < data.length; i++) {
            data[i] = (float) (1000 + 8 * random.nextGaussian());
        }
        var n = 12;
        var x = new double[n];
        var y = new double[n];
        for (var i = 0; i < n; i++) {
            x[i] = 30 + (i % 4) * 100 + 0.37 * i;
            y[i] = 40 + (i / 4) * 100 + 0.21 * i;
            for (var py = (int) y[i] - 10; py <= (int) y[i] + 10; py++) {
                for (var px = (int) x[i] - 10; px <= (int) x[i] + 10; px++) {
                    var d2 = (px - x[i]) * (px - x[i]) + (py - y[i]) * (py - y[i]);
                    data[py * width + px] += (float) (400 * Math.exp(-d2 / (2 * 1.4 * 1.4)));
                }
            }
        }
        // the widths which the detector measured are too narrow, as noise makes them
        var stars = stars(x, y, filled(n, 2.5f), filled(n, 50), new boolean[n]);
        var matched = new int[]{0, 2, 3, 5, 6, 8, 9, 11};
        assertEquals(FWHM, StarWidths.fwhm(stars, matched, new GrayImage(width, height, data)), 0.03 * FWHM);
        // too few stars to measure: the widths of the detections are the best known
        assertEquals(2.5, StarWidths.fwhm(stars, new int[]{0, 2, 3, 5}, new GrayImage(width, height, data)), 1e-6);
    }

    private static float[] filled(int n, float value) {
        var values = new float[n];
        Arrays.fill(values, value);
        return values;
    }

    private static StarList stars(double[] x, double[] y, float[] fwhm, float[] snr, boolean[] saturated) {
        var n = fwhm.length;
        return new StarList(x, y, filled(n, 1000), fwhm, filled(n, 1), snr, saturated);
    }
}
