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

import java.util.ArrayList;
import java.util.List;

/**
 * The search of images taken with wide angle lenses. Such an image is a gnomonic projection of
 * the sky, centered on the optical axis: far from the center, shapes are stretched away from it,
 * by 1/cos θ at an angle θ from the axis, 7% at 20° and 40% at 45°. The quads of the index are
 * shapes on the sky, so the quads of the image don't match them, except near the center of the
 * image, where the stars which are visible in a wide field are too far apart to make quads.
 *
 * <p>Given the focal length, in pixels, the stars can be moved to where a stereographic
 * projection centered on the axis puts them: this projection is conformal, so the shapes of small
 * quads are the ones they have on the sky, and the search finds them. The focal length isn't
 * known, so several are tried, a fifth apart. A match of the search tells where the stars of its
 * quad are in the sky, which gives the exact focal length and how the camera was pointed: a
 * hypothesis in the pixels of the image, which is verified and refined like the others.
 */
final class WideField {
    /** The narrowest field searched, as the angle between the center of the image and its corners, in degrees. */
    private static final double MIN_HALF_DIAGONAL_DEG = 15;
    /**
     * The widest field searched: beyond, rectilinear lenses are rare, and a gnomonic projection
     * isn't a good model of fisheye lenses.
     */
    private static final double MAX_HALF_DIAGONAL_DEG = 65;
    /**
     * The ratio between the focal lengths which are tried: the quads of the center of the image
     * match with a focal length a tenth off, the camera gets the exact one from them, and most of
     * the quads of the corners match within a twentieth.
     */
    private static final double FOCAL_STEP = 1.2;

    private WideField() {
    }

    /** The focal lengths to try, in pixels, from the narrowest field to the widest one. */
    static List<Double> focalLengths(int width, int height) {
        var halfDiagonal = Math.hypot(width, height) / 2;
        var focals = new ArrayList<Double>();
        var shortest = halfDiagonal / Math.tan(Math.toRadians(MAX_HALF_DIAGONAL_DEG));
        for (var focal = halfDiagonal / Math.tan(Math.toRadians(MIN_HALF_DIAGONAL_DEG)); focal >= shortest; focal /= FOCAL_STEP) {
            focals.add(focal);
        }
        return focals;
    }

    /**
     * Moves the stars of an image to where the stereographic projection centered on the center
     * of the image puts them, given the focal length of the image: closer to the center, which
     * stays where it is, so that the stars stay in the image.
     */
    static StarList stereographic(StarList stars, double focal, int width, int height) {
        var cx = (width - 1) / 2.0;
        var cy = (height - 1) / 2.0;
        var n = stars.size();
        var x = new double[n];
        var y = new double[n];
        for (var i = 0; i < n; i++) {
            var dx = stars.x(i) - cx;
            var dy = stars.y(i) - cy;
            // tan θ = r / f, and the stereographic radius is 2f tan(θ/2)
            var factor = 2 * focal / (Math.sqrt(dx * dx + dy * dy + focal * focal) + focal);
            x[i] = cx + factor * dx;
            y[i] = cy + factor * dy;
        }
        return new StarList(x, y, stars.flux(), stars.fwhm(), stars.concentration());
    }

    /**
     * The hypothesis in the pixels of the image which corresponds to the match of a quad found
     * in the stereographic projection: the rotation of the camera which brings the directions of
     * the stars of the quad, as seen through a lens of this focal length, onto their directions in
     * the sky, from which the image is a gnomonic projection around its center. The hypothesis is
     * exact for a rectilinear lens, however wide the field: it is what the verification of wide
     * fields uses, as the similarity of the quad only holds near it. The focal length which was
     * tried is only accurate to a few percent, which the search tolerates but the camera doesn't:
     * the angles between the stars of the quad, which are known from the sky, give the focal
     * length to a fraction of a percent, and this is the one of the camera.
     *
     * @param sky the unit vectors of the 4 stars of the index quad
     * @param x the abscissas of the stars of the image, in pixels
     * @param y the ordinates of the stars of the image, in pixels
     * @param imageStars the stars of the image which match the stars of the quad, in the same order
     * @param focal the focal length which was tried, in pixels
     * @return the hypothesis in pixels, with the stars of the quad set, or null if they don't fit a camera
     */
    static Hypothesis toPixels(double[] sky, double[] x, double[] y, int[] imageStars, double focal, int width, int height) {
        var cx = (width - 1) / 2.0;
        var cy = (height - 1) / 2.0;
        focal = fitFocal(sky, x, y, imageStars, cx, cy, focal);
        if (Double.isNaN(focal)) {
            return null;
        }
        // the image may be mirrored: both handednesses are tried, the wrong one doesn't fit
        double[] bestRotation = null;
        var bestSign = 0;
        var bestError = Double.MAX_VALUE;
        var camera = new double[12];
        for (var sign = -1; sign <= 1; sign += 2) {
            for (var k = 0; k < 4; k++) {
                var s = imageStars[k];
                var vx = x[s] - cx;
                var vy = sign * (y[s] - cy);
                var norm = Math.sqrt(vx * vx + vy * vy + focal * focal);
                camera[3 * k] = vx / norm;
                camera[3 * k + 1] = vy / norm;
                camera[3 * k + 2] = focal / norm;
            }
            var rotation = rotation(camera, sky);
            if (rotation == null) {
                continue;
            }
            var error = 0.0;
            for (var k = 0; k < 4; k++) {
                for (var i = 0; i < 3; i++) {
                    var v = rotation[3 * i] * camera[3 * k] + rotation[3 * i + 1] * camera[3 * k + 1]
                            + rotation[3 * i + 2] * camera[3 * k + 2];
                    var e = v - sky[3 * k + i];
                    error += e * e;
                }
            }
            if (error < bestError) {
                bestError = error;
                bestRotation = rotation;
                bestSign = sign;
            }
        }
        if (bestRotation == null) {
            return null;
        }
        var r = bestRotation;
        // the optical axis, then the axes of the image, in the sky
        var plane = TangentPlane.at(r[2], r[5], r[8]);
        var xAxisEast = plane.ex() * r[0] + plane.ey() * r[3] + plane.ez() * r[6];
        var xAxisNorth = plane.nx() * r[0] + plane.ny() * r[3] + plane.nz() * r[6];
        var yAxisEast = plane.ex() * r[1] + plane.ey() * r[4] + plane.ez() * r[7];
        var yAxisNorth = plane.nx() * r[1] + plane.ny() * r[4] + plane.nz() * r[7];
        var p = new Hypothesis();
        p.plane = plane;
        // a pixel (x, y) is in the direction (x - cx, sign (y - cy), f) of the camera, which the
        // gnomonic projection on the plane tangent to the axis puts at (x - cx, sign (y - cy)) / f
        p.m00 = xAxisEast / focal;
        p.m01 = bestSign * yAxisEast / focal;
        p.m10 = xAxisNorth / focal;
        p.m11 = bestSign * yAxisNorth / focal;
        p.tx = -(p.m00 * cx + p.m01 * cy);
        p.ty = -(p.m10 * cx + p.m11 * cy);
        p.scale = 1 / focal;
        System.arraycopy(imageStars, 0, p.imageStars, 0, 4);
        // the uncertainty grows away from the quad, like for the other hypotheses
        var mx = 0.0;
        var my = 0.0;
        for (var k = 0; k < 4; k++) {
            mx += x[imageStars[k]];
            my += y[imageStars[k]];
        }
        mx /= 4;
        my /= 4;
        var r2 = 0.0;
        for (var k = 0; k < 4; k++) {
            var dx = x[imageStars[k]] - mx;
            var dy = y[imageStars[k]] - my;
            r2 += dx * dx + dy * dy;
        }
        p.quadX = mx;
        p.quadY = my;
        p.quadR2 = r2;
        return p;
    }

    /**
     * The focal length, in pixels, for which the angles between the stars of a quad, as seen by
     * the camera, are the ones between them in the sky: a few steps of Gauss-Newton on the
     * cosines of the angles of the 6 pairs, from the focal length which was tried.
     *
     * @return the focal length, or NaN if the stars don't fit a camera, or if the focal length is
     * too far from the one which was tried: the match is wrong
     */
    static double fitFocal(double[] sky, double[] x, double[] y, int[] imageStars, double cx, double cy, double focal) {
        var f = focal;
        for (var iter = 0; iter < FOCAL_ITERATIONS; iter++) {
            var f2 = f * f;
            var jr = 0.0;
            var jj = 0.0;
            for (var k = 0; k < 4; k++) {
                var dxk = x[imageStars[k]] - cx;
                var dyk = y[imageStars[k]] - cy;
                var a = dxk * dxk + dyk * dyk;
                for (var l = k + 1; l < 4; l++) {
                    var dxl = x[imageStars[l]] - cx;
                    var dyl = y[imageStars[l]] - cy;
                    var b = dxl * dxl + dyl * dyl;
                    var p = dxk * dxl + dyk * dyl;
                    var c = sky[3 * k] * sky[3 * l] + sky[3 * k + 1] * sky[3 * l + 1] + sky[3 * k + 2] * sky[3 * l + 2];
                    var u = p + f2;
                    var v2 = (a + f2) * (b + f2);
                    var v = Math.sqrt(v2);
                    var residual = u / v - c;
                    var jacobian = f * (2 * v2 - u * (a + b + 2 * f2)) / (v2 * v);
                    jr += jacobian * residual;
                    jj += jacobian * jacobian;
                }
            }
            if (jj == 0) {
                return Double.NaN;
            }
            f -= jr / jj;
            if (!(f > 0)) {
                return Double.NaN;
            }
        }
        return Math.abs(f / focal - 1) > MAX_FOCAL_ERROR ? Double.NaN : f;
    }

    /** The steps of the fit of the focal length, see {@link #fitFocal}. */
    private static final int FOCAL_ITERATIONS = 4;
    /** How far from the focal length which was tried the one of a quad may be, see {@link #fitFocal}: beyond, the match is wrong. */
    private static final double MAX_FOCAL_ERROR = 0.2;

    /**
     * The rotation which brings the first directions onto the second ones, as a row-major matrix:
     * the one which aligns their means, then the directions from the first star of the quad to the
     * second one, which are the farthest apart.
     */
    private static double[] rotation(double[] from, double[] to) {
        var a1 = mean(from);
        var b1 = mean(to);
        var a2 = cross(a1, new double[]{from[3] - from[0], from[4] - from[1], from[5] - from[2]});
        var b2 = cross(b1, new double[]{to[3] - to[0], to[4] - to[1], to[5] - to[2]});
        if (!normalize(a1) || !normalize(b1) || !normalize(a2) || !normalize(b2)) {
            return null;
        }
        var a3 = cross(a1, a2);
        var b3 = cross(b1, b2);
        // R = B A^T, where the columns of A and B are the triads
        var r = new double[9];
        for (var i = 0; i < 3; i++) {
            for (var j = 0; j < 3; j++) {
                r[3 * i + j] = b1[i] * a1[j] + b2[i] * a2[j] + b3[i] * a3[j];
            }
        }
        return r;
    }

    private static double[] mean(double[] v) {
        var m = new double[3];
        for (var k = 0; k < v.length / 3; k++) {
            m[0] += v[3 * k];
            m[1] += v[3 * k + 1];
            m[2] += v[3 * k + 2];
        }
        return m;
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static boolean normalize(double[] v) {
        var n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (n < 1e-12) {
            return false;
        }
        v[0] /= n;
        v[1] /= n;
        v[2] /= n;
        return true;
    }
}
