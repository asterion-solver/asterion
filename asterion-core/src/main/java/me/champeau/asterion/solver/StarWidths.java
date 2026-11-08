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

import me.champeau.asterion.image.GaussianFit;
import me.champeau.asterion.image.GrayImage;
import me.champeau.asterion.image.StarList;

import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * The width of the stars of an image. Many detections of images with little signal aren't stars but noise and hot
 * pixels, narrower than stars, noise narrows the faint stars, and saturation widens the brightest ones: once the image
 * is solved, the width is measured on the stars which were matched with the catalog, bright enough to be measured
 * reliably, and not saturated. A gaussian is fitted to them, as image processing software does.
 */
final class StarWidths {
    /** The signal-to-noise ratio of the peak of the stars whose width is measured reliably. */
    private static final float MIN_SNR = 20;
    /** The fewest stars whose median width is meaningful. */
    private static final int MIN_STARS = 5;
    /** The most stars fitted with a gaussian: enough for a stable median, few enough for a negligible cost. */
    private static final int MAX_FITTED = 50;

    private StarWidths() {
    }

    /**
     * The median full width at half maximum of the stars, in pixels, or 0 without stars: the one of the stars which
     * were matched with the catalog and can be measured, fitted with a gaussian if the pixels of the image are given,
     * or the one which the detector measured; with too few of them, or without a solution, the one of the detections.
     *
     * @param matched the indices of the stars which were matched with the catalog, empty without a solution
     * @param image the image in which the stars were detected, or null if unknown
     */
    static double fwhm(StarList stars, int[] matched, GrayImage image) {
        var measurable = Arrays.stream(matched)
                .filter(i -> !stars.saturated()[i] && stars.snr()[i] >= MIN_SNR)
                .sorted()
                .toArray();
        if (image != null) {
            // the brightest stars of an image are often wider than the others: the fitted ones are taken from all
            var count = Math.min(measurable.length, MAX_FITTED);
            var fitted = IntStream.range(0, count)
                    .map(k -> measurable[(int) ((long) k * measurable.length / count)])
                    .parallel()
                    .mapToDouble(i -> GaussianFit.fwhm(image, stars.x(i), stars.y(i), stars.fwhm()[i]))
                    .filter(Double::isFinite)
                    .toArray();
            if (fitted.length >= MIN_STARS) {
                return median(fitted);
            }
        }
        if (measurable.length >= MIN_STARS) {
            return median(IntStream.of(measurable).mapToDouble(i -> stars.fwhm()[i]).toArray());
        }
        return stars.medianFwhm();
    }

    private static double median(double[] values) {
        Arrays.sort(values);
        return values[values.length / 2];
    }
}
