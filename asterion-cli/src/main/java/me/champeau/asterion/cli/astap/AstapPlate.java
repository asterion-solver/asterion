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
package me.champeau.asterion.cli.astap;

import me.champeau.asterion.wcs.Wcs;

/**
 * The linear part of a solution, as ASTAP describes it: FITS conventions, in degrees.
 *
 * @param crpix1 the abscissa of the reference pixel, 1-based
 * @param crpix2 the ordinate of the reference pixel, 1-based
 * @param crval1 the right ascension of the reference pixel
 * @param crval2 the declination of the reference pixel
 * @param cd11 the CD matrix, in degrees per pixel
 * @param cd12 the CD matrix, in degrees per pixel
 * @param cd21 the CD matrix, in degrees per pixel
 * @param cd22 the CD matrix, in degrees per pixel
 */
record AstapPlate(
        double crpix1,
        double crpix2,
        double crval1,
        double crval2,
        double cd11,
        double cd12,
        double cd21,
        double cd22) {
    static AstapPlate of(Wcs wcs) {
        var cd = wcs.cd();
        return new AstapPlate(wcs.crpixX() + 1, wcs.crpixY() + 1, wcs.crvalRa(), wcs.crvalDec(),
                Math.toDegrees(cd[0]), Math.toDegrees(cd[1]), Math.toDegrees(cd[2]), Math.toDegrees(cd[3]));
    }

    /** The sign of the determinant of the CD matrix, which ASTAP applies to the X axis. */
    private double parity() {
        return Math.signum(cd11 * cd22 - cd12 * cd21);
    }

    /** The size of pixels along X, negative when the image isn't mirrored. */
    double cdelt1() {
        return parity() * Math.hypot(cd11, cd12);
    }

    /** The size of pixels along Y. */
    double cdelt2() {
        return Math.hypot(cd21, cd22);
    }

    /** The rotation of the X axis, in degrees. */
    double crota1() {
        return Math.toDegrees(Math.atan2(-cd12, parity() * cd11));
    }

    /** The rotation of the Y axis, in degrees. */
    double crota2() {
        return Math.toDegrees(Math.atan2(parity() * cd21, cd22));
    }
}
