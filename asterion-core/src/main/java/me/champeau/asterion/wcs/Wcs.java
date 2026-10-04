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

import me.champeau.asterion.math.Sphere;
import me.champeau.asterion.math.TangentPlane;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A world coordinate system: a gnomonic (TAN) projection, with optional SIP distortion polynomials.
 * Pixel coordinates are zero-based: the center of the first pixel of the image is at (0, 0), which
 * is (1, 1) in the FITS convention. Polynomial coefficients are stored in arrays of (order + 1)²
 * values, the coefficient of u^p.v^q being at index p * (order + 1) + q.
 *
 * @param crpixX the abscissa of the reference pixel, zero-based
 * @param crpixY the ordinate of the reference pixel, zero-based
 * @param plane the plane tangent to the sky at the reference pixel
 * @param cd the CD matrix (cd11, cd12, cd21, cd22), in radians per pixel
 * @param sipOrder the order of the SIP polynomials, or 0 if there's no distortion
 * @param sipA the SIP polynomial which corrects abscissas, or null
 * @param sipB the SIP polynomial which corrects ordinates, or null
 * @param inverseOrder the order of the inverse SIP polynomials, or 0 if there are none
 * @param sipAp the inverse SIP polynomial for abscissas, or null
 * @param sipBp the inverse SIP polynomial for ordinates, or null
 */
@SuppressWarnings("ArrayRecordComponent") // a WCS is never modified once created
public record Wcs(
        double crpixX,
        double crpixY,
        TangentPlane plane,
        double[] cd,
        int sipOrder,
        double[] sipA,
        double[] sipB,
        int inverseOrder,
        double[] sipAp,
        double[] sipBp) {
    public Wcs {
        if (sipA == null) {
            sipOrder = 0;
        }
        if (sipAp == null) {
            inverseOrder = 0;
        }
    }

    /**
     * Creates a linear WCS.
     *
     * @param crpixX the abscissa of the reference pixel, zero-based
     * @param crpixY the ordinate of the reference pixel, zero-based
     * @param crvalRa the right ascension of the reference pixel, in radians
     * @param crvalDec the declination of the reference pixel, in radians
     * @param cd the CD matrix (cd11, cd12, cd21, cd22), in radians per pixel
     */
    public Wcs(double crpixX, double crpixY, double crvalRa, double crvalDec, double[] cd) {
        this(crpixX, crpixY, TangentPlane.at(crvalRa, crvalDec), cd, 0, null, null, 0, null, null);
    }

    /** Creates a WCS with SIP distortion, the reference pixel being at the given position, in radians. */
    public Wcs(double crpixX, double crpixY, double crvalRa, double crvalDec, double[] cd,
               int sipOrder, double[] sipA, double[] sipB, int inverseOrder, double[] sipAp, double[] sipBp) {
        this(crpixX, crpixY, TangentPlane.at(crvalRa, crvalDec), cd, sipOrder, sipA, sipB, inverseOrder, sipAp, sipBp);
    }

    Wcs withInverse(int order, double[] ap, double[] bp) {
        return new Wcs(crpixX, crpixY, plane, cd, sipOrder, sipA, sipB, order, ap, bp);
    }

    private double determinant() {
        return cd[0] * cd[3] - cd[1] * cd[2];
    }

    static double polynomial(double[] c, int order, double u, double v) {
        var result = 0.0;
        var up = 1.0;
        var n = order + 1;
        for (var p = 0; p <= order; p++) {
            var s = 0.0;
            for (var q = order - p; q >= 0; q--) {
                s = s * v + c[p * n + q];
            }
            result += up * s;
            up *= u;
        }
        return result;
    }

    /** Converts a pixel position to a unit vector on the celestial sphere. */
    public void pixelToVector(double x, double y, double[] out) {
        var u = x - crpixX;
        var v = y - crpixY;
        if (sipOrder > 0) {
            var du = polynomial(sipA, sipOrder, u, v);
            var dv = polynomial(sipB, sipOrder, u, v);
            u += du;
            v += dv;
        }
        plane.unproject(cd[0] * u + cd[1] * v, cd[2] * u + cd[3] * v, out);
    }

    /**
     * Converts a unit vector on the celestial sphere to a pixel position.
     *
     * @return false if the direction can't be projected, because it's on the other side of the sky
     */
    public boolean vectorToPixel(double vx, double vy, double vz, double[] out) {
        if (plane.dot(vx, vy, vz) <= 1e-6) {
            return false;
        }
        var xi = plane.xi(vx, vy, vz);
        var eta = plane.eta(vx, vy, vz);
        var det = determinant();
        var bigU = (cd[3] / det) * xi + (-cd[1] / det) * eta;
        var bigV = (-cd[2] / det) * xi + (cd[0] / det) * eta;
        var u = bigU;
        var v = bigV;
        if (sipOrder > 0) {
            for (var i = 0; i < 6; i++) {
                var nu = bigU - polynomial(sipA, sipOrder, u, v);
                var nv = bigV - polynomial(sipB, sipOrder, u, v);
                var delta = Math.abs(nu - u) + Math.abs(nv - v);
                u = nu;
                v = nv;
                if (delta < 1e-5) {
                    break;
                }
            }
        }
        out[0] = u + crpixX;
        out[1] = v + crpixY;
        return true;
    }

    /**
     * Converts a pixel position to sky coordinates.
     *
     * @return (right ascension, declination), in degrees
     */
    public double[] pixelToSky(double x, double y) {
        var vec = new double[3];
        pixelToVector(x, y, vec);
        return new double[]{Math.toDegrees(Sphere.ra(vec[0], vec[1])), Math.toDegrees(Sphere.dec(vec[2]))};
    }

    /**
     * Converts sky coordinates, in degrees, to a pixel position.
     *
     * @return (x, y), or null if the position can't be projected
     */
    public double[] skyToPixel(double raDeg, double decDeg) {
        var vec = new double[3];
        Sphere.toVector(Math.toRadians(raDeg), Math.toRadians(decDeg), vec);
        var out = new double[2];
        return vectorToPixel(vec[0], vec[1], vec[2], out) ? out : null;
    }

    /** Right ascension of the reference pixel, in degrees. */
    public double crvalRa() {
        return Math.toDegrees(plane.ra());
    }

    /** Declination of the reference pixel, in degrees. */
    public double crvalDec() {
        return Math.toDegrees(plane.dec());
    }

    /** The scale at the reference pixel, in arcseconds per pixel. */
    public double pixelScale() {
        return Math.sqrt(Math.abs(determinant())) / Sphere.ARCSEC;
    }

    /**
     * The position angle of the Y axis of the image, in degrees east of north.
     */
    public double rotation() {
        return Math.toDegrees(Math.atan2(cd[1], cd[3]));
    }

    /**
     * Tells if the image is mirrored, when compared to the sky as displayed with the FITS convention,
     * the first row at the bottom. Raw frames which are stored top row first will be reported as
     * flipped.
     */
    public boolean flipped() {
        return determinant() > 0;
    }

    /**
     * Describes this WCS as FITS keywords. Values are either strings, integers or doubles.
     */
    public Map<String, Object> toFitsKeywords() {
        var cards = new LinkedHashMap<String, Object>();
        var sip = sipOrder > 0;
        cards.put("WCSAXES", 2);
        cards.put("CTYPE1", sip ? "RA---TAN-SIP" : "RA---TAN");
        cards.put("CTYPE2", sip ? "DEC--TAN-SIP" : "DEC--TAN");
        cards.put("EQUINOX", 2000.0);
        cards.put("RADESYS", "ICRS");
        cards.put("CRPIX1", crpixX + 1);
        cards.put("CRPIX2", crpixY + 1);
        cards.put("CRVAL1", crvalRa());
        cards.put("CRVAL2", crvalDec());
        cards.put("CUNIT1", "deg");
        cards.put("CUNIT2", "deg");
        cards.put("CD1_1", Math.toDegrees(cd[0]));
        cards.put("CD1_2", Math.toDegrees(cd[1]));
        cards.put("CD2_1", Math.toDegrees(cd[2]));
        cards.put("CD2_2", Math.toDegrees(cd[3]));
        if (sip) {
            putPolynomial(cards, "A", sipA, sipOrder, 2);
            putPolynomial(cards, "B", sipB, sipOrder, 2);
            if (sipAp != null) {
                putPolynomial(cards, "AP", sipAp, inverseOrder, 0);
                putPolynomial(cards, "BP", sipBp, inverseOrder, 0);
            }
        }
        return cards;
    }

    private static void putPolynomial(Map<String, Object> cards, String name, double[] c, int order, int minDegree) {
        cards.put(name + "_ORDER", order);
        var n = order + 1;
        for (var p = 0; p <= order; p++) {
            for (var q = 0; p + q <= order; q++) {
                if (p + q >= minDegree && c[p * n + q] != 0) {
                    cards.put(name + "_" + p + "_" + q, c[p * n + q]);
                }
            }
        }
    }
}
