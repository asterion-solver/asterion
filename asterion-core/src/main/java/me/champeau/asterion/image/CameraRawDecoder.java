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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the raw files of digital cameras: Canon CR2 and CR3, Nikon NEF, Sony ARW, Adobe DNG, Pentax
 * PEF, Olympus ORF, Panasonic RW2, Fujifilm RAF, and the other raw files which are TIFF files. Each of
 * them holds a JPEG image which the camera made from the raw data, often at full resolution: this
 * is the image which is solved, the largest of the ones of the file. Its stars are where they are
 * on the sensor, which is what plate solving needs, and it is decoded much faster than the raw
 * data, which each brand compresses in its own way.
 *
 * <p>The EXIF data of the file gives the date of the observation, the size of the pixels, and
 * the focal length of the lens, which is unknown through a telescope.
 */
final class CameraRawDecoder {
    /** The JPEG markers of the frames which the JPEG decoder decodes: baseline, extended and progressive. */
    private static final Set<Integer> DECODED_FRAMES = Set.of(0xC0, 0xC1, 0xC2);
    private static final int CFA = 32803;
    private static final int LINEAR_RAW = 34892;
    private static final int DNG_VERSION = 50706;
    private static final int SUB_IFDS = 330;
    private static final int EXIF_IFD = 34665;
    /** The tag of the JPEG image of Panasonic raw files. */
    private static final int PANASONIC_JPEG = 0x2E;
    /** The smallest images which are kept when the JPEG streams of a file are found by scanning it. */
    private static final int MIN_SCANNED_SIZE = 256;

    private CameraRawDecoder() {
    }

    /** A JPEG stream in the file, and the size of its image. */
    private record Preview(
            int offset,
            int length,
            int width,
            int height) {
    }

    /** What the EXIF data of a file tells. */
    private static final class Exif {
        double epoch = Double.NaN;
        double focalLength = Double.NaN;
        /** The resolution of the focal plane, in pixels per micrometer of the image of the given width. */
        double pixelsPerMicron = Double.NaN;
        int width;
    }

    /** Tells if the start of a file is the one of a camera raw file which isn't an ordinary TIFF file. */
    static boolean acceptsMagic(byte[] data) {
        return isCr2(data) || isCr3(data) || isRaf(data) || isOrf(data) || isRw2(data);
    }

    /** Tells if a TIFF file is the raw file of a camera: a DNG file, or a file with an image of the raw data of a color sensor. */
    static boolean isCameraRaw(byte[] data) {
        if (acceptsMagic(data)) {
            return true;
        }
        if (!TiffDecoder.accepts(data)) {
            return false;
        }
        try {
            var tiff = new Tiff(data);
            for (var ifd : tiff.allIfds()) {
                if (tiff.has(ifd, DNG_VERSION)) {
                    return true;
                }
                var photometric = tiff.intValue(ifd, 262, -1);
                if (photometric == CFA || photometric == LINEAR_RAW) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            // not a TIFF file which can be walked
        }
        return false;
    }

    /** Tells if a file is a Canon CR3 file: an ISO media container, like MP4 files. */
    static boolean isCr3(byte[] data) {
        return data.length >= 12 && new String(data, 4, 8, StandardCharsets.US_ASCII).equals("ftypcrx ");
    }

    /**
     * Decodes the largest JPEG image of a camera raw file which can be decoded, with the hints of
     * its EXIF data.
     */
    static LoadedImage load(byte[] data, int binning) throws IOException {
        List<Preview> previews;
        try {
            if (isRaf(data)) {
                previews = rafPreviews(data);
            } else if (isCr3(data)) {
                previews = new Cr3(data).previews();
            } else {
                previews = tiffPreviews(new Tiff(data), data);
            }
            if (previews.isEmpty()) {
                // Olympus files have their JPEG image in their maker notes, which differ from one
                // brand to the other: it is found in the file itself
                previews = scannedPreviews(data);
            }
        } catch (RuntimeException e) {
            throw new IOException("Invalid camera raw file: " + e, e);
        }
        previews.sort((a, b) -> Long.compare((long) b.width * b.height, (long) a.width * a.height));
        IOException failure = null;
        for (var preview : previews) {
            var jpeg = Arrays.copyOfRange(data, preview.offset, preview.offset + preview.length);
            GrayImage image;
            try {
                image = JpegDecoder.decode(jpeg, binning);
            } catch (IOException e) {
                // a damaged image, or the start of a JPEG stream found by chance: the next one
                failure = e;
                continue;
            }
            return new LoadedImage(image, hints(exif(data, jpeg), image.sourceWidth()));
        }
        if (TiffDecoder.accepts(data)) {
            // a raw file without JPEG image, whose first image may be an ordinary one, as the
            // TIFF decoder decoded before raw files were recognized
            try {
                return new LoadedImage(TiffDecoder.decode(data, binning), ImageHints.none());
            } catch (IOException e) {
                // its first image is the raw data, or a thumbnail which can't be decoded either
            }
        }
        if (failure != null) {
            throw failure;
        }
        throw new IOException("This camera raw file has no JPEG image which can be solved: convert it to FITS or TIFF first");
    }

    /**
     * The EXIF data of a camera raw file, or of its JPEG image when the file has none. It only
     * gives hints: damaged EXIF data gives none.
     */
    private static Exif exif(byte[] data, byte[] jpeg) {
        Exif exif;
        try {
            if (isRaf(data)) {
                exif = new Exif();
            } else if (isCr3(data)) {
                exif = new Cr3(data).exif();
            } else {
                exif = new Tiff(data).exif();
            }
        } catch (RuntimeException e) {
            exif = new Exif();
        }
        if (Double.isNaN(exif.epoch) && Double.isNaN(exif.pixelsPerMicron)) {
            // Fujifilm files, and the others whose EXIF data is only in their JPEG images
            exif = jpegExif(jpeg);
        }
        return exif;
    }

    private static ImageHints hints(Exif exif, int width) {
        var hints = ImageHints.builder().epoch(exif.epoch);
        if (exif.pixelsPerMicron > 0) {
            // the resolution of the focal plane is the one of the image of the EXIF data, which may be
            // larger than the JPEG image
            var reference = exif.width > 0 ? exif.width : width;
            var pixelSize = reference / (exif.pixelsPerMicron * width);
            hints.pixelSizeMicrons(pixelSize);
            if (exif.focalLength > 0) {
                hints.pixelScale(206.264806 * pixelSize / exif.focalLength);
            }
        }
        return hints.build();
    }

    private static boolean isCr2(byte[] data) {
        return TiffDecoder.accepts(data) && data.length >= 10 && data[8] == 'C' && data[9] == 'R';
    }

    private static boolean isRaf(byte[] data) {
        return data.length >= 16 && new String(data, 0, 15, StandardCharsets.US_ASCII).equals("FUJIFILMCCD-RAW");
    }

    private static boolean isOrf(byte[] data) {
        return data.length >= 4 && ((data[0] == 'I' && data[1] == 'I' && data[2] == 'R' && (data[3] == 'O' || data[3] == 'S'))
                || (data[0] == 'M' && data[1] == 'M' && data[2] == 'O' && data[3] == 'R'));
    }

    private static boolean isRw2(byte[] data) {
        return data.length >= 4 && data[0] == 'I' && data[1] == 'I' && data[2] == 'U' && data[3] == 0;
    }

    /** The JPEG image of a Fujifilm file, whose offset and length are in its header. */
    private static List<Preview> rafPreviews(byte[] data) {
        var buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        var offset = buffer.getInt(84);
        var length = buffer.getInt(88);
        var previews = new ArrayList<Preview>();
        addJpeg(data, offset, length, previews);
        return previews;
    }

    /** The JPEG images of a TIFF file: the ones of its image directories and their sub-directories. */
    private static List<Preview> tiffPreviews(Tiff tiff, byte[] data) {
        var previews = new ArrayList<Preview>();
        for (var ifd : tiff.allIfds()) {
            var jpegOffset = tiff.intValue(ifd, 513, -1);
            var jpegLength = tiff.intValue(ifd, 514, -1);
            if (jpegOffset > 0 && jpegLength > 0) {
                addJpeg(data, jpegOffset, jpegLength, previews);
            }
            var compression = tiff.intValue(ifd, 259, -1);
            var offsets = tiff.intValues(ifd, 273);
            var counts = tiff.intValues(ifd, 279);
            if ((compression == 6 || compression == 7) && offsets.length == 1 && counts.length == 1) {
                // a strip which is a whole JPEG stream, as Canon and Nikon store their images: the
                // raw data is a lossless JPEG stream, which isn't kept
                addJpeg(data, offsets[0], counts[0], previews);
            }
            var panasonic = tiff.entry(ifd, PANASONIC_JPEG);
            if (panasonic >= 0) {
                addJpeg(data, tiff.valueOffset(panasonic), tiff.count(panasonic), previews);
            }
        }
        return previews;
    }

    /**
     * The JPEG streams of a file, found by their start: a marker, then the segments of tables
     * which lead to a frame which can be decoded. Their length is the rest of the file, as the
     * decoder stops at the end of the image.
     */
    private static List<Preview> scannedPreviews(byte[] data) {
        var previews = new ArrayList<Preview>();
        for (var p = 0; p + 3 < data.length; p++) {
            if ((data[p] & 0xFF) == 0xFF && (data[p + 1] & 0xFF) == 0xD8 && (data[p + 2] & 0xFF) == 0xFF) {
                var before = previews.size();
                addJpeg(data, p, data.length - p, previews);
                if (previews.size() > before) {
                    var found = previews.getLast();
                    // the start of a JPEG stream may also occur by chance in the raw data
                    if (found.width < MIN_SCANNED_SIZE || found.height < MIN_SCANNED_SIZE || found.width > 65000 || found.height > 65000) {
                        previews.removeLast();
                    }
                }
            }
        }
        return previews;
    }

    /** Adds a JPEG stream to the previews if the JPEG decoder can decode it. */
    private static void addJpeg(byte[] data, int offset, int length, List<Preview> previews) {
        if (offset < 0 || length < 4 || (long) offset + length > data.length) {
            return;
        }
        if ((data[offset] & 0xFF) != 0xFF || (data[offset + 1] & 0xFF) != 0xD8) {
            return;
        }
        // the size of the image, in the first frame header
        var p = offset + 2;
        var end = offset + length;
        while (p + 4 <= end) {
            if ((data[p] & 0xFF) != 0xFF) {
                return;
            }
            var marker = data[p + 1] & 0xFF;
            if (marker == 0xFF) {
                p++;
                continue;
            }
            var size = ((data[p + 2] & 0xFF) << 8) | (data[p + 3] & 0xFF);
            if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                if (!DECODED_FRAMES.contains(marker) || p + 9 > end) {
                    return;
                }
                var height = ((data[p + 5] & 0xFF) << 8) | (data[p + 6] & 0xFF);
                var width = ((data[p + 7] & 0xFF) << 8) | (data[p + 8] & 0xFF);
                if (width > 0 && height > 0) {
                    previews.add(new Preview(offset, length, width, height));
                }
                return;
            }
            if (marker == 0xDA || marker == 0xD9) {
                return;
            }
            p += 2 + size;
        }
    }

    /** The EXIF data of a JPEG stream, in its APP1 segment, or none when it can't be read. */
    private static Exif jpegExif(byte[] jpeg) {
        try {
            var p = 2;
            while (p + 4 <= jpeg.length && (jpeg[p] & 0xFF) == 0xFF) {
                var marker = jpeg[p + 1] & 0xFF;
                var size = ((jpeg[p + 2] & 0xFF) << 8) | (jpeg[p + 3] & 0xFF);
                if (marker == 0xDA) {
                    break;
                }
                if (marker == 0xE1 && size > 8 && new String(jpeg, p + 4, 4, StandardCharsets.US_ASCII).equals("Exif")) {
                    return new Tiff(Arrays.copyOfRange(jpeg, p + 10, Math.min(jpeg.length, p + 2 + size))).exif();
                }
                p += 2 + size;
            }
        } catch (RuntimeException e) {
            // a truncated or damaged segment: no EXIF data
        }
        return new Exif();
    }

    /**
     * A Canon CR3 file: an ISO media container, whose first track is the JPEG image which the camera
     * made, at full size, and whose other tracks are the raw data. Its EXIF data is in TIFF
     * structures in a box of Canon: CMT1 for the main image directory, CMT2 for the EXIF tags.
     */
    private static final class Cr3 {
        private static final byte[] CANON_UUID = hex("85c0b687820f11e08111f4ce462b6a48");
        private final byte[] data;
        private final ByteBuffer buffer;
        private final List<Preview> previews = new ArrayList<>();
        private byte[] cmt1;
        private byte[] cmt2;

        Cr3(byte[] data) {
            this.data = data;
            this.buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
            walk(0, data.length, 0);
        }

        private static byte[] hex(String text) {
            var bytes = new byte[text.length() / 2];
            for (var i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
            }
            return bytes;
        }

        /** Walks the boxes of a range of the file, the ones which contain others too. */
        private void walk(int start, int end, int depth) {
            var p = start;
            while (p + 8 <= end && depth < 8) {
                long size = buffer.getInt(p) & 0xFFFFFFFFL;
                var type = new String(data, p + 4, 4, StandardCharsets.US_ASCII);
                var header = 8;
                if (size == 1) {
                    size = buffer.getLong(p + 8);
                    header = 16;
                } else if (size == 0) {
                    size = end - p;
                }
                if (size < header || p + size > end) {
                    return;
                }
                var body = p + header;
                var boxEnd = (int) (p + size);
                switch (type) {
                    case "moov", "trak", "mdia", "minf" -> walk(body, boxEnd, depth + 1);
                    case "stbl" -> track(body, boxEnd);
                    case "uuid" -> {
                        if (body + 16 <= boxEnd && Arrays.equals(data, body, body + 16, CANON_UUID, 0, 16)) {
                            walk(body + 16, boxEnd, depth + 1);
                        }
                    }
                    case "CMT1" -> cmt1 = Arrays.copyOfRange(data, body, boxEnd);
                    case "CMT2" -> cmt2 = Arrays.copyOfRange(data, body, boxEnd);
                    default -> {
                        // the other boxes don't matter
                    }
                }
                p = boxEnd;
            }
        }

        /** The first sample of a track: its size, and where its chunk starts. */
        private void track(int start, int end) {
            long sampleSize = -1;
            long offset = -1;
            var p = start;
            while (p + 8 <= end) {
                var size = buffer.getInt(p);
                if (size < 8 || p + size > end) {
                    return;
                }
                var type = new String(data, p + 4, 4, StandardCharsets.US_ASCII);
                var body = p + 8;
                switch (type) {
                    case "stsz" -> {
                        // version and flags, then the size of all samples, or 0 and the size of each one
                        var all = buffer.getInt(body + 4) & 0xFFFFFFFFL;
                        sampleSize = all != 0 ? all : buffer.getInt(body + 12) & 0xFFFFFFFFL;
                    }
                    case "co64" -> offset = buffer.getLong(body + 8);
                    case "stco" -> offset = buffer.getInt(body + 8) & 0xFFFFFFFFL;
                    default -> {
                        // the description of the samples, their times...
                    }
                }
                p += size;
            }
            if (sampleSize > 0 && offset > 0 && offset + sampleSize <= data.length) {
                // the tracks of raw data aren't JPEG streams: they are left out
                addJpeg(data, (int) offset, (int) sampleSize, previews);
            }
        }

        List<Preview> previews() {
            return previews;
        }

        Exif exif() {
            if (cmt2 == null || !TiffDecoder.accepts(cmt2)) {
                return new Exif();
            }
            // the first directory of CMT2 holds the EXIF tags themselves
            var tiff = new Tiff(cmt2);
            var exif = tiff.exif(tiff.firstIfd(), -1);
            if (Double.isNaN(exif.epoch) && cmt1 != null && TiffDecoder.accepts(cmt1)) {
                exif.epoch = new Tiff(cmt1).mainEpoch();
            }
            return exif;
        }
    }

    /**
     * A TIFF structure, to walk its image directories. Olympus and Panasonic files have their own
     * magic numbers, but the structure of TIFF files.
     */
    private static final class Tiff {
        private final ByteBuffer buffer;

        Tiff(byte[] data) {
            this.buffer = ByteBuffer.wrap(data).order(data[0] == 'M' ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        }

        /** The image directories: the chain of the main ones, and their sub-directories. */
        List<Integer> allIfds() {
            var ifds = new ArrayList<Integer>();
            var seen = new HashSet<Integer>();
            var offset = buffer.getInt(4);
            while (offset > 0 && offset < buffer.limit() - 2 && seen.add(offset) && ifds.size() < 64) {
                ifds.add(offset);
                addSubIfds(offset, ifds, seen, 0);
                offset = buffer.getInt(offset + 2 + 12 * entries(offset));
            }
            return ifds;
        }

        private void addSubIfds(int ifd, List<Integer> ifds, Set<Integer> seen, int depth) {
            if (depth > 3) {
                return;
            }
            for (var sub : intValues(ifd, SUB_IFDS)) {
                if (sub > 0 && sub < buffer.limit() - 2 && seen.add(sub)) {
                    ifds.add(sub);
                    addSubIfds(sub, ifds, seen, depth + 1);
                }
            }
        }

        int entries(int ifd) {
            return buffer.getShort(ifd) & 0xFFFF;
        }

        /** The position of the entry of a tag in a directory, or -1. */
        int entry(int ifd, int tag) {
            var n = entries(ifd);
            for (var i = 0; i < n; i++) {
                var e = ifd + 2 + 12 * i;
                if ((buffer.getShort(e) & 0xFFFF) == tag) {
                    return e;
                }
            }
            return -1;
        }

        boolean has(int ifd, int tag) {
            return entry(ifd, tag) >= 0;
        }

        int type(int entry) {
            return buffer.getShort(entry + 2) & 0xFFFF;
        }

        int count(int entry) {
            return buffer.getInt(entry + 4);
        }

        private static int typeSize(int type) {
            return switch (type) {
                case 3, 8 -> 2;
                case 4, 9, 11, 13 -> 4;
                case 5, 10, 12 -> 8;
                default -> 1;
            };
        }

        /** Where the values of an entry are: in the entry itself when they fit in 4 bytes. */
        int valueOffset(int entry) {
            var size = (long) typeSize(type(entry)) * count(entry);
            return size <= 4 ? entry + 8 : buffer.getInt(entry + 8);
        }

        int[] intValues(int ifd, int tag) {
            var e = entry(ifd, tag);
            if (e < 0) {
                return new int[0];
            }
            var type = type(e);
            var n = Math.min(count(e), 4096);
            var offset = valueOffset(e);
            var values = new int[n];
            for (var i = 0; i < n; i++) {
                values[i] = switch (type) {
                    case 3 -> buffer.getShort(offset + 2 * i) & 0xFFFF;
                    case 8 -> buffer.getShort(offset + 2 * i);
                    case 4, 9, 13 -> buffer.getInt(offset + 4 * i);
                    case 1, 7 -> buffer.get(offset + i) & 0xFF;
                    default -> 0;
                };
            }
            return values;
        }

        int intValue(int ifd, int tag, int missing) {
            var values = intValues(ifd, tag);
            return values.length > 0 ? values[0] : missing;
        }

        double rational(int ifd, int tag) {
            var e = entry(ifd, tag);
            if (e < 0 || (type(e) != 5 && type(e) != 10)) {
                return Double.NaN;
            }
            var offset = valueOffset(e);
            double numerator = type(e) == 5 ? buffer.getInt(offset) & 0xFFFFFFFFL : buffer.getInt(offset);
            double denominator = type(e) == 5 ? buffer.getInt(offset + 4) & 0xFFFFFFFFL : buffer.getInt(offset + 4);
            return denominator == 0 ? Double.NaN : numerator / denominator;
        }

        String string(int ifd, int tag) {
            var e = entry(ifd, tag);
            if (e < 0 || type(e) != 2) {
                return null;
            }
            var offset = valueOffset(e);
            var n = count(e);
            var bytes = new byte[Math.max(0, Math.min(n, buffer.limit() - offset))];
            buffer.get(offset, bytes);
            var text = new String(bytes, StandardCharsets.US_ASCII);
            var nul = text.indexOf('\0');
            return nul >= 0 ? text.substring(0, nul) : text;
        }

        /** The offset of the first image directory. */
        int firstIfd() {
            return buffer.getInt(4);
        }

        /** The date of the first image directory, as a Julian year, or NaN. */
        double mainEpoch() {
            return epoch(string(firstIfd(), 306));
        }

        /** A date of EXIF data, such as "2020:05:24 23:51:26", as a Julian year, or NaN. */
        private static double epoch(String date) {
            if (date == null || date.length() < 10) {
                return Double.NaN;
            }
            try {
                var day = LocalDate.parse(date.substring(0, 10).replace(':', '-'));
                return day.getYear() + (day.getDayOfYear() - 1) / 365.25;
            } catch (DateTimeParseException e) {
                // an unset date
                return Double.NaN;
            }
        }

        /** The EXIF data of the first image directory. */
        Exif exif() {
            var ifd0 = buffer.getInt(4);
            var exifIfd = intValue(ifd0, EXIF_IFD, -1);
            if (exifIfd <= 0 || exifIfd >= buffer.limit() - 2) {
                return new Exif();
            }
            return exif(exifIfd, ifd0);
        }

        /**
         * The EXIF data of a directory of EXIF tags.
         *
         * @param main the main image directory, whose date is the one of the file when the EXIF data has none, or -1
         */
        Exif exif(int exifIfd, int main) {
            var exif = new Exif();
            var date = string(exifIfd, 36867);
            if (date == null && main >= 0) {
                date = string(main, 306);
            }
            exif.epoch = epoch(date);
            exif.focalLength = rational(exifIfd, 37386);
            var resolution = rational(exifIfd, 41486);
            var unit = intValue(exifIfd, 41488, 2);
            double micronsPerUnit = switch (unit) {
                case 3 -> 1e4;
                case 4 -> 1e3;
                case 5 -> 1;
                default -> 25400;
            };
            if (resolution > 0) {
                exif.pixelsPerMicron = resolution / micronsPerUnit;
            }
            exif.width = intValue(exifIfd, 40962, 0);
            return exif;
        }
    }
}
