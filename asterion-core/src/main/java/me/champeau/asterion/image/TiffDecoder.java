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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Decodes the first image of TIFF files: strips or tiles, uncompressed or compressed with LZW,
 * Deflate or PackBits, with or without predictor, 8, 16 or 32-bit samples, integer or floating
 * point, gray, RGB or palette, interleaved or planar. Extra samples, such as transparency, are
 * ignored.
 */
final class TiffDecoder {
    private static final int NONE = 1;
    private static final int LZW = 5;
    private static final int DEFLATE = 8;
    private static final int ADOBE_DEFLATE = 32946;
    private static final int PACKBITS = 32773;
    private static final int JPEG = 7;
    private static final int WHITE_IS_ZERO = 0;
    private static final int BLACK_IS_ZERO = 1;
    private static final int RGB = 2;
    private static final int PALETTE = 3;
    private static final int YCBCR = 6;
    private static final int UNSIGNED = 1;
    private static final int SIGNED = 2;
    private static final int FLOAT = 3;

    private final ByteBuffer buffer;
    private int width;
    private int height;
    private int bitsPerSample = 1;
    private int samplesPerPixel = 1;
    private int compression = NONE;
    private int photometric = -1;
    private int planar = 1;
    private int predictor = 1;
    private int sampleFormat = UNSIGNED;
    private int rowsPerStrip = Integer.MAX_VALUE;
    private int tileWidth;
    private int tileHeight;
    private long[] offsets;
    private long[] byteCounts;
    private int[] palette;
    private byte[] jpegTables;

    private TiffDecoder(ByteBuffer buffer) {
        this.buffer = buffer;
    }

    static boolean accepts(byte[] header) {
        return header.length >= 4 && (header[0] == 'I' && header[1] == 'I' && header[2] == 42 && header[3] == 0
                || header[0] == 'M' && header[1] == 'M' && header[2] == 0 && header[3] == 42);
    }

    static GrayImage decode(byte[] data, int binning) throws IOException {
        var buffer = ByteBuffer.wrap(data).order(data[0] == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        try {
            var decoder = new TiffDecoder(buffer);
            decoder.readDirectory(buffer.getInt(4) & 0xFFFFFFFFL);
            return decoder.decode(binning);
        } catch (RuntimeException e) {
            throw new IOException("Invalid TIFF file: " + e, e);
        }
    }

    private void readDirectory(long offset) throws IOException {
        if (offset < 8 || offset > buffer.limit() - 2) {
            throw new IOException("Invalid TIFF directory offset");
        }
        var count = buffer.getShort((int) offset) & 0xFFFF;
        for (var i = 0; i < count; i++) {
            var entry = (int) offset + 2 + 12 * i;
            var tag = buffer.getShort(entry) & 0xFFFF;
            var type = buffer.getShort(entry + 2) & 0xFFFF;
            var values = buffer.getInt(entry + 4) & 0xFFFFFFFFL;
            switch (tag) {
                case 256 -> width = (int) value(entry, type, 0);
                case 257 -> height = (int) value(entry, type, 0);
                case 258 -> bitsPerSample = (int) value(entry, type, 0);
                case 259 -> compression = (int) value(entry, type, 0);
                case 262 -> photometric = (int) value(entry, type, 0);
                case 273, 324 -> offsets = values(entry, type, values);
                case 277 -> samplesPerPixel = (int) value(entry, type, 0);
                case 278 -> rowsPerStrip = (int) Math.min(Integer.MAX_VALUE, value(entry, type, 0));
                case 279, 325 -> byteCounts = values(entry, type, values);
                case 284 -> planar = (int) value(entry, type, 0);
                case 317 -> predictor = (int) value(entry, type, 0);
                case 320 -> {
                    var colors = values(entry, type, values);
                    var n = colors.length / 3;
                    palette = new int[n];
                    for (var c = 0; c < n; c++) {
                        // 16-bit colors, scaled to 8 bits like the TIFF reader of the JDK does
                        palette[c] = (int) (colors[c] * 255 / 65535 + colors[n + c] * 255 / 65535 + colors[2 * n + c] * 255 / 65535);
                    }
                }
                case 347 -> {
                    var tables = values(entry, type, values);
                    jpegTables = new byte[tables.length];
                    for (var b = 0; b < tables.length; b++) {
                        jpegTables[b] = (byte) tables[b];
                    }
                }
                case 322 -> tileWidth = (int) value(entry, type, 0);
                case 323 -> tileHeight = (int) value(entry, type, 0);
                case 339 -> sampleFormat = (int) value(entry, type, 0);
                default -> {
                    // other tags don't change the pixels
                }
            }
        }
    }

    private static int typeSize(int type) {
        return switch (type) {
            case 1, 2, 6, 7 -> 1;
            case 3, 8 -> 2;
            case 4, 9, 11 -> 4;
            case 5, 10, 12 -> 8;
            default -> 1;
        };
    }

    /** The offset of the values of an entry: in the entry itself when they fit in 4 bytes. */
    private int valuesOffset(int entry, int type, long count) {
        return count * typeSize(type) <= 4 ? entry + 8 : buffer.getInt(entry + 8);
    }

    private long value(int entry, int type, int index) {
        var count = buffer.getInt(entry + 4) & 0xFFFFFFFFL;
        return read(valuesOffset(entry, type, count), type, index);
    }

    private long[] values(int entry, int type, long count) throws IOException {
        if (count > buffer.limit()) {
            throw new IOException("Invalid TIFF directory entry");
        }
        var offset = valuesOffset(entry, type, count);
        var result = new long[(int) count];
        for (var i = 0; i < count; i++) {
            result[i] = read(offset, type, i);
        }
        return result;
    }

    private long read(int offset, int type, int index) {
        return switch (type) {
            case 1, 7 -> buffer.get(offset + index) & 0xFF;
            case 3 -> buffer.getShort(offset + 2 * index) & 0xFFFF;
            case 8 -> buffer.getShort(offset + 2 * index);
            case 4 -> buffer.getInt(offset + 4 * index) & 0xFFFFFFFFL;
            case 9 -> buffer.getInt(offset + 4 * index);
            case 16 -> buffer.getLong(offset + 8 * index);
            default -> 0;
        };
    }

    private GrayImage decode(int binning) throws IOException {
        if (width <= 0 || height <= 0) {
            throw new IOException("Invalid TIFF dimensions " + width + "x" + height);
        }
        if (bitsPerSample != 8 && bitsPerSample != 16 && bitsPerSample != 32
                || sampleFormat == FLOAT && bitsPerSample != 32) {
            throw new IOException("Unsupported TIFF with " + bitsPerSample + " bits per sample");
        }
        if (compression != NONE && compression != LZW && compression != DEFLATE && compression != ADOBE_DEFLATE && compression != PACKBITS
                && compression != JPEG) {
            throw new IOException("Unsupported TIFF compression " + compression);
        }
        if (photometric == -1) {
            photometric = samplesPerPixel >= 3 ? RGB : BLACK_IS_ZERO;
        }
        var colors = switch (photometric) {
            case WHITE_IS_ZERO, BLACK_IS_ZERO -> 1;
            case RGB -> 3;
            case YCBCR -> {
                if (compression != JPEG) {
                    throw new IOException("Unsupported uncompressed YCbCr TIFF");
                }
                yield 3;
            }
            case PALETTE -> {
                if (palette == null) {
                    throw new IOException("TIFF palette image without palette");
                }
                yield 1;
            }
            default -> throw new IOException("Unsupported TIFF photometric interpretation " + photometric);
        };
        if (samplesPerPixel < colors || offsets == null || byteCounts == null || offsets.length != byteCounts.length) {
            throw new IOException("Invalid TIFF image structure");
        }
        var tiled = tileWidth > 0 && tileHeight > 0;
        var segmentWidth = tiled ? tileWidth : width;
        var segmentHeight = tiled ? tileHeight : Math.min(rowsPerStrip, height);
        var across = tiled ? (width + tileWidth - 1) / tileWidth : 1;
        var down = (height + segmentHeight - 1) / segmentHeight;
        var planes = planar == 2 ? samplesPerPixel : 1;
        if (offsets.length < (long) across * down * planes) {
            throw new IOException("Missing TIFF strips or tiles");
        }
        var compressed = 0L;
        for (var count : byteCounts) {
            compressed += count;
        }
        var maxRatio = switch (compression) {
            case NONE -> 1;
            case PACKBITS -> 64;
            case JPEG -> 64 * 64;
            default -> 4096;
        };
        // larger dimensions than the data can hold are those of a damaged file
        if ((long) width * height * samplesPerPixel * (bitsPerSample / 8) > maxRatio * compressed + 4096 * (long) offsets.length) {
            throw new IOException("Truncated or damaged TIFF file: " + width + "x" + height + " pixels in " + compressed + " bytes");
        }
        var samplesPerSegmentPixel = planar == 2 ? 1 : samplesPerPixel;
        var bytesPerSample = bitsPerSample / 8;
        var segmentRowBytes = segmentWidth * samplesPerSegmentPixel * bytesPerSample;
        var binner = new GrayBinner(width, height, binning);
        var band = new float[segmentHeight * width];
        var row = new float[width];
        for (var by = 0; by < down; by++) {
            java.util.Arrays.fill(band, 0);
            var bandRows = Math.min(segmentHeight, height - by * segmentHeight);
            for (var plane = 0; plane < planes; plane++) {
                if (planar == 2 && plane >= colors) {
                    // extra samples, such as transparency
                    continue;
                }
                for (var bx = 0; bx < across; bx++) {
                    var index = plane * across * down + by * across + bx;
                    if (compression == JPEG) {
                        // the brightness of the pixels, rather than samples
                        addJpegSegment(index, band, bx * segmentWidth, bandRows);
                        continue;
                    }
                    var bytes = segment(index, segmentRowBytes * segmentHeight);
                    unpredict(bytes, segmentWidth, samplesPerSegmentPixel, bytesPerSample, segmentHeight);
                    var x0 = bx * segmentWidth;
                    var columns = Math.min(segmentWidth, width - x0);
                    for (var y = 0; y < bandRows; y++) {
                        for (var x = 0; x < columns; x++) {
                            var pixel = y * segmentRowBytes + x * samplesPerSegmentPixel * bytesPerSample;
                            var sum = 0f;
                            var channels = planar == 2 ? 1 : colors;
                            for (var c = 0; c < channels; c++) {
                                sum += sample(bytes, pixel + c * bytesPerSample);
                            }
                            band[y * width + x0 + x] += sum;
                        }
                    }
                }
            }
            for (var y = 0; y < bandRows; y++) {
                System.arraycopy(band, y * width, row, 0, width);
                if (photometric == PALETTE) {
                    for (var x = 0; x < width; x++) {
                        var i = (int) row[x];
                        row[x] = i >= 0 && i < palette.length ? palette[i] : 0;
                    }
                } else if (photometric == WHITE_IS_ZERO) {
                    var max = sampleFormat == FLOAT ? 1f : (float) ((1L << bitsPerSample) - 1);
                    for (var x = 0; x < width; x++) {
                        row[x] = max - row[x];
                    }
                }
                binner.row(row);
            }
        }
        return binner.image();
    }

    /** Adds the brightness of the pixels of a strip or a tile compressed with JPEG to a band of rows. */
    private void addJpegSegment(int index, float[] band, int x0, int bandRows) throws IOException {
        var offset = offsets[index];
        var count = byteCounts[index];
        if (offset < 0 || count < 0 || offset + count > buffer.limit()) {
            throw new IOException("Truncated TIFF file");
        }
        var data = new byte[(int) count];
        buffer.get((int) offset, data);
        var columns = Math.min(tileWidth > 0 ? tileWidth : width, width - x0);
        var y = new int[1];
        JpegDecoder.decodeEmbedded(jpegTables, data, photometric == RGB, columns, row -> {
            if (y[0] < bandRows) {
                System.arraycopy(row, 0, band, y[0] * width + x0, columns);
            }
            y[0]++;
        });
    }

    /** A strip or a tile, decompressed. */
    private byte[] segment(int index, int size) throws IOException {
        var offset = offsets[index];
        var count = byteCounts[index];
        if (offset < 0 || count < 0 || offset + count > buffer.limit()) {
            throw new IOException("Truncated TIFF file");
        }
        var start = (int) offset;
        var length = (int) count;
        var out = switch (compression) {
            case LZW -> lzw(start, length, size);
            case DEFLATE, ADOBE_DEFLATE -> inflate(start, length, size);
            case PACKBITS -> packBits(start, length, size);
            default -> {
                var bytes = new byte[size];
                buffer.get(start, bytes, 0, Math.min(size, length));
                yield bytes;
            }
        };
        // a short segment is padded with zeros, like the last strip of some writers
        return out.length >= size ? out : java.util.Arrays.copyOf(out, size);
    }

    private float sample(byte[] bytes, int offset) {
        var little = buffer.order() == ByteOrder.LITTLE_ENDIAN;
        return switch (bitsPerSample) {
            case 8 -> sampleFormat == SIGNED ? bytes[offset] : bytes[offset] & 0xFF;
            case 16 -> {
                var v = little ? (bytes[offset] & 0xFF) | (bytes[offset + 1] & 0xFF) << 8
                        : (bytes[offset] & 0xFF) << 8 | (bytes[offset + 1] & 0xFF);
                yield sampleFormat == SIGNED ? (short) v : v;
            }
            default -> {
                var v = little
                        ? (bytes[offset] & 0xFF) | (bytes[offset + 1] & 0xFF) << 8 | (bytes[offset + 2] & 0xFF) << 16
                                | (bytes[offset + 3] & 0xFF) << 24
                        : (bytes[offset] & 0xFF) << 24 | (bytes[offset + 1] & 0xFF) << 16 | (bytes[offset + 2] & 0xFF) << 8
                                | (bytes[offset + 3] & 0xFF);
                yield sampleFormat == FLOAT ? Float.intBitsToFloat(v) : sampleFormat == SIGNED ? v : (float) (v & 0xFFFFFFFFL);
            }
        };
    }

    /** Removes the horizontal differencing of predictors 2 and 3. */
    private void unpredict(byte[] bytes, int pixels, int samples, int bytesPerSample, int rows) throws IOException {
        if (predictor == 1) {
            return;
        }
        var rowBytes = pixels * samples * bytesPerSample;
        var little = buffer.order() == ByteOrder.LITTLE_ENDIAN;
        if (predictor == 2) {
            for (var y = 0; y < rows; y++) {
                var o = y * rowBytes;
                for (var i = samples; i < pixels * samples; i++) {
                    switch (bytesPerSample) {
                        case 1 -> bytes[o + i] += bytes[o + i - samples];
                        case 2 -> {
                            var p = o + 2 * i;
                            var q = p - 2 * samples;
                            var v = (get16(bytes, p, little) + get16(bytes, q, little)) & 0xFFFF;
                            set16(bytes, p, v, little);
                        }
                        default -> {
                            var p = o + 4 * i;
                            var q = p - 4 * samples;
                            set32(bytes, p, get32(bytes, p, little) + get32(bytes, q, little), little);
                        }
                    }
                }
            }
        } else if (predictor == 3) {
            // floating point: the bytes of each row are differenced, then reordered by significance
            var row = new byte[rowBytes];
            for (var y = 0; y < rows; y++) {
                var o = y * rowBytes;
                for (var i = samples; i < rowBytes; i++) {
                    bytes[o + i] += bytes[o + i - samples];
                }
                var count = pixels * samples;
                for (var i = 0; i < count; i++) {
                    for (var b = 0; b < bytesPerSample; b++) {
                        // the most significant byte comes first, whatever the byte order of the file
                        var target = little ? bytesPerSample - 1 - b : b;
                        row[i * bytesPerSample + target] = bytes[o + b * count + i];
                    }
                }
                System.arraycopy(row, 0, bytes, o, rowBytes);
            }
        } else {
            throw new IOException("Unsupported TIFF predictor " + predictor);
        }
    }

    private static int get16(byte[] b, int o, boolean little) {
        return little ? (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8 : (b[o] & 0xFF) << 8 | (b[o + 1] & 0xFF);
    }

    private static void set16(byte[] b, int o, int v, boolean little) {
        b[o] = (byte) (little ? v : v >> 8);
        b[o + 1] = (byte) (little ? v >> 8 : v);
    }

    private static int get32(byte[] b, int o, boolean little) {
        return little ? (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8 | (b[o + 2] & 0xFF) << 16 | (b[o + 3] & 0xFF) << 24
                : (b[o] & 0xFF) << 24 | (b[o + 1] & 0xFF) << 16 | (b[o + 2] & 0xFF) << 8 | (b[o + 3] & 0xFF);
    }

    private static void set32(byte[] b, int o, int v, boolean little) {
        for (var i = 0; i < 4; i++) {
            b[o + i] = (byte) (little ? v >> (8 * i) : v >> (24 - 8 * i));
        }
    }

    private byte[] inflate(int start, int length, int size) throws IOException {
        var inflater = new Inflater();
        try {
            var in = new byte[length];
            buffer.get(start, in);
            inflater.setInput(in);
            var out = new byte[size];
            var n = 0;
            while (n < size && !inflater.finished()) {
                var read = inflater.inflate(out, n, size - n);
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                n += read;
            }
            return out;
        } catch (DataFormatException e) {
            throw new IOException("Invalid TIFF Deflate data: " + e.getMessage(), e);
        } finally {
            inflater.end();
        }
    }

    private byte[] packBits(int start, int length, int size) {
        var out = new ByteArrayOutputStream(size);
        var i = start;
        var end = start + length;
        while (i < end && out.size() < size) {
            var n = buffer.get(i++);
            if (n >= 0) {
                var count = Math.min(n + 1, end - i);
                out.write(buffer.array(), buffer.arrayOffset() + i, count);
                i += count;
            } else if (n != -128 && i < end) {
                var value = buffer.get(i++);
                for (var k = 0; k < 1 - n; k++) {
                    out.write(value);
                }
            }
        }
        return out.toByteArray();
    }

    /** TIFF's LZW: codes of 9 to 12 bits, most significant bit first, with the "early change". */
    private byte[] lzw(int start, int length, int size) throws IOException {
        var out = new byte[size];
        var n = 0;
        var prefix = new int[4096];
        var suffix = new byte[4096];
        for (var i = 0; i < 256; i++) {
            suffix[i] = (byte) i;
        }
        var next = 258;
        var codeLength = 9;
        var previous = -1;
        var bitBuffer = 0L;
        var bits = 0;
        var position = start;
        var end = start + length;
        var stack = new byte[4096];
        while (n < size) {
            while (bits < codeLength) {
                bitBuffer = (bitBuffer << 8) | (position < end ? buffer.get(position) & 0xFF : 0);
                position++;
                bits += 8;
            }
            var code = (int) (bitBuffer >>> (bits - codeLength)) & ((1 << codeLength) - 1);
            bits -= codeLength;
            if (code == 257 || position > end + 2) {
                break;
            }
            if (code == 256) {
                next = 258;
                codeLength = 9;
                previous = -1;
                continue;
            }
            int entry;
            byte first;
            if (code < next) {
                entry = code;
            } else if (code == next && previous >= 0) {
                entry = previous;
            } else {
                throw new IOException("Invalid TIFF LZW data");
            }
            // the string of the entry, written backwards
            var len = 0;
            for (var e = entry;; e = prefix[e]) {
                stack[len++] = suffix[e];
                if (e < 256) {
                    break;
                }
            }
            first = stack[len - 1];
            for (var k = len - 1; k >= 0 && n < size; k--) {
                out[n++] = stack[k];
            }
            if (code == next && n < size) {
                out[n++] = first;
            }
            if (previous >= 0 && next < 4096) {
                prefix[next] = previous;
                suffix[next] = first;
                next++;
            }
            previous = code;
            if (next + 1 >= 1 << codeLength && codeLength < 12) {
                codeLength++;
            }
        }
        return out;
    }
}
