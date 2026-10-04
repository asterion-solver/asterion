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
package me.champeau.asterion.cli.astap;

import me.champeau.asterion.image.ImageLoader;
import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.FitsException;
import nom.tam.fits.ImageHDU;
import nom.tam.image.compression.hdu.CompressedImageHDU;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The .wcs file: the header of the image, without its data, followed by the solution. By default,
 * it is a text file with a card per line, which CCDciel reads; with {@code -wcs}, it is a FITS
 * header.
 */
final class AstapWcsFile {
    private static final int CARD = 80;
    private static final int BLOCK = 2880;
    /** Cards of the original header which describe the data, or a previous solution. */
    private static final Pattern REPLACED_KEYS = Pattern.compile(
            "SIMPLE|BITPIX|NAXIS[0-9]*|XTENSION|PCOUNT|GCOUNT|END|PLTSOLVD|WCSAXES|EQUINOX|RADESYS|LONPOLE|LATPOLE"
                    + "|C(TYPE|UNIT|RPIX|RVAL|DELT|ROTA)[0-9]|(CD|PC)[0-9]_[0-9]|(A|B|AP|BP)_([0-9]_[0-9]|ORDER)");

    private AstapWcsFile() {
    }

    /**
     * Formats the file.
     *
     * @param originalCards the cards of the header of the image, see {@link #originalCards(Path)}
     * @param fits true for a FITS header, false for a text file
     */
    static String format(List<String> originalCards, AstapOutcome outcome, String commandLine, boolean sip, boolean fits) {
        var cards = new Cards(fits);
        cards.add("SIMPLE", logical(true), "file does conform to FITS standard");
        cards.add("BITPIX", integer(8), "number of bits per data pixel");
        cards.add("NAXIS", integer(0), "number of data axes");
        originalCards.forEach(cards::padded);
        var wcs = outcome.solution().wcs();
        var withSip = sip && wcs.sipOrder() > 0;
        if (withSip) {
            polynomial(cards, "A", wcs.sipA(), wcs.sipOrder(), "Polynomial order, axis 1. Pixel to Sky");
            polynomial(cards, "B", wcs.sipB(), wcs.sipOrder(), "Polynomial order, axis 2. Pixel to sky.");
            if (wcs.inverseOrder() > 0) {
                polynomial(cards, "AP", wcs.sipAp(), wcs.inverseOrder(), "Inv polynomial order, axis 1. Sky to pixel.");
                polynomial(cards, "BP", wcs.sipBp(), wcs.inverseOrder(), "Inv polynomial order, axis 2. Sky to pixel.");
            }
            cards.add("CTYPE1", string("RA---TAN-SIP"), "TAN (gnomic) projection + SIP distortions");
            cards.add("CTYPE2", string("DEC--TAN-SIP"), "TAN (gnomic) projection + SIP distortions");
        } else {
            cards.add("CTYPE1", string("RA---TAN"), "first parameter RA,    projection TANgential");
            cards.add("CTYPE2", string("DEC--TAN"), "second parameter DEC,  projection TANgential");
        }
        var plate = AstapPlate.of(wcs);
        cards.add("CUNIT1", string("deg"), "Unit of coordinates");
        cards.add("EQUINOX", "%20s".formatted("2000.0"), "Equinox of coordinates");
        cards.add("CRPIX1", number(plate.crpix1()), "X of reference pixel");
        cards.add("CRPIX2", number(plate.crpix2()), "Y of reference pixel");
        cards.add("CRVAL1", number(plate.crval1()), "RA of reference pixel (deg)");
        cards.add("CRVAL2", number(plate.crval2()), "DEC of reference pixel (deg)");
        cards.add("CDELT1", number(plate.cdelt1()), "X pixel size (deg)");
        cards.add("CDELT2", number(plate.cdelt2()), "Y pixel size (deg)");
        cards.add("CROTA1", number(plate.crota1()), "Image twist of X axis        (deg)");
        cards.add("CROTA2", number(plate.crota2()), "Image twist of Y axis        (deg)");
        cards.add("CD1_1", number(plate.cd11()), "CD matrix to convert (x,y) to (Ra, Dec)");
        cards.add("CD1_2", number(plate.cd12()), "CD matrix to convert (x,y) to (Ra, Dec)");
        cards.add("CD2_1", number(plate.cd21()), "CD matrix to convert (x,y) to (Ra, Dec)");
        cards.add("CD2_2", number(plate.cd22()), "CD matrix to convert (x,y) to (Ra, Dec)");
        cards.add("PLTSOLVD", logical(true), "Astrometric solved by Asterion Solver");
        var solved = "COMMENT 7 Solved in " + AstapNumbers.fixed("%.1f", outcome.seconds()) + " sec.";
        if (!Double.isNaN(outcome.offsetDeg())) {
            solved += " Offset was " + AstapNumbers.distance(outcome.offsetDeg()) + ".";
        }
        cards.raw(solved);
        for (var warning : outcome.warnings()) {
            cards.raw("WARNING = '" + warning.replace("'", "''") + "'");
        }
        var text = "cmdline:" + commandLine;
        for (var i = 0; i < text.length(); i += CARD - 8) {
            cards.raw("COMMENT " + text.substring(i, Math.min(text.length(), i + CARD - 8)));
        }
        cards.padded("END");
        return cards.toString();
    }

    /**
     * Reads the header of the image, without the cards which describe its data or a previous
     * solution. Images which aren't FITS files have no header.
     */
    static List<String> originalCards(Path image) throws IOException {
        var cards = new ArrayList<String>();
        if (!ImageLoader.isFits(image)) {
            return cards;
        }
        try (var fits = new Fits(image.toFile())) {
            BasicHDU<?> hdu;
            while ((hdu = fits.readHDU()) != null) {
                if (hdu instanceof CompressedImageHDU compressed) {
                    hdu = compressed.asImageHDU();
                }
                if (hdu instanceof ImageHDU && hdu.getAxes() != null && hdu.getAxes().length >= 2) {
                    for (var cursor = hdu.getHeader().iterator(); cursor.hasNext();) {
                        var card = cursor.next();
                        var key = card.getKey();
                        if (key != null && !REPLACED_KEYS.matcher(key).matches()) {
                            // long strings are several cards
                            var image80 = card.toString();
                            for (var i = 0; i < image80.length(); i += CARD) {
                                cards.add(image80.substring(i, Math.min(image80.length(), i + CARD)).stripTrailing());
                            }
                        }
                    }
                    break;
                }
            }
        } catch (FitsException e) {
            throw new IOException(e.getMessage(), e);
        }
        return cards;
    }

    private static void polynomial(Cards cards, String name, double[] c, int order, String comment) {
        cards.add(name + "_ORDER", integer(order), comment);
        var n = order + 1;
        for (var degree = 0; degree <= order; degree++) {
            for (var p = degree; p >= 0; p--) {
                var q = degree - p;
                cards.add(name + "_" + p + "_" + q, number(c[p * n + q]), "SIP coefficient");
            }
        }
    }

    private static String logical(boolean value) {
        return "%20s".formatted(value ? "T" : "F");
    }

    private static String integer(int value) {
        return "%20s".formatted(Integer.toString(value));
    }

    private static String number(double value) {
        return "%20s".formatted(AstapNumbers.wcs(value));
    }

    /** A FITS string, which is at least 8 characters long. */
    private static String string(String value) {
        return "%-20s".formatted("'" + "%-8s".formatted(value) + "'");
    }

    private static final class Cards {
        private final boolean fits;
        private final StringBuilder sb = new StringBuilder();

        Cards(boolean fits) {
            this.fits = fits;
        }

        void add(String key, String value, String comment) {
            padded("%-8s= %s / %s".formatted(key, value, comment));
        }

        /** Adds a card padded to 80 characters. */
        void padded(String card) {
            append("%-80s".formatted(card));
        }

        /** Adds a card which isn't padded in text files, like the comments and warnings of ASTAP. */
        void raw(String card) {
            append(fits ? "%-80s".formatted(card) : card);
        }

        private void append(String card) {
            if (fits) {
                sb.append(card, 0, Math.min(card.length(), CARD));
            } else {
                sb.append(card).append(System.lineSeparator());
            }
        }

        @Override
        public String toString() {
            if (fits) {
                sb.repeat(' ', (BLOCK - sb.length() % BLOCK) % BLOCK);
            }
            return sb.toString();
        }
    }
}
