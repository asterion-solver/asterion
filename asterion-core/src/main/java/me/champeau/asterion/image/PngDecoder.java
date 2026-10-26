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
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Decodes PNG images: all the color types and bit depths, interlaced or not. Transparency is
 * ignored, and palette images are converted to colors.
 * <p>
 * The rows of an image which isn't interlaced are read in three stages, on three threads, which work on
 * consecutive blocks of rows at the same time: one inflates them, which zlib does, one removes their
 * filters, and the calling thread converts their pixels. The stages take comparable times on large
 * images, so the read takes about as long as the slowest one, instead of all of them.
 */
final class PngDecoder {
    private static final long SIGNATURE = 0x89504E470D0A1A0AL;
    /** The size of the blocks of rows which are inflated at once. */
    private static final int BLOCK_BYTES = 1 << 20;
    /** The number of blocks: one for each stage, and one ready. */
    private static final int BLOCKS = 4;
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
        try (var in = new InflaterInputStream(new ByteArrayInputStream(idat.toByteArray()), new Inflater(), 1 << 16)) {
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

    /** A block of rows: each row starts with its filter byte. A stage which fails passes its failure instead. */
    private record Block(
            byte[] data,
            int rows,
            IOException failure) {
        static Block failed(Exception failure) {
            return new Block(null, 0,
                    failure instanceof IOException e ? e : new IOException("Invalid PNG file: " + failure.getMessage(), failure));
        }
    }

    private void readSequential(InputStream in, GrayBinner binner) throws IOException {
        var stride = rowBytes(width) + 1;
        var rowsPerBlock = Math.max(1, BLOCK_BYTES / stride);
        var blocks = (height + rowsPerBlock - 1) / rowsPerBlock;
        var free = new ArrayBlockingQueue<byte[]>(BLOCKS);
        var inflated = new ArrayBlockingQueue<Block>(BLOCKS);
        var unfiltered = new ArrayBlockingQueue<Block>(BLOCKS);
        for (var i = 0; i < Math.min(BLOCKS, blocks); i++) {
            free.add(new byte[rowsPerBlock * stride]);
        }
        Runnable inflate = () -> inflate(in, stride, rowsPerBlock, free, inflated);
        Runnable unfilter = () -> unfilterBlocks(stride, inflated, unfiltered);
        if (blocks == 1) {
            // a single block: there is nothing to overlap
            inflate.run();
            unfilter.run();
            convertBlocks(stride, unfiltered, free, binner);
            return;
        }
        var inflater = Thread.ofPlatform().name("png-inflater").daemon().start(inflate);
        var unfilterer = Thread.ofPlatform().name("png-unfilter").daemon().start(unfilter);
        try {
            convertBlocks(stride, unfiltered, free, binner);
        } finally {
            // stops the other stages when the conversion fails
            inflater.interrupt();
            unfilterer.interrupt();
        }
    }

    /** Inflates the rows of the image, block after block, until all of them are inflated or one fails. */
    private void inflate(InputStream in, int stride, int rowsPerBlock, BlockingQueue<byte[]> free, BlockingQueue<Block> out) {
        try {
            for (var y = 0; y < height; y += rowsPerBlock) {
                var rows = Math.min(rowsPerBlock, height - y);
                var data = free.take();
                Block block;
                try {
                    if (in.readNBytes(data, 0, rows * stride) != rows * stride) {
                        throw new IOException("Truncated PNG image data");
                    }
                    block = new Block(data, rows, null);
                } catch (IOException | RuntimeException e) {
                    block = Block.failed(e);
                }
                out.put(block);
                if (block.failure() != null) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            // the conversion failed: the rows aren't needed anymore
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Removes the filters of the rows of each block, as they are inflated. The previous row of the first
     * row of a block is the last row of the previous block, which is kept before the block is passed on.
     */
    private void unfilterBlocks(int stride, BlockingQueue<Block> in, BlockingQueue<Block> out) {
        var bytes = stride - 1;
        var previous = new byte[bytes];
        try {
            for (var y = 0; y < height;) {
                var block = in.take();
                if (block.failure() == null) {
                    try {
                        var data = block.data();
                        for (var r = 0; r < block.rows(); r++) {
                            var offset = r * stride + 1;
                            if (r == 0) {
                                unfilter(data[offset - 1], data, offset, bytes, previous, 0);
                            } else {
                                unfilter(data[offset - 1], data, offset, bytes, data, offset - stride);
                            }
                        }
                        System.arraycopy(data, (block.rows() - 1) * stride + 1, previous, 0, bytes);
                    } catch (IOException | RuntimeException e) {
                        block = Block.failed(e);
                    }
                }
                out.put(block);
                if (block.failure() != null) {
                    return;
                }
                y += block.rows();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Converts the pixels of each block, once its filters are removed, then gives its buffer back to the inflater. */
    private void convertBlocks(int stride, BlockingQueue<Block> in, BlockingQueue<byte[]> free, GrayBinner binner) throws IOException {
        var values = new float[width];
        try {
            for (var y = 0; y < height;) {
                var block = in.take();
                if (block.failure() != null) {
                    throw block.failure();
                }
                var data = block.data();
                for (var r = 0; r < block.rows(); r++) {
                    convert(data, r * stride + 1, width, values, 0, 1);
                    binner.row(values);
                }
                y += block.rows();
                free.put(data);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while reading a PNG image");
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
                convert(current, 0, passWidth, values, 0, 1);
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
        unfilter((byte) filter, row, 0, row.length, previous, 0);
    }

    /**
     * Removes the filter of a row, in place, the previous row being already unfiltered. The bytes of
     * the first pixel, which have no left neighbor, are handled first, so that the loops of the other
     * ones have no tests.
     */
    private void unfilter(byte filter, byte[] row, int offset, int length, byte[] previous, int previousOffset) throws IOException {
        var bpp = Math.min(filterStride(), length);
        switch (filter) {
            case 0 -> {
            }
            case 1 -> {
                for (var i = offset + bpp; i < offset + length; i++) {
                    row[i] += row[i - bpp];
                }
            }
            case 2 -> {
                for (var i = 0; i < length; i++) {
                    row[offset + i] += previous[previousOffset + i];
                }
            }
            case 3 -> {
                for (var i = 0; i < bpp; i++) {
                    row[offset + i] += (byte) ((previous[previousOffset + i] & 0xFF) >>> 1);
                }
                for (var i = bpp; i < length; i++) {
                    var left = row[offset + i - bpp] & 0xFF;
                    row[offset + i] += (byte) ((left + (previous[previousOffset + i] & 0xFF)) >>> 1);
                }
            }
            case 4 -> {
                // without left neighbors, the predictor is the byte above
                for (var i = 0; i < bpp; i++) {
                    row[offset + i] += previous[previousOffset + i];
                }
                for (var i = bpp; i < length; i++) {
                    var a = row[offset + i - bpp] & 0xFF;
                    var b = previous[previousOffset + i] & 0xFF;
                    var c = previous[previousOffset + i - bpp] & 0xFF;
                    row[offset + i] += (byte) paeth(a, b, c);
                }
            }
            default -> throw new IOException("Invalid PNG filter " + (filter & 0xFF));
        }
    }

    /**
     * The Paeth predictor: the one of a, b and c which is the closest to a + b - c, with ties broken
     * in that order. Its comparisons aren't short-circuited, which lets the compiler select without jumps.
     */
    private static int paeth(int a, int b, int c) {
        var pa = Math.abs(b - c);
        var pb = Math.abs(a - c);
        var pc = Math.abs(a + b - c - c);
        var bc = pb <= pc ? b : c;
        return (pa <= pb) & (pa <= pc) ? a : bc;
    }

    /** Converts the bytes of a row to the brightness of its pixels: the sum of their color channels. */
    private void convert(byte[] row, int start, int pixels, float[] values, int offset, int step) {
        if (bitDepth < 8) {
            var perByte = 8 / bitDepth;
            var mask = (1 << bitDepth) - 1;
            for (var x = 0; x < pixels; x++) {
                var shift = 8 - bitDepth * (x % perByte + 1);
                var v = ((row[start + x / perByte] & 0xFF) >>> shift) & mask;
                values[offset + x * step] = colorType == PALETTE ? paletteEntry(v) : v;
            }
            return;
        }
        // the color channels come first: the last channel of gray + alpha and RGBA is ignored
        var colors = colorType == GRAY_ALPHA ? 1 : colorType == RGBA ? 3 : channels;
        if (bitDepth == 8) {
            for (var x = 0; x < pixels; x++) {
                var p = start + x * channels;
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
                var p = start + 2 * x * channels;
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
