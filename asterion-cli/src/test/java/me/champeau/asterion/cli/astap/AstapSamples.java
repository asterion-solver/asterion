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

import me.champeau.asterion.solver.Solution;
import me.champeau.asterion.wcs.Wcs;

import java.util.List;

/** Solutions found by ASTAP 2026.09.01, to compare the files written by Asterion with the ones of ASTAP. */
final class AstapSamples {
    private AstapSamples() {
    }

    /** M31, 3040 x 2016 pixels, not mirrored. */
    static final AstapPlate M31 = new AstapPlate(1520.5, 1008.5, 1.0575983821916944E+001, 4.1277119598828868E+001,
            6.0995875061270910E-004, 5.9872025473793752E-004, 5.9873200907712722E-004, -6.1006918186867418E-004);
    /** M33, 9576 x 6388 pixels, mirrored. */
    static final AstapPlate M33 = new AstapPlate(4788.5, 3194.5, 2.3467052550379687E+001, 3.0644024509339211E+001,
            -1.6478576942210619E-004, -1.9214244838713392E-004, 1.9213513508435032E-004, -1.6467466160424149E-004);

    static Wcs wcs(AstapPlate plate) {
        return new Wcs(plate.crpix1() - 1, plate.crpix2() - 1, Math.toRadians(plate.crval1()), Math.toRadians(plate.crval2()),
                new double[]{Math.toRadians(plate.cd11()), Math.toRadians(plate.cd12()), Math.toRadians(plate.cd21()),
                        Math.toRadians(plate.cd22())});
    }

    static Solution solution(AstapPlate plate) {
        var wcs = wcs(plate);
        return new Solution(wcs, wcs.crvalRa(), wcs.crvalDec(), wcs.pixelScale(), wcs.rotation(), wcs.flipped(),
                0, 0, 100, 1, 100, "test", List.of());
    }
}
