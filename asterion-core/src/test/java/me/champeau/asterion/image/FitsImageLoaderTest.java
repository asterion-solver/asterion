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
package me.champeau.asterion.image;

import nom.tam.fits.Fits;
import nom.tam.fits.ImageHDU;
import nom.tam.util.FitsFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FitsImageLoaderTest {
    @TempDir
    Path directory;

    private Path write(String name, Object data, Customizer customizer) throws Exception {
        var file = directory.resolve(name);
        try (var fits = new Fits()) {
            var hdu = (ImageHDU) Fits.makeHDU(data);
            customizer.customize(hdu);
            fits.addHDU(hdu);
            try (var out = new FitsFile(file.toFile(), "rw")) {
                fits.write(out);
            }
        }
        return file;
    }

    @FunctionalInterface
    private interface Customizer {
        void customize(ImageHDU hdu) throws Exception;
    }

    @Test
    void readsUnsigned16BitsImages() throws Exception {
        var data = new short[40][60];
        for (var y = 0; y < 40; y++) {
            for (var x = 0; x < 60; x++) {
                // stored with an offset of 32768, as cameras do
                data[y][x] = (short) (x + 100 * y + 30000 - 32768);
            }
        }
        var file = write("u16.fits", data, hdu -> {
            hdu.getHeader().addValue("BZERO", 32768, null);
            hdu.getHeader().addValue("BSCALE", 1, null);
        });
        var loaded = FitsImageLoader.load(file);
        var image = loaded.image();
        assertEquals(60, image.width());
        assertEquals(40, image.height());
        assertEquals(1, image.binning());
        assertEquals(30000 + 7 + 100 * 3, image.data()[3 * 60 + 7]);
        assertEquals(30000 + 59 + 100 * 39, image.data()[39 * 60 + 59]);
        assertFalse(loaded.hints().hasPosition());
    }

    @Test
    void binsRawColorFrames() throws Exception {
        var data = new short[40][60];
        for (var y = 0; y < 40; y++) {
            for (var x = 0; x < 60; x++) {
                data[y][x] = (short) (x + y);
            }
        }
        var file = write("bayer.fits", data, hdu -> hdu.getHeader().addValue("BAYERPAT", "RGGB", null));
        var loaded = FitsImageLoader.load(file);
        var image = loaded.image();
        assertTrue(loaded.hints().bayer());
        assertEquals(2, image.binning());
        assertEquals(30, image.width());
        assertEquals(20, image.height());
        assertEquals(60, image.sourceWidth());
        // sum of pixels (10, 6), (11, 6), (10, 7) and (11, 7)
        assertEquals(16 + 17 + 17 + 18, image.data()[3 * 30 + 5]);
        // explicit binning wins
        assertEquals(1, FitsImageLoader.load(file, 1).image().binning());
    }

    @Test
    void doesNotBinDebayeredColorImages() throws Exception {
        // SharpCap describes RGB images with 3 planes, which are already debayered, with COLORTYP = 'RGB'
        var file = write("rgb-sharpcap.fits", new short[3][40][60], hdu -> hdu.getHeader().addValue("COLORTYP", "RGB", null));
        var loaded = FitsImageLoader.load(file);
        assertFalse(loaded.hints().bayer());
        assertEquals(1, loaded.image().binning());
        // a single plane with a Bayer pattern is a raw frame, whatever the keyword
        var raw = write("raw-sharpcap.fits", new short[40][60], hdu -> hdu.getHeader().addValue("COLORTYP", "RGGB", null));
        assertTrue(FitsImageLoader.load(raw).hints().bayer());
        var mono = write("mono.fits", new short[40][60], hdu -> hdu.getHeader().addValue("COLORTYP", "MONO", null));
        assertFalse(FitsImageLoader.load(mono).hints().bayer());
    }

    @Test
    void readsFloatCubes() throws Exception {
        var data = new float[3][20][30];
        for (var p = 0; p < 3; p++) {
            for (var y = 0; y < 20; y++) {
                for (var x = 0; x < 30; x++) {
                    data[p][y][x] = (p + 1) * (x + 0.5f * y);
                }
            }
        }
        var file = write("rgb.fits", data, _ -> {
        });
        var image = FitsImageLoader.load(file).image();
        assertEquals(30, image.width());
        assertEquals(20, image.height());
        assertEquals(6 * (4 + 0.5f * 2), image.data()[2 * 30 + 4], 1e-4);
    }

    @Test
    void readsHints() throws Exception {
        var file = write("hints.fits", new short[20][20], hdu -> {
            hdu.getHeader().addValue("RA", 344.47, null);
            hdu.getHeader().addValue("DEC", 62.52, null);
            hdu.getHeader().addValue("FOCALLEN", 850.0, null);
            hdu.getHeader().addValue("XPIXSZ", 3.76, null);
            hdu.getHeader().addValue("DATE-OBS", "2024-08-25T20:51:07.676", null);
        });
        var hints = FitsImageLoader.load(file).hints();
        assertEquals(344.47, hints.raDeg().orElseThrow(), 1e-9);
        assertEquals(62.52, hints.decDeg().orElseThrow(), 1e-9);
        assertEquals(0.9124, hints.pixelScale().orElseThrow(), 1e-3);
        assertEquals(3.76, hints.pixelSizeMicrons().orElseThrow(), 1e-9);
        assertEquals(2024.65, hints.epoch().orElseThrow(), 0.01);

        var sexagesimal = write("sexagesimal.fits", new short[20][20], hdu -> {
            hdu.getHeader().addValue("OBJCTRA", "22 57 54", null);
            hdu.getHeader().addValue("OBJCTDEC", "-62 31 06", null);
        });
        hints = FitsImageLoader.load(sexagesimal).hints();
        assertEquals(344.475, hints.raDeg().orElseThrow(), 1e-6);
        assertEquals(-62.518333, hints.decDeg().orElseThrow(), 1e-6);

        // a previous solution wins over the position of the mount, like in SharpCap files without a mount
        var solved = write("solved.fits", new short[20][20], hdu -> {
            hdu.getHeader().addValue("RA", 75.0, null);
            hdu.getHeader().addValue("DEC", -5.0, null);
            hdu.getHeader().addValue("CTYPE1", "RA---TAN", null);
            hdu.getHeader().addValue("CTYPE2", "DEC--TAN", null);
            hdu.getHeader().addValue("CRVAL1", 83.8358, null);
            hdu.getHeader().addValue("CRVAL2", -5.3092, null);
        });
        hints = FitsImageLoader.load(solved).hints();
        assertEquals(83.8358, hints.raDeg().orElseThrow(), 1e-9);
        assertEquals(-5.3092, hints.decDeg().orElseThrow(), 1e-9);
    }
}
