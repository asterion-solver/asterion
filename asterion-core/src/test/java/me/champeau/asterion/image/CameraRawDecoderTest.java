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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraRawDecoderTest {
    @TempDir
    Path dir;

    private static byte[] jpeg(int width, int height) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                image.getRaster().setSample(x, y, 0, (x * 7 + y * 3) & 0xFF);
            }
        }
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    /** A JPEG stream with an EXIF segment, which holds a TIFF structure. */
    private static byte[] withExif(byte[] jpeg, byte[] tiff) {
        var out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        var size = 2 + 6 + tiff.length;
        out.write(0xFF);
        out.write(0xE1);
        out.write(size >> 8);
        out.write(size & 0xFF);
        out.writeBytes("Exif\0\0".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(tiff);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    /**
     * Writes TIFF structures: image directories, whose large values and data are appended after
     * them. Offsets are resolved when the file is built.
     */
    private static final class TiffWriter {
        private final ByteOrder order;
        private final byte[] header;
        private final List<Ifd> ifds = new ArrayList<>();
        private final List<byte[]> blobs = new ArrayList<>();

        TiffWriter(ByteOrder order, byte[] header) {
            this.order = order;
            this.header = header;
        }

        /** A value: inline numbers, or a reference to a blob, or to directories. */
        private record Value(
                int tag,
                int type,
                int count,
                long[] numbers,
                int blob,
                int[] ifds) {
        }

        final class Ifd {
            final List<Value> values = new ArrayList<>();
            final boolean chained;

            Ifd(boolean chained) {
                this.chained = chained;
            }

            Ifd add(int tag, int type, long... numbers) {
                values.add(new Value(tag, type, numbers.length, numbers, -1, null));
                return this;
            }

            Ifd rational(int tag, long numerator, long denominator) {
                blobs.add(ByteBuffer.allocate(8).order(order).putInt((int) numerator).putInt((int) denominator).array());
                values.add(new Value(tag, 5, 1, null, blobs.size() - 1, null));
                return this;
            }

            Ifd ascii(int tag, String text) {
                var bytes = (text + "\0").getBytes(StandardCharsets.US_ASCII);
                blobs.add(bytes);
                values.add(new Value(tag, 2, bytes.length, null, blobs.size() - 1, null));
                return this;
            }

            /** A tag whose value is the offset of a blob, such as the data of a strip. */
            Ifd offsetOf(int tag, byte[] data) {
                blobs.add(data);
                values.add(new Value(tag, 4, 1, null, blobs.size() - 1, null));
                return this;
            }

            /** A tag whose values are the offsets of other directories: EXIF data, sub-directories. */
            Ifd pointer(int tag, Ifd... targets) {
                var indexes = new int[targets.length];
                for (var i = 0; i < targets.length; i++) {
                    indexes[i] = ifds.indexOf(targets[i]);
                }
                // more than one offset doesn't fit in the entry: they are written in a blob
                var blob = -1;
                if (targets.length > 1) {
                    blobs.add(new byte[4 * targets.length]);
                    blob = blobs.size() - 1;
                }
                values.add(new Value(tag, 4, targets.length, null, blob, indexes));
                return this;
            }
        }

        Ifd ifd() {
            var ifd = new Ifd(true);
            ifds.add(ifd);
            return ifd;
        }

        Ifd subIfd() {
            var ifd = new Ifd(false);
            ifds.add(ifd);
            return ifd;
        }

        byte[] build() {
            // layout: header, directories, blobs
            var ifdOffsets = new int[ifds.size()];
            var position = header.length;
            for (var i = 0; i < ifds.size(); i++) {
                ifdOffsets[i] = position;
                position += 2 + 12 * ifds.get(i).values.size() + 4;
            }
            var blobOffsets = new int[blobs.size()];
            for (var i = 0; i < blobs.size(); i++) {
                position += position & 1;
                blobOffsets[i] = position;
                position += blobs.get(i).length;
            }
            var buffer = ByteBuffer.allocate(position).order(order);
            buffer.put(header);
            buffer.putInt(4, ifdOffsets[0]);
            var chained = new ArrayList<Integer>();
            for (var i = 0; i < ifds.size(); i++) {
                if (ifds.get(i).chained) {
                    chained.add(i);
                }
            }
            for (var i = 0; i < ifds.size(); i++) {
                var ifd = ifds.get(i);
                buffer.position(ifdOffsets[i]);
                buffer.putShort((short) ifd.values.size());
                for (var v : ifd.values.stream().sorted((a, b) -> Integer.compare(a.tag, b.tag)).toList()) {
                    buffer.putShort((short) v.tag).putShort((short) v.type).putInt(v.count);
                    if (v.ifds != null && v.blob >= 0) {
                        buffer.putInt(blobOffsets[v.blob]);
                        for (var k = 0; k < v.ifds.length; k++) {
                            ByteBuffer.wrap(blobs.get(v.blob)).order(order).putInt(4 * k, ifdOffsets[v.ifds[k]]);
                        }
                    } else if (v.ifds != null) {
                        buffer.putInt(ifdOffsets[v.ifds[0]]);
                    } else if (v.blob >= 0) {
                        buffer.putInt(blobOffsets[v.blob]);
                    } else if (v.type == 3) {
                        buffer.putShort((short) v.numbers[0]).putShort(v.count > 1 ? (short) v.numbers[1] : 0);
                    } else {
                        buffer.putInt((int) v.numbers[0]);
                    }
                }
                var next = chained.indexOf(i);
                buffer.putInt(ifd.chained && next + 1 < chained.size() ? ifdOffsets[chained.get(next + 1)] : 0);
            }
            for (var i = 0; i < blobs.size(); i++) {
                buffer.put(blobOffsets[i], blobs.get(i));
            }
            return buffer.array();
        }
    }

    private static byte[] tiffHeader(ByteOrder order, int extra) {
        var header = ByteBuffer.allocate(8 + extra).order(order);
        header.put(order == ByteOrder.LITTLE_ENDIAN ? (byte) 'I' : (byte) 'M')
                .put(order == ByteOrder.LITTLE_ENDIAN ? (byte) 'I' : (byte) 'M');
        header.putShort((short) 42);
        return header.array();
    }

    /** EXIF data as cameras write it: the date, the resolution of the focal plane, the focal length. */
    private static void exif(TiffWriter.Ifd exif, int width, long focalLength) {
        exif.ascii(36867, "2020:05:24 23:51:26")
                .rational(37386, focalLength, 1)
                .rational(41486, 381058, 100)
                .add(41488, 3, 2)
                .add(40962, 4, width);
    }

    @Test
    void solvesTheFullSizeJpegOfCanonFiles() throws IOException {
        var header = tiffHeader(ByteOrder.LITTLE_ENDIAN, 8);
        header[8] = 'C';
        header[9] = 'R';
        header[10] = 2;
        var tiff = new TiffWriter(ByteOrder.LITTLE_ENDIAN, header);
        var full = jpeg(240, 160);
        var ifd0 = tiff.ifd();
        var exif = tiff.subIfd();
        ifd0.add(256, 4, 240).add(257, 4, 160).add(259, 3, 6).offsetOf(273, full).add(279, 4, full.length).pointer(34665, exif);
        exif(exif, 240, 600);
        var thumbnail = jpeg(48, 32);
        tiff.ifd().offsetOf(513, thumbnail).add(514, 4, thumbnail.length);
        var file = dir.resolve("image.cr2");
        Files.write(file, tiff.build());

        var loaded = ImageLoader.load(file, 1);
        assertEquals(240, loaded.image().width());
        assertEquals(160, loaded.image().height());
        var hints = loaded.hints();
        assertEquals(2020.39, hints.epoch().orElseThrow(), 0.01);
        // 25400 µm per inch, at 3810.58 pixels per inch
        assertEquals(6.6656, hints.pixelSizeMicrons().orElseThrow(), 1e-3);
        assertEquals(206.264806 * 6.6656 / 600, hints.pixelScale().orElseThrow(), 1e-3);
        assertFalse(hints.bayer());
    }

    @Test
    void solvesTheLargestJpegOfTheSubDirectoriesOfNikonFiles() throws IOException {
        var tiff = new TiffWriter(ByteOrder.BIG_ENDIAN, tiffHeader(ByteOrder.BIG_ENDIAN, 0));
        var thumbnail = new byte[64 * 48 * 3];
        var ifd0 = tiff.ifd();
        var preview = tiff.subIfd();
        var raw = tiff.subIfd();
        var exif = tiff.subIfd();
        ifd0.add(256, 4, 64).add(257, 4, 48).add(259, 3, 1).add(262, 3, 2).offsetOf(273, thumbnail).add(279, 4, thumbnail.length)
                .pointer(330, preview, raw).pointer(34665, exif);
        var full = jpeg(300, 200);
        preview.offsetOf(513, full).add(514, 4, full.length);
        raw.add(256, 4, 320).add(257, 4, 212).add(259, 3, 34713).add(262, 3, 32803);
        exif(exif, 0, 0);
        var bytes = tiff.build();
        var file = dir.resolve("image.nef");
        Files.write(file, bytes);

        assertTrue(CameraRawDecoder.isCameraRaw(bytes));
        var loaded = ImageLoader.load(file, 1);
        assertEquals(300, loaded.image().width());
        assertEquals(200, loaded.image().height());
        // no focal length through a telescope: no scale, but the size of the pixels
        assertTrue(loaded.hints().pixelScale().isEmpty());
        assertEquals(6.6656, loaded.hints().pixelSizeMicrons().orElseThrow(), 1e-3);
    }

    @Test
    void readsTheExifDataOfTheJpegOfFujifilmFiles() throws IOException {
        var exifTiff = new TiffWriter(ByteOrder.LITTLE_ENDIAN, tiffHeader(ByteOrder.LITTLE_ENDIAN, 0));
        var ifd0 = exifTiff.ifd();
        var exif = exifTiff.subIfd();
        ifd0.pointer(34665, exif);
        exif(exif, 0, 400);
        var full = withExif(jpeg(160, 120), exifTiff.build());
        var header = new byte[160];
        System.arraycopy("FUJIFILMCCD-RAW 0201FF383501".getBytes(StandardCharsets.US_ASCII), 0, header, 0, 28);
        var file = ByteBuffer.allocate(header.length + full.length).order(ByteOrder.BIG_ENDIAN);
        file.put(header).put(full);
        file.putInt(84, header.length).putInt(88, full.length);
        var path = dir.resolve("image.raf");
        Files.write(path, file.array());

        var loaded = ImageLoader.load(path, 1);
        assertEquals(160, loaded.image().width());
        assertEquals(2020.39, loaded.hints().epoch().orElseThrow(), 0.01);
        assertEquals(206.264806 * 6.6656 / 400, loaded.hints().pixelScale().orElseThrow(), 1e-3);
    }

    @Test
    void findsTheJpegOfOlympusFilesInTheirMakerNotes() throws IOException {
        // an Olympus file: its own magic number, a TIFF structure, and a JPEG image which none of
        // its image directories points to, as it is in the maker notes
        var header = tiffHeader(ByteOrder.LITTLE_ENDIAN, 0);
        header[2] = 'R';
        header[3] = 'O';
        var tiff = new TiffWriter(ByteOrder.LITTLE_ENDIAN, header);
        tiff.ifd().add(256, 4, 640).add(257, 4, 480).add(259, 3, 1).add(262, 3, 32803);
        var structure = tiff.build();
        var full = jpeg(480, 320);
        var file = ByteBuffer.allocate(structure.length + 100 + full.length);
        file.put(structure).position(structure.length + 100);
        file.put(full);
        var path = dir.resolve("image.orf");
        Files.write(path, file.array());

        var loaded = ImageLoader.load(path, 1);
        assertEquals(480, loaded.image().width());
        assertEquals(320, loaded.image().height());
    }

    @Test
    void solvesTheNextJpegWhenTheLargestCantBeDecoded() throws IOException {
        // a frame header which announces a large image, without anything to decode
        var broken = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xC0, 0, 11, 8, 0x0F, (byte) 0xA0, 0x17, 0x70, 1, 1, 0x11, 0,
                (byte) 0xFF, (byte) 0xD9};
        var header = tiffHeader(ByteOrder.LITTLE_ENDIAN, 8);
        header[8] = 'C';
        header[9] = 'R';
        var tiff = new TiffWriter(ByteOrder.LITTLE_ENDIAN, header);
        tiff.ifd().add(259, 3, 6).offsetOf(273, broken).add(279, 4, broken.length);
        var small = jpeg(200, 120);
        tiff.ifd().offsetOf(513, small).add(514, 4, small.length);
        var file = dir.resolve("image.cr2");
        Files.write(file, tiff.build());

        assertEquals(200, ImageLoader.load(file, 1).image().width());
    }

    @Test
    void decodesTheFirstImageOfRawFilesWithoutJpeg() throws IOException {
        // a DNG file whose first image is an ordinary one, and whose raw data is in a sub-directory
        var tiff = new TiffWriter(ByteOrder.LITTLE_ENDIAN, tiffHeader(ByteOrder.LITTLE_ENDIAN, 0));
        var pixels = new byte[48 * 32];
        var ifd0 = tiff.ifd();
        var raw = tiff.subIfd();
        ifd0.add(256, 4, 48).add(257, 4, 32).add(258, 3, 8).add(259, 3, 1).add(262, 3, 1).offsetOf(273, pixels).add(277, 3, 1)
                .add(278, 4, 32).add(279, 4, pixels.length).add(50706, 1, 0x01040000L).pointer(330, raw);
        raw.add(256, 4, 96).add(257, 4, 64).add(259, 3, 7).add(262, 3, 32803);
        var bytes = tiff.build();
        var file = dir.resolve("image.dng");
        Files.write(file, bytes);

        assertTrue(CameraRawDecoder.isCameraRaw(bytes));
        assertEquals(48, ImageLoader.load(file, 1).image().width());
    }

    @Test
    void leavesOrdinaryTiffFilesToTheTiffDecoder() throws IOException {
        var tiff = new TiffWriter(ByteOrder.LITTLE_ENDIAN, tiffHeader(ByteOrder.LITTLE_ENDIAN, 0));
        var pixels = new byte[32 * 16];
        tiff.ifd().add(256, 4, 32).add(257, 4, 16).add(258, 3, 8).add(259, 3, 1).add(262, 3, 1).offsetOf(273, pixels)
                .add(277, 3, 1).add(278, 4, 16).add(279, 4, pixels.length);
        var bytes = tiff.build();
        assertFalse(CameraRawDecoder.isCameraRaw(bytes));
        var file = dir.resolve("image.tif");
        Files.write(file, bytes);
        assertEquals(32, ImageLoader.load(file, 1).image().width());
    }

    /** An ISO media box: its size, its type, and its content. */
    private static byte[] box(String type, byte[]... content) {
        var size = 8;
        for (var c : content) {
            size += c.length;
        }
        var box = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        box.putInt(size).put(type.getBytes(StandardCharsets.US_ASCII));
        for (var c : content) {
            box.put(c);
        }
        return box.array();
    }

    private static byte[] ints(long... values) {
        var buffer = ByteBuffer.allocate(4 * values.length).order(ByteOrder.BIG_ENDIAN);
        for (var v : values) {
            buffer.putInt((int) v);
        }
        return buffer.array();
    }

    /** A track whose single sample is at an offset of the file. */
    private static byte[] track(int offset, int size) {
        var stsz = box("stsz", ints(0, size, 1));
        var co64 = box("co64", ints(0, 1), ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(offset).array());
        return box("trak", box("mdia", box("minf", box("stbl", stsz, co64))));
    }

    @Test
    void solvesTheFirstTrackOfCanonCr3Files() throws IOException {
        // the EXIF tags are the first directory of the TIFF structure of the CMT2 box
        var exifTiff = new TiffWriter(ByteOrder.LITTLE_ENDIAN, tiffHeader(ByteOrder.LITTLE_ENDIAN, 0));
        exif(exifTiff.ifd(), 0, 600);
        var canon = new byte[16];
        var uuid = ByteBuffer.wrap(canon);
        uuid.putLong(0x85c0b687820f11e0L).putLong(0x8111f4ce462b6a48L);
        var full = jpeg(320, 200);
        var small = jpeg(64, 40);
        var raw = new byte[4096];
        // the size of the boxes before the data doesn't depend on the offsets they hold
        var ftyp = box("ftyp", "crx ".getBytes(StandardCharsets.US_ASCII), ints(1), "crx isom".getBytes(StandardCharsets.US_ASCII));
        var canonBox = box("uuid", canon, box("CMT2", exifTiff.build()));
        var moovSize = box("moov", canonBox, track(0, 0), track(0, 0), track(0, 0)).length;
        var mdatStart = ftyp.length + moovSize + 8;
        var fullOffset = mdatStart;
        var smallOffset = fullOffset + full.length;
        var rawOffset = smallOffset + small.length;
        var moov = box("moov", canonBox, track(fullOffset, full.length), track(smallOffset, small.length), track(rawOffset, raw.length));
        var mdat = box("mdat", full, small, raw);
        var file = ByteBuffer.allocate(ftyp.length + moov.length + mdat.length).put(ftyp).put(moov).put(mdat).array();
        var path = dir.resolve("image.cr3");
        Files.write(path, file);

        assertTrue(CameraRawDecoder.isCameraRaw(file));
        var loaded = ImageLoader.load(path, 1);
        assertEquals(320, loaded.image().width());
        assertEquals(200, loaded.image().height());
        assertEquals(2020.39, loaded.hints().epoch().orElseThrow(), 0.01);
        assertEquals(206.264806 * 6.6656 / 600, loaded.hints().pixelScale().orElseThrow(), 1e-3);
    }

    @Test
    void tellsWhenARawFileHasNoJpegImage() throws IOException {
        var header = tiffHeader(ByteOrder.LITTLE_ENDIAN, 8);
        header[8] = 'C';
        header[9] = 'R';
        var tiff = new TiffWriter(ByteOrder.LITTLE_ENDIAN, header);
        tiff.ifd().add(256, 4, 64).add(257, 4, 48).add(262, 3, 32803);
        var file = dir.resolve("image.cr2");
        Files.write(file, tiff.build());
        var e = assertThrows(IOException.class, () -> ImageLoader.load(file, 1));
        assertTrue(e.getMessage().contains("no JPEG image"), e.getMessage());
    }
}
