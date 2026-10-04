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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.InflaterInputStream;

/**
 * Decodes PNG images: all the color types and bit depths, interlaced or not. Transparency is
 * ignored, and palette images are converted to colors.
 */
final class PngDecoder {
    private static final long SIGNATURE = 0x89504E470D0A1A0AL;
    private static final long MAX_DEFLATE_RATIO = 1032;
    private static final int GRAY = 0;
    private static final int RGB = 2;
    private static final int PALETTE = 3;
    private static final int GRAY_ALPHA = 4;
    private static final int RGBA = 6;
    /** The passes of Adam7 interlacing: first column, first row, column step, row step. */
    private static final int[][] ADAM7 = {
            {0, 0, 8, 8},
            {4, 0, 8, 8},
            {0, 4, 4, 8},
            {2, 0, 4, 4},
            {0, 2, 2, 4},
            {1, 0, 2, 2},
            {0, 1, 1, 2}};

    private final int width;
    private final int height;
    private final int bitDepth;
    private final int colorType;
    private final int channels;
    private final int[] palette;

    private PngDecoder(int width, int height, int bitDepth, int colorType, int[] palette) {
        this.width = width;
        this.height = height;
        this.bitDepth = bitDepth;
        this.colorType = colorType;
        this.channels = switch (colorType) {
            case GRAY, PALETTE -> 1;
            case GRAY_ALPHA -> 2;
            case RGB -> 3;
            default -> 4;
        };
        this.palette = palette;
    }

    static boolean accepts(byte[] header) {
        return header.length >= 8 && ByteBuffer.wrap(header).getLong() == SIGNATURE;
    }

    static GrayImage decode(byte[] data, int binning) throws IOException {
        try {
            return read(ByteBuffer.wrap(data), binning);
        } catch (RuntimeException e) {
            throw new IOException("Invalid PNG file: " + e.getMessage(), e);
        }
    }

    private static GrayImage read(ByteBuffer buffer, int binning) throws IOException {
        if (buffer.getLong() != SIGNATURE) {
            throw new IOException("Not a PNG file");
        }
        var idat = new ByteArrayOutputStream();
        int width = 0;
        int height = 0;
        int bitDepth = 0;
        int colorType = -1;
        var interlaced = false;
        int[] palette = null;
        while (buffer.remaining() >= 12) {
            var length = buffer.getInt();
            var type = new String(new byte[]{buffer.get(), buffer.get(), buffer.get(), buffer.get()}, StandardCharsets.US_ASCII);
            if (length < 0 || length > buffer.remaining() - 4) {
                throw new IOException("Truncated PNG file");
            }
            var start = buffer.position();
            switch (type) {
                case "IHDR" -> {
                    width = buffer.getInt();
                    height = buffer.getInt();
                    bitDepth = buffer.get() & 0xFF;
                    colorType = buffer.get() & 0xFF;
                    var compression = buffer.get();
                    var filter = buffer.get();
                    interlaced = buffer.get() == 1;
                    if (compression != 0 || filter != 0) {
                        throw new IOException("Unsupported PNG compression or filter method");
                    }
                }
                case "PLTE" -> {
                    palette = new int[length / 3];
                    for (var i = 0; i < palette.length; i++) {
                        palette[i] = (buffer.get() & 0xFF) + (buffer.get() & 0xFF) + (buffer.get() & 0xFF);
                    }
                }
                case "IDAT" -> idat.write(buffer.array(), buffer.arrayOffset() + start, length);
                default -> {
                    // ancillary chunks don't change the pixels
                }
            }
            buffer.position(start + length + 4);
            if (type.equals("IEND")) {
                break;
            }
        }
        if (width <= 0 || height <= 0) {
            throw new IOException("Invalid PNG dimensions " + width + "x" + height);
        }
        if (!validDepth(colorType, bitDepth)) {
            throw new IOException("Invalid PNG bit depth " + bitDepth + " for color type " + colorType);
        }
        if (colorType == PALETTE && palette == null) {
            throw new IOException("PNG palette image without palette");
        }
        // Deflate expands data 1032 times at most: larger dimensions are those of a damaged file
        var decoder0 = new PngDecoder(width, height, bitDepth, colorType, palette);
        if (((long) decoder0.rowBytes(width) + 1) * height > MAX_DEFLATE_RATIO * idat.size() + 1024) {
            throw new IOException("Truncated or damaged PNG file: " + width + "x" + height + " pixels in " + idat.size() + " bytes");
        }
        var decoder = decoder0;
        var binner = new GrayBinner(width, height, binning);
        try (var in = new InflaterInputStream(new ByteArrayInputStream(idat.toByteArray()), new java.util.zip.Inflater(), 1 << 16)) {
            if (interlaced) {
                decoder.readInterlaced(in, binner);
            } else {
                decoder.readSequential(in, binner);
            }
        }
        return binner.image();
    }

    private static boolean validDepth(int colorType, int bitDepth) {
        return switch (colorType) {
            case GRAY -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8 || bitDepth == 16;
            case PALETTE -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8;
            case RGB, GRAY_ALPHA, RGBA -> bitDepth == 8 || bitDepth == 16;
            default -> false;
        };
    }

    /** The number of bytes of a row of pixels, without its filter byte. */
    private int rowBytes(int pixels) {
        return (int) (((long) pixels * channels * bitDepth + 7) / 8);
    }

    /** The distance between a byte and the byte of the same channel of the previous pixel. */
    private int filterStride() {
        return Math.max(1, channels * bitDepth / 8);
    }

    private void readSequential(InputStream in, GrayBinner binner) throws IOException {
        var bytes = rowBytes(width);
        var previous = new byte[bytes];
        var current = new byte[bytes];
        var values = new float[width];
        for (var y = 0; y < height; y++) {
            readRow(in, current, previous);
            convert(current, width, values, 0, 1);
            binner.row(values);
            var swap = previous;
            previous = current;
            current = swap;
        }
    }

    private void readInterlaced(InputStream in, GrayBinner binner) throws IOException {
        var image = new float[width * height];
        var values = new float[width];
        for (var pass : ADAM7) {
            var passWidth = (width - pass[0] + pass[2] - 1) / pass[2];
            var passHeight = (height - pass[1] + pass[3] - 1) / pass[3];
            if (passWidth <= 0 || passHeight <= 0) {
                continue;
            }
            var bytes = rowBytes(passWidth);
            var previous = new byte[bytes];
            var current = new byte[bytes];
            for (var py = 0; py < passHeight; py++) {
                readRow(in, current, previous);
                convert(current, passWidth, values, 0, 1);
                var y = pass[1] + py * pass[3];
                for (var px = 0; px < passWidth; px++) {
                    image[y * width + pass[0] + px * pass[2]] = values[px];
                }
                var swap = previous;
                previous = current;
                current = swap;
            }
        }
        var row = new float[width];
        for (var y = 0; y < height; y++) {
            System.arraycopy(image, y * width, row, 0, width);
            binner.row(row);
        }
    }

    /** Reads a row and removes its filter, the previous row being already unfiltered. */
    private void readRow(InputStream in, byte[] row, byte[] previous) throws IOException {
        var filter = in.read();
        if (filter < 0 || in.readNBytes(row, 0, row.length) != row.length) {
            throw new IOException("Truncated PNG image data");
        }
        var bpp = filterStride();
        switch (filter) {
            case 0 -> {
            }
            case 1 -> {
                for (var i = bpp; i < row.length; i++) {
                    row[i] += row[i - bpp];
                }
            }
            case 2 -> {
                for (var i = 0; i < row.length; i++) {
                    row[i] += previous[i];
                }
            }
            case 3 -> {
                for (var i = 0; i < row.length; i++) {
                    var left = i >= bpp ? row[i - bpp] & 0xFF : 0;
                    row[i] += (byte) ((left + (previous[i] & 0xFF)) >>> 1);
                }
            }
            case 4 -> {
                for (var i = 0; i < row.length; i++) {
                    var a = i >= bpp ? row[i - bpp] & 0xFF : 0;
                    var b = previous[i] & 0xFF;
                    var c = i >= bpp ? previous[i - bpp] & 0xFF : 0;
                    row[i] += (byte) paeth(a, b, c);
                }
            }
            default -> throw new IOException("Invalid PNG filter " + filter);
        }
    }

    private static int paeth(int a, int b, int c) {
        var p = a + b - c;
        var pa = Math.abs(p - a);
        var pb = Math.abs(p - b);
        var pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) {
            return a;
        }
        return pb <= pc ? b : c;
    }

    /** Converts the bytes of a row to the brightness of its pixels: the sum of their color channels. */
    private void convert(byte[] row, int pixels, float[] values, int offset, int step) {
        if (bitDepth < 8) {
            var perByte = 8 / bitDepth;
            var mask = (1 << bitDepth) - 1;
            for (var x = 0; x < pixels; x++) {
                var shift = 8 - bitDepth * (x % perByte + 1);
                var v = ((row[x / perByte] & 0xFF) >>> shift) & mask;
                values[offset + x * step] = colorType == PALETTE ? paletteEntry(v) : v;
            }
            return;
        }
        // the color channels come first: the last channel of gray + alpha and RGBA is ignored
        var colors = colorType == GRAY_ALPHA ? 1 : colorType == RGBA ? 3 : channels;
        if (bitDepth == 8) {
            for (var x = 0; x < pixels; x++) {
                var p = x * channels;
                if (colorType == PALETTE) {
                    values[offset + x * step] = paletteEntry(row[p] & 0xFF);
                } else {
                    var sum = 0;
                    for (var c = 0; c < colors; c++) {
                        sum += row[p + c] & 0xFF;
                    }
                    values[offset + x * step] = sum;
                }
            }
        } else {
            for (var x = 0; x < pixels; x++) {
                var p = 2 * x * channels;
                var sum = 0;
                for (var c = 0; c < colors; c++) {
                    sum += ((row[p + 2 * c] & 0xFF) << 8) | (row[p + 2 * c + 1] & 0xFF);
                }
                values[offset + x * step] = sum;
            }
        }
    }

    private int paletteEntry(int index) {
        // like other decoders, an index outside the palette is black
        return index < palette.length ? palette[index] : 0;
    }
}
