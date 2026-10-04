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

import me.champeau.asterion.math.LinearSolver;
import me.champeau.asterion.math.Sphere;
import me.champeau.asterion.math.TangentPlane;

/**
 * Least squares fitting of a WCS on a set of stars for which both the position in the image and
 * the position on the sky are known.
 */
public final class WcsFitter {
    private WcsFitter() {
    }

    /**
     * Fits a linear TAN projection.
     *
     * @param px the abscissa of stars in the image
     * @param py the ordinate of stars in the image
     * @param vec the unit vectors of the stars on the sky, 3 values per star
     * @param n the number of stars
     * @param crpixX the abscissa of the reference pixel
     * @param crpixY the ordinate of the reference pixel
     * @param center a first estimate of the direction of the reference pixel
     * @return the WCS, or null if stars don't constrain the fit
     */
    public static Wcs fitLinear(double[] px, double[] py, double[] vec, int n, double crpixX, double crpixY, double[] center) {
        if (n < 3) {
            return null;
        }
        var c = center.clone();
        var basis = new double[3 * n];
        var xi = new double[n];
        var eta = new double[n];
        for (var i = 0; i < n; i++) {
            basis[3 * i] = px[i] - crpixX;
            basis[3 * i + 1] = py[i] - crpixY;
            basis[3 * i + 2] = 1;
        }
        double[] cd = null;
        for (var iter = 0; iter < 8; iter++) {
            var plane = TangentPlane.at(c[0], c[1], c[2]);
            project(plane, vec, n, xi, eta);
            var sol = LinearSolver.leastSquares(basis, xi, eta, n, 3);
            if (sol == null) {
                return null;
            }
            cd = new double[]{sol[0][0], sol[0][1], sol[1][0], sol[1][1]};
            plane.unproject(sol[0][2], sol[1][2], c);
            if (Math.hypot(sol[0][2], sol[1][2]) < 1e-13) {
                break;
            }
        }
        if (Math.abs(cd[0] * cd[3] - cd[1] * cd[2]) < 1e-30) {
            return null;
        }
        return new Wcs(crpixX, crpixY, Sphere.ra(c[0], c[1]), Sphere.dec(c[2]), cd);
    }

    private static void project(TangentPlane plane, double[] vec, int n, double[] xi, double[] eta) {
        for (var i = 0; i < n; i++) {
            var x = vec[3 * i];
            var y = vec[3 * i + 1];
            var z = vec[3 * i + 2];
            xi[i] = plane.xi(x, y, z);
            eta[i] = plane.eta(x, y, z);
        }
    }

    /** The number of stars required to fit SIP polynomials of a given order. */
    public static int minStarsForSip(int order) {
        return 10 * ((order + 1) * (order + 2) / 2 - 3);
    }

    /**
     * Fits a TAN projection with SIP distortion polynomials. All the terms of the projection are
     * fitted together: standard coordinates are modelled as polynomials of pixel coordinates, the
     * linear terms of which are the CD matrix.
     *
     * @param linear the linear solution to start from
     * @param order the order of the polynomials, at least 2
     * @param width the width of the image
     * @param height the height of the image
     * @return the WCS, without inverse polynomials, or null if stars don't constrain the fit
     * @see #withInverse(Wcs, int, int)
     */
    public static Wcs fitSip(double[] px, double[] py, double[] vec, int n, Wcs linear, int order, int width, int height) {
        var terms = (order + 1) * (order + 2) / 2;
        if (n < terms + 3) {
            return null;
        }
        var crpixX = linear.crpixX();
        var crpixY = linear.crpixY();
        // pixel offsets are normalized to keep the problem well conditioned
        var norm = Math.max(width, height) / 2.0;
        var c = new double[3];
        linear.pixelToVector(crpixX, crpixY, c);
        var size = order + 1;
        var xi = new double[n];
        var eta = new double[n];
        var basis = new double[terms * n];
        var powU = new double[size];
        var powV = new double[size];
        for (var i = 0; i < n; i++) {
            powers((px[i] - crpixX) / norm, powU);
            powers((py[i] - crpixY) / norm, powV);
            var t = 0;
            for (var p = 0; p <= order; p++) {
                for (var q = 0; p + q <= order; q++) {
                    basis[i * terms + t++] = powU[p] * powV[q];
                }
            }
        }
        double[][] sol = null;
        for (var round = 0; round < 6; round++) {
            var plane = TangentPlane.at(c[0], c[1], c[2]);
            project(plane, vec, n, xi, eta);
            sol = LinearSolver.leastSquares(basis, xi, eta, n, terms);
            if (sol == null) {
                return null;
            }
            // the constant terms tell how far the reference pixel is from the tangent point
            if (Math.hypot(sol[0][0], sol[1][0]) < 1e-13) {
                break;
            }
            plane.unproject(sol[0][0], sol[1][0], c);
        }
        // terms are ordered by increasing power of u, then of v: v is at index 1 and u at index order + 1
        var cd = new double[]{sol[0][size] / norm, sol[0][1] / norm, sol[1][size] / norm, sol[1][1] / norm};
        var det = cd[0] * cd[3] - cd[1] * cd[2];
        if (Math.abs(det) < 1e-30) {
            return null;
        }
        var a = new double[size * size];
        var b = new double[size * size];
        var t = 0;
        for (var p = 0; p <= order; p++) {
            for (var q = 0; p + q <= order; q++) {
                if (p + q >= 2) {
                    var scale = Math.pow(norm, p + q);
                    var cx = sol[0][t] / scale;
                    var cy = sol[1][t] / scale;
                    a[p * size + q] = (cd[3] * cx - cd[1] * cy) / det;
                    b[p * size + q] = (-cd[2] * cx + cd[0] * cy) / det;
                }
                t++;
            }
        }
        return new Wcs(crpixX, crpixY, Sphere.ra(c[0], c[1]), Sphere.dec(c[2]), cd, order, a, b, 0, null, null);
    }

    /**
     * Computes the inverse SIP polynomials of a WCS, which convert sky coordinates to pixels
     * without iterating. They are only required to describe the WCS as FITS keywords: this library
     * doesn't use them. The polynomials are fitted on a grid of points which covers the image.
     */
    public static Wcs withInverse(Wcs wcs, int width, int height) {
        var order = wcs.sipOrder();
        if (order < 2) {
            return wcs;
        }
        var crpixX = wcs.crpixX();
        var crpixY = wcs.crpixY();
        var norm = Math.max(width, height) / 2.0;
        var inverseOrder = order + 2;
        var invSize = inverseOrder + 1;
        var invTerms = (inverseOrder + 1) * (inverseOrder + 2) / 2;
        var grid = 24;
        var points = grid * grid;
        var invBasis = new double[invTerms * points];
        var du = new double[points];
        var dv = new double[points];
        var powU = new double[invSize];
        var powV = new double[invSize];
        for (var j = 0; j < grid; j++) {
            for (var i = 0; i < grid; i++) {
                var k = j * grid + i;
                var u = i * (width - 1.0) / (grid - 1) - crpixX;
                var v = j * (height - 1.0) / (grid - 1) - crpixY;
                var bigU = u + Wcs.polynomial(wcs.sipA(), order, u, v);
                var bigV = v + Wcs.polynomial(wcs.sipB(), order, u, v);
                du[k] = u - bigU;
                dv[k] = v - bigV;
                powers(bigU / norm, powU);
                powers(bigV / norm, powV);
                var t = 0;
                for (var p = 0; p <= inverseOrder; p++) {
                    for (var q = 0; p + q <= inverseOrder; q++) {
                        invBasis[k * invTerms + t++] = powU[p] * powV[q];
                    }
                }
            }
        }
        var inv = LinearSolver.leastSquares(invBasis, du, dv, points, invTerms);
        if (inv == null) {
            return wcs;
        }
        var ap = new double[invSize * invSize];
        var bp = new double[invSize * invSize];
        var t = 0;
        for (var p = 0; p <= inverseOrder; p++) {
            for (var q = 0; p + q <= inverseOrder; q++) {
                var scale = Math.pow(norm, p + q);
                ap[p * invSize + q] = inv[0][t] / scale;
                bp[p * invSize + q] = inv[1][t] / scale;
                t++;
            }
        }
        return wcs.withInverse(inverseOrder, ap, bp);
    }

    private static void powers(double value, double[] out) {
        out[0] = 1;
        for (var i = 1; i < out.length; i++) {
            out[i] = out[i - 1] * value;
        }
    }
}
