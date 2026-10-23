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

import me.champeau.asterion.Locales;
import me.champeau.asterion.SyntheticSky;
import nom.tam.fits.Fits;
import nom.tam.fits.ImageHDU;
import nom.tam.util.FitsFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SolutionFilesTest {
    @TempDir
    Path directory;

    private static Solution solution() {
        var wcs = SyntheticSky.wcs(60, 40, 83.8, -5.4, 2.5, 30, false);
        return new Solution(wcs, 83.8, -5.4, 2.5, 30, false, 60 * 2.5 / 3600, 40 * 2.5 / 3600, 2, 0.4, 50, "test",
                List.of(new Solution.Match(10.5, 20.25, 83.81, -5.39, 0.3), new Solution.Match(30, 5, 83.79, -5.41, 0.5)));
    }

    private Path image(String name) throws Exception {
        var data = new short[40][60];
        for (var y = 0; y < 40; y++) {
            for (var x = 0; x < 60; x++) {
                data[y][x] = (short) (x + 100 * y);
            }
        }
        var file = directory.resolve(name);
        try (var fits = new Fits()) {
            var hdu = (ImageHDU) Fits.makeHDU(data);
            // a previous solution, with keywords which the new one doesn't have
            hdu.getHeader().addValue("CDELT1", 1.0, null);
            hdu.getHeader().addValue("CROTA2", 12.0, null);
            hdu.getHeader().addValue("OBJECT", "M42", null);
            fits.addHDU(hdu);
            try (var out = new FitsFile(file.toFile(), "rw")) {
                fits.write(out);
            }
        }
        return file;
    }

    private static short[][] check(Path file) throws Exception {
        try (var fits = new Fits(file.toFile())) {
            var hdu = (ImageHDU) fits.readHDU();
            var header = hdu.getHeader();
            assertEquals(83.8, header.getDoubleValue("CRVAL1"), 1);
            assertEquals("RA---TAN", header.getStringValue("CTYPE1").substring(0, 8));
            assertFalse(header.containsKey("CDELT1"));
            assertFalse(header.containsKey("CROTA2"));
            assertEquals("M42", header.getStringValue("OBJECT"));
            return (short[][]) hdu.getKernel();
        }
    }

    @Test
    void writesHeaders() {
        var header = SolutionFiles.wcsHeader(solution());
        assertEquals(0, header.length() % 2880);
        assertTrue(header.startsWith("SIMPLE  =                    T"));
        assertTrue(header.contains("CRVAL1  = "));
        assertTrue(header.contains("NMATCH  =                    2"));
        assertTrue(header.contains("END     "));
    }

    @ParameterizedTest
    @FieldSource("me.champeau.asterion.Locales#UNUSUAL")
    void writesTheSameFilesWhateverTheLocale(String locale) {
        var solution = solution();
        assertEquals(SolutionFiles.wcsHeader(solution), Locales.with(locale, () -> SolutionFiles.wcsHeader(solution)));
        assertEquals(SolutionFiles.matchesCsv(solution), Locales.with(locale, () -> SolutionFiles.matchesCsv(solution)));
    }

    @Test
    void writesMatches() {
        assertEquals("""
                x,y,ra,dec,residual_arcsec
                10.500,20.250,83.8100000,-5.3900000,0.300
                30.000,5.000,83.7900000,-5.4100000,0.500
                """, SolutionFiles.matchesCsv(solution()).replace("\r\n", "\n"));
    }

    @Test
    void writesCopiesOfImages() throws Exception {
        var image = image("image.fits");
        var original = Files.readAllBytes(image);
        var copy = directory.resolve("copy.fits");
        SolutionFiles.writeCopy(image, solution(), copy);
        assertArrayEquals(original, Files.readAllBytes(image));
        var data = check(copy);
        assertEquals(7 + 100 * 3, data[3][7]);

        // compressed images are written uncompressed
        var compressed = directory.resolve("image.fits.gz");
        try (var out = new GZIPOutputStream(Files.newOutputStream(compressed))) {
            out.write(original);
        }
        var uncompressed = directory.resolve("uncompressed.fits");
        SolutionFiles.writeCopy(compressed, solution(), uncompressed);
        assertEquals(7 + 100 * 3, check(uncompressed)[3][7]);

        var png = Files.write(directory.resolve("image.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        assertThrows(IOException.class, () -> SolutionFiles.writeCopy(png, solution(), directory.resolve("png.fits")));
        assertThrows(IOException.class, () -> SolutionFiles.writeCopy(image, solution(), image));
    }

    @Test
    void updatesHeaders() throws Exception {
        var image = image("update.fits");
        SolutionFiles.updateHeader(image, solution());
        assertEquals(7 + 100 * 3, check(image)[3][7]);
        var compressed = directory.resolve("image.fits.gz");
        Files.copy(image, compressed);
        assertThrows(IOException.class, () -> SolutionFiles.updateHeader(compressed, solution()));
    }
}
