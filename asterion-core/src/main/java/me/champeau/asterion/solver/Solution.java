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

import me.champeau.asterion.wcs.Wcs;

import java.util.List;

/**
 * An astrometric solution.
 *
 * @param wcs the world coordinate system of the image
 * @param raDeg the right ascension of the center of the image, in degrees
 * @param decDeg the declination of the center of the image, in degrees
 * @param pixelScale the scale of the image, in arcseconds per pixel
 * @param rotationDeg the position angle of the Y axis of the image, in degrees east of north
 * @param flipped true if the image is mirrored, see {@link Wcs#flipped()}
 * @param fieldWidthDeg the width of the field of view, in degrees
 * @param fieldHeightDeg the height of the field of view, in degrees
 * @param matchedStars the number of stars of the image which were matched with the catalog
 * @param rmsArcsec the RMS distance between matched stars and their catalog position, in arcseconds
 * @param logOdds the confidence in the solution: logarithm of the odds ratio between a true and a false match
 * @param indexName the name of the index which solved the image
 * @param matches the stars of the image which were matched with the catalog
 */
public record Solution(
        Wcs wcs,
        double raDeg,
        double decDeg,
        double pixelScale,
        double rotationDeg,
        boolean flipped,
        double fieldWidthDeg,
        double fieldHeightDeg,
        int matchedStars,
        double rmsArcsec,
        double logOdds,
        String indexName,
        List<Match> matches) {
    /**
     * The focal length of the optics which took the image.
     *
     * @param pixelSizeMicrons the size of the pixels of the image, in micrometers, binning included
     * @return the focal length, in millimeters
     */
    public double focalLengthMm(double pixelSizeMicrons) {
        return 206.264806 * pixelSizeMicrons / pixelScale;
    }

    /**
     * A star of the image which was identified in the catalog.
     *
     * @param x the abscissa of the star in the image, in pixels
     * @param y the ordinate of the star in the image, in pixels
     * @param raDeg the right ascension of the catalog star at the date of the image, in degrees
     * @param decDeg the declination of the catalog star at the date of the image, in degrees
     * @param residualArcsec the distance between the catalog star and the position of the image star according to the solution
     */
    public record Match(
            double x,
            double y,
            double raDeg,
            double decDeg,
            double residualArcsec) {
    }
}
