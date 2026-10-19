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

import me.champeau.asterion.image.GrayImage;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThumbnailTest {
    /** Linear data, like a raw frame: a faint background with noise, a fainter nebula and a bright star. */
    private static GrayImage frame(int width, int height) {
        var data = new float[width * height];
        var random = new Random(1);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var v = 1000 + 5 * random.nextGaussian();
                if (x < width / 2) {
                    v += 20;
                }
                var d2 = (x - 3 * width / 4.0) * (x - 3 * width / 4.0) + (y - height / 2.0) * (y - height / 2.0);
                v += 60000 * Math.exp(-d2 / 50);
                data[y * width + x] = (float) v;
            }
        }
        return new GrayImage(width, height, data);
    }

    @Test
    void reducesAndStretchesImages() throws Exception {
        var png = Thumbnail.png(frame(1600, 1000), 800, false);
        var image = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(800, image.getWidth());
        assertEquals(500, image.getHeight());
        var raster = image.getRaster();
        assertEquals(1, raster.getNumBands());
        // the background is dark but not black, the nebula brighter, the star white: the stretch
        // shows what linear data hides
        var background = raster.getSample(500, 100, 0);
        var nebula = raster.getSample(100, 100, 0);
        var star = raster.getSample(600, 250, 0);
        assertTrue(background > 10 && background < 90, "background " + background);
        assertTrue(nebula > background + 30, "nebula " + nebula + ", background " + background);
        assertEquals(255, star);
    }

    @Test
    void keepsTheSizeOfSmallImages() throws Exception {
        var image = ImageIO.read(new ByteArrayInputStream(Thumbnail.png(frame(300, 200), 800, false)));
        assertEquals(300, image.getWidth());
        assertEquals(200, image.getHeight());
    }

    @Test
    void displaysTheFirstRowAtTheBottomWhenAsked() throws Exception {
        var data = new float[100];
        for (var x = 0; x < 10; x++) {
            data[x] = 1000;
        }
        var image = new GrayImage(10, 10, data);
        assertEquals(255, ImageIO.read(new ByteArrayInputStream(Thumbnail.png(image, 800, false))).getRaster().getSample(5, 0, 0));
        assertEquals(255, ImageIO.read(new ByteArrayInputStream(Thumbnail.png(image, 800, true))).getRaster().getSample(5, 9, 0));
    }

    @Test
    void tellsTheOrderOfTheRows(@TempDir Path directory) throws Exception {
        var conventional = fits(directory.resolve("a.fits"), null);
        var nina = fits(directory.resolve("b.fits"), "TOP-DOWN");
        var explicit = fits(directory.resolve("c.fit"), "BOTTOM-UP");
        assertTrue(Thumbnail.bottomUp(conventional, "a.fits"));
        assertFalse(Thumbnail.bottomUp(nina, "b.fits"));
        assertTrue(Thumbnail.bottomUp(explicit, "c.fit"));
        assertFalse(Thumbnail.bottomUp(conventional, "a.jpg"));
        var compressed = directory.resolve("d.fits.gz");
        try (var out = new GZIPOutputStream(Files.newOutputStream(compressed))) {
            out.write(Files.readAllBytes(nina));
        }
        assertFalse(Thumbnail.bottomUp(compressed, "d.fits.gz"));
    }

    /** The header of a FITS file. */
    private static Path fits(Path file, String rowOrder) throws Exception {
        var header = new StringBuilder();
        for (var card : new String[]{"SIMPLE  =                    T", "BITPIX  =                   16", "NAXIS   =                    0",
                rowOrder == null ? null : "ROWORDER= '" + rowOrder + "'", "END"}) {
            if (card != null) {
                header.append(String.format("%-80s", card));
            }
        }
        while (header.length() % 2880 != 0) {
            header.append(' ');
        }
        return Files.writeString(file, header, StandardCharsets.US_ASCII);
    }

    @Test
    void displaysFlatImages() throws Exception {
        var image = ImageIO.read(new ByteArrayInputStream(Thumbnail.png(new GrayImage(10, 10, new float[100]), 800, false)));
        assertEquals(0, image.getRaster().getSample(5, 5, 0));
    }
}
