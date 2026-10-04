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
package me.champeau.asterion.catalog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Reader of the Tycho-2 catalogue (Høg et al., 2000), as distributed by the CDS: 2.5 million
 * stars, complete to magnitude 11. The first supplement is read too, since it contains the bright
 * stars which are missing from the main catalogue.
 */
public final class Tycho2 {
    public static final String BASE_URL = "https://cdsarc.cds.unistra.fr/ftp/I/259/";
    private static final String SUPPLEMENT = "suppl_1.dat.gz";
    private static final double EPOCH = 2000.0;
    /** The epoch of positions of the supplement. */
    private static final double SUPPLEMENT_EPOCH = 1991.25;
    private static final double MAS = Math.PI / (180 * 3600 * 1000);

    private Tycho2() {
    }

    /** The names of the files of the catalogue. */
    public static List<String> files() {
        var files = new ArrayList<String>();
        for (var i = 0; i < 20; i++) {
            files.add("tyc2.dat.%02d.gz".formatted(i));
        }
        files.add(SUPPLEMENT);
        return files;
    }

    /** Reads the catalogue from a directory which contains its files. */
    public static StarData read(Path directory) throws IOException {
        List<StarData> parts;
        try {
            parts = files().parallelStream().map(name -> {
                try {
                    return readFile(directory.resolve(name), name.equals(SUPPLEMENT));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        var all = new StarData(EPOCH, 2_600_000);
        for (var part : parts) {
            all.addAll(part);
        }
        return all;
    }

    private static StarData readFile(Path file, boolean supplement) throws IOException {
        var stars = new StarData(EPOCH, supplement ? 20_000 : 130_000);
        try (var reader = new BufferedReader(
                new InputStreamReader(new GZIPInputStream(Files.newInputStream(file), 1 << 16), StandardCharsets.ISO_8859_1))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (supplement) {
                    parseSupplement(line, stars);
                } else {
                    parseMain(line, stars);
                }
            }
        }
        return stars;
    }

    private static void parseMain(String line, StarData stars) {
        if (line.length() < 177) {
            return;
        }
        var ra = number(line, 15, 27);
        var dec = number(line, 28, 40);
        var pmRa = number(line, 41, 48);
        var pmDec = number(line, 49, 56);
        if (Double.isNaN(ra) || Double.isNaN(dec)) {
            // no mean position: use the observed one, which has no proper motion
            ra = number(line, 152, 164);
            dec = number(line, 165, 177);
            pmRa = 0;
            pmDec = 0;
        }
        var mag = magnitude(number(line, 110, 116), number(line, 123, 129));
        if (Double.isNaN(ra) || Double.isNaN(dec) || Float.isNaN(mag)) {
            return;
        }
        stars.add(Math.toRadians(ra), Math.toRadians(dec), zeroIfNaN(pmRa), zeroIfNaN(pmDec), mag);
    }

    private static void parseSupplement(String line, StarData stars) {
        if (line.length() < 102) {
            return;
        }
        var ra = Math.toRadians(number(line, 15, 27));
        var dec = Math.toRadians(number(line, 28, 40));
        var pmRa = zeroIfNaN(number(line, 41, 48));
        var pmDec = zeroIfNaN(number(line, 49, 56));
        var mag = magnitude(number(line, 83, 89), number(line, 96, 102));
        if (Double.isNaN(ra) || Double.isNaN(dec) || Float.isNaN(mag)) {
            return;
        }
        var years = EPOCH - SUPPLEMENT_EPOCH;
        ra += pmRa * MAS * years / Math.max(1e-6, Math.cos(dec));
        dec += pmDec * MAS * years;
        stars.add(ra, dec, pmRa, pmDec, mag);
    }

    /** Approximates the Johnson V magnitude from Tycho BT and VT magnitudes. */
    private static float magnitude(double bt, double vt) {
        if (Double.isNaN(vt)) {
            return (float) bt;
        }
        if (Double.isNaN(bt)) {
            return (float) vt;
        }
        return (float) (vt - 0.090 * (bt - vt));
    }

    private static float zeroIfNaN(double v) {
        return Double.isNaN(v) ? 0 : (float) v;
    }

    private static double number(String line, int from, int to) {
        var s = line.substring(from, to).trim();
        if (s.isEmpty()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException _) {
            return Double.NaN;
        }
    }
}
