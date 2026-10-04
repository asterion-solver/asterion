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
package me.champeau.asterion.cli;

import me.champeau.asterion.image.ImageLoader;
import me.champeau.asterion.solver.Solution;
import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.FitsException;
import nom.tam.fits.Header;
import nom.tam.fits.ImageHDU;
import nom.tam.image.compression.hdu.CompressedImageHDU;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;

/** Saves solutions as FITS headers. */
public final class WcsWriter {
    private static final int BLOCK = 2880;
    /** Keywords of a previous solution, which would conflict with the new one. */
    private static final Pattern STALE_KEYS = Pattern.compile(
            "(CD|PC)[0-9]_[0-9]|(A|B|AP|BP)_[0-9]_[0-9]|(A|B|AP|BP)_ORDER|CROTA[12]|CDELT[12]");

    private WcsWriter() {
    }

    private static Path sibling(Path image, String extension) {
        var name = image.getFileName().toString();
        var dot = name.lastIndexOf('.');
        return image.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + extension);
    }

    /** Writes the stars which were matched with the catalog next to the image, as a CSV file. */
    static void writeMatches(Path image, Solution solution) throws IOException {
        var sb = new StringBuilder("x,y,ra,dec,residual_arcsec\n");
        for (var match : solution.matches()) {
            sb.append(String.format(Locale.ROOT, "%.3f,%.3f,%.7f,%.7f,%.3f%n", match.x(), match.y(), match.raDeg(), match.decDeg(),
                    match.residualArcsec()));
        }
        Files.writeString(sibling(image, ".matches.csv"), sb, StandardCharsets.US_ASCII);
    }

    /** Writes a FITS file without data next to the image, which header describes the solution. */
    static void writeWcsFile(Path image, Solution solution) throws IOException {
        var target = sibling(image, ".wcs");
        var sb = new StringBuilder();
        card(sb, "SIMPLE", true, "FITS header");
        card(sb, "BITPIX", 8, null);
        card(sb, "NAXIS", 0, "no image data");
        for (var entry : solution.wcs().toFitsKeywords().entrySet()) {
            card(sb, entry.getKey(), entry.getValue(), null);
        }
        card(sb, "PIXSCALE", solution.pixelScale(), "[arcsec/pixel]");
        card(sb, "ROTATION", solution.rotationDeg(), "[deg] Y axis, east of north");
        card(sb, "NMATCH", solution.matchedStars(), "matched stars");
        card(sb, "RMSFIT", solution.rmsArcsec(), "[arcsec] residuals of matched stars");
        sb.append("%-80s".formatted("END"));
        while (sb.length() % BLOCK != 0) {
            sb.append(' ');
        }
        Files.writeString(target, sb, StandardCharsets.US_ASCII);
    }

    private static void card(StringBuilder sb, String key, Object value, String comment) {
        var text = switch (value) {
            case Boolean b -> "%20s".formatted(b ? "T" : "F");
            // FITS numbers are ASCII whatever the locale
            case Integer i -> String.format(Locale.ROOT, "%20d", i);
            case Double d -> "%20s".formatted(formatDouble(d));
            default -> "%-20s".formatted("'" + "%-8s".formatted(value) + "'");
        };
        var card = "%-8s= %s".formatted(key, text);
        if (comment != null) {
            card += " / " + comment;
        }
        sb.append("%-80s".formatted(card), 0, 80);
    }

    private static String formatDouble(double d) {
        var s = String.format(Locale.ROOT, "%.14G", d);
        if (s.contains("E")) {
            // remove the padding zeros of the mantissa
            var mantissa = s.substring(0, s.indexOf('E')).replaceAll("0+$", "");
            return (mantissa.endsWith(".") ? mantissa + "0" : mantissa) + s.substring(s.indexOf('E'));
        }
        var trimmed = s.contains(".") ? s.replaceAll("0+$", "") : s;
        return trimmed.endsWith(".") ? trimmed + "0" : trimmed;
    }

    /** Adds the solution to the header of the image, which must be an uncompressed FITS file. */
    public static void updateHeader(Path image, Solution solution) throws IOException {
        var name = image.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".fit") || name.endsWith(".fits") || name.endsWith(".fts"))) {
            throw new IOException("Only uncompressed FITS files can be updated, use --output or --wcs for " + image);
        }
        if (!updateInPlace(image, solution)) {
            throw new IOException("Only uncompressed FITS files can be updated, use --output or --wcs for " + image);
        }
    }

    /**
     * Writes a copy of the image, with the solution in its header. Compressed images are written
     * uncompressed. The image itself is not modified.
     */
    static void writeCopy(Path image, Solution solution, Path target) throws IOException {
        if (Files.exists(target) && Files.isSameFile(image, target)) {
            throw new IOException("The output would replace " + image + ", use --update to modify it");
        }
        if (!ImageLoader.isFits(image)) {
            throw new IOException("Only FITS images can be written with the solution, use --wcs for " + image);
        }
        var parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        var tmp = target.resolveSibling(target.getFileName() + ".tmp");
        var name = image.getFileName().toString().toLowerCase(Locale.ROOT);
        var plain = !(name.endsWith(".gz") || name.endsWith(".fz") || name.endsWith(".z") || name.endsWith(".bz2"));
        try {
            var done = false;
            if (plain) {
                // a raw copy keeps the file exactly as it is, except for the header
                Files.copy(image, tmp, StandardCopyOption.REPLACE_EXISTING);
                done = updateInPlace(tmp, solution);
            }
            if (!done) {
                writeDecompressed(image, solution, tmp);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Adds the solution to the header of the first image of a FITS file.
     *
     * @return false if the file has no uncompressed image
     */
    private static boolean updateInPlace(Path image, Solution solution) throws IOException {
        Path tmp = null;
        try (var fits = new Fits(image.toFile())) {
            BasicHDU<?> target = null;
            BasicHDU<?> hdu;
            while ((hdu = fits.readHDU()) != null) {
                if (target == null && isImage(hdu)) {
                    target = hdu;
                }
            }
            if (target == null) {
                return false;
            }
            var header = target.getHeader();
            addSolution(header, solution);
            if (header.rewriteable()) {
                header.rewrite();
            } else {
                // the header grew: the whole file has to be written again
                tmp = image.resolveSibling(image.getFileName() + ".rewrite");
                fits.write(tmp.toFile());
            }
        } catch (FitsException e) {
            throw new IOException("Unable to update " + image + ": " + e.getMessage(), e);
        }
        if (tmp != null) {
            Files.move(tmp, image, StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }

    /** Writes all the HDUs of a FITS file to a new one, decompressing images, with the solution in the header of the first image. */
    private static void writeDecompressed(Path image, Solution solution, Path target) throws IOException {
        try (var in = new Fits(image.toFile()); var out = new Fits()) {
            var found = false;
            BasicHDU<?> hdu;
            while ((hdu = in.readHDU()) != null) {
                if (hdu instanceof CompressedImageHDU compressed) {
                    hdu = compressed.asImageHDU();
                }
                if (!found && isImage(hdu)) {
                    addSolution(hdu.getHeader(), solution);
                    found = true;
                }
                out.addHDU(hdu);
            }
            if (!found) {
                throw new IOException("No image found in " + image);
            }
            out.write(target.toFile());
        } catch (FitsException e) {
            throw new IOException("Unable to write " + target + ": " + e.getMessage(), e);
        }
    }

    private static boolean isImage(BasicHDU<?> hdu) {
        return hdu instanceof ImageHDU && hdu.getAxes() != null && hdu.getAxes().length >= 2;
    }

    /** Replaces the WCS keywords of a header with the ones of a solution. */
    private static void addSolution(Header header, Solution solution) {
        var stale = new ArrayList<String>();
        for (var cursor = header.iterator(); cursor.hasNext();) {
            var key = cursor.next().getKey();
            if (key != null && STALE_KEYS.matcher(key).matches()) {
                stale.add(key);
            }
        }
        stale.forEach(header::deleteKey);
        for (var entry : solution.wcs().toFitsKeywords().entrySet()) {
            switch (entry.getValue()) {
                case Integer i -> header.addValue(entry.getKey(), i, null);
                case Double d -> header.addValue(entry.getKey(), d, null);
                default -> header.addValue(entry.getKey(), entry.getValue().toString(), null);
            }
        }
    }
}
