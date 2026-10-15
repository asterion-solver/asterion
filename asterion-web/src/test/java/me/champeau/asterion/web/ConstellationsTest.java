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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConstellationsTest {
    @ParameterizedTest
    @CsvSource({
            // J2000 positions of well-known objects
            "88.7929, 7.4071, Ori, Orion",
            "37.9546, 89.2641, UMi, Ursa Minor",
            "101.2872, -16.7161, CMa, Canis Major",
            "10.6847, 41.2690, And, Andromeda",
            "219.9021, -60.8340, Cen, Centaurus",
            "186.6496, -63.0991, Cru, Crux",
            "283.8163, -26.2967, Sgr, Sagittarius",
            "14.1772, 60.7167, Cas, Cassiopeia",
            "0, 90, UMi, Ursa Minor",
            "0, -90, Oct, Octans",
            // Serpens has two parts, which share their name
            "236.0670, 6.4256, Ser, Serpens",
            "274.7, -13.8, Ser, Serpens"
    })
    void namesTheConstellationOfAPosition(double ra, double dec, String abbreviation, String name) {
        assertEquals(new Constellations.Constellation(abbreviation, name), Constellations.of(ra, dec));
    }

    @ParameterizedTest
    @CsvSource({
            // the example of Roman (1987), for the equinox of 1950
            "9.0000, 65.0000, UMa",
            "23.5000, -20.0000, Aqr",
            "5.1200, 9.1200, Ori",
            "9.4555, -19.9000, Hya",
            "12.8888, 22.0000, Com",
            "15.6687, -12.1234, Lib",
            "19.0000, -40.0000, CrA",
            "6.2222, -81.1234, Men"
    })
    void agreesWithTheExampleOfRoman(double raHours, double decDeg, String abbreviation) {
        // B1950 to J2000
        var p = Constellations.precession((2433282.4235 - 2451545.0) / 36525);
        var ra = Math.toRadians(raHours * 15);
        var dec = Math.toRadians(decDeg);
        double[] v = {Math.cos(dec) * Math.cos(ra), Math.cos(dec) * Math.sin(ra), Math.sin(dec)};
        var x = p[0] * v[0] + p[3] * v[1] + p[6] * v[2];
        var y = p[1] * v[0] + p[4] * v[1] + p[7] * v[2];
        var z = p[2] * v[0] + p[5] * v[1] + p[8] * v[2];
        var raJ2000 = Math.toDegrees(Math.atan2(y, x));
        var decJ2000 = Math.toDegrees(Math.asin(z));
        assertEquals(abbreviation, Constellations.of(raJ2000 < 0 ? raJ2000 + 360 : raJ2000, decJ2000).abbreviation());
    }
}
