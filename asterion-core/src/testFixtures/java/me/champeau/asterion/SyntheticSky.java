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
package me.champeau.asterion;

import me.champeau.asterion.catalog.StarData;
import me.champeau.asterion.image.GrayImage;
import me.champeau.asterion.math.Sphere;
import me.champeau.asterion.wcs.Wcs;

import java.util.Random;

/** A random sky, and synthetic images of it. */
public final class SyntheticSky {
    public static final double EPOCH = 2000.0;
    private final StarData stars;

    /**
     * Creates stars which are spread uniformly over the sphere, with a realistic distribution of
     * magnitudes: there are about 3 times more stars at each magnitude.
     */
    public SyntheticSky(int count, long seed) {
        var random = new Random(seed);
        stars = new StarData(EPOCH, count);
        for (var i = 0; i < count; i++) {
            var ra = random.nextDouble() * Sphere.TWO_PI;
            var dec = Math.asin(2 * random.nextDouble() - 1);
            var mag = (float) (12 + Math.log10(Math.max(1e-9, random.nextDouble())) / 0.5);
            stars.add(ra, dec, 0, 0, mag);
        }
    }

    public StarData stars() {
        return stars;
    }

    /**
     * Creates the WCS of an image.
     *
     * @param scale the scale, in arcseconds per pixel
     * @param rotationDeg the position angle of the Y axis
     * @param flipped true to mirror the image
     */
    public static Wcs wcs(int width, int height, double raDeg, double decDeg, double scale, double rotationDeg, boolean flipped) {
        var s = scale * Sphere.ARCSEC;
        var cos = Math.cos(Math.toRadians(rotationDeg));
        var sin = Math.sin(Math.toRadians(rotationDeg));
        var parity = flipped ? 1.0 : -1.0;
        var cd = new double[]{parity * s * cos, s * sin, -parity * s * sin, s * cos};
        return new Wcs((width - 1) / 2.0, (height - 1) / 2.0, Math.toRadians(raDeg), Math.toRadians(decDeg), cd);
    }

    /** The pixel positions of the stars which are in an image, brightest first: (x, y, magnitude) triples. */
    public double[] project(Wcs wcs, int width, int height) {
        var out = new double[3 * 4096];
        var n = 0;
        var vec = new double[3];
        var pixel = new double[2];
        for (var i = 0; i < stars.size(); i++) {
            Sphere.toVector(stars.ra(i), stars.dec(i), vec);
            if (wcs.vectorToPixel(vec[0], vec[1], vec[2], pixel)
                    && pixel[0] >= 0 && pixel[1] >= 0 && pixel[0] <= width - 1 && pixel[1] <= height - 1) {
                if (3 * n == out.length) {
                    out = java.util.Arrays.copyOf(out, 2 * out.length);
                }
                out[3 * n] = pixel[0];
                out[3 * n + 1] = pixel[1];
                out[3 * n + 2] = stars.mag(i);
                n++;
            }
        }
        // sort by magnitude
        var order = new Integer[n];
        for (var i = 0; i < n; i++) {
            order[i] = i;
        }
        var unsorted = out;
        java.util.Arrays.sort(order, java.util.Comparator.comparingDouble(i -> unsorted[3 * i + 2]));
        var sorted = new double[3 * n];
        for (var i = 0; i < n; i++) {
            System.arraycopy(unsorted, 3 * order[i], sorted, 3 * i, 3);
        }
        return sorted;
    }

    /** Renders an image: gaussian stars over a noisy background with a gradient. */
    public GrayImage render(Wcs wcs, int width, int height, long seed) {
        var data = new float[width * height];
        var random = new Random(seed);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                data[y * width + x] = (float) (1000 + 0.05 * x + 0.02 * y + 8 * random.nextGaussian());
            }
        }
        var projected = project(wcs, width, height);
        var sigma = 1.4;
        for (var i = 0; i < projected.length / 3; i++) {
            var cx = projected[3 * i];
            var cy = projected[3 * i + 1];
            var peak = Math.min(60000, 300 * Math.pow(10, -0.4 * (projected[3 * i + 2] - 11)));
            var r = 7;
            for (var y = Math.max(0, (int) cy - r); y <= Math.min(height - 1, (int) cy + r); y++) {
                for (var x = Math.max(0, (int) cx - r); x <= Math.min(width - 1, (int) cx + r); x++) {
                    var d2 = (x - cx) * (x - cx) + (y - cy) * (y - cy);
                    data[y * width + x] += (float) (peak * Math.exp(-d2 / (2 * sigma * sigma)));
                }
            }
        }
        return new GrayImage(width, height, data);
    }

    /** The angle between the sky positions of a pixel according to two WCS, in arcseconds. */
    public static double separation(Wcs a, Wcs b, double x, double y) {
        var p = a.pixelToSky(x, y);
        var q = b.pixelToSky(x, y);
        return Sphere.separation(Math.toRadians(p[0]), Math.toRadians(p[1]), Math.toRadians(q[0]), Math.toRadians(q[1])) / Sphere.ARCSEC;
    }
}
