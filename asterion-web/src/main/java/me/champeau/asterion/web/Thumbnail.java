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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;

/**
 * Renders the thumbnails of the images which browsers can't display, such as FITS files: the
 * image is reduced, stretched like astronomy software displays raw data, and encoded as a
 * grayscale PNG file.
 */
final class Thumbnail {
    /** The pixels whose statistics give the stretch, at most: a sample is as good as all of them. */
    private static final int SAMPLE = 200_000;
    /** Where the median of the image is displayed, in [0, 1]: the background is dark, but visible. */
    private static final double TARGET_MEDIAN = 0.2;
    /** How many deviations under the median is black. */
    private static final double SHADOWS_CLIPPING = 2.8;
    /** The fraction of the brightest pixels which are white. */
    private static final double HIGHLIGHTS_CLIPPING = 0.0005;

    /** FITS files, compressed or not. */
    private static final Pattern FITS_NAME = Pattern.compile("(?i).*\\.(fits?|fts)(\\.(gz|fz|z))?$");
    /** The header blocks which are searched for the order of the rows: the primary header, and the one of compressed images. */
    private static final int HEADER_BYTES = 20 * 2880;

    private Thumbnail() {
    }

    /**
     * @param maxWidth the width of the thumbnail, at most: smaller images keep their width
     * @param bottomUp true to display the first row of the image at the bottom
     * @return the PNG file
     */
    static byte[] png(GrayImage image, int maxWidth, boolean bottomUp) throws IOException {
        var width = Math.min(maxWidth, image.width());
        var height = Math.max(1, (int) Math.round((double) image.height() * width / image.width()));
        var reduced = reduce(image, width, height);
        var pixels = stretch(reduced);
        return encode(pixels, width, height, bottomUp);
    }

    /**
     * Tells if the first row of an image is displayed at the bottom: the convention of FITS files,
     * unless their header says otherwise, as the ROWORDER keyword of N.I.N.A., SharpCap and others,
     * which store the frames of cameras top row first. The rows of other images go down.
     *
     * @param name the name of the file, which tells its format
     */
    static boolean bottomUp(Path file, String name) {
        if (!FITS_NAME.matcher(name).matches()) {
            return false;
        }
        var compressed = name.toLowerCase(Locale.ROOT).matches(".*\\.(gz|z)$");
        try (var raw = Files.newInputStream(file); var in = compressed ? new GZIPInputStream(raw) : raw) {
            var header = new String(in.readNBytes(HEADER_BYTES), StandardCharsets.US_ASCII);
            for (var i = 0; i + 80 <= header.length(); i += 80) {
                var card = header.substring(i, i + 80);
                if (card.startsWith("ROWORDER=")) {
                    return !card.substring(9).contains("TOP-DOWN");
                }
            }
        } catch (IOException e) {
            // an unreadable header: the convention
        }
        return true;
    }

    /** The image, reduced by averaging the pixels of each pixel of the thumbnail. */
    private static float[] reduce(GrayImage image, int width, int height) {
        var out = new float[width * height];
        var data = image.data();
        for (var y = 0; y < height; y++) {
            var y0 = (int) ((long) y * image.height() / height);
            var y1 = Math.max(y0 + 1, (int) ((long) (y + 1) * image.height() / height));
            for (var x = 0; x < width; x++) {
                var x0 = (int) ((long) x * image.width() / width);
                var x1 = Math.max(x0 + 1, (int) ((long) (x + 1) * image.width() / width));
                var sum = 0.0;
                for (var sy = y0; sy < y1; sy++) {
                    var row = sy * image.width();
                    for (var sx = x0; sx < x1; sx++) {
                        sum += data[row + sx];
                    }
                }
                out[y * width + x] = (float) (sum / ((y1 - y0) * (x1 - x0)));
            }
        }
        return out;
    }

    /**
     * The pixels as bytes: below the background noise is black, the brightest pixels are white,
     * and a midtones transfer function puts the median at {@link #TARGET_MEDIAN}, which shows the
     * faint parts of linear data.
     */
    private static byte[] stretch(float[] values) {
        var step = Math.max(1, values.length / SAMPLE);
        var sample = new float[(values.length + step - 1) / step];
        for (var i = 0; i < sample.length; i++) {
            sample[i] = values[i * step];
        }
        Arrays.sort(sample);
        var median = sample[sample.length / 2];
        var deviations = new float[sample.length];
        for (var i = 0; i < sample.length; i++) {
            deviations[i] = Math.abs(sample[i] - median);
        }
        Arrays.sort(deviations);
        var sigma = 1.4826 * deviations[deviations.length / 2];
        var black = Math.max(sample[0], median - SHADOWS_CLIPPING * sigma);
        var white = sample[Math.min(sample.length - 1, (int) (sample.length * (1 - HIGHLIGHTS_CLIPPING)))];
        if (white <= black) {
            white = sample[sample.length - 1];
        }
        var range = white > black ? white - black : 1;
        var m = midtones((median - black) / range, TARGET_MEDIAN);
        var out = new byte[values.length];
        for (var i = 0; i < values.length; i++) {
            var x = Math.clamp((values[i] - black) / range, 0, 1);
            out[i] = (byte) Math.round(255 * transfer(x, m));
        }
        return out;
    }

    /** The midtones balance which displays a value at a target: the inverse of {@link #transfer}. */
    private static double midtones(double value, double target) {
        if (value <= 0 || value >= 1) {
            return 0.5;
        }
        return value * (1 - target) / (value - 2 * target * value + target);
    }

    /** The midtones transfer function of PixInsight: 0, m and 1 are displayed as 0, 0.5 and 1. */
    private static double transfer(double x, double m) {
        if (x <= 0 || x >= 1) {
            return x;
        }
        return (m - 1) * x / ((2 * m - 1) * x - m);
    }

    /** An 8-bit grayscale PNG file, without filtering: the thumbnails are small. */
    private static byte[] encode(byte[] pixels, int width, int height, boolean bottomUp) throws IOException {
        var data = new ByteArrayOutputStream();
        try (var deflate = new DeflaterOutputStream(data)) {
            for (var y = 0; y < height; y++) {
                deflate.write(0);
                deflate.write(pixels, (bottomUp ? height - 1 - y : y) * width, width);
            }
        }
        var out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        var header = new ByteArrayOutputStream();
        writeInt(header, width);
        writeInt(header, height);
        // 8 bits per sample, grayscale, deflate, adaptive filtering, no interlacing
        header.write(new byte[]{8, 0, 0, 0, 0});
        chunk(out, "IHDR", header.toByteArray());
        chunk(out, "IDAT", data.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        writeInt(out, data.length);
        var typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        var crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        writeInt(out, (int) crc.getValue());
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }
}
