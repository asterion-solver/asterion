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
package me.champeau.asterion.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Names the constellation of a position, with the method of Roman (1987): the boundaries of the
 * constellations follow meridians and parallels of the equinox of 1875, so a position is precessed
 * to that equinox, and looked up in a table of the southern boundaries of the constellations.
 */
final class Constellations {
    /** B1875.0, in Julian centuries since J2000. */
    private static final double B1875 = (2405889.258550475 - 2451545.0) / 36525;
    private static final double[] TO_B1875 = precession(B1875);
    private static final List<Row> ROWS = load();

    /** A constellation, by its IAU abbreviation and its name. */
    record Constellation(
            String abbreviation,
            String name) {
    }

    /** A southern boundary: right ascensions in hours and declination in degrees, of 1875. */
    private record Row(
            double raLow,
            double raHigh,
            double decLow,
            Constellation constellation) {
    }

    private Constellations() {
    }

    /**
     * The constellation of a position.
     *
     * @param raDeg the right ascension, J2000, in degrees
     * @param decDeg the declination, J2000, in degrees
     */
    static Constellation of(double raDeg, double decDeg) {
        var ra = Math.toRadians(raDeg);
        var dec = Math.toRadians(decDeg);
        var x = Math.cos(dec) * Math.cos(ra);
        var y = Math.cos(dec) * Math.sin(ra);
        var z = Math.sin(dec);
        var m = TO_B1875;
        var px = m[0] * x + m[1] * y + m[2] * z;
        var py = m[3] * x + m[4] * y + m[5] * z;
        var pz = m[6] * x + m[7] * y + m[8] * z;
        var hours = Math.toDegrees(Math.atan2(py, px)) / 15;
        if (hours < 0) {
            hours += 24;
        }
        var degrees = Math.toDegrees(Math.asin(Math.clamp(pz, -1, 1)));
        for (var row : ROWS) {
            if (degrees >= row.decLow && hours >= row.raLow && hours < row.raHigh) {
                return row.constellation;
            }
        }
        // the last row covers the south pole: never reached
        throw new IllegalStateException("No constellation for " + raDeg + ", " + decDeg);
    }

    /**
     * The IAU 1976 precession matrix, from J2000 to the mean equator and equinox of a date, in
     * row-major order.
     *
     * @param t the date, in Julian centuries since J2000
     */
    static double[] precession(double t) {
        var arcsec = Math.PI / 180 / 3600;
        var zeta = (2306.2181 + (0.30188 + 0.017998 * t) * t) * t * arcsec;
        var z = (2306.2181 + (1.09468 + 0.018203 * t) * t) * t * arcsec;
        var theta = (2004.3109 - (0.42665 + 0.041833 * t) * t) * t * arcsec;
        double cZeta = Math.cos(zeta), sZeta = Math.sin(zeta);
        double cZ = Math.cos(z), sZ = Math.sin(z);
        double cTheta = Math.cos(theta), sTheta = Math.sin(theta);
        return new double[]{
                cZ * cTheta * cZeta - sZ * sZeta, -cZ * cTheta * sZeta - sZ * cZeta, -cZ * sTheta,
                sZ * cTheta * cZeta + cZ * sZeta, -sZ * cTheta * sZeta + cZ * cZeta, -sZ * sTheta,
                sTheta * cZeta, -sTheta * sZeta, cTheta};
    }

    private static List<Row> load() {
        try (var in = Constellations.class.getResourceAsStream("constellations.txt")) {
            if (in == null) {
                throw new IOException("Missing resource constellations.txt");
            }
            var rows = new ArrayList<Row>();
            for (var line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                var fields = line.trim().split(" ", 5);
                rows.add(new Row(Double.parseDouble(fields[0]), Double.parseDouble(fields[1]), Double.parseDouble(fields[2]),
                        new Constellation(fields[3], fields[4])));
            }
            return List.copyOf(rows);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
