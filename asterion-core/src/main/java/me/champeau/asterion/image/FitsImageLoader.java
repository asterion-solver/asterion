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

import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.FitsException;
import nom.tam.fits.Header;
import nom.tam.fits.ImageHDU;
import nom.tam.image.compression.hdu.CompressedImageHDU;
import nom.tam.util.RandomAccess;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.OptionalDouble;
import java.util.regex.Pattern;

/**
 * Reads FITS images. The structure of the file is read with nom-tam-fits. The pixels of regular,
 * uncompressed images are then decoded and binned in parallel straight from the memory mapped file,
 * which is several times faster than going through intermediate arrays for large frames. Other
 * files (compressed images, for example) are fully decoded by nom-tam-fits.
 */
public final class FitsImageLoader {
    /** The patterns of Bayer filters, as FITS headers describe them. */
    private static final Pattern BAYER_PATTERN = Pattern.compile("[RGB]{4}", Pattern.CASE_INSENSITIVE);

    private FitsImageLoader() {
    }

    /** Loads an image, with automatic binning. */
    public static LoadedImage load(Path path) throws IOException {
        return load(path, 0);
    }

    /**
     * Loads an image.
     *
     * @param binning the binning factor, or 0 to select it automatically: raw frames of color sensors
     * are binned 2x2, as well as very large images
     */
    public static LoadedImage load(Path path, int binning) throws IOException {
        try (var fits = new Fits(path.toFile())) {
            BasicHDU<?> hdu;
            while ((hdu = fits.readHDU()) != null) {
                if (hdu instanceof CompressedImageHDU compressed) {
                    hdu = compressed.asImageHDU();
                }
                if (hdu instanceof ImageHDU image && image.getAxes() != null && image.getAxes().length >= 2) {
                    return load(path, fits, image, binning);
                }
            }
            throw new IOException("No image found in " + path);
        } catch (FitsException e) {
            throw new IOException("Unable to read " + path + ": " + e.getMessage(), e);
        }
    }

    private static LoadedImage load(Path path, Fits fits, ImageHDU hdu, int binning) throws FitsException, IOException {
        var header = hdu.getHeader();
        var axes = hdu.getAxes();
        var width = axes[axes.length - 1];
        var height = axes[axes.length - 2];
        var planes = 1;
        for (var i = 0; i < axes.length - 2; i++) {
            planes *= axes[i];
        }
        var hints = hints(header);
        binning = GrayBinner.binningFor(width, height, binning, hints.bayer());
        var bitpix = header.getIntValue("BITPIX");
        var bzero = header.getDoubleValue("BZERO", 0);
        var bscale = header.getDoubleValue("BSCALE", 1);
        var offset = hdu.getData().getFileOffset();
        var direct = offset > 0
                && fits.getStream() instanceof RandomAccess
                && (bitpix == 8 || bitpix == 16 || bitpix == 32 || bitpix == -32 || bitpix == -64);
        float[] pixels;
        var w = width / binning;
        var h = height / binning;
        if (direct) {
            pixels = readDirect(path, offset, bitpix, width, height, planes, binning, bzero, bscale);
        } else {
            pixels = fromKernel(hdu.getKernel(), width, height, binning, bzero, bscale);
        }
        return new LoadedImage(new GrayImage(w, h, pixels, binning, width, height), hints);
    }

    private static float[] readDirect(Path path, long offset, int bitpix, int width, int height, int planes, int binning, double bzero,
                                      double bscale) throws IOException {
        var bytes = Math.abs(bitpix) / 8;
        var planeSize = (long) width * height * bytes;
        var rowBytes = (long) width * bytes;
        var zero = (float) (bzero * planes * binning * binning);
        // the file is mapped rather than read: pixels are decoded straight from the page cache,
        // without copying them to intermediate buffers first
        try (var channel = FileChannel.open(path, StandardOpenOption.READ); var arena = Arena.ofShared()) {
            if (channel.size() < offset + planeSize * planes) {
                throw new IOException("Truncated FITS file " + path);
            }
            var segment = channel.map(FileChannel.MapMode.READ_ONLY, offset, planeSize * planes, arena);
            return RowDecoder.decode(segment, () -> RowDecoder.fits(bitpix, width), width, height, planes, planeSize, rowBytes, binning,
                    (float) bscale, zero);
        }
    }

    /** Converts the array decoded by nom-tam-fits, summing planes and binning pixels. */
    private static float[] fromKernel(Object kernel, int width, int height, int binning, double bzero, double bscale) throws IOException {
        var w = width / binning;
        var h = height / binning;
        var out = new float[w * h];
        accumulate(kernel, out, w, h, binning, bzero, bscale);
        return out;
    }

    private static void accumulate(Object array, float[] out, int w, int h, int binning, double bzero, double bscale) throws IOException {
        if (array instanceof Object[] nested && !(nested instanceof byte[][] || nested instanceof short[][] || nested instanceof int[][]
                || nested instanceof long[][] || nested instanceof float[][] || nested instanceof double[][])) {
            for (var plane : nested) {
                accumulate(plane, out, w, h, binning, bzero, bscale);
            }
            return;
        }
        if (!(array instanceof Object[] rows)) {
            throw new IOException("Unsupported FITS image data");
        }
        for (var y = 0; y < h * binning; y++) {
            var row = rows[y];
            var base = (y / binning) * w;
            for (var x = 0; x < w * binning; x++) {
                var v = switch (row) {
                    case byte[] a -> a[x] & 0xFF;
                    case short[] a -> a[x];
                    case int[] a -> a[x];
                    case long[] a -> a[x];
                    case float[] a -> Float.isNaN(a[x]) ? 0 : a[x];
                    case double[] a -> Double.isNaN(a[x]) ? 0 : a[x];
                    default -> throw new IOException("Unsupported FITS image data");
                };
                out[base + x / binning] += (float) (v * bscale + bzero);
            }
        }
    }

    /** Extracts solving hints from a FITS header. */
    public static ImageHints hints(Header header) {
        OptionalDouble ra;
        OptionalDouble dec;
        // a previous solution is more reliable than the position of the mount, which may be a
        // placeholder: SharpCap writes RA and DEC even when no mount is connected
        if (header.getStringValue("CTYPE1", "").startsWith("RA") && header.containsKey("CRVAL1") && header.containsKey("CRVAL2")) {
            ra = OptionalDouble.of(header.getDoubleValue("CRVAL1"));
            dec = OptionalDouble.of(header.getDoubleValue("CRVAL2"));
        } else {
            ra = angle(header, "RA", false);
            if (ra.isEmpty()) {
                ra = angle(header, "OBJCTRA", true);
            }
            dec = angle(header, "DEC", false);
            if (dec.isEmpty()) {
                dec = angle(header, "OBJCTDEC", false);
            }
        }
        // the size of the pixels of the file, which includes binning
        var pixelSize = header.getDoubleValue("XPIXSZ", header.getDoubleValue("PIXSIZE1", 0));
        var scale = OptionalDouble.empty();
        for (var key : new String[]{"PIXSCALE", "SCALE", "SECPIX"}) {
            var v = header.getDoubleValue(key, 0);
            if (v > 0) {
                scale = OptionalDouble.of(v);
                break;
            }
        }
        if (scale.isEmpty()) {
            var focal = header.getDoubleValue("FOCALLEN", 0);
            if (focal > 0 && pixelSize > 0) {
                scale = OptionalDouble.of(206.264806 * pixelSize / focal);
            }
        }
        var epoch = OptionalDouble.empty();
        var date = header.getStringValue("DATE-OBS");
        if (date != null && date.length() >= 10) {
            try {
                var day = LocalDate.parse(date.substring(0, 10));
                epoch = OptionalDouble.of(day.getYear() + (day.getDayOfYear() - 1) / 365.25);
            } catch (RuntimeException _) {
                // not an ISO date, ignore it
            }
        }
        // a raw frame of a color sensor has a single plane, and a Bayer pattern such as RGGB: an RGB
        // image with 3 planes, which SharpCap describes with COLORTYP = 'RGB', is already debayered
        var planes = 1;
        for (var axis = 3; axis <= header.getIntValue("NAXIS", 0); axis++) {
            planes *= header.getIntValue("NAXIS" + axis, 1);
        }
        var bayer = planes == 1
                && (header.containsKey("BAYERPAT") || BAYER_PATTERN.matcher(header.getStringValue("COLORTYP", "")).matches());
        var hints = ImageHints.builder().bayer(bayer);
        ra.ifPresent(hints::raDeg);
        dec.ifPresent(hints::decDeg);
        scale.ifPresent(hints::pixelScale);
        epoch.ifPresent(hints::epoch);
        if (pixelSize > 0) {
            hints.pixelSizeMicrons(pixelSize);
        }
        return hints.build();
    }

    /**
     * Reads an angle which is either a number of degrees, or a sexagesimal string.
     *
     * @param hours true if sexagesimal strings are expressed in hours
     */
    private static OptionalDouble angle(Header header, String key, boolean hours) {
        if (!header.containsKey(key)) {
            return OptionalDouble.empty();
        }
        var value = header.findCard(key).getValue();
        if (value == null || value.isBlank()) {
            return OptionalDouble.empty();
        }
        value = value.trim();
        try {
            return OptionalDouble.of(Double.parseDouble(value));
        } catch (NumberFormatException _) {
            // sexagesimal
        }
        var parts = value.split("[\\s:]+");
        try {
            var negative = parts[0].startsWith("-");
            var v = Math.abs(Double.parseDouble(parts[0]));
            if (parts.length > 1) {
                v += Double.parseDouble(parts[1]) / 60;
            }
            if (parts.length > 2) {
                v += Double.parseDouble(parts[2]) / 3600;
            }
            if (hours || "RA".equals(key)) {
                v *= 15;
            }
            return OptionalDouble.of(negative ? -v : v);
        } catch (NumberFormatException _) {
            return OptionalDouble.empty();
        }
    }
}
