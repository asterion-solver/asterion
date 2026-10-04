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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.IntStream;

/**
 * Reads images of any supported format: FITS files, including compressed ones, and the formats
 * supported by the Java runtime, such as PNG, JPEG and TIFF.
 */
public final class ImageLoader {
    private static final long AUTO_BINNING_PIXELS = 20_000_000;

    private ImageLoader() {
    }

    /**
     * Loads an image.
     *
     * @param binning the binning factor, or 0 to select it automatically
     */
    public static LoadedImage load(Path path, int binning) throws IOException {
        if (!Files.isReadable(path)) {
            throw new IOException("Unable to read " + path);
        }
        return isFits(path) ? FitsImageLoader.load(path, binning) : loadRaster(path, binning);
    }

    /** Tells if a file is a FITS file, possibly compressed. */
    public static boolean isFits(Path path) throws IOException {
        var name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".fz") || name.endsWith(".gz") || name.endsWith(".fit") || name.endsWith(".fits") || name.endsWith(".fts")) {
            return true;
        }
        try (var in = Files.newInputStream(path)) {
            return new String(in.readNBytes(6), java.nio.charset.StandardCharsets.US_ASCII).equals("SIMPLE");
        }
    }

    private static LoadedImage loadRaster(Path path, int binning) throws IOException {
        BufferedImage image;
        try {
            image = ImageIO.read(path.toFile());
        } catch (RuntimeException | LinkageError e) {
            throw new IOException("Unable to decode " + path + ": " + e, e);
        }
        if (image == null) {
            throw new IOException("Unsupported image format: " + path);
        }
        return new LoadedImage(toGray(image, binning), ImageHints.none());
    }

    /**
     * Converts an image to a single channel, by summing its color channels.
     *
     * @param binning the binning factor, or 0 to select it automatically
     */
    public static GrayImage toGray(BufferedImage image, int binning) {
        var width = image.getWidth();
        var height = image.getHeight();
        if (binning <= 0) {
            binning = 1;
            while ((long) (width / binning) * (height / binning) > AUTO_BINNING_PIXELS) {
                binning++;
            }
        }
        var b = Math.max(1, Math.min(binning, Math.min(width, height) / 16));
        var w = width / b;
        var h = height / b;
        var raster = image.getRaster();
        var bands = raster.getNumBands();
        // transparency is not light
        var colors = image.getColorModel().hasAlpha() ? bands - 1 : bands;
        var out = new float[w * h];
        var tasks = Math.clamp(h / 16, 1, 4 * Runtime.getRuntime().availableProcessors());
        IntStream.range(0, tasks).parallel().forEach(t -> {
            var from = (int) ((long) h * t / tasks);
            var to = (int) ((long) h * (t + 1) / tasks);
            var row = new int[width * bands];
            for (var y = from; y < to; y++) {
                for (var sy = 0; sy < b; sy++) {
                    raster.getPixels(0, y * b + sy, width, 1, row);
                    for (var x = 0; x < w * b; x++) {
                        var sum = 0;
                        for (var c = 0; c < colors; c++) {
                            sum += row[x * bands + c];
                        }
                        out[y * w + x / b] += sum;
                    }
                }
            }
        });
        return new GrayImage(w, h, out, b, width, height);
    }
}
