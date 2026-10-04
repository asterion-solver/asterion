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

    private final byte[] data;
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
    // the bit reader of the entropy-coded data
    private int bitBuffer;
    private int bitCount;
    private boolean markerFound;

    private JpegDecoder(byte[] data) {
        this.data = data;
    }

    static boolean accepts(byte[] header) {
        return header.length >= 3 && (header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8 && (header[2] & 0xFF) == 0xFF;
    }

    static GrayImage decode(byte[] data, int binning) throws IOException {
        try {
            var decoder = new JpegDecoder(data);
            decoder.readMarkers();
            var binner = new GrayBinner(decoder.width, decoder.height, binning);
            decoder.decodeRows(decoder.rgb(), binner::row);
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
            var decoder = new JpegDecoder(data);
            if (tables != null) {
                var tablesDecoder = new JpegDecoder(tables);
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
        /** The coefficients of all the blocks, 64 per block, in natural order. */
        short[] coefficients;
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
        for (var c : components) {
            c.sampledWidth = ceilDiv(width * c.h, maxH);
            c.sampledHeight = ceilDiv(height * c.v, maxV);
            c.blocksPerLine = mcusX * c.h;
            c.blocksPerColumn = mcusY * c.v;
            c.coefficients = new short[c.blocksPerLine * c.blocksPerColumn * 64];
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
        decodeScan(scanComponents, ss, se, ah, al);
    }

    private void decodeScan(Component[] scan, int ss, int se, int ah, int al) throws IOException {
        bitBuffer = 0;
        bitCount = 0;
        markerFound = false;
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
        var restarts = restartInterval;
        for (var mcu = 0; mcu < totalMcus; mcu++) {
            if (restarts > 0 && mcu > 0 && mcu % restarts == 0) {
                restart(scan);
            }
            if (single) {
                var c = scan[0];
                var row = mcu / unitsPerLine;
                var col = mcu % unitsPerLine;
                decodeBlock(c, (row * c.blocksPerLine + col) * 64, ss, se, ah, al);
            } else {
                var mcuRow = mcu / mcusX;
                var mcuCol = mcu % mcusX;
                for (var c : scan) {
                    for (var v = 0; v < c.v; v++) {
                        for (var h = 0; h < c.h; h++) {
                            var blockRow = mcuRow * c.v + v;
                            var blockCol = mcuCol * c.h + h;
                            decodeBlock(c, (blockRow * c.blocksPerLine + blockCol) * 64, ss, se, ah, al);
                        }
                    }
                }
            }
        }
        // the entropy-coded data ends at the next marker: when the bit reader found it, it's read again
        if (markerFound) {
            position -= 2;
        }
    }

    /** Moves to the data which follows a restart marker, with a fresh state. */
    private void restart(Component[] scan) {
        if (markerFound) {
            // the bit reader stopped right after the marker
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
            } else if (bit() != 0) {
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
        var s = decodeHuffman(table);
        return s == 0 ? 0 : extend(bits(s), s);
    }

    private void decodeBaseline(Component c, int offset) throws IOException {
        var coefficients = c.coefficients;
        c.predictor += decodeDc(c);
        coefficients[offset] = (short) c.predictor;
        var table = acTables[c.acTable];
        if (table == null) {
            throw new IOException("Missing JPEG Huffman table");
        }
        for (var k = 1; k < 64; k++) {
            var rs = decodeHuffman(table);
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
            coefficients[offset + ZIGZAG[k]] = (short) extend(bits(s), s);
        }
    }

    private void decodeAcFirst(Component c, int offset, int ss, int se, int al) throws IOException {
        if (eobRun > 0) {
            eobRun--;
            return;
        }
        var table = acTables[c.acTable];
        for (var k = ss; k <= se; k++) {
            var rs = decodeHuffman(table);
            var r = rs >> 4;
            var s = rs & 15;
            if (s == 0) {
                if (r < 15) {
                    eobRun = (1 << r) - 1;
                    if (r > 0) {
                        eobRun += bits(r);
                    }
                    break;
                }
                k += 15;
                continue;
            }
            k += r;
            c.coefficients[offset + ZIGZAG[k]] = (short) (extend(bits(s), s) * (1 << al));
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
                var rs = decodeHuffman(table);
                var r = rs >> 4;
                var s = rs & 15;
                if (s != 0) {
                    s = bit() != 0 ? p1 : m1;
                } else if (r != 15) {
                    eobRun = 1 << r;
                    if (r > 0) {
                        eobRun += bits(r);
                    }
                    break;
                }
                do {
                    var index = offset + ZIGZAG[k];
                    if (coefficients[index] != 0) {
                        if (bit() != 0 && (coefficients[index] & p1) == 0) {
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
                if (coefficients[index] != 0 && bit() != 0 && (coefficients[index] & p1) == 0) {
                    coefficients[index] += (short) (coefficients[index] >= 0 ? p1 : m1);
                }
            }
            eobRun--;
        }
    }

    private static int extend(int v, int s) {
        return v < 1 << (s - 1) ? v - (1 << s) + 1 : v;
    }

    /** Fills the bit buffer to at least 25 bits: after a marker, with zeros, like libjpeg. */
    private void fill() {
        while (bitCount <= 24) {
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
            bitBuffer |= b << (24 - bitCount);
            bitCount += 8;
        }
    }

    private int bit() {
        return bits(1);
    }

    private int bits(int n) {
        if (n == 0) {
            return 0;
        }
        if (bitCount < n) {
            fill();
        }
        var v = bitBuffer >>> (32 - n);
        bitBuffer <<= n;
        bitCount -= n;
        return v;
    }

    private int decodeHuffman(HuffmanTable table) throws IOException {
        if (bitCount < 16) {
            fill();
        }
        var entry = table.lookup[bitBuffer >>> (32 - 9)];
        if (entry != 0) {
            var length = entry & 0xFF;
            bitBuffer <<= length;
            bitCount -= length;
            return entry >> 8;
        }
        var code = 0;
        for (var length = 1; length <= 16; length++) {
            code = (code << 1) | bit();
            if (code <= table.maxCode[length]) {
                return table.values[table.valueOffset[length] + code];
            }
        }
        // a corrupt code: libjpeg returns 0 too
        return 0;
    }

    /** Tells if the components are red, green and blue, like libjpeg guesses it from the markers. */
    private boolean rgb() {
        return components.length == 3 && !jfif
                && (adobeTransform == 0
                        || adobeTransform < 0 && components[0].id == 'R' && components[1].id == 'G' && components[2].id == 'B');
    }

    private void decodeRows(boolean rgb, RowConsumer rows) throws IOException {
        if (components == null) {
            throw new IOException("No image in the JPEG file");
        }
        for (var c : components) {
            if (c.quant == null) {
                throw new IOException("JPEG component without data");
            }
            inverseDct(c);
        }
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

    /** Computes the brightness of the pixels of a row: the sum of their red, green and blue values. */
    private void convertRow(int y, boolean rgb, int[][] upsampled, float[] values) {
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
