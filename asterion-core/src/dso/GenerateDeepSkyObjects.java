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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Generates the table of deep sky objects of Asterion from OpenNGC and from the catalogs of
 * Sharpless and Lynds, see README.md.
 *
 * <pre>java asterion-core/src/dso/GenerateDeepSkyObjects.java asterion-core/src/main/resources</pre>
 */
class GenerateDeepSkyObjects {
    static final String RELEASE = "v20260501";
    static final String BASE = "https://raw.githubusercontent.com/mattiaverga/OpenNGC/" + RELEASE + "/database_files/";
    /** The tables of VizieR, with the positions of their objects computed for J2000 by VizieR. */
    static final String VIZIER = "https://vizier.cds.unistra.fr/viz-bin/asu-tsv?-out.max=unlimited&-out.add=_RAJ2000,_DEJ2000&-out.all&-oc.form=d&-source=";

    /** The types of OpenNGC, in the order of the codes of the table. */
    static final List<String> TYPES = List.of("G", "GPair", "GTrpl", "GGroup", "OCl", "GCl", "Cl+N", "PN", "Neb", "HII", "EmN", "RfN",
            "DrkN", "SNR", "Nova", "*", "**", "*Ass", "Other");
    /** The types of the objects which bright nebulae can be: dark nebulae are only dark nebulae. */
    static final Set<String> BRIGHT_NEBULAE = Set.of("Neb", "HII", "EmN", "RfN", "SNR", "Cl+N");

    /** The catalogs of the designations, by the prefix of the names of OpenNGC. */
    static final Map<String, String> CATALOGS = Map.ofEntries(
            Map.entry("NGC", "NGC"), Map.entry("IC", "IC"), Map.entry("M", "M"), Map.entry("B", "Barnard"), Map.entry("C", "Caldwell"),
            Map.entry("Cl", "Collinder"), Map.entry("ESO", "ESO"), Map.entry("H", "Harvard"), Map.entry("HCG", "HCG"),
            Map.entry("Mel", "Melotte"), Map.entry("MWSC", "MWSC"), Map.entry("PGC", "PGC"), Map.entry("UGC", "UGC"));

    /**
     * The catalogs, the most famous first, in the order of the codes of the table: an object which
     * several catalogs list keeps the designation of the most famous one, and the others become
     * its aliases. Messier objects are NGC or IC objects, or others for OpenNGC.
     */
    static final int NGC = 0;
    static final int IC = 1;
    static final int OTHER = 2;
    static final int SHARPLESS = 3;
    static final int LBN = 4;
    static final int LDN = 5;

    /**
     * Common names of objects of Sharpless and Lynds, which these catalogs don't have, and of a
     * few NGC and IC objects which OpenNGC doesn't name: the names astrophotographers use.
     */
    static final Map<String, String> COMMON_NAMES = Map.ofEntries(
            Map.entry("IC 1805", "Heart Nebula"), Map.entry("IC 1848", "Soul Nebula"), Map.entry("NGC 281", "Pacman Nebula"),
            Map.entry("Sh2-101", "Tulip Nebula"), Map.entry("Sh2-103", "Cygnus Loop"), Map.entry("Sh2-106", "Celestial Snow Angel"),
            Map.entry("Sh2-119", "Clamshell Nebula"), Map.entry("Sh2-129", "Flying Bat Nebula"), Map.entry("Sh2-132", "Lion Nebula"),
            Map.entry("Sh2-136", "Ghost Nebula"), Map.entry("Sh2-157", "Lobster Claw Nebula"), Map.entry("Sh2-240", "Spaghetti Nebula"),
            Map.entry("Sh2-261", "Lower's Nebula"), Map.entry("Sh2-264", "Lambda Orionis Ring"), Map.entry("Sh2-276", "Barnard's Loop"),
            Map.entry("Sh2-308", "Dolphin Head Nebula"), Map.entry("LBN 437", "Gecko Nebula"), Map.entry("LBN 777", "Baby Eagle Nebula"),
            Map.entry("LDN 1235", "Shark Nebula"), Map.entry("LDN 1622", "Boogeyman Nebula"));

    /** A position closer than this to the one of an object of a more famous catalog is the same object, in arcminutes. */
    static final double SAME_POSITION = 5;
    /** The positions of these catalogs are rough: objects of similar sizes are the same if they're this close, relative to their size. */
    static final double SAME_EXTENT = 0.5;
    /** The objects whose centers are this close, relative to the size of the lesser known one, are the same, whatever their sizes. */
    static final double SAME_CENTER = 0.1;
    /** The distance under which the centers of objects are the same, at most, in arcminutes: the largest regions contain others. */
    static final double SAME_CENTER_MAX = 15;

    /** An object of the table. */
    static final class Entry {
        String designation;
        int catalog;
        int messier;
        String commonName = "";
        final List<String> aliases = new ArrayList<>();
        String type;
        double ra;
        double dec;
        float major = Float.NaN;
        float minor = Float.NaN;
        float angle = Float.NaN;
        float magnitude = Float.NaN;
        /** The major axis before the object took the size of others, which tells the objects which are the same. */
        float listedMajor = Float.NaN;
    }

    private final List<Entry> entries = new ArrayList<>();
    /** The objects, by their designations and their aliases. */
    private final Map<String, Entry> byName = new HashMap<>();
    /** The objects of OpenNGC, by the designations of the other catalogs which OpenNGC gives them: "LBN 373", "Sh2-155". */
    private final Map<String, Entry> identified = new HashMap<>();

    public static void main(String[] args) throws Exception {
        var resources = Path.of(args.length > 0 ? args[0] : "asterion-core/src/main/resources");
        var generator = new GenerateDeepSkyObjects();
        generator.openNgc();
        var sharpless = generator.sharpless();
        var lbn = generator.lynds("VII/9", LBN);
        var ldn = generator.lynds("VII/7A", LDN);
        generator.commonNames();
        var bytes = generator.table();
        var target = resources.resolve("me/champeau/asterion/catalog/deep-sky-objects.bin");
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
        System.out.printf("%d objects, %d bytes; %s of Sharpless, %s of LBN, %s of LDN%n", generator.entries.size(), bytes.length,
                sharpless, lbn, ldn);
    }

    private void openNgc() throws IOException, InterruptedException {
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
                var type = row.get("Type");
                // duplicates of other entries, and objects which don't exist
                if (type.equals("Dup") || type.equals("NonEx") || row.get("RA").isEmpty()) {
                    continue;
                }
                var entry = new Entry();
                entry.designation = designation(row.get("Name"));
                entry.catalog = entry.designation.startsWith("NGC ") ? NGC : entry.designation.startsWith("IC ") ? IC : OTHER;
                entry.messier = row.get("M").isEmpty() ? 0 : Integer.parseInt(row.get("M"));
                entry.commonName = row.get("Common names").replace(",", ", ");
                entry.type = type;
                entry.ra = hours(row.get("RA"));
                entry.dec = degrees(row.get("Dec"));
                entry.major = number(row.get("MajAx"));
                entry.minor = number(row.get("MinAx"));
                entry.angle = number(row.get("PosAng"));
                var magnitude = number(row.get("V-Mag"));
                entry.magnitude = Float.isNaN(magnitude) ? number(row.get("B-Mag")) : magnitude;
                add(entry);
                // the designations of the same object in the catalogs of Lynds and Sharpless
                for (var identifier : row.get("Identifiers").split(",")) {
                    var id = identifier.trim();
                    if (id.startsWith("LBN ")) {
                        identified.putIfAbsent("LBN " + Integer.parseInt(id.substring(4).trim()), entry);
                    } else if (id.startsWith("SH 2-")) {
                        identified.putIfAbsent("Sh2-" + Integer.parseInt(id.substring(5).trim()), entry);
                    }
                }
            }
        }
    }

    /** The HII regions of Sharpless (1959): their positions, for 1900, are computed for J2000 by VizieR. */
    private String sharpless() throws IOException, InterruptedException {
        var merged = 0;
        var rows = vizier("VII/20");
        for (var row : rows) {
            var entry = new Entry();
            entry.designation = "Sh2-" + Integer.parseInt(row.get("Sh2"));
            entry.catalog = SHARPLESS;
            entry.type = "HII";
            entry.ra = Double.parseDouble(row.get("_RAJ2000"));
            entry.dec = Double.parseDouble(row.get("_DEJ2000"));
            entry.major = number(row.get("Diam"));
            entry.minor = entry.major;
            var same = identified.get(entry.designation);
            if (same == null) {
                same = samePosition(entry, BRIGHT_NEBULAE);
            }
            if (same != null) {
                merge(entry, same);
                merged++;
            } else {
                add(entry);
            }
        }
        return rows.size() + " objects, " + merged + " merged";
    }

    /** The bright (1965) or dark (1962) nebulae of Lynds. */
    private String lynds(String table, int catalog) throws IOException, InterruptedException {
        var merged = 0;
        var rows = vizier(table);
        var names = Pattern.compile("^(S|NGC|IC)\\s*0*(\\d+)\\s*$");
        for (var row : rows) {
            // a few clouds were added in a revision of the catalog, without an LDN number
            if (catalog == LDN && row.get("LDN").isEmpty()) {
                continue;
            }
            var entry = new Entry();
            entry.catalog = catalog;
            entry.ra = Double.parseDouble(row.get("_RAJ2000"));
            entry.dec = Double.parseDouble(row.get("_DEJ2000"));
            Entry same = null;
            if (catalog == LBN) {
                entry.designation = "LBN " + Integer.parseInt(row.get("Seq"));
                entry.type = "Neb";
                entry.major = number(row.get("Diam1"));
                entry.minor = number(row.get("Diam2"));
                same = identified.get(entry.designation);
                // the other name of the nebula: "S 17" is Sh2-17, "NGC 6523" is the Lagoon nebula
                var name = names.matcher(row.get("Name"));
                if (same == null && name.matches()) {
                    same = byName.get((name.group(1).equals("S") ? "Sh2-" : name.group(1) + " ") + name.group(2));
                }
                if (same == null) {
                    same = samePosition(entry, BRIGHT_NEBULAE);
                }
            } else {
                entry.designation = "LDN " + Integer.parseInt(row.get("LDN"));
                entry.type = "DrkN";
                // the size of a circle of the area of the cloud, in square degrees
                var area = number(row.get("Area"));
                entry.major = area > 0 ? (float) (2 * Math.sqrt(area / Math.PI) * 60) : Float.NaN;
                entry.minor = entry.major;
                // the first object of Barnard which the cloud is part of
                var barnard = Pattern.compile("\\d+").matcher(row.get("Barn"));
                if (barnard.find()) {
                    same = byName.get("Barnard " + Integer.parseInt(barnard.group()));
                }
                if (same == null) {
                    same = samePosition(entry, Set.of("DrkN"));
                }
            }
            if (same != null) {
                merge(entry, same);
                merged++;
            } else {
                add(entry);
            }
        }
        return rows.size() + " objects, " + merged + " merged";
    }

    /**
     * The object of a more famous catalog which is the same as an object, by their positions and
     * sizes, or null: the closest one, relative to their sizes. The catalogs list the parts of
     * complexes separately, which are not merged with each other.
     */
    private Entry samePosition(Entry entry, Set<String> types) {
        Entry best = null;
        var bestScore = Double.MAX_VALUE;
        var own = Float.isNaN(entry.major) ? 0 : entry.major;
        for (var other : entries) {
            // the largest objects are 20° across
            if (other.catalog >= entry.catalog || !types.contains(other.type) || Math.abs(other.dec - entry.dec) > 12) {
                continue;
            }
            var distance = separation(entry, other);
            var listed = Float.isNaN(other.listedMajor) ? other.major : other.listedMajor;
            var largest = Math.max(own, Float.isNaN(listed) ? 0 : listed);
            var reach = Math.max(SAME_POSITION, SAME_EXTENT * largest);
            var sameCenter = distance <= Math.max(SAME_POSITION, Math.min(SAME_CENTER_MAX, SAME_CENTER * own));
            // sizes which aren't known aren't similar: the object can only be the one at the same center
            var similarSize = own > 0 && listed > 0 && own / listed >= 1 / 3.0 && own / listed <= 3;
            if (!sameCenter && !(distance <= reach && similarSize)) {
                continue;
            }
            var score = distance / reach;
            if (score < bestScore) {
                best = other;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * Merges an object into the same one of a more famous catalog. The sizes of OpenNGC are the ones
     * of the nebulae, but the ones of the clusters of the clusters with nebulae: the extents of their
     * HII regions from Sharpless replace them. The extents of Sharpless include faint outskirts,
     * and the ones of Lynds are generous: they don't replace the sizes of nebulae.
     */
    private void merge(Entry entry, Entry into) {
        into.aliases.add(entry.designation);
        byName.putIfAbsent(entry.designation, into);
        if (Float.isNaN(into.listedMajor)) {
            into.listedMajor = into.major;
        }
        var cluster = into.type.equals("Cl+N") || into.type.equals("OCl");
        if (!Float.isNaN(entry.major) && (Float.isNaN(into.major) || entry.catalog == SHARPLESS && cluster && entry.major > into.major)) {
            into.major = entry.major;
            into.minor = entry.minor;
            into.angle = Float.NaN;
        }
    }

    private void add(Entry entry) {
        entries.add(entry);
        byName.putIfAbsent(entry.designation, entry);
    }

    private void commonNames() {
        for (var name : COMMON_NAMES.entrySet()) {
            var entry = byName.get(name.getKey());
            if (entry == null) {
                throw new IllegalStateException("No object " + name.getKey());
            }
            if (entry.commonName.isEmpty()) {
                entry.commonName = name.getValue();
            }
        }
    }

    /** The table, see DeepSkyObjects for its format. */
    private byte[] table() throws IOException {
        var count = entries.size();
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeBytes("DSO3");
        out.writeInt(count);
        for (var entry : entries) {
            out.writeFloat((float) entry.ra);
            out.writeFloat((float) entry.dec);
            out.writeFloat((float) (Math.cos(Math.toRadians(entry.dec)) * Math.cos(Math.toRadians(entry.ra))));
            out.writeFloat((float) (Math.cos(Math.toRadians(entry.dec)) * Math.sin(Math.toRadians(entry.ra))));
            out.writeFloat((float) Math.sin(Math.toRadians(entry.dec)));
            out.writeFloat(entry.major);
            out.writeFloat(entry.minor);
            out.writeFloat(entry.angle);
            out.writeFloat(entry.magnitude);
        }
        for (var entry : entries) {
            var typeCode = TYPES.indexOf(entry.type);
            if (typeCode < 0) {
                throw new IllegalStateException("Unknown type " + entry.type + " of " + entry.designation);
            }
            out.writeByte(typeCode);
        }
        for (var entry : entries) {
            out.writeByte(entry.catalog);
        }
        for (var entry : entries) {
            out.writeShort(entry.messier);
        }
        var strings = new ByteArrayOutputStream();
        var offsets = new int[3 * count + 1];
        for (var i = 0; i < count; i++) {
            var entry = entries.get(i);
            offsets[3 * i] = strings.size();
            strings.writeBytes(entry.designation.getBytes(StandardCharsets.UTF_8));
            offsets[3 * i + 1] = strings.size();
            strings.writeBytes(entry.commonName.getBytes(StandardCharsets.UTF_8));
            offsets[3 * i + 2] = strings.size();
            strings.writeBytes(String.join(", ", entry.aliases).getBytes(StandardCharsets.UTF_8));
        }
        offsets[3 * count] = strings.size();
        for (var offset : offsets) {
            out.writeInt(offset);
        }
        out.write(strings.toByteArray());
        return bytes.toByteArray();
    }

    /** The rows of a table of VizieR, by the names of its columns. */
    static List<Map<String, String>> vizier(String table) throws IOException, InterruptedException {
        var rows = new ArrayList<Map<String, String>>();
        String[] columns = null;
        var skip = 0;
        for (var line : new String(download(VIZIER + table), StandardCharsets.ISO_8859_1).split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            if (columns == null) {
                columns = line.split("\t");
                // the units and the dashes under the names of the columns
                skip = 2;
                continue;
            }
            if (skip > 0) {
                skip--;
                continue;
            }
            var values = line.split("\t", -1);
            var row = new HashMap<String, String>();
            for (var c = 0; c < columns.length && c < values.length; c++) {
                row.put(columns[c].trim(), values[c].trim());
            }
            rows.add(row);
        }
        return rows;
    }

    /** The angular distance between two objects, in arcminutes. */
    static double separation(Entry a, Entry b) {
        var dec1 = Math.toRadians(a.dec);
        var dec2 = Math.toRadians(b.dec);
        var cos = Math.sin(dec1) * Math.sin(dec2) + Math.cos(dec1) * Math.cos(dec2) * Math.cos(Math.toRadians(a.ra - b.ra));
        return Math.toDegrees(Math.acos(Math.min(1, cos))) * 60;
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
        return value == null || value.isEmpty() ? Float.NaN : Float.parseFloat(value);
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
