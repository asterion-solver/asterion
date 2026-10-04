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

import me.champeau.asterion.SyntheticSky;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StarDetectorTest {
    @Test
    void findsStarsWithSubPixelAccuracy() {
        var sky = new SyntheticSky(150_000, 11);
        var width = 1600;
        var height = 1200;
        var wcs = SyntheticSky.wcs(width, height, 83, -5, 20, 30, false);
        var truth = sky.project(wcs, width, height);
        var stars = StarDetector.detect(sky.render(wcs, width, height, 5));
        assertTrue(stars.size() > 20, "only " + stars.size() + " stars detected");
        // the brightest stars must be detected where they were drawn
        var sum = 0.0;
        var checked = 0;
        for (var i = 0; i < 30; i++) {
            var tx = truth[3 * i];
            var ty = truth[3 * i + 1];
            var isolated = tx > 10 && ty > 10 && tx < width - 10 && ty < height - 10;
            for (var k = 0; k < truth.length / 3 && isolated; k++) {
                isolated = k == i || Math.hypot(truth[3 * k] - tx, truth[3 * k + 1] - ty) > 12;
            }
            if (!isolated) {
                // blended stars and stars on the border can't be measured accurately
                continue;
            }
            var best = Double.MAX_VALUE;
            for (var j = 0; j < stars.size(); j++) {
                best = Math.min(best, Math.hypot(stars.x(j) - tx, stars.y(j) - ty));
            }
            assertTrue(best < 0.25, "star " + i + " found at " + best + " pixels of its position");
            sum += best * best;
            checked++;
        }
        assertTrue(checked > 20);
        assertTrue(Math.sqrt(sum / checked) < 0.1, "RMS error of " + Math.sqrt(sum / checked) + " pixels");
        // stars are sorted by brightness
        for (var i = 1; i < stars.size(); i++) {
            assertTrue(stars.flux(i) <= stars.flux(i - 1));
        }
    }

    @Test
    void ignoresHotPixelsAndEmptyImages() {
        var width = 400;
        var height = 300;
        var data = new float[width * height];
        var random = new java.util.Random(3);
        for (var i = 0; i < data.length; i++) {
            data[i] = (float) (500 + 5 * random.nextGaussian());
        }
        data[150 * width + 200] = 60000;
        assertEquals(0, StarDetector.detect(new GrayImage(width, height, data)).size());
    }

    @Test
    void reportsPositionsInSourcePixelsWhenBinned() {
        var width = 200;
        var height = 200;
        var data = new float[width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var d2 = (x - 80.3) * (x - 80.3) + (y - 120.6) * (y - 120.6);
                data[y * width + x] = (float) (100 + 5000 * Math.exp(-d2 / 8));
            }
        }
        // the image is the 2x2 binning of a 400x400 source
        var stars = StarDetector.detect(new GrayImage(width, height, data, 2, 400, 400));
        assertEquals(1, stars.size());
        assertEquals((80.3 + 0.5) * 2 - 0.5, stars.x(0), 0.1);
        assertEquals((120.6 + 0.5) * 2 - 0.5, stars.y(0), 0.1);
    }
}
