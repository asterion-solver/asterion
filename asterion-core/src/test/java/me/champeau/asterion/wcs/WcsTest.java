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
package me.champeau.asterion.wcs;

import me.champeau.asterion.SyntheticSky;
import me.champeau.asterion.math.Sphere;
import org.junit.jupiter.api.Test;

import java.util.Objects;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WcsTest {
    @Test
    void roundTrip() {
        var wcs = SyntheticSky.wcs(4000, 3000, 350.2, 88.5, 2.5, 37, false);
        var sky = wcs.pixelToSky(123.4, 2890.1);
        var pixel = Objects.requireNonNull(wcs.skyToPixel(sky[0], sky[1]));
        assertEquals(123.4, pixel[0], 1e-6);
        assertEquals(2890.1, pixel[1], 1e-6);
    }

    @Test
    void describesGeometry() {
        var wcs = SyntheticSky.wcs(4000, 3000, 120, -30, 1.8, 25, false);
        assertEquals(1.8, wcs.pixelScale(), 1e-9);
        assertEquals(25, wcs.rotation(), 1e-9);
        assertFalse(wcs.flipped());
        assertTrue(SyntheticSky.wcs(4000, 3000, 120, -30, 1.8, 25, true).flipped());
        // north is up and east is left in an image which isn't rotated
        var upright = SyntheticSky.wcs(4000, 3000, 120, 0, 1.8, 0, false);
        assertTrue(upright.pixelToSky(2000, 2500)[1] > upright.pixelToSky(2000, 500)[1]);
        assertTrue(upright.pixelToSky(500, 1500)[0] > upright.pixelToSky(3500, 1500)[0]);
        assertEquals(2000.5, (double) wcs.toFitsKeywords().get("CRPIX1"), 1e-9);
    }

    @Test
    void fitsLinearSolution() {
        var truth = SyntheticSky.wcs(3000, 2000, 10, 89.2, 3.1, -110, true);
        var stars = stars(truth, 60, 3000, 2000, 0, 3);
        var center = new double[3];
        // start from a direction which is 0.5 degree off
        Sphere.toVector(Math.toRadians(40), Math.toRadians(88.9), center);
        var fitted = WcsFitter.fitLinear(stars.x, stars.y, stars.vec, stars.n, 1499.5, 999.5, center);
        assertNotNull(fitted);
        assertTrue(SyntheticSky.separation(truth, fitted, 0, 0) < 1e-4);
        assertTrue(SyntheticSky.separation(truth, fitted, 2999, 1999) < 1e-4);
    }

    @Test
    void fitsDistortion() {
        var width = 3000;
        var height = 2000;
        var linear = SyntheticSky.wcs(width, height, 200, 45, 4, 12, false);
        // the same projection, with a radial distortion of 6 pixels in the corners
        var order = 3;
        var a = new double[16];
        var b = new double[16];
        var k = 6 / Math.pow(1800, 3);
        a[3 * 4] = k;
        a[4 + 2] = k;
        b[2 * 4 + 1] = k;
        b[3] = k;
        var truth = new Wcs(linear.crpixX(), linear.crpixY(), linear.plane(), linear.cd(), order, a, b, 0, null, null);
        var stars = stars(truth, 300, width, height, 0.05, 4);
        var sip = WcsFitter.fitSip(stars.x, stars.y, stars.vec, stars.n, linear, 3, width, height);
        assertNotNull(sip);
        var fitted = WcsFitter.withInverse(sip, width, height);
        for (var p : new double[][]{{0, 0}, {2999, 0}, {1500, 1000}, {2999, 1999}, {700, 1800}}) {
            assertTrue(SyntheticSky.separation(truth, fitted, p[0], p[1]) < 0.3, "distortion not fitted at " + p[0] + ", " + p[1]);
            var sky = fitted.pixelToSky(p[0], p[1]);
            var back = Objects.requireNonNull(fitted.skyToPixel(sky[0], sky[1]));
            assertEquals(p[0], back[0], 1e-3);
            assertEquals(p[1], back[1], 1e-3);
        }
        assertEquals("RA---TAN-SIP", fitted.toFitsKeywords().get("CTYPE1"));
    }

    private record Stars(
            double[] x,
            double[] y,
            double[] vec,
            int n) {
    }

    private static Stars stars(Wcs wcs, int n, int width, int height, double noise, long seed) {
        var random = new Random(seed);
        var x = new double[n];
        var y = new double[n];
        var vec = new double[3 * n];
        var v = new double[3];
        for (var i = 0; i < n; i++) {
            x[i] = random.nextDouble() * width;
            y[i] = random.nextDouble() * height;
            wcs.pixelToVector(x[i], y[i], v);
            System.arraycopy(v, 0, vec, 3 * i, 3);
            x[i] += noise * random.nextGaussian();
            y[i] += noise * random.nextGaussian();
        }
        return new Stars(x, y, vec, n);
    }
}
