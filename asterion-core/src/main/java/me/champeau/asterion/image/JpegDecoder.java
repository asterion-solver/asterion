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
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

/**
 * Decodes JPEG images: baseline and progressive, 8-bit, gray, YCbCr or RGB, with any chroma
 * subsampling. Arithmetic coding, lossless, 12-bit and CMYK images aren't supported, as with the
 * JPEG reader of the JDK.
 * <p>
 * The pixels are those of the IJG JPEG library, release 6b, which the JDK uses: its integer inverse
 * DCT, its "fancy" upsampling of chroma and its color conversion are translated from C to Java. The
 * brightness of a pixel is the sum of its red, green and blue values.
 * <p>
 * This software is based in part on the work of the Independent JPEG Group. The license of the IJG
 * JPEG library, and the changes made to it, are in {@code third-party/ijg-libjpeg.md}.
 */
final class JpegDecoder {
    private static final int[] ZIGZAG = {
            0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
            12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
            35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
            58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
            // extra entries for corrupt data, like libjpeg
            63, 63, 63, 63, 63, 63, 63, 63, 63, 63, 63, 63, 63, 63, 63, 63};
    /** The output of the IDCT, in [-512, 511], to samples: libjpeg's range limit table. */
    private static final int[] IDCT_LIMIT = new int[1024];
    // the color conversion tables of libjpeg (jdcolor.c)
    private static final int SCALEBITS = 16;
    private static final int[] CR_R = new int[256];
    private static final int[] CB_B = new int[256];
    private static final int[] CR_G = new int[256];
    private static final int[] CB_G = new int[256];

    static {
        for (var i = 0; i < 1024; i++) {
            // 0..127 -> 128..255, 128..511 -> 255, 512..895 -> 0, 896..1023 -> 0..127
            IDCT_LIMIT[i] = i < 128 ? i + 128 : i < 512 ? 255 : i < 896 ? 0 : i - 896;
        }
        for (var i = 0; i < 256; i++) {
            var x = i - 128;
            CR_R[i] = (fix(1.40200) * x + (1 << 15)) >> SCALEBITS;
            CB_B[i] = (fix(1.77200) * x + (1 << 15)) >> SCALEBITS;
            CR_G[i] = -fix(0.71414) * x;
            CB_G[i] = -fix(0.34414) * x + (1 << 15);
        }
    }

    private static int fix(double x) {
        return (int) (x * (1 << SCALEBITS) + 0.5);
    }

    /** Scans with at least this many bytes of data are decoded in parallel lanes, see {@link Lane}; tests lower it. */
    static int parallelThreshold = 1 << 20;
    /** The data of a lane, at least: lanes need thousands of blocks to be worth their probing and handoff; tests lower it. */
    static int minLaneBytes = 1 << 18;
    /** How many blocks the probe of a phase decodes before the phase is believed. */
    private static final int PROBE_BLOCKS = 64;
    /** How many blocks the probes of phases which all survive decode before giving up telling them apart. */
    private static final int MAX_PROBE_BLOCKS = 4096;
    /** A phase, as a position in the MCU, whose tables are those of another phase: probing one is probing the other. */
    private static final int SAME_AS_EARLIER = -1;
    /** How many consecutive block boundaries two lanes must share for a handoff, in MCUs. */
    private static final int HANDOFF_MCUS = 2;
    /** The blocks a lane decodes after reaching the end of the data, for the ones its buffer still holds. */
    private static final int BLOCKS_AFTER_END = 8;

    private final byte[] data;
    /** True when only the brightness of the pixels is needed: the chroma of YCbCr images isn't decoded. */
    private final boolean brightnessOnly;
    /** True when the image is YCbCr and only its luma is decoded, see {@link #brightnessOnly}. */
    private boolean lumaOnly;
    private int position;
    private final int[][] quantTables = new int[4][];
    private final HuffmanTable[] dcTables = new HuffmanTable[4];
    private final HuffmanTable[] acTables = new HuffmanTable[4];
    private Component[] components;
    private int width;
    private int height;
    private boolean progressive;
    private int maxH;
    private int maxV;
    private int mcusX;
    private int mcusY;
    private int restartInterval;
    private boolean jfif;
    private int adobeTransform = -1;
    private boolean scanned;
    private int eobRun;
    /** The reader of the entropy-coded data of the scan which is decoded. */
    private BitReader in;
    // the bit reader of the entropy-coded data

    private JpegDecoder(byte[] data, boolean brightnessOnly) {
        this.data = data;
        this.brightnessOnly = brightnessOnly;
    }

    static boolean accepts(byte[] header) {
        return header.length >= 3 && (header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8 && (header[2] & 0xFF) == 0xFF;
    }

    /**
     * Decodes the brightness of the pixels of an image: the luma of YCbCr images, which is decoded
     * alone, the sum of the components otherwise.
     */
    static GrayImage decode(byte[] data, int binning) throws IOException {
        try {
            var decoder = new JpegDecoder(data, true);
            decoder.readMarkers();
            var binner = new GrayBinner(decoder.width, decoder.height, binning);
            if (decoder.lumaOnly) {
                // the luma is the brightness: the binner reads its samples directly
                decoder.transform();
                var luma = decoder.components[0];
                binner.samples(luma.samples, luma.blocksPerLine * 8);
            } else {
                decoder.decodeRows(decoder.rgb(), binner::row);
            }
            return binner.image();
        } catch (RuntimeException e) {
            throw new IOException("Invalid JPEG file: " + e, e);
        }
    }

    /**
     * Decodes a JPEG stream embedded in another format, such as a strip of a TIFF file.
     *
     * @param tables an abbreviated stream with the tables which the stream refers to, or null
     * @param rgb true if the components are red, green and blue rather than YCbCr
     * @param width the number of pixels of the rows which are given to the consumer
     * @param rows receives the brightness of each row of the image, as long as it has rows
     */
    static void decodeEmbedded(byte[] tables, byte[] data, boolean rgb, int width, RowConsumer rows) throws IOException {
        try {
            var decoder = new JpegDecoder(data, false);
            if (tables != null) {
                var tablesDecoder = new JpegDecoder(tables, false);
                tablesDecoder.readMarkers();
                System.arraycopy(tablesDecoder.quantTables, 0, decoder.quantTables, 0, 4);
                System.arraycopy(tablesDecoder.dcTables, 0, decoder.dcTables, 0, 4);
                System.arraycopy(tablesDecoder.acTables, 0, decoder.acTables, 0, 4);
            }
            decoder.readMarkers();
            if (decoder.components == null) {
                throw new IOException("No image in the JPEG stream");
            }
            var row = new float[width];
            decoder.decodeRows(rgb, values -> {
                System.arraycopy(values, 0, row, 0, Math.min(width, values.length));
                rows.row(row);
            });
        } catch (RuntimeException e) {
            throw new IOException("Invalid JPEG data: " + e, e);
        }
    }

    /** Receives the rows of an image. */
    @FunctionalInterface
    interface RowConsumer {
        void row(float[] values) throws IOException;
    }

    private static final class Component {
        final int id;
        final int h;
        final int v;
        final int quantTable;
        int[] quant;
        int dcTable;
        int acTable;
        int blocksPerLine;
        int blocksPerColumn;
        /**
         * The coefficients of all the blocks, 64 per block, in natural order; a single block when
         * the component is skipped, and none when the blocks were transformed as they were decoded.
         */
        short[] coefficients;
        /** True if the component isn't decoded: its blocks are parsed when a scan interleaves them, and dropped. */
        boolean skipped;
        int predictor;
        /** The size of the component, in samples. */
        int sampledWidth;
        int sampledHeight;
        /** The samples, after the inverse DCT, padded to whole blocks. */
        byte[] samples;

        Component(int id, int h, int v, int quantTable) {
            this.id = id;
            this.h = h;
            this.v = v;
            this.quantTable = quantTable;
        }
    }

    private static final class HuffmanTable {
        /** For codes of up to 9 bits: the value and the length of the code, or 0. */
        final int[] lookup = new int[1 << 9];
        final int[] maxCode = new int[18];
        final int[] valueOffset = new int[17];
        final int[] values;

        HuffmanTable(int[] counts, int[] values) {
            this.values = values;
            var code = 0;
            var k = 0;
            for (var length = 1; length <= 16; length++) {
                valueOffset[length] = k - code;
                for (var i = 0; i < counts[length]; i++) {
                    if (length <= 9) {
                        var shift = 9 - length;
                        for (var j = 0; j < 1 << shift; j++) {
                            lookup[(code << shift) | j] = (values[k] << 8) | length;
                        }
                    }
                    code++;
                    k++;
                }
                maxCode[length] = counts[length] > 0 ? code - 1 : -1;
                code <<= 1;
            }
            maxCode[17] = Integer.MAX_VALUE;
        }
    }

    private int u8() {
        return data[position++] & 0xFF;
    }

    private int u16() {
        return (u8() << 8) | u8();
    }

    private void readMarkers() throws IOException {
        position = 2;
        while (true) {
            var marker = nextMarker();
            switch (marker) {
                case 0xD9 -> {
                    // an abbreviated stream may only contain tables
                    return;
                }
                case 0xC0, 0xC1, 0xC2 -> readFrame(marker == 0xC2);
                case 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF ->
                    throw new IOException("Unsupported JPEG coding (marker 0x" + Integer.toHexString(marker) + ")");
                case 0xC4 -> readHuffmanTables();
                case 0xDB -> readQuantTables();
                case 0xDD -> {
                    u16();
                    restartInterval = u16();
                }
                case 0xDA -> readScan();
                case 0xE0 -> {
                    var end = position + u16();
                    jfif |= end - position >= 5 && data[position] == 'J' && data[position + 1] == 'F' && data[position + 2] == 'I'
                            && data[position + 3] == 'F' && data[position + 4] == 0;
                    position = end;
                }
                case 0xEE -> {
                    var start = position;
                    var length = u16();
                    if (length >= 12 && data[position] == 'A' && data[position + 1] == 'd' && data[position + 2] == 'o'
                            && data[position + 3] == 'b' && data[position + 4] == 'e') {
                        adobeTransform = data[position + 11] & 0xFF;
                    }
                    position = start + length;
                }
                default -> {
                    if (marker >= 0xD0 && marker <= 0xD7 || marker == 0x01) {
                        continue;
                    }
                    // APPn, COM, DNL...: skipped
                    position += u16();
                }
            }
        }
    }

    /** Skips to the next marker, and returns its code. */
    private int nextMarker() throws IOException {
        while (position < data.length - 1) {
            if ((data[position] & 0xFF) == 0xFF) {
                var code = data[position + 1] & 0xFF;
                if (code != 0 && code != 0xFF) {
                    position += 2;
                    return code;
                }
            }
            position++;
        }
        if (scanned) {
            // a missing end of image marker: what was decoded is the image, like libjpeg
            return 0xD9;
        }
        throw new IOException("Truncated JPEG file");
    }

    private void readFrame(boolean progressive) throws IOException {
        if (components != null) {
            throw new IOException("Several frames in a JPEG file");
        }
        var end = position + u16();
        this.progressive = progressive;
        var precision = u8();
        if (precision != 8) {
            throw new IOException("Unsupported JPEG precision: " + precision + " bits");
        }
        height = u16();
        width = u16();
        var count = u8();
        if (width == 0 || height == 0) {
            throw new IOException("Invalid JPEG dimensions " + width + "x" + height);
        }
        if (count != 1 && count != 3) {
            throw new IOException("Unsupported JPEG with " + count + " components (CMYK?)");
        }
        components = new Component[count];
        for (var i = 0; i < count; i++) {
            var id = u8();
            var sampling = u8();
            var c = new Component(id, sampling >> 4, sampling & 15, u8());
            if (c.h < 1 || c.h > 4 || c.v < 1 || c.v > 4 || c.quantTable > 3) {
                throw new IOException("Invalid JPEG component");
            }
            components[i] = c;
            maxH = Math.max(maxH, c.h);
            maxV = Math.max(maxV, c.v);
        }
        mcusX = ceilDiv(width, 8 * maxH);
        mcusY = ceilDiv(height, 8 * maxV);
        var blocks = 0L;
        for (var c : components) {
            blocks += (long) mcusX * c.h * mcusY * c.v;
        }
        // each block takes one bit of data at least: more blocks are those of a damaged file
        if (blocks > 8L * data.length + 1024) {
            throw new IOException("Truncated or damaged JPEG file: " + width + "x" + height + " pixels in " + data.length + " bytes");
        }
        // the luma of YCbCr images is their brightness: the chroma components are skipped when the
        // luma has the full resolution, which is always the case in practice
        lumaOnly = brightnessOnly && count == 3 && !rgb() && components[0].h == maxH && components[0].v == maxV;
        for (var i = 0; i < count; i++) {
            var c = components[i];
            c.sampledWidth = ceilDiv(width * c.h, maxH);
            c.sampledHeight = ceilDiv(height * c.v, maxV);
            c.blocksPerLine = mcusX * c.h;
            c.blocksPerColumn = mcusY * c.v;
            c.skipped = lumaOnly && i > 0;
            if (c.skipped) {
                c.coefficients = new short[64];
            }
        }
        position = end;
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    private void readQuantTables() throws IOException {
        var end = position + u16();
        while (position < end) {
            var spec = u8();
            var table = new int[64];
            for (var i = 0; i < 64; i++) {
                table[ZIGZAG[i]] = (spec >> 4) == 0 ? u8() : u16();
            }
            if ((spec & 15) > 3) {
                throw new IOException("Invalid JPEG quantization table");
            }
            quantTables[spec & 15] = table;
        }
    }

    private void readHuffmanTables() throws IOException {
        var end = position + u16();
        while (position < end) {
            var spec = u8();
            var counts = new int[17];
            var total = 0;
            for (var i = 1; i <= 16; i++) {
                counts[i] = u8();
                total += counts[i];
            }
            if (total > 256 || (spec & 15) > 3) {
                throw new IOException("Invalid JPEG Huffman table");
            }
            var values = new int[total];
            for (var i = 0; i < total; i++) {
                values[i] = u8();
            }
            var table = new HuffmanTable(counts, values);
            if ((spec >> 4) == 0) {
                dcTables[spec & 15] = table;
            } else {
                acTables[spec & 15] = table;
            }
        }
    }

    private void readScan() throws IOException {
        if (components == null) {
            throw new IOException("JPEG scan before the frame");
        }
        u16();
        var count = u8();
        var scanComponents = new Component[count];
        for (var i = 0; i < count; i++) {
            var id = u8();
            var tables = u8();
            Component found = null;
            for (var c : components) {
                if (c.id == id) {
                    found = c;
                }
            }
            if (found == null) {
                throw new IOException("Unknown JPEG component " + id);
            }
            found.dcTable = tables >> 4;
            found.acTable = tables & 15;
            if (found.quant == null) {
                // like libjpeg, the quantization table is the one when the component is first used
                var table = quantTables[found.quantTable];
                if (table == null) {
                    throw new IOException("Missing JPEG quantization table");
                }
                found.quant = table.clone();
            }
            scanComponents[i] = found;
        }
        var ss = u8();
        var se = u8();
        var approximation = u8();
        var ah = approximation >> 4;
        var al = approximation & 15;
        if (!progressive) {
            ss = 0;
            se = 63;
            ah = 0;
            al = 0;
        }
        scanned = true;
        var needed = false;
        for (var c : scanComponents) {
            needed |= !c.skipped;
        }
        if (needed) {
            decodeScan(scanComponents, ss, se, ah, al);
        } else {
            skipScan();
        }
    }

    /** Skips the entropy-coded data of a scan which only has skipped components, up to the next marker. */
    private void skipScan() throws IOException {
        while (true) {
            var marker = nextMarker();
            if (marker < 0xD0 || marker > 0xD7) {
                // the marker is read again by the caller
                position -= 2;
                return;
            }
        }
    }

    private void decodeScan(Component[] scan, int ss, int se, int ah, int al) throws IOException {
        in = new BitReader(data, position);
        eobRun = 0;
        for (var c : scan) {
            c.predictor = 0;
        }
        var single = scan.length == 1;
        int totalMcus;
        int unitsPerLine;
        if (single) {
            // non-interleaved scans cover the blocks of the component, not whole MCUs
            var c = scan[0];
            unitsPerLine = ceilDiv(c.sampledWidth, 8);
            totalMcus = unitsPerLine * ceilDiv(c.sampledHeight, 8);
        } else {
            unitsPerLine = mcusX;
            totalMcus = mcusX * mcusY;
        }
        if (!progressive && restartInterval == 0 && data.length - position >= parallelThreshold
                && ForkJoinPool.getCommonPoolParallelism() > 1
                && decodeInLanes(scan, unitsPerLine, totalMcus)) {
            return;
        }
        for (var c : scan) {
            if (c.coefficients == null) {
                c.coefficients = new short[c.blocksPerLine * c.blocksPerColumn * 64];
            }
        }
        var restarts = restartInterval;
        var untilRestart = restarts;
        // the row and the column of the unit, counted rather than divided for each of them
        var mcuRow = 0;
        var mcuCol = 0;
        for (var mcu = 0; mcu < totalMcus; mcu++) {
            if (restarts > 0 && mcu > 0 && --untilRestart == 0) {
                restart(scan);
                untilRestart = restarts;
            }
            if (mcuCol == unitsPerLine) {
                mcuCol = 0;
                mcuRow++;
            }
            if (single) {
                var c = scan[0];
                decodeBlock(c, (mcuRow * c.blocksPerLine + mcuCol) * 64, ss, se, ah, al);
            } else {
                for (var c : scan) {
                    for (var v = 0; v < c.v; v++) {
                        for (var h = 0; h < c.h; h++) {
                            var blockRow = mcuRow * c.v + v;
                            var blockCol = mcuCol * c.h + h;
                            // a skipped component is parsed into its single block: what it decodes
                            // doesn't depend on the coefficients, in baseline and DC scans
                            decodeBlock(c, c.skipped ? 0 : (blockRow * c.blocksPerLine + blockCol) * 64, ss, se, ah, al);
                        }
                    }
                }
            }
            mcuCol++;
        }
        // the entropy-coded data ends at the next marker: when the bit reader found it, it's read again
        position = in.markerFound ? in.position - 2 : in.position;
    }

    /** Moves to the data which follows a restart marker, with a fresh state. */
    private void restart(Component[] scan) {
        in.restart();
        eobRun = 0;
        for (var c : scan) {
            c.predictor = 0;
        }
    }

    private void decodeBlock(Component c, int offset, int ss, int se, int ah, int al) throws IOException {
        if (!progressive) {
            decodeBaseline(c, offset);
        } else if (ss == 0) {
            if (ah == 0) {
                var diff = decodeDc(c);
                c.predictor += diff;
                c.coefficients[offset] = (short) (c.predictor << al);
            } else if (in.bit() != 0) {
                c.coefficients[offset] |= (short) (1 << al);
            }
        } else if (ah == 0) {
            decodeAcFirst(c, offset, ss, se, al);
        } else {
            decodeAcRefine(c, offset, ss, se, al);
        }
    }

    private int decodeDc(Component c) throws IOException {
        var table = dcTables[c.dcTable];
        if (table == null) {
            throw new IOException("Missing JPEG Huffman table");
        }
        return decodeDc(in, table);
    }

    /** @return the difference between the DC coefficient of a block and the one of the previous block */
    private static int decodeDc(BitReader in, HuffmanTable table) {
        var s = in.decodeHuffman(table);
        return s == 0 ? 0 : extend(in.bits(s), s);
    }

    private void decodeBaseline(Component c, int offset) throws IOException {
        var dc = dcTables[c.dcTable];
        var ac = acTables[c.acTable];
        if (dc == null || ac == null) {
            throw new IOException("Missing JPEG Huffman table");
        }
        c.predictor += decodeBaseline(in, dc, ac, c.coefficients, offset);
        c.coefficients[offset] = (short) c.predictor;
    }

    /**
     * Decodes the AC coefficients of a block of a baseline scan, and returns the difference of
     * its DC coefficient with the one of the previous block of its component.
     */
    private static int decodeBaseline(BitReader in, HuffmanTable dc, HuffmanTable ac, short[] coefficients, int offset) {
        var diff = decodeDc(in, dc);
        for (var k = 1; k < 64; k++) {
            var rs = in.decodeHuffman(ac);
            var r = rs >> 4;
            var s = rs & 15;
            if (s == 0) {
                if (r != 15) {
                    break;
                }
                k += 15;
                continue;
            }
            k += r;
            if (k > 63) {
                // a run past the end of the block: tolerated like libjpeg, but not what a right decoding gives
                in.corrupt = true;
            }
            coefficients[offset + ZIGZAG[k]] = (short) extend(in.bits(s), s);
        }
        return diff;
    }

    private void decodeAcFirst(Component c, int offset, int ss, int se, int al) throws IOException {
        if (eobRun > 0) {
            eobRun--;
            return;
        }
        var table = acTables[c.acTable];
        for (var k = ss; k <= se; k++) {
            var rs = in.decodeHuffman(table);
            var r = rs >> 4;
            var s = rs & 15;
            if (s == 0) {
                if (r < 15) {
                    eobRun = (1 << r) - 1;
                    if (r > 0) {
                        eobRun += in.bits(r);
                    }
                    break;
                }
                k += 15;
                continue;
            }
            k += r;
            c.coefficients[offset + ZIGZAG[k]] = (short) (extend(in.bits(s), s) * (1 << al));
        }
    }

    private void decodeAcRefine(Component c, int offset, int ss, int se, int al) throws IOException {
        var p1 = 1 << al;
        var m1 = -1 << al;
        var coefficients = c.coefficients;
        var table = acTables[c.acTable];
        var k = ss;
        if (eobRun == 0) {
            for (; k <= se; k++) {
                var rs = in.decodeHuffman(table);
                var r = rs >> 4;
                var s = rs & 15;
                if (s != 0) {
                    s = in.bit() != 0 ? p1 : m1;
                } else if (r != 15) {
                    eobRun = 1 << r;
                    if (r > 0) {
                        eobRun += in.bits(r);
                    }
                    break;
                }
                do {
                    var index = offset + ZIGZAG[k];
                    if (coefficients[index] != 0) {
                        if (in.bit() != 0 && (coefficients[index] & p1) == 0) {
                            coefficients[index] += (short) (coefficients[index] >= 0 ? p1 : m1);
                        }
                    } else {
                        if (--r < 0) {
                            break;
                        }
                    }
                    k++;
                } while (k <= se);
                if (s != 0 && k <= 63) {
                    coefficients[offset + ZIGZAG[k]] = (short) s;
                }
            }
        }
        if (eobRun > 0) {
            for (; k <= se; k++) {
                var index = offset + ZIGZAG[k];
                if (coefficients[index] != 0 && in.bit() != 0 && (coefficients[index] & p1) == 0) {
                    coefficients[index] += (short) (coefficients[index] >= 0 ? p1 : m1);
                }
            }
            eobRun--;
        }
    }

    private static int extend(int v, int s) {
        return v < 1 << (s - 1) ? v - (1 << s) + 1 : v;
    }

    /**
     * Reads the bits of the entropy-coded data of a scan: the bytes of stuffing which follow the
     * 0xFF bytes of the data are removed, and the data ends at a marker, which zeros follow, like
     * libjpeg. The bits which were read and not consumed yet are kept in a word, the first one in
     * the most significant bit.
     */
    static final class BitReader {
        private static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);
        private final byte[] data;
        int position;
        private long bitBuffer;
        private int bitCount;
        boolean markerFound;
        /**
         * True when a code which isn't in its table was read, or a run of coefficients went past the
         * end of a block: what the data of a scan read from the wrong position gives, see {@link Lane}.
         */
        boolean corrupt;

        BitReader(byte[] data, int position) {
            this.data = data;
            this.position = position;
        }

        /**
         * The position of the next bit, counted from the start of the data, bytes of stuffing
         * included: a position identifies a place in the data, whatever was read before.
         */
        long bitPosition() {
            return (long) position * 8 - bitCount;
        }

        /** Moves to the data which follows a restart marker, with a fresh state. */
        void restart() {
            if (markerFound) {
                // the reader stopped right after the marker
                markerFound = false;
            } else {
                // the padding bits before the marker
                while (position < data.length - 1 && !((data[position] & 0xFF) == 0xFF && (data[position + 1] & 0xFF) >= 0xD0
                        && (data[position + 1] & 0xFF) <= 0xD7)) {
                    position++;
                }
                position += 2;
            }
            bitBuffer = 0;
            bitCount = 0;
        }

        /** Fills the buffer to at least 57 bits. */
        private void fill() {
            if (!markerFound && position + 8 <= data.length) {
                // the next 8 bytes, when none is 0xFF, are data: whole bytes fill the buffer at once
                var v = (long) LONG.get(data, position);
                var x = ~v;
                if (((x - 0x0101010101010101L) & ~x & 0x8080808080808080L) == 0) {
                    var bytes = (64 - bitCount) >>> 3;
                    bitBuffer |= (v & (-1L << (64 - 8 * bytes))) >>> bitCount;
                    bitCount += 8 * bytes;
                    position += bytes;
                    return;
                }
            }
            while (bitCount <= 56) {
                var b = 0;
                if (!markerFound && position < data.length) {
                    b = data[position++] & 0xFF;
                    if (b == 0xFF) {
                        var next = position < data.length ? data[position] & 0xFF : 0xD9;
                        if (next == 0) {
                            position++;
                        } else {
                            while (next == 0xFF && position < data.length - 1) {
                                next = data[++position] & 0xFF;
                            }
                            position++;
                            markerFound = true;
                            b = 0;
                        }
                    }
                }
                bitBuffer |= (long) b << (56 - bitCount);
                bitCount += 8;
            }
        }

        int bit() {
            return bits(1);
        }

        int bits(int n) {
            if (n == 0) {
                return 0;
            }
            if (bitCount < n) {
                fill();
            }
            var v = (int) (bitBuffer >>> (64 - n));
            bitBuffer <<= n;
            bitCount -= n;
            return v;
        }

        int decodeHuffman(HuffmanTable table) {
            if (bitCount < 16) {
                fill();
            }
            var entry = table.lookup[(int) (bitBuffer >>> (64 - 9))];
            if (entry != 0) {
                var length = entry & 0xFF;
                bitBuffer <<= length;
                bitCount -= length;
                return entry >> 8;
            }
            // longer codes: compared with the largest code of each length, like libjpeg
            for (var length = 10; length <= 16; length++) {
                var code = (int) (bitBuffer >>> (64 - length));
                if (code <= table.maxCode[length]) {
                    bitBuffer <<= length;
                    bitCount -= length;
                    return table.values[table.valueOffset[length] + code];
                }
            }
            // a corrupt code: libjpeg returns 0 too
            corrupt = true;
            return 0;
        }
    }

    /**
     * A part of the entropy-coded data of a baseline scan, decoded on its own: the data has no
     * restart markers, so no part but the first starts at a known place. A lane starts at a byte
     * of its choosing and decodes blocks from there: a Huffman code resynchronizes within a few
     * blocks, and the component of the first block, which selects the tables, is found by trying
     * each one, as the wrong tables give corrupt blocks almost at once. The lane then decodes its
     * part, and goes on into the next part until its block boundaries coincide with the ones found
     * by the next lane, which is where the next lane takes over. The DC coefficients, which are
     * coded as differences, are kept as such until the lanes are put together, when the ones of
     * the previous lanes are known.
     */
    private static final class Lane {
        final BitReader in;
        /** The part of the data: the lane starts at {@code start}, and decodes beyond {@code end} until a handoff. */
        final int start;
        final int end;
        /** The component of the first block, as the position in the pattern of blocks of an MCU, as found by probing. */
        int phase;
        /** The coefficients of the decoded blocks, 64 per block, the DC one being the difference with the previous block. */
        short[] coefficients;
        /** Where each block starts, see {@link BitReader#bitPosition()}. */
        long[] starts;
        int count;
        /** The sums of the DC differences of the decoded blocks, by the position in the MCU the lane assumes. */
        final long[] sums;
        /** The blocks the lane contributes: from the handoff of the previous lane to the one of the next. */
        int validFrom;
        int validTo;
        /** The index of the first valid block, in the scan. */
        long base;
        /** True when no phase decodes the data: the previous lane decodes this part. */
        boolean dead;

        /** The blocks the buffers are made for, when they are made: on the thread of the lane. */
        private final int capacity;

        Lane(byte[] data, int start, int end, int capacity, int period) {
            this.in = new BitReader(data, start);
            this.start = start;
            this.end = end;
            this.capacity = capacity;
            sums = new long[period];
        }

        private void allocate() {
            coefficients = new short[capacity * 64];
            starts = new long[capacity];
        }

        private void grow() {
            var capacity = Math.max(1024, count * 2);
            coefficients = Arrays.copyOf(coefficients, capacity * 64);
            starts = Arrays.copyOf(starts, capacity);
        }
    }

    /**
     * Decodes the blocks of a baseline scan in parallel lanes, see {@link Lane}.
     *
     * @return false if the data couldn't be decoded in lanes: it is decoded sequentially
     */
    private boolean decodeInLanes(Component[] scan, int unitsPerLine, int totalMcus) throws IOException {
        // the blocks of an MCU, in order: the component, and the position of the block in it
        var single = scan.length == 1;
        var blocksPerMcu = 0;
        for (var c : scan) {
            blocksPerMcu += single ? 1 : c.h * c.v;
            if (dcTables[c.dcTable] == null || acTables[c.acTable] == null) {
                throw new IOException("Missing JPEG Huffman table");
            }
        }
        var period = blocksPerMcu;
        var pattern = new int[period];
        var patternH = new int[period];
        var patternV = new int[period];
        var q = 0;
        for (var k = 0; k < scan.length; k++) {
            var c = scan[k];
            for (var v = 0; v < (single ? 1 : c.v); v++) {
                for (var h = 0; h < (single ? 1 : c.h); h++) {
                    pattern[q] = k;
                    patternH[q] = h;
                    patternV[q] = v;
                    q++;
                }
            }
        }
        var totalBlocks = (long) totalMcus * period;
        var length = data.length - position;
        var laneCount = Math.min(ForkJoinPool.getCommonPoolParallelism() + 1, length / minLaneBytes);
        if (laneCount < 2) {
            return false;
        }
        var lanes = new Lane[laneCount];
        // the blocks of a lane, about: the ones of its share of the data, and the blocks of the handoff
        var estimated = (int) Math.min(Integer.MAX_VALUE / 64, totalBlocks / laneCount + totalBlocks / (8 * laneCount) + 1024);
        for (var i = 0; i < laneCount; i++) {
            var start = position + (int) ((long) length * i / laneCount);
            var end = position + (int) ((long) length * (i + 1) / laneCount);
            lanes[i] = new Lane(data, start, end, estimated, period);
        }
        var dc = new HuffmanTable[period];
        var ac = new HuffmanTable[period];
        for (var t = 0; t < period; t++) {
            dc[t] = dcTables[scan[pattern[t]].dcTable];
            ac[t] = acTables[scan[pattern[t]].acTable];
        }
        // the phases whose tables are the ones of an earlier phase, in the same order, decode the
        // same blocks: probing one is probing the other, and no probing is needed when all do
        var phases = new int[period];
        for (var phase = 0; phase < period; phase++) {
            for (var earlier = 0; earlier < phase && phases[phase] != SAME_AS_EARLIER; earlier++) {
                var same = phases[earlier] != SAME_AS_EARLIER;
                for (var t = 0; t < period && same; t++) {
                    same = dc[(phase + t) % period] == dc[(earlier + t) % period] && ac[(phase + t) % period] == ac[(earlier + t) % period];
                }
                if (same) {
                    phases[phase] = SAME_AS_EARLIER;
                }
            }
        }
        // each lane decodes its part, then goes on until the handoff to the next lane
        IntStream.range(0, laneCount).parallel().forEach(i -> probe(lanes[i], i == 0, phases, dc, ac, period, totalBlocks));
        IntStream.range(0, laneCount).parallel().forEach(i -> decodePart(lanes[i], dc, ac, period, totalBlocks));
        IntStream.range(0, laneCount).parallel().forEach(i -> handOff(lanes, i, dc, ac, period, totalBlocks));
        // the blocks of the lanes, put together: the last lanes may have been decoded by a previous one
        var blocks = 0L;
        for (var lane : lanes) {
            if (lane.dead || lane.validTo < 0) {
                lane.validFrom = 0;
                lane.validTo = 0;
            }
            lane.base = blocks;
            blocks += lane.validTo - lane.validFrom;
        }
        if (blocks < totalBlocks) {
            // damaged or truncated data: decoded sequentially, which handles it like libjpeg
            return false;
        }
        // the DC coefficients are coded as differences: the ones of the blocks of each lane are
        // summed by component, which gives the predictor each lane starts with. A lane summed the
        // blocks it decoded by the position in the MCU it assumed: the blocks it doesn't
        // contribute are taken out, and the positions are mapped to the true ones
        var sums = new int[laneCount][scan.length];
        for (var i = 0; i < laneCount; i++) {
            var lane = lanes[i];
            if (lane.validTo == lane.validFrom) {
                continue;
            }
            var byPhase = lane.sums.clone();
            for (var b = 0; b < lane.validFrom; b++) {
                byPhase[(lane.phase + b) % period] -= lane.coefficients[b * 64];
            }
            for (var b = lane.validTo; b < lane.count; b++) {
                byPhase[(lane.phase + b) % period] -= lane.coefficients[b * 64];
            }
            var shift = (int) Math.floorMod(lane.base - lane.validFrom - lane.phase, (long) period);
            for (var t = 0; t < period; t++) {
                sums[i][pattern[(t + shift) % period]] += (int) byPhase[t];
            }
        }
        for (var i = 1; i < laneCount; i++) {
            for (var k = 0; k < scan.length; k++) {
                sums[i][k] += sums[i - 1][k];
            }
        }
        // the blocks are transformed into samples from the buffers of the lanes, with their DC
        // coefficients made whole: the coefficients of the components are never stored
        for (var c : scan) {
            if (!c.skipped) {
                c.samples = new byte[c.blocksPerLine * 8 * c.blocksPerColumn * 8];
                c.coefficients = null;
            }
        }
        IntStream.range(0, laneCount).parallel().forEach(i -> {
            var lane = lanes[i];
            var predictors = i == 0 ? new int[scan.length] : sums[i - 1].clone();
            var kept = Math.min(lane.validTo - lane.validFrom, totalBlocks - lane.base);
            var phase = (int) (lane.base % period);
            var mcu = (int) (lane.base / period);
            var mcuRow = mcu / unitsPerLine;
            var mcuCol = mcu % unitsPerLine;
            var workspace = new int[64];
            for (var b = lane.validFrom; b < lane.validFrom + kept; b++) {
                var k = pattern[phase];
                var c = scan[k];
                predictors[k] += lane.coefficients[b * 64];
                if (!c.skipped) {
                    var blockRow = single ? mcuRow : mcuRow * c.v + patternV[phase];
                    var blockCol = single ? mcuCol : mcuCol * c.h + patternH[phase];
                    var stride = c.blocksPerLine * 8;
                    lane.coefficients[b * 64] = (short) predictors[k];
                    idct(lane.coefficients, b * 64, c.quant, workspace, c.samples, blockRow * 8 * stride + blockCol * 8, stride);
                }
                if (++phase == period) {
                    phase = 0;
                    if (++mcuCol == unitsPerLine) {
                        mcuCol = 0;
                        mcuRow++;
                    }
                }
            }
        });
        for (var c : scan) {
            c.predictor = 0;
        }
        // the data ends where the last lane which decoded stopped
        var last = lanes[0];
        for (var lane : lanes) {
            if (lane.validTo > lane.validFrom) {
                last = lane;
            }
        }
        position = last.in.markerFound ? last.in.position - 2 : last.in.position;
        return true;
    }

    /** Decodes a block, and returns false when it's corrupt or past the end of the data for a while. */
    private static boolean decodeBlock(Lane lane, HuffmanTable[] dc, HuffmanTable[] ac, int period, long totalBlocks) {
        if (lane.count == lane.starts.length) {
            lane.grow();
        }
        var q = (lane.phase + lane.count) % period;
        lane.starts[lane.count] = lane.in.bitPosition();
        var offset = lane.count * 64;
        var diff = decodeBaseline(lane.in, dc[q], ac[q], lane.coefficients, offset);
        lane.coefficients[offset] = (short) diff;
        lane.sums[q] += diff;
        lane.count++;
        return !lane.in.corrupt && lane.count <= totalBlocks + period;
    }

    /** Finds the phase of a lane: the position in the MCU of the block its data starts with. */
    private static void probe(Lane lane, boolean first, int[] phases, HuffmanTable[] dc, HuffmanTable[] ac, int period, long totalBlocks) {
        lane.allocate();
        if (first) {
            return;
        }
        var probes = new Lane[period];
        var alive = 0;
        for (var phase = 0; phase < period; phase++) {
            if (phases[phase] != SAME_AS_EARLIER) {
                probes[phase] = new Lane(lane.in.data, lane.start, lane.end, MAX_PROBE_BLOCKS + 1, period);
                probes[phase].phase = phase;
                probes[phase].allocate();
                alive++;
            }
        }
        // the phases are tried a few blocks at a time, until one is left: with the wrong tables,
        // the data is corrupt almost at once, but a smooth image has few codes which can be wrong
        for (var blocks = PROBE_BLOCKS; alive > 1 && blocks <= MAX_PROBE_BLOCKS; blocks *= 4) {
            for (var phase = 0; phase < period; phase++) {
                var probe = probes[phase];
                if (probe == null) {
                    continue;
                }
                var ok = true;
                while (ok && probe.count < blocks && !probe.in.markerFound) {
                    ok = decodeBlock(probe, dc, ac, period, totalBlocks);
                }
                if (!ok) {
                    probes[phase] = null;
                    alive--;
                }
            }
        }
        if (alive == 1) {
            // the single survivor is the phase, probed or not: the only one when all share their tables
            for (var phase = 0; phase < period; phase++) {
                if (probes[phase] != null) {
                    lane.phase = phase;
                    return;
                }
            }
        }
        for (var phase = 0; phase < period; phase++) {
            if (probes[phase] != null) {
                var probe = probes[phase];
                var ok = true;
                while (ok && probe.count < PROBE_BLOCKS && !probe.in.markerFound) {
                    ok = decodeBlock(probe, dc, ac, period, totalBlocks);
                }
                if (ok) {
                    lane.phase = phase;
                    return;
                }
            }
        }
        lane.dead = true;
    }

    /** Decodes the part of a lane, up to the block which crosses its end. */
    private static void decodePart(Lane lane, HuffmanTable[] dc, HuffmanTable[] ac, int period, long totalBlocks) {
        if (lane.dead) {
            return;
        }
        var afterEnd = 0;
        while (lane.in.bitPosition() < (long) lane.end * 8) {
            if (!decodeBlock(lane, dc, ac, period, totalBlocks)) {
                if (lane.in.markerFound) {
                    // the zeros which follow the end of the data: the blocks are over
                    break;
                }
                // the lane went wrong: what it decoded is dropped, the previous lane decodes its part
                lane.dead = true;
                return;
            }
            if (lane.in.markerFound && ++afterEnd > BLOCKS_AFTER_END) {
                break;
            }
        }
        lane.validTo = lane.in.markerFound ? lane.count : -1;
    }

    /**
     * Goes on decoding after the end of the part of a lane, until its block boundaries coincide
     * with the ones of a next lane, which takes over from there, or until the end of the data.
     */
    private static void handOff(Lane[] lanes, int i, HuffmanTable[] dc, HuffmanTable[] ac, int period, long totalBlocks) {
        var lane = lanes[i];
        if (lane.dead || lane.validTo >= 0) {
            return;
        }
        var required = HANDOFF_MCUS * period;
        var afterEnd = 0;
        var n = i + 1;
        var j = 0;
        var run = 0;
        while (true) {
            if (lane.in.markerFound && ++afterEnd > BLOCKS_AFTER_END) {
                // the end of the data: this lane decoded everything which was left
                lane.validTo = lane.count;
                return;
            }
            if (!decodeBlock(lane, dc, ac, period, totalBlocks)) {
                if (lane.in.markerFound) {
                    lane.validTo = lane.count;
                } else {
                    lane.dead = true;
                }
                return;
            }
            var at = lane.starts[lane.count - 1];
            while (n < lanes.length) {
                var next = lanes[n];
                if (next.dead) {
                    n++;
                    j = 0;
                    run = 0;
                    continue;
                }
                var from = j;
                while (j < next.count && next.starts[j] < at) {
                    j++;
                }
                if (j == next.count) {
                    // past all the blocks of the next lane: it is dropped, and the lane goes on
                    next.dead = true;
                    n++;
                    j = 0;
                    run = 0;
                    continue;
                }
                if (j != from) {
                    // blocks of the next lane which this lane doesn't have: the boundaries which
                    // coincide must be consecutive in both lanes, a boundary coincides by chance
                    run = 0;
                }
                if (next.starts[j] == at) {
                    if (++run == required) {
                        next.validFrom = j - required + 1;
                        lane.validTo = lane.count - required;
                        return;
                    }
                    j++;
                } else {
                    run = 0;
                }
                break;
            }
        }
    }

    /** Tells if the components are red, green and blue, like libjpeg guesses it from the markers. */
    private boolean rgb() {
        return components.length == 3 && !jfif
                && (adobeTransform == 0
                        || adobeTransform < 0 && components[0].id == 'R' && components[1].id == 'G' && components[2].id == 'B');
    }

    /** Transforms the coefficients of the components into samples. */
    private void transform() throws IOException {
        if (components == null) {
            throw new IOException("No image in the JPEG file");
        }
        for (var c : components) {
            if (c.quant == null || c.coefficients == null && c.samples == null) {
                throw new IOException("JPEG component without data");
            }
            if (!c.skipped && c.samples == null) {
                inverseDct(c);
            }
        }
    }

    private void decodeRows(boolean rgb, RowConsumer rows) throws IOException {
        transform();
        // rows are converted in parallel, by bands, and given to the consumer in order
        var band = new float[Math.min(height, ROWS_PER_BAND)][width];
        for (var first = 0; first < height; first += ROWS_PER_BAND) {
            var y0 = first;
            var count = Math.min(ROWS_PER_BAND, height - first);
            IntStream.range(0, ceilDiv(count, ROWS_PER_TASK)).parallel().forEach(task -> {
                var upsampled = new int[components.length][width];
                var to = Math.min(count, (task + 1) * ROWS_PER_TASK);
                for (var r = task * ROWS_PER_TASK; r < to; r++) {
                    convertRow(y0 + r, rgb, upsampled, band[r]);
                }
            });
            for (var r = 0; r < count; r++) {
                rows.row(band[r]);
            }
        }
    }

    private static final int ROWS_PER_BAND = 256;
    private static final int ROWS_PER_TASK = 16;

    /**
     * Computes the brightness of the pixels of a row: their luma when only the luma was decoded,
     * the sum of their red, green and blue values otherwise.
     */
    private void convertRow(int y, boolean rgb, int[][] upsampled, float[] values) {
        if (lumaOnly) {
            var luma = components[0];
            var row = y * luma.blocksPerLine * 8;
            for (var x = 0; x < width; x++) {
                values[x] = luma.samples[row + x] & 0xFF;
            }
            return;
        }
        for (var i = 0; i < components.length; i++) {
            upsample(components[i], y, upsampled[i]);
        }
        if (components.length == 1) {
            var gray = upsampled[0];
            for (var x = 0; x < width; x++) {
                values[x] = gray[x];
            }
        } else if (rgb) {
            for (var x = 0; x < width; x++) {
                values[x] = upsampled[0][x] + upsampled[1][x] + upsampled[2][x];
            }
        } else {
            var luma = upsampled[0];
            var cb = upsampled[1];
            var cr = upsampled[2];
            for (var x = 0; x < width; x++) {
                var yy = luma[x];
                var r = clamp(yy + CR_R[cr[x]]);
                var g = clamp(yy + ((CB_G[cb[x]] + CR_G[cr[x]]) >> SCALEBITS));
                var b = clamp(yy + CB_B[cb[x]]);
                values[x] = r + g + b;
            }
        }
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    /**
     * Computes a row of a component at the full resolution, like libjpeg 6b (jdsample.c): "fancy"
     * triangular interpolation for h2v1 and h2v2 subsampling, replication otherwise.
     */
    private void upsample(Component c, int y, int[] out) {
        var hRatio = maxH / c.h;
        var vRatio = maxV / c.v;
        var stride = c.blocksPerLine * 8;
        var samples = c.samples;
        var fancy = c.sampledWidth > 2;
        if (hRatio == 2 && vRatio == 1 && c.h * 2 == maxH && fancy) {
            var row = y * stride;
            h2v1(samples, row, c.sampledWidth, out);
        } else if (hRatio == 2 && vRatio == 2 && c.h * 2 == maxH && c.v * 2 == maxV && fancy) {
            var inRow = y / 2;
            // the nearest row, and the next nearest: above for even rows, below for odd ones
            var other = (y & 1) == 0 ? Math.max(0, inRow - 1) : Math.min(c.sampledHeight - 1, inRow + 1);
            h2v2(samples, inRow * stride, other * stride, c.sampledWidth, (y & 1) == 0, out);
        } else if (maxH % c.h == 0 && maxV % c.v == 0) {
            var row = (y / vRatio) * stride;
            for (var x = 0; x < width; x++) {
                out[x] = samples[row + x / hRatio] & 0xFF;
            }
        } else {
            throw new IllegalStateException("Unsupported JPEG sampling factors");
        }
    }

    private void h2v1(byte[] in, int row, int sampledWidth, int[] out) {
        var o = 0;
        var value = in[row] & 0xFF;
        out[o++] = value;
        if (o < width) {
            out[o++] = (value * 3 + (in[row + 1] & 0xFF) + 2) >> 2;
        }
        for (var x = 1; x < sampledWidth - 1 && o < width; x++) {
            var v3 = (in[row + x] & 0xFF) * 3;
            out[o++] = (v3 + (in[row + x - 1] & 0xFF) + 1) >> 2;
            if (o < width) {
                out[o++] = (v3 + (in[row + x + 1] & 0xFF) + 2) >> 2;
            }
        }
        if (o < width) {
            var last = sampledWidth - 1;
            value = in[row + last] & 0xFF;
            out[o++] = (value * 3 + (in[row + last - 1] & 0xFF) + 1) >> 2;
            if (o < width) {
                out[o] = value;
            }
        }
    }

    private void h2v2(byte[] in, int near, int far, int sampledWidth, boolean above, int[] out) {
        var o = 0;
        var thisSum = (in[near] & 0xFF) * 3 + (in[far] & 0xFF);
        var nextSum = (in[near + 1] & 0xFF) * 3 + (in[far + 1] & 0xFF);
        out[o++] = (thisSum * 4 + 8) >> 4;
        if (o < width) {
            out[o++] = (thisSum * 3 + nextSum + 7) >> 4;
        }
        var lastSum = thisSum;
        thisSum = nextSum;
        for (var x = 2; x < sampledWidth && o < width; x++) {
            nextSum = (in[near + x] & 0xFF) * 3 + (in[far + x] & 0xFF);
            out[o++] = (thisSum * 3 + lastSum + 8) >> 4;
            if (o < width) {
                out[o++] = (thisSum * 3 + nextSum + 7) >> 4;
            }
            lastSum = thisSum;
            thisSum = nextSum;
        }
        if (o < width) {
            out[o++] = (thisSum * 3 + lastSum + 8) >> 4;
            if (o < width) {
                out[o] = (thisSum * 4 + 7) >> 4;
            }
        }
    }

    // the integer inverse DCT of libjpeg 6b (jidctint.c)
    private static final int CONST_BITS = 13;
    private static final int PASS1_BITS = 2;
    private static final int FIX_0_298631336 = 2446;
    private static final int FIX_0_390180644 = 3196;
    private static final int FIX_0_541196100 = 4433;
    private static final int FIX_0_765366865 = 6270;
    private static final int FIX_0_899976223 = 7373;
    private static final int FIX_1_175875602 = 9633;
    private static final int FIX_1_501321110 = 12299;
    private static final int FIX_1_847759065 = 15137;
    private static final int FIX_1_961570560 = 16069;
    private static final int FIX_2_053119869 = 16819;
    private static final int FIX_2_562915447 = 20995;
    private static final int FIX_3_072711026 = 25172;

    private static int descale(int x, int n) {
        return (x + (1 << (n - 1))) >> n;
    }

    private void inverseDct(Component c) {
        var stride = c.blocksPerLine * 8;
        var samples = new byte[stride * c.blocksPerColumn * 8];
        var quant = c.quant;
        var coefficients = c.coefficients;
        // blocks are independent: rows of blocks are transformed in parallel
        IntStream.range(0, c.blocksPerColumn).parallel().forEach(blockRow -> {
            var workspace = new int[64];
            for (var blockCol = 0; blockCol < c.blocksPerLine; blockCol++) {
                var offset = (blockRow * c.blocksPerLine + blockCol) * 64;
                idct(coefficients, offset, quant, workspace, samples, blockRow * 8 * stride + blockCol * 8, stride);
            }
        });
        c.samples = samples;
        c.coefficients = null;
    }

    private static void idct(short[] in, int offset, int[] quant, int[] ws, byte[] out, int outOffset, int stride) {
        // pass 1: columns
        for (var col = 0; col < 8; col++) {
            var i = offset + col;
            if (in[i + 8] == 0 && in[i + 16] == 0 && in[i + 24] == 0 && in[i + 32] == 0 && in[i + 40] == 0 && in[i + 48] == 0
                    && in[i + 56] == 0) {
                var dc = (in[i] * quant[col]) << PASS1_BITS;
                for (var row = 0; row < 8; row++) {
                    ws[row * 8 + col] = dc;
                }
                continue;
            }
            var z2 = in[i + 16] * quant[col + 16];
            var z3 = in[i + 48] * quant[col + 48];
            var z1 = (z2 + z3) * FIX_0_541196100;
            var tmp2 = z1 + z3 * -FIX_1_847759065;
            var tmp3 = z1 + z2 * FIX_0_765366865;
            z2 = in[i] * quant[col];
            z3 = in[i + 32] * quant[col + 32];
            var tmp0 = (z2 + z3) << CONST_BITS;
            var tmp1 = (z2 - z3) << CONST_BITS;
            var tmp10 = tmp0 + tmp3;
            var tmp13 = tmp0 - tmp3;
            var tmp11 = tmp1 + tmp2;
            var tmp12 = tmp1 - tmp2;
            tmp0 = in[i + 56] * quant[col + 56];
            tmp1 = in[i + 40] * quant[col + 40];
            tmp2 = in[i + 24] * quant[col + 24];
            tmp3 = in[i + 8] * quant[col + 8];
            z1 = tmp0 + tmp3;
            z2 = tmp1 + tmp2;
            z3 = tmp0 + tmp2;
            var z4 = tmp1 + tmp3;
            var z5 = (z3 + z4) * FIX_1_175875602;
            tmp0 *= FIX_0_298631336;
            tmp1 *= FIX_2_053119869;
            tmp2 *= FIX_3_072711026;
            tmp3 *= FIX_1_501321110;
            z1 *= -FIX_0_899976223;
            z2 *= -FIX_2_562915447;
            z3 *= -FIX_1_961570560;
            z4 *= -FIX_0_390180644;
            z3 += z5;
            z4 += z5;
            tmp0 += z1 + z3;
            tmp1 += z2 + z4;
            tmp2 += z2 + z3;
            tmp3 += z1 + z4;
            var shift = CONST_BITS - PASS1_BITS;
            ws[col] = descale(tmp10 + tmp3, shift);
            ws[56 + col] = descale(tmp10 - tmp3, shift);
            ws[8 + col] = descale(tmp11 + tmp2, shift);
            ws[48 + col] = descale(tmp11 - tmp2, shift);
            ws[16 + col] = descale(tmp12 + tmp1, shift);
            ws[40 + col] = descale(tmp12 - tmp1, shift);
            ws[24 + col] = descale(tmp13 + tmp0, shift);
            ws[32 + col] = descale(tmp13 - tmp0, shift);
        }
        // pass 2: rows
        var shift = CONST_BITS + PASS1_BITS + 3;
        for (var row = 0; row < 8; row++) {
            var w = row * 8;
            var o = outOffset + row * stride;
            if (ws[w + 1] == 0 && ws[w + 2] == 0 && ws[w + 3] == 0 && ws[w + 4] == 0 && ws[w + 5] == 0 && ws[w + 6] == 0
                    && ws[w + 7] == 0) {
                var value = (byte) IDCT_LIMIT[descale(ws[w], PASS1_BITS + 3) & 1023];
                for (var x = 0; x < 8; x++) {
                    out[o + x] = value;
                }
                continue;
            }
            var z2 = ws[w + 2];
            var z3 = ws[w + 6];
            var z1 = (z2 + z3) * FIX_0_541196100;
            var tmp2 = z1 + z3 * -FIX_1_847759065;
            var tmp3 = z1 + z2 * FIX_0_765366865;
            var tmp0 = (ws[w] + ws[w + 4]) << CONST_BITS;
            var tmp1 = (ws[w] - ws[w + 4]) << CONST_BITS;
            var tmp10 = tmp0 + tmp3;
            var tmp13 = tmp0 - tmp3;
            var tmp11 = tmp1 + tmp2;
            var tmp12 = tmp1 - tmp2;
            tmp0 = ws[w + 7];
            tmp1 = ws[w + 5];
            tmp2 = ws[w + 3];
            tmp3 = ws[w + 1];
            z1 = tmp0 + tmp3;
            z2 = tmp1 + tmp2;
            z3 = tmp0 + tmp2;
            var z4 = tmp1 + tmp3;
            var z5 = (z3 + z4) * FIX_1_175875602;
            tmp0 *= FIX_0_298631336;
            tmp1 *= FIX_2_053119869;
            tmp2 *= FIX_3_072711026;
            tmp3 *= FIX_1_501321110;
            z1 *= -FIX_0_899976223;
            z2 *= -FIX_2_562915447;
            z3 *= -FIX_1_961570560;
            z4 *= -FIX_0_390180644;
            z3 += z5;
            z4 += z5;
            tmp0 += z1 + z3;
            tmp1 += z2 + z4;
            tmp2 += z2 + z3;
            tmp3 += z1 + z4;
            out[o] = (byte) IDCT_LIMIT[descale(tmp10 + tmp3, shift) & 1023];
            out[o + 7] = (byte) IDCT_LIMIT[descale(tmp10 - tmp3, shift) & 1023];
            out[o + 1] = (byte) IDCT_LIMIT[descale(tmp11 + tmp2, shift) & 1023];
            out[o + 6] = (byte) IDCT_LIMIT[descale(tmp11 - tmp2, shift) & 1023];
            out[o + 2] = (byte) IDCT_LIMIT[descale(tmp12 + tmp1, shift) & 1023];
            out[o + 5] = (byte) IDCT_LIMIT[descale(tmp12 - tmp1, shift) & 1023];
            out[o + 3] = (byte) IDCT_LIMIT[descale(tmp13 + tmp0, shift) & 1023];
            out[o + 4] = (byte) IDCT_LIMIT[descale(tmp13 - tmp0, shift) & 1023];
        }
    }
}
