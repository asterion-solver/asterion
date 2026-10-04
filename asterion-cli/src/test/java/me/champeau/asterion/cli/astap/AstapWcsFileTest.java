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
import nom.tam.fits.Fits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AstapWcsFileTest {
    private static final AstapOutcome OUTCOME = new AstapOutcome(AstapStatus.SOLVED, null, AstapSamples.solution(AstapSamples.M31),
            List.of("Warning scale was inaccurate! Set FOV=1.72d, scale=3.1\", FL=523mm"), 3.4, 42.4);

    @Test
    void writesSolutionsLikeAstap() {
        var lines = AstapWcsFile.format(List.of("FILTER  = 'No filter'          / Optical filter name"), OUTCOME,
                "astap/astap_cli -f img/m31.fit -d astap/db -o astap/out/m31", false, false).lines().toList();
        // the .wcs file written by ASTAP for the same solution, except for the comment of PLTSOLVD
        var expected = """
                SIMPLE  =                    T / file does conform to FITS standard
                BITPIX  =                    8 / number of bits per data pixel
                NAXIS   =                    0 / number of data axes
                FILTER  = 'No filter'          / Optical filter name
                CTYPE1  = 'RA---TAN'           / first parameter RA,    projection TANgential
                CTYPE2  = 'DEC--TAN'           / second parameter DEC,  projection TANgential
                CUNIT1  = 'deg     '           / Unit of coordinates
                EQUINOX =               2000.0 / Equinox of coordinates
                CRPIX1  =  1.520500000000E+003 / X of reference pixel
                CRPIX2  =  1.008500000000E+003 / Y of reference pixel
                CRVAL1  =  1.057598382192E+001 / RA of reference pixel (deg)
                CRVAL2  =  4.127711959883E+001 / DEC of reference pixel (deg)
                CDELT1  = -8.547020655658E-004 / X pixel size (deg)
                CDELT2  =  8.547891116290E-004 / Y pixel size (deg)
                CROTA1  = -1.355327302145E+002 / Image twist of X axis        (deg)
                CROTA2  = -1.355373531333E+002 / Image twist of Y axis        (deg)
                CD1_1   =  6.099587506127E-004 / CD matrix to convert (x,y) to (Ra, Dec)
                CD1_2   =  5.987202547379E-004 / CD matrix to convert (x,y) to (Ra, Dec)
                CD2_1   =  5.987320090771E-004 / CD matrix to convert (x,y) to (Ra, Dec)
                CD2_2   = -6.100691818687E-004 / CD matrix to convert (x,y) to (Ra, Dec)
                PLTSOLVD=                    T / Astrometric solved by Asterion Solver
                COMMENT 7 Solved in 3.4 sec. Offset was 42.4d.
                WARNING = 'Warning scale was inaccurate! Set FOV=1.72d, scale=3.1", FL=523mm'
                COMMENT cmdline:astap/astap_cli -f img/m31.fit -d astap/db -o astap/out/m31
                END
                """.lines().toList();
        assertEquals(expected, lines.stream().map(String::stripTrailing).toList());
        // like ASTAP, cards are padded, but not comments and warnings
        assertEquals(80, lines.getFirst().length());
        assertTrue(lines.stream().filter(line -> line.startsWith("COMMENT") || line.startsWith("WARNING"))
                .allMatch(line -> line.equals(line.stripTrailing())));
    }

    @Test
    void cutsLongCommandLines() {
        var commandLine = "/a/long/path/".repeat(10) + "astap -f image.fits";
        var lines = AstapWcsFile.format(List.of(), OUTCOME, commandLine, false, false).lines()
                .filter(line -> line.startsWith("COMMENT") && !line.startsWith("COMMENT 7"))
                .toList();
        assertEquals(3, lines.size());
        assertEquals(80, lines.getFirst().length());
        assertEquals("cmdline:" + commandLine, String.join("", lines.stream().map(line -> line.substring(8)).toList()));
    }

    @Test
    void writesFitsHeaders() {
        var text = AstapWcsFile.format(List.of(), OUTCOME, "astap -f image.fits -wcs", false, true);
        assertEquals(0, text.length() % 2880);
        assertTrue(text.indexOf('\n') < 0);
        assertEquals("END", text.substring(text.lastIndexOf("END"), text.lastIndexOf("END") + 80).strip());
        assertEquals(0, text.lastIndexOf("END") % 80);
    }

    @Test
    void writesSipCoefficientsWhenAsked() {
        var wcs = AstapSamples.wcs(AstapSamples.M31);
        var a = new double[16];
        var b = new double[16];
        a[2 * 4] = 1e-7;
        b[2] = 2e-7;
        var sip = new Wcs(wcs.crpixX(), wcs.crpixY(), wcs.plane(), wcs.cd(), 3, a, b, 0, null, null);
        var solution = AstapSamples.solution(AstapSamples.M31);
        var outcome = new AstapOutcome(AstapStatus.SOLVED, null, new Solution(sip, solution.raDeg(), solution.decDeg(),
                solution.pixelScale(), solution.rotationDeg(), solution.flipped(), 0, 0, 100, 1, 100, "test", List.of()), List.of(), 1,
                Double.NaN);
        var lines = AstapWcsFile.format(List.of(), outcome, "astap -sip", true, false).lines().map(String::stripTrailing).toList();
        assertTrue(lines.contains("CTYPE1  = 'RA---TAN-SIP'       / TAN (gnomic) projection + SIP distortions"));
        assertTrue(lines.contains("A_ORDER =                    3 / Polynomial order, axis 1. Pixel to Sky"));
        assertTrue(lines.contains("A_2_0   =  1.000000000000E-007 / SIP coefficient"));
        assertTrue(lines.contains("B_0_2   =  2.000000000000E-007 / SIP coefficient"));
        assertTrue(lines.contains("A_0_0   =  0.000000000000E+000 / SIP coefficient"));
        assertEquals(10, lines.stream().filter(line -> line.startsWith("A_") && !line.startsWith("A_ORDER")).count());
        // SIP cards come before CTYPE1, like ASTAP
        var order = lines.indexOf("A_ORDER =                    3 / Polynomial order, axis 1. Pixel to Sky");
        var ctype = lines.indexOf("CTYPE1  = 'RA---TAN-SIP'       / TAN (gnomic) projection + SIP distortions");
        assertTrue(order < ctype);
        // the solve didn't start from a position
        assertTrue(lines.contains("COMMENT 7 Solved in 1.0 sec."));
    }

    @Test
    void keepsTheHeaderOfTheImageWithoutItsDataNorAPreviousSolution(@TempDir Path dir) throws Exception {
        var file = dir.resolve("image.fits");
        try (var fits = new Fits()) {
            var hdu = Fits.makeHDU(new short[4][6]);
            var header = hdu.getHeader();
            header.addValue("EXPTIME", 60.0, "Exposure time [s]");
            header.addValue("CRVAL1", 10.0, "a previous solution");
            header.addValue("CD1_1", 1e-4, "a previous solution");
            header.addValue("FILTER", "No filter", "Optical filter name");
            fits.addHDU(hdu);
            fits.write(file.toFile());
        }
        var cards = AstapWcsFile.originalCards(file);
        var keys = cards.stream().map(card -> card.substring(0, Math.min(8, card.length())).strip()).toList();
        assertTrue(keys.contains("EXPTIME"));
        assertTrue(keys.contains("FILTER"));
        assertTrue(keys.contains("EXTEND"));
        assertTrue(keys.stream().noneMatch(key -> key.startsWith("NAXIS") || key.equals("SIMPLE") || key.equals("BITPIX")
                || key.equals("CRVAL1") || key.equals("CD1_1") || key.equals("END")));
    }
}
