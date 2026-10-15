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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Generates the sky data of the web interface from their sources, see README.md.
 *
 * <pre>java asterion-web/src/sky/GenerateSkyData.java asterion-web/src/main/resources</pre>
 */
class GenerateSkyData {
    static final String BSC = "https://cdsarc.cds.unistra.fr/ftp/V/50/catalog.gz";
    static final String BOUNDARIES = "https://cdsarc.cds.unistra.fr/ftp/VI/49/bound_18.dat";
    static final String ROMAN = "https://cdsarc.cds.unistra.fr/ftp/VI/42/data.dat";
    static final String LINES = "https://raw.githubusercontent.com/MarcvdSluys/ConstellationLines/master/ConstellationLines.dat";

    /** The stars of the charts, down to this magnitude. */
    static final double FAINTEST = 5.5;
    /** The maximum length of the segments of boundaries, in degrees. */
    static final double STEP = 1;

    static final String[][] NAMES = {
            {"And", "Andromeda"}, {"Ant", "Antlia"}, {"Aps", "Apus"}, {"Aql", "Aquila"}, {"Aqr", "Aquarius"},
            {"Ara", "Ara"}, {"Ari", "Aries"}, {"Aur", "Auriga"}, {"Boo", "Boötes"}, {"Cae", "Caelum"},
            {"Cam", "Camelopardalis"}, {"Cap", "Capricornus"}, {"Car", "Carina"}, {"Cas", "Cassiopeia"},
            {"Cen", "Centaurus"}, {"Cep", "Cepheus"}, {"Cet", "Cetus"}, {"Cha", "Chamaeleon"}, {"Cir", "Circinus"},
            {"CMa", "Canis Major"}, {"CMi", "Canis Minor"}, {"Cnc", "Cancer"}, {"Col", "Columba"},
            {"Com", "Coma Berenices"}, {"CrA", "Corona Australis"}, {"CrB", "Corona Borealis"}, {"Crt", "Crater"},
            {"Cru", "Crux"}, {"Crv", "Corvus"}, {"CVn", "Canes Venatici"}, {"Cyg", "Cygnus"}, {"Del", "Delphinus"},
            {"Dor", "Dorado"}, {"Dra", "Draco"}, {"Equ", "Equuleus"}, {"Eri", "Eridanus"}, {"For", "Fornax"},
            {"Gem", "Gemini"}, {"Gru", "Grus"}, {"Her", "Hercules"}, {"Hor", "Horologium"}, {"Hya", "Hydra"},
            {"Hyi", "Hydrus"}, {"Ind", "Indus"}, {"Lac", "Lacerta"}, {"Leo", "Leo"}, {"Lep", "Lepus"},
            {"Lib", "Libra"}, {"LMi", "Leo Minor"}, {"Lup", "Lupus"}, {"Lyn", "Lynx"}, {"Lyr", "Lyra"},
            {"Men", "Mensa"}, {"Mic", "Microscopium"}, {"Mon", "Monoceros"}, {"Mus", "Musca"}, {"Nor", "Norma"},
            {"Oct", "Octans"}, {"Oph", "Ophiuchus"}, {"Ori", "Orion"}, {"Pav", "Pavo"}, {"Peg", "Pegasus"},
            {"Per", "Perseus"}, {"Phe", "Phoenix"}, {"Pic", "Pictor"}, {"PsA", "Piscis Austrinus"},
            {"Psc", "Pisces"}, {"Pup", "Puppis"}, {"Pyx", "Pyxis"}, {"Ret", "Reticulum"}, {"Scl", "Sculptor"},
            {"Sco", "Scorpius"}, {"Sct", "Scutum"}, {"Ser", "Serpens"}, {"Sex", "Sextans"}, {"Sge", "Sagitta"},
            {"Sgr", "Sagittarius"}, {"Tau", "Taurus"}, {"Tel", "Telescopium"}, {"TrA", "Triangulum Australe"},
            {"Tri", "Triangulum"}, {"Tuc", "Tucana"}, {"UMa", "Ursa Major"}, {"UMi", "Ursa Minor"}, {"Vel", "Vela"},
            {"Vir", "Virgo"}, {"Vol", "Volans"}, {"Vul", "Vulpecula"}
    };

    public static void main(String[] args) throws Exception {
        var resources = Path.of(args.length > 0 ? args[0] : "asterion-web/src/main/resources");
        var abbreviations = new LinkedHashMap<String, String>();
        for (var name : NAMES) {
            abbreviations.put(name[0].toUpperCase(Locale.ROOT), name[0]);
        }
        if (abbreviations.size() != 88) {
            throw new IllegalStateException("Expected 88 constellations");
        }

        // the Bright Star Catalogue: J2000 positions and visual magnitudes, by HR number
        var stars = new LinkedHashMap<Integer, double[]>();
        var catalog = new String(new GZIPInputStream(new ByteArrayInputStream(download(BSC))).readAllBytes(), StandardCharsets.US_ASCII);
        for (var line : catalog.split("\n")) {
            if (line.length() < 107 || line.substring(75, 77).isBlank()) {
                continue;
            }
            var ra = 15 * (Integer.parseInt(line.substring(75, 77).trim()) + Integer.parseInt(line.substring(77, 79).trim()) / 60.0
                    + Double.parseDouble(line.substring(79, 83).trim()) / 3600);
            var dec = Integer.parseInt(line.substring(84, 86).trim()) + Integer.parseInt(line.substring(86, 88).trim()) / 60.0
                    + Integer.parseInt(line.substring(88, 90).trim()) / 3600.0;
            if (line.charAt(83) == '-') {
                dec = -dec;
            }
            var magnitude = line.substring(102, 107).isBlank() ? 99 : Double.parseDouble(line.substring(102, 107).trim());
            stars.put(Integer.parseInt(line.substring(0, 4).trim()), new double[]{ra, dec, magnitude});
        }

        var json = new StringBuilder("{\n\"names\":{");
        for (var i = 0; i < NAMES.length; i++) {
            json.append(i == 0 ? "" : ",").append('"').append(NAMES[i][0]).append("\":\"").append(NAMES[i][1]).append('"');
        }

        // stick figures, as polylines of BSC stars
        json.append("},\n\"lines\":[");
        var first = true;
        var figures = 0;
        for (var line : new String(download(LINES), StandardCharsets.UTF_8).split("\n")) {
            var fields = line.trim().split("\\s+");
            if (line.startsWith("#") || fields.length < 3) {
                continue;
            }
            var constellation = abbreviation(abbreviations, fields[0]);
            if (Integer.parseInt(fields[1]) != fields.length - 2) {
                throw new IllegalStateException("Unexpected line: " + line);
            }
            json.append(first ? "\n" : ",\n").append("[\"").append(constellation).append('"');
            first = false;
            figures++;
            for (var i = 2; i < fields.length; i++) {
                var star = stars.get(Integer.parseInt(fields[i]));
                json.append(',').append(format(star[0])).append(',').append(format(star[1]));
            }
            json.append(']');
        }

        // boundaries: the B1875 edges, along meridians and parallels, cut in short segments and precessed
        json.append("],\n\"boundaries\":[");
        var polygons = new LinkedHashMap<String, List<double[]>>();
        for (var line : new String(download(BOUNDARIES), StandardCharsets.US_ASCII).split("\n")) {
            var fields = line.trim().split("\\s+");
            if (fields.length < 3) {
                continue;
            }
            polygons.computeIfAbsent(fields[2], _ -> new ArrayList<>())
                    .add(new double[]{15 * Double.parseDouble(fields[0]), Double.parseDouble(fields[1])});
        }
        var toJ2000 = transpose(precession(B1875));
        first = true;
        var outlines = 0;
        for (var polygon : polygons.entrySet()) {
            var constellation = abbreviation(abbreviations, polygon.getKey().replaceAll("[12]$", ""));
            // the outline, as polylines: Octans goes around the pole of 1875, which isn't drawn
            var polylines = new ArrayList<List<double[]>>();
            var current = new ArrayList<double[]>();
            var vertices = polygon.getValue();
            for (var i = 0; i < vertices.size(); i++) {
                var a = vertices.get(i);
                var b = vertices.get((i + 1) % vertices.size());
                if (Math.abs(a[1]) == 90 || Math.abs(b[1]) == 90) {
                    if (current.size() > 1) {
                        polylines.add(current);
                    }
                    current = new ArrayList<>();
                    continue;
                }
                var dRa = b[0] - a[0];
                dRa -= 360 * Math.round(dRa / 360);
                var dDec = b[1] - a[1];
                // the length of the edge, on the sky
                var length = Math.max(Math.abs(dRa) * Math.cos(Math.toRadians(Math.max(Math.abs(a[1]), Math.abs(b[1])))), Math.abs(dDec));
                var steps = Math.max(1, (int) Math.ceil(length / STEP));
                for (var s = current.isEmpty() ? 0 : 1; s <= steps; s++) {
                    current.add(rotate(toJ2000, a[0] + dRa * s / steps, a[1] + dDec * s / steps));
                }
            }
            if (current.size() > 1) {
                polylines.add(current);
            }
            for (var polyline : polylines) {
                json.append(first ? "\n" : ",\n").append("[\"").append(constellation).append('"');
                first = false;
                outlines++;
                for (var p : polyline) {
                    json.append(',').append(format(p[0], 2)).append(',').append(format(p[1], 2));
                }
                json.append(']');
            }
        }

        // the stars of the charts, brightest first: right ascension, declination, magnitude
        json.append("],\n\"stars\":[");
        var bright = stars.values().stream().filter(s -> s[2] <= FAINTEST).sorted((a, b) -> Double.compare(a[2], b[2])).toList();
        for (var i = 0; i < bright.size(); i++) {
            var s = bright.get(i);
            json.append(i == 0 ? "" : ",").append(i % 8 == 0 ? "\n" : "")
                    .append(format(s[0])).append(',').append(format(s[1])).append(',').append(String.format(Locale.ROOT, "%.2f", s[2]));
        }
        json.append("]\n}\n");
        Files.writeString(resources.resolve("web/sky.json"), json.toString());

        // what the server needs to name the constellation of a position: Roman's table, and the names
        var sky = Files.createDirectories(resources.resolve("me/champeau/asterion/web"));
        var table = new StringBuilder("""
                # The constellations, by their southern boundaries in the equinox of B1875, from Roman (1987),
                # Identification of a Constellation From Position, PASP 99, 695 (CDS VI/42): the first row
                # with a declination below the one of a position, and a range of right ascensions which
                # contains it, gives its constellation. Generated by asterion-web/src/sky/GenerateSkyData.java.
                # Lower right ascension (h), upper right ascension (h), lower declination (°), constellation, name
                """);
        var names = new LinkedHashMap<String, String>();
        for (var name : NAMES) {
            names.put(name[0], name[1]);
        }
        for (var line : new String(download(ROMAN), StandardCharsets.US_ASCII).split("\n")) {
            var fields = line.trim().split("\\s+");
            if (fields.length < 4) {
                continue;
            }
            var constellation = abbreviation(abbreviations, fields[3]);
            table.append(String.join(" ", fields[0], fields[1], fields[2], constellation, names.get(constellation))).append('\n');
        }
        Files.writeString(sky.resolve("constellations.txt"), table.toString());
        System.out.printf("%d stars, %d stick figures, %d boundary lines%n", bright.size(), figures, outlines);
    }

    static String abbreviation(Map<String, String> abbreviations, String name) {
        var abbreviation = abbreviations.get(name.toUpperCase(Locale.ROOT));
        if (abbreviation == null) {
            throw new IllegalStateException("Unknown constellation " + name);
        }
        return abbreviation;
    }

    static String format(double value) {
        return format(value, 3);
    }

    static String format(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value).replaceAll("\\.?0+$", "");
    }

    /** B1875.0, in Julian centuries since J2000. */
    static final double B1875 = (2405889.258550475 - 2451545.0) / 36525;

    /** The IAU 1976 precession matrix, from J2000 to the mean equator and equinox of a date in Julian centuries since J2000. */
    static double[][] precession(double t) {
        var arcsec = Math.PI / 180 / 3600;
        var zeta = (2306.2181 + (0.30188 + 0.017998 * t) * t) * t * arcsec;
        var z = (2306.2181 + (1.09468 + 0.018203 * t) * t) * t * arcsec;
        var theta = (2004.3109 - (0.42665 + 0.041833 * t) * t) * t * arcsec;
        double cz = Math.cos(zeta), sz = Math.sin(zeta), cZ = Math.cos(z), sZ = Math.sin(z), ct = Math.cos(theta), st = Math.sin(theta);
        return new double[][]{
                {cZ * ct * cz - sZ * sz, -cZ * ct * sz - sZ * cz, -cZ * st},
                {sZ * ct * cz + cZ * sz, -sZ * ct * sz + cZ * cz, -sZ * st},
                {st * cz, -st * sz, ct}};
    }

    static double[][] transpose(double[][] m) {
        return new double[][]{{m[0][0], m[1][0], m[2][0]}, {m[0][1], m[1][1], m[2][1]}, {m[0][2], m[1][2], m[2][2]}};
    }

    /** Applies a rotation to a position in degrees. */
    static double[] rotate(double[][] m, double raDeg, double decDeg) {
        var ra = Math.toRadians(raDeg);
        var dec = Math.toRadians(decDeg);
        double[] v = {Math.cos(dec) * Math.cos(ra), Math.cos(dec) * Math.sin(ra), Math.sin(dec)};
        var x = m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2];
        var y = m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2];
        var zz = m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2];
        var r = Math.toDegrees(Math.atan2(y, x));
        return new double[]{r < 0 ? r + 360 : r, Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, zz))))};
    }

    static byte[] download(String url) throws IOException, InterruptedException {
        try (var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()) {
            var response = client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException(url + ": HTTP " + response.statusCode());
            }
            return response.body();
        }
    }
}
