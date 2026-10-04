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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AstapPlateTest {
    @Test
    void computesCdeltAndCrotaLikeAstap() {
        var m31 = AstapSamples.M31;
        assertEquals(-8.5470206556581920E-004, m31.cdelt1(), 1e-17);
        assertEquals(8.5478911162897170E-004, m31.cdelt2(), 1e-17);
        assertEquals(-1.3553273021453424E+002, m31.crota1(), 1e-11);
        assertEquals(-1.3553735313328852E+002, m31.crota2(), 1e-11);
        var m33 = AstapSamples.M33;
        assertEquals(2.5312658942955394E-004, m33.cdelt1(), 1e-17);
        assertEquals(2.5304871923871299E-004, m33.cdelt2(), 1e-17);
        assertEquals(1.3061714734555031E+002, m33.crota1(), 1e-11);
        assertEquals(1.3059912905839769E+002, m33.crota2(), 1e-11);
    }

    @Test
    void readsTheWcsWithFitsConventions() {
        var plate = AstapPlate.of(AstapSamples.wcs(AstapSamples.M31));
        assertEquals(1520.5, plate.crpix1());
        assertEquals(1008.5, plate.crpix2());
        assertEquals(AstapSamples.M31.crval1(), plate.crval1(), 1e-12);
        assertEquals(AstapSamples.M31.cd12(), plate.cd12(), 1e-18);
    }
}
