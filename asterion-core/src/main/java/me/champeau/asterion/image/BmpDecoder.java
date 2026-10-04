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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Decodes uncompressed BMP images: 1, 4 or 8-bit palettes, 16-bit (5-5-5 or bit fields), 24 and
 * 32-bit, stored bottom-up or top-down. Run-length encoded images aren't supported.
 */
final class BmpDecoder {
    private static final int BI_RGB = 0;
    private static final int BI_BITFIELDS = 3;

    private BmpDecoder() {
    }

    static boolean accepts(byte[] header) {
        return header.length >= 2 && header[0] == 'B' && header[1] == 'M';
    }

    static GrayImage decode(byte[] data, int binning) throws IOException {
        try {
            return read(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN), binning);
        } catch (RuntimeException e) {
            throw new IOException("Invalid BMP file: " + e, e);
        }
    }

    private static GrayImage read(ByteBuffer buffer, int binning) throws IOException {
        var pixelOffset = buffer.getInt(10);
        var headerSize = buffer.getInt(14);
        if (headerSize < 40) {
            throw new IOException("Unsupported BMP header");
        }
        var width = buffer.getInt(18);
        var rawHeight = buffer.getInt(22);
        var topDown = rawHeight < 0;
        var height = Math.abs(rawHeight);
        var bitCount = buffer.getShort(28) & 0xFFFF;
        var compression = buffer.getInt(30);
        if (compression != BI_RGB && !(compression == BI_BITFIELDS && (bitCount == 16 || bitCount == 32))) {
            throw new IOException("Unsupported BMP compression " + compression);
        }
        int[] masks;
        if (compression == BI_BITFIELDS) {
            // right after the 40 bytes of the basic header, in all its versions
            var at = 54;
            masks = new int[]{buffer.getInt(at), buffer.getInt(at + 4), buffer.getInt(at + 8)};
        } else if (bitCount == 16) {
            masks = new int[]{0x7C00, 0x03E0, 0x001F};
        } else {
            masks = new int[]{0xFF0000, 0xFF00, 0xFF};
        }
        int[] palette = null;
        if (bitCount <= 8) {
            var colors = buffer.getInt(46);
            if (colors == 0) {
                colors = 1 << bitCount;
            }
            palette = new int[colors];
            var at = 14 + headerSize;
            for (var i = 0; i < colors; i++) {
                var p = at + 4 * i;
                palette[i] = (buffer.get(p) & 0xFF) + (buffer.get(p + 1) & 0xFF) + (buffer.get(p + 2) & 0xFF);
            }
        } else if (bitCount != 16 && bitCount != 24 && bitCount != 32) {
            throw new IOException("Unsupported BMP with " + bitCount + " bits per pixel");
        }
        var stride = (int) (((long) width * bitCount + 31) / 32 * 4);
        if (pixelOffset + (long) stride * height > buffer.limit()) {
            throw new IOException("Truncated BMP file");
        }
        var binner = new GrayBinner(width, height, binning);
        var values = new float[width];
        for (var y = 0; y < height; y++) {
            var row = pixelOffset + (topDown ? y : height - 1 - y) * stride;
            for (var x = 0; x < width; x++) {
                values[x] = switch (bitCount) {
                    case 24 ->
                        (buffer.get(row + 3 * x) & 0xFF) + (buffer.get(row + 3 * x + 1) & 0xFF) + (buffer.get(row + 3 * x + 2) & 0xFF);
                    case 32 -> masked(buffer.getInt(row + 4 * x), masks);
                    case 16 -> masked(buffer.getShort(row + 2 * x) & 0xFFFF, masks);
                    default -> {
                        var perByte = 8 / bitCount;
                        var shift = 8 - bitCount * (x % perByte + 1);
                        var index = ((buffer.get(row + x / perByte) & 0xFF) >>> shift) & ((1 << bitCount) - 1);
                        yield index < palette.length ? palette[index] : 0;
                    }
                };
            }
            binner.row(values);
        }
        return binner.image();
    }

    /** The sum of the color channels of a pixel, each channel being a group of bits. */
    private static int masked(int pixel, int[] masks) {
        var sum = 0;
        for (var mask : masks) {
            if (mask != 0) {
                sum += (pixel & mask) >>> Integer.numberOfTrailingZeros(mask);
            }
        }
        return sum;
    }
}
