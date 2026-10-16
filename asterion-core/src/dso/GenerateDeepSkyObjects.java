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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates the table of deep sky objects of Asterion from OpenNGC, see README.md.
 *
 * <pre>java asterion-core/src/dso/GenerateDeepSkyObjects.java asterion-core/src/main/resources</pre>
 */
class GenerateDeepSkyObjects {
    static final String RELEASE = "v20260501";
    static final String BASE = "https://raw.githubusercontent.com/mattiaverga/OpenNGC/" + RELEASE + "/database_files/";

    /** The types of OpenNGC, in the order of the codes of the table. */
    static final List<String> TYPES = List.of("G", "GPair", "GTrpl", "GGroup", "OCl", "GCl", "Cl+N", "PN", "Neb", "HII", "EmN", "RfN",
            "DrkN", "SNR", "Nova", "*", "**", "*Ass", "Other");

    /** The catalogs of the designations, by the prefix of the names of OpenNGC. */
    static final Map<String, String> CATALOGS = Map.ofEntries(
            Map.entry("NGC", "NGC"), Map.entry("IC", "IC"), Map.entry("M", "M"), Map.entry("B", "Barnard"), Map.entry("C", "Caldwell"),
            Map.entry("Cl", "Collinder"), Map.entry("ESO", "ESO"), Map.entry("H", "Harvard"), Map.entry("HCG", "HCG"),
            Map.entry("Mel", "Melotte"), Map.entry("MWSC", "MWSC"), Map.entry("PGC", "PGC"), Map.entry("UGC", "UGC"));

    public static void main(String[] args) throws Exception {
        var resources = Path.of(args.length > 0 ? args[0] : "asterion-core/src/main/resources");
        var rows = new ArrayList<Map<String, String>>();
        for (var file : new String[]{"NGC.csv", "addendum.csv"}) {
            var lines = new String(download(BASE + file), StandardCharsets.UTF_8).split("\n");
            var columns = lines[0].trim().split(";");
            for (var i = 1; i < lines.length; i++) {
                if (lines[i].isBlank()) {
                    continue;
                }
                var values = lines[i].split(";", -1);
                var row = new HashMap<String, String>();
                for (var c = 0; c < columns.length && c < values.length; c++) {
                    row.put(columns[c], values[c].trim());
                }
                rows.add(row);
            }
        }
        // the objects which are kept, see DeepSkyObjects for the format of the table
        var kept = new ArrayList<Map<String, String>>();
        for (var row : rows) {
            var type = row.get("Type");
            // duplicates of other entries, and objects which don't exist
            if (!type.equals("Dup") && !type.equals("NonEx") && !row.get("RA").isEmpty()) {
                kept.add(row);
            }
        }
        var count = kept.size();
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeBytes("DSO2");
        out.writeInt(count);
        var strings = new ByteArrayOutputStream();
        var offsets = new int[2 * count + 1];
        for (var i = 0; i < count; i++) {
            var row = kept.get(i);
            var ra = hours(row.get("RA"));
            var dec = degrees(row.get("Dec"));
            out.writeFloat((float) ra);
            out.writeFloat((float) dec);
            out.writeFloat((float) (Math.cos(Math.toRadians(dec)) * Math.cos(Math.toRadians(ra))));
            out.writeFloat((float) (Math.cos(Math.toRadians(dec)) * Math.sin(Math.toRadians(ra))));
            out.writeFloat((float) Math.sin(Math.toRadians(dec)));
            out.writeFloat(number(row.get("MajAx")));
            out.writeFloat(number(row.get("MinAx")));
            out.writeFloat(number(row.get("PosAng")));
            var magnitude = number(row.get("V-Mag"));
            out.writeFloat(Float.isNaN(magnitude) ? number(row.get("B-Mag")) : magnitude);
        }
        for (var row : kept) {
            var type = row.get("Type");
            var typeCode = TYPES.indexOf(type);
            if (typeCode < 0) {
                throw new IllegalStateException("Unknown type " + type + " of " + row.get("Name"));
            }
            out.writeByte(typeCode);
        }
        for (var row : kept) {
            out.writeShort(row.get("M").isEmpty() ? 0 : Integer.parseInt(row.get("M")));
        }
        for (var i = 0; i < count; i++) {
            var row = kept.get(i);
            offsets[2 * i] = strings.size();
            strings.writeBytes(designation(row.get("Name")).getBytes(StandardCharsets.UTF_8));
            offsets[2 * i + 1] = strings.size();
            strings.writeBytes(row.get("Common names").replace(",", ", ").getBytes(StandardCharsets.UTF_8));
        }
        offsets[2 * count] = strings.size();
        for (var offset : offsets) {
            out.writeInt(offset);
        }
        out.write(strings.toByteArray());
        var target = resources.resolve("me/champeau/asterion/catalog/deep-sky-objects.bin");
        Files.createDirectories(target.getParent());
        Files.write(target, bytes.toByteArray());
        System.out.printf("%d objects, %d bytes%n", count, bytes.size());
    }

    /** "NGC0224" gives "NGC 224", "Mel022" gives "Melotte 22", "IC0186A" gives "IC 186A". */
    static String designation(String name) {
        var i = 0;
        while (i < name.length() && Character.isLetter(name.charAt(i))) {
            i++;
        }
        var catalog = CATALOGS.get(name.substring(0, i));
        if (catalog == null) {
            throw new IllegalStateException("Unknown catalog of " + name);
        }
        var number = name.substring(i).replaceFirst("^0+(?=\\d)", "");
        return catalog + " " + number;
    }

    static float number(String value) {
        return value.isEmpty() ? Float.NaN : Float.parseFloat(value);
    }

    /** Right ascension in degrees, from "hh:mm:ss.ss". */
    static double hours(String value) {
        var parts = value.split(":");
        return 15 * (Integer.parseInt(parts[0]) + Integer.parseInt(parts[1]) / 60.0 + Double.parseDouble(parts[2]) / 3600);
    }

    /** Declination in degrees, from "+dd:mm:ss.s". */
    static double degrees(String value) {
        var parts = value.split(":");
        var sign = parts[0].startsWith("-") ? -1 : 1;
        return sign * (Math.abs(Integer.parseInt(parts[0])) + Integer.parseInt(parts[1]) / 60.0 + Double.parseDouble(parts[2]) / 3600);
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
