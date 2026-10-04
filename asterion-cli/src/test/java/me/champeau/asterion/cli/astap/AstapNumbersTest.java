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

class AstapNumbersTest {
    @Test
    void formatsNumbersOfIniFiles() {
        assertEquals(" 1.5205000000000000E+003", AstapNumbers.ini(1520.5));
        assertEquals("-8.5470206556581920E-004", AstapNumbers.ini(-8.5470206556581920E-004));
        assertEquals(" 4.1277119598828868E+001", AstapNumbers.ini(4.1277119598828868E+001));
        assertEquals(" 0.0000000000000000E+000", AstapNumbers.ini(0));
    }

    @Test
    void formatsNumbersOfWcsFiles() {
        assertEquals(" 1.520500000000E+003", AstapNumbers.wcs(1520.5));
        assertEquals("-1.355327302145E+002", AstapNumbers.wcs(-1.3553273021453424E+002));
        assertEquals(" 5.987202547379E-004", AstapNumbers.wcs(5.9872025473793752E-004));
        assertEquals(20, AstapNumbers.wcs(-1e-123).length());
    }

    @Test
    void formatsCoordinatesLikeAstap() {
        // 00: 42  18.2 +41d 16  38 for the solution of the M31 frame
        assertEquals("00: 42  18.2", AstapNumbers.ra(1.0575983821916944E+001));
        assertEquals("+41d 16  38", AstapNumbers.dec(4.1277119598828868E+001));
        assertEquals("23: 59  59.9", AstapNumbers.ra(359.9997));
        assertEquals("00: 00  00.0", AstapNumbers.ra(359.99999));
        assertEquals("-05d 30  00", AstapNumbers.dec(-5.5));
    }

    @Test
    void formatsDistancesLikeAstap() {
        assertEquals("42.4d", AstapNumbers.distance(42.4));
        assertEquals("3.7'", AstapNumbers.distance(3.7 / 60));
        assertEquals("12\"", AstapNumbers.distance(12 / 3600.0));
    }
}
