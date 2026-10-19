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

import me.champeau.asterion.image.StarList;
import me.champeau.asterion.math.TangentPlane;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WideFieldTest {
    private static final int WIDTH = 6000;
    private static final int HEIGHT = 4000;

    /** A camera pointed at a direction, rolled by an angle: the pixel of a direction of the sky, or null if it's behind. */
    private record Camera(
            TangentPlane axis,
            double roll,
            double focal,
            boolean mirrored) {
        double[] pixel(double[] v) {
            if (axis.dot(v[0], v[1], v[2]) <= 0) {
                return null;
            }
            var xi = axis.xi(v[0], v[1], v[2]);
            var eta = axis.eta(v[0], v[1], v[2]);
            var cos = Math.cos(roll);
            var sin = Math.sin(roll);
            var u = cos * xi - sin * eta;
            var w = sin * xi + cos * eta;
            return new double[]{(WIDTH - 1) / 2.0 + focal * u, (HEIGHT - 1) / 2.0 + (mirrored ? -1 : 1) * focal * w};
        }
    }

    private static double[] direction(TangentPlane axis, double xiDeg, double etaDeg) {
        var out = new double[3];
        axis.unproject(Math.tan(Math.toRadians(xiDeg)), Math.tan(Math.toRadians(etaDeg)), out);
        return out;
    }

    @ParameterizedTest
    @CsvSource({
            // focal length in pixels, roll, mirrored, the error on the focal length which is tried
            "3600, 0, false, 1.0",
            "3600, 1.3, true, 1.15",
            "2500, -2.5, false, 0.9",
            "5000, 0.4, true, 1.2",
    })
    void findsTheCameraOfAQuadFarFromTheCenter(double focal, double roll, boolean mirrored, double focalError) {
        var axis = TangentPlane.at(Math.toRadians(120), Math.toRadians(35));
        var camera = new Camera(axis, roll, focal, mirrored);
        // a quad of 12° in the upper left of the image, 35° from the axis, and a few more stars
        var offsets = new double[][]{{-20, 22}, {-31, 18}, {-24, 28}, {-28, 12}, {15, -10}, {30, 20}, {-5, 25}};
        var sky = new double[12];
        var x = new double[offsets.length];
        var y = new double[offsets.length];
        for (var k = 0; k < offsets.length; k++) {
            var v = direction(axis, offsets[k][0], offsets[k][1]);
            var p = camera.pixel(v);
            x[k] = p[0];
            y[k] = p[1];
            if (k < 4) {
                System.arraycopy(v, 0, sky, 3 * k, 3);
            }
        }
        var h = WideField.toPixels(sky, x, y, new int[]{0, 1, 2, 3}, focal * focalError, WIDTH, HEIGHT);
        assertNotNull(h);
        assertEquals(1 / focal, h.scale, 1e-6 / focal);
        // the camera puts every star where it is, not only the ones of the quad
        var pixel = new double[2];
        for (var k = 0; k < offsets.length; k++) {
            var v = direction(axis, offsets[k][0], offsets[k][1]);
            assertTrue(h.toPixel(v[0], v[1], v[2], pixel));
            assertEquals(x[k], pixel[0], 1e-3, "star " + k);
            assertEquals(y[k], pixel[1], 1e-3, "star " + k);
        }
        var center = new double[3];
        h.toVector((WIDTH - 1) / 2.0, (HEIGHT - 1) / 2.0, center);
        assertEquals(1, axis.dot(center[0], center[1], center[2]), 1e-9);
    }

    @ParameterizedTest
    @CsvSource({"0.7", "1.4"})
    void rejectsAQuadWhoseFocalLengthIsFarFromTheOneTried(double focalError) {
        var axis = TangentPlane.at(Math.toRadians(10), Math.toRadians(-20));
        var camera = new Camera(axis, 0.2, 3000, false);
        var offsets = new double[][]{{-20, 22}, {-31, 18}, {-24, 28}, {-28, 12}};
        var sky = new double[12];
        var x = new double[4];
        var y = new double[4];
        for (var k = 0; k < 4; k++) {
            var v = direction(axis, offsets[k][0], offsets[k][1]);
            var p = camera.pixel(v);
            x[k] = p[0];
            y[k] = p[1];
            System.arraycopy(v, 0, sky, 3 * k, 3);
        }
        assertNull(WideField.toPixels(sky, x, y, new int[]{0, 1, 2, 3}, 3000 * focalError, WIDTH, HEIGHT));
    }

    @ParameterizedTest
    @CsvSource({"6000, 4000", "2000, 1333", "1333, 2000"})
    void triesFocalLengthsFromNarrowToWideFields(int width, int height) {
        var focals = WideField.focalLengths(width, height);
        var halfDiagonal = Math.hypot(width, height) / 2;
        assertTrue(focals.size() >= 10 && focals.size() <= 14, "" + focals.size());
        var first = Math.toDegrees(Math.atan(halfDiagonal / focals.getFirst()));
        var last = Math.toDegrees(Math.atan(halfDiagonal / focals.getLast()));
        assertEquals(15, first, 0.01);
        assertTrue(last > 55 && last <= 65, "" + last);
        for (var i = 1; i < focals.size(); i++) {
            assertEquals(1.2, focals.get(i - 1) / focals.get(i), 1e-9);
        }
    }

    @ParameterizedTest
    @CsvSource({"2000, 1333, 1010", "6000, 4000, 3600"})
    void theStereographicProjectionKeepsTheCenterAndShrinksTheCorners(int width, int height, double focal) {
        var stars = StarList.of(new double[]{(width - 1) / 2.0, 0, width - 1}, new double[]{(height - 1) / 2.0, 0, height - 1});
        var projected = WideField.stereographic(stars, focal, width, height);
        assertEquals(stars.x(0), projected.x(0), 1e-9);
        assertEquals(stars.y(0), projected.y(0), 1e-9);
        // a corner at angle θ from the axis moves from f tan θ to 2 f tan(θ / 2) from the center
        for (var i = 1; i < 3; i++) {
            var corner = Math.hypot(stars.x(i) - stars.x(0), stars.y(i) - stars.y(0));
            var theta = Math.atan(corner / focal);
            var r = Math.hypot(projected.x(i) - stars.x(0), projected.y(i) - stars.y(0));
            assertEquals(2 * focal * Math.tan(theta / 2), r, 1e-6);
            assertTrue(r < corner);
        }
    }
}
