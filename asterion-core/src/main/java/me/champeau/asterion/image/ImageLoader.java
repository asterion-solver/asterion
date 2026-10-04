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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * Reads images of any supported format: FITS files, including compressed ones, PNG, JPEG, TIFF and
 * BMP. Images are decoded by Asterion itself, without AWT, which native executables don't support
 * on every platform.
 */
public final class ImageLoader {
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
        var data = Files.readAllBytes(path);
        if (data.length == 0) {
            throw new IOException("Empty file: " + path);
        }
        var image = decode(data, binning);
        if (image == null) {
            throw new IOException("Unsupported image format: " + path + ", expected FITS, PNG, JPEG, TIFF or BMP");
        }
        return new LoadedImage(image, ImageHints.none());
    }

    /**
     * Decodes a PNG, JPEG, TIFF or BMP image.
     *
     * @return null if the format isn't one of them
     */
    static GrayImage decode(byte[] data, int binning) throws IOException {
        var header = Arrays.copyOf(data, Math.min(data.length, 16));
        GrayImage image;
        if (PngDecoder.accepts(header)) {
            image = PngDecoder.decode(data, binning);
        } else if (JpegDecoder.accepts(header)) {
            image = JpegDecoder.decode(data, binning);
        } else if (TiffDecoder.accepts(header)) {
            image = TiffDecoder.decode(data, binning);
        } else if (BmpDecoder.accepts(header)) {
            image = BmpDecoder.decode(data, binning);
        } else {
            return null;
        }
        return image;
    }
}
