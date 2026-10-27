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

import me.champeau.asterion.wcs.Wcs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The deep sky objects of the Messier, NGC and IC catalogs, of the catalogs of HII regions of
 * Sharpless, of reflection nebulae of van den Bergh and of bright and dark nebulae of Lynds, and a
 * few others: galaxies, clusters and nebulae, with their size, to tell which ones an image
 * contains. An object which several catalogs list appears once, with the designation of the most
 * famous one, and the others as aliases. The
 * table comes from OpenNGC and VizieR, see the credits in {@code third-party/openngc.md} and
 * {@code third-party/vizier-nebulae.md}.
 */
public final class DeepSkyObjects {
    /** The kinds of objects. */
    public enum Type {
        GALAXY("galaxy"),
        GALAXY_PAIR("galaxy pair"),
        GALAXY_TRIPLET("galaxy triplet"),
        GALAXY_GROUP("galaxy group"),
        OPEN_CLUSTER("open cluster"),
        GLOBULAR_CLUSTER("globular cluster"),
        CLUSTER_WITH_NEBULA("cluster with nebula"),
        PLANETARY_NEBULA("planetary nebula"),
        NEBULA("nebula"),
        HII_REGION("HII region"),
        EMISSION_NEBULA("emission nebula"),
        REFLECTION_NEBULA("reflection nebula"),
        DARK_NEBULA("dark nebula"),
        SUPERNOVA_REMNANT("supernova remnant"),
        NOVA("nova"),
        STAR("star"),
        DOUBLE_STAR("double star"),
        ASTERISM("asterism"),
        OTHER("object");

        private final String description;

        Type(String description) {
            this.description = description;
        }

        /** The kind of object, in words. */
        public String description() {
            return description;
        }
    }

    /** The catalogs of the designations of objects, the most famous first. */
    public enum Catalog {
        NGC,
        IC,
        /** The catalogs of the few other objects of OpenNGC: Caldwell, Melotte, Collinder, Barnard... */
        OTHER,
        /** The HII regions of Sharpless (1959). */
        SHARPLESS,
        /** The reflection nebulae of van den Bergh (1966). */
        VDB,
        /** The bright nebulae of Lynds (1965). */
        LBN,
        /** The dark nebulae of Lynds (1962). */
        LDN
    }

    /**
     * A deep sky object.
     *
     * @param designation the designation in its catalog, such as "NGC 224"
     * @param catalog the catalog of the designation
     * @param messier the Messier number, or 0
     * @param commonName the common names, separated by commas, or an empty string
     * @param aliases the designations of the object in less famous catalogs, separated by commas, or an empty string
     * @param raDeg the right ascension of the center, J2000, in degrees
     * @param decDeg the declination of the center, J2000, in degrees
     * @param majorArcmin the major axis, in arcminutes, or NaN
     * @param minorArcmin the minor axis, in arcminutes, or NaN
     * @param angleDeg the position angle of the major axis, east of north, in degrees, or NaN
     * @param magnitude the visual magnitude, or the blue one, or NaN
     */
    public record DeepSkyObject(
            String designation,
            Catalog catalog,
            int messier,
            String commonName,
            String aliases,
            Type type,
            double raDeg,
            double decDeg,
            double majorArcmin,
            double minorArcmin,
            double angleDeg,
            double magnitude) {
        /** The name the object is best known by: its Messier number, or its designation. */
        public String name() {
            return messier > 0 ? "M " + messier : designation;
        }

        /** The name, with the common name when there's one: "M 31 (Andromeda Galaxy)". */
        public String label() {
            return commonName.isEmpty() ? name() : name() + " (" + commonName + ")";
        }

        /** The order in which objects are listed: Messier objects, then named ones, then the others. */
        int tier() {
            return messier > 0 ? 0 : commonName.isEmpty() ? 2 : 1;
        }
    }

    /**
     * An object which is in an image.
     *
     * @param x the abscissa of its center in the image, in pixels, zero-based, possibly outside of it
     * @param y the ordinate of its center in the image
     * @param distanceDeg the angular distance between its center and the center of the image
     */
    public record InField(
            DeepSkyObject object,
            double x,
            double y,
            double distanceDeg) {
    }

    /** Messier objects, then named ones, then the others, by catalog, the most famous first, then the largest and the brightest. */
    private static final Comparator<InField> PROMINENCE = Comparator.comparingInt((InField f) -> f.object().tier())
            .thenComparing(f -> f.object().catalog())
            .thenComparing(f -> f.object().majorArcmin(), (a, b) -> Double.compare(nanLast(b), nanLast(a)))
            .thenComparing(f -> f.object().magnitude(), (a, b) -> Double.compare(nanLast(a), nanLast(b)))
            .thenComparingDouble(InField::distanceDeg);

    /**
     * The table, parsed when the class is initialized: at build time in native images, where it
     * lives in the image heap, see native-image.properties.
     */
    private static final DeepSkyObjects TABLE = load();

    private final int count;
    /** For each object: right ascension, declination, the 3 coordinates of its unit vector, the axes, the angle and the magnitude. */
    private final float[] numbers;
    private final byte[] types;
    private final byte[] catalogs;
    private final short[] messiers;
    /** For each object, where its designation, its common name and its aliases start in the strings; one more for the end. */
    private final int[] offsets;
    private final byte[] strings;
    /** The largest major axis of the objects, in degrees. */
    private final double largestDeg;

    private static final int NUMBERS = 9;
    private static final int STRINGS = 3;

    private DeepSkyObjects(int count, float[] numbers, byte[] types, byte[] catalogs, short[] messiers, int[] offsets, byte[] strings) {
        this.count = count;
        this.numbers = numbers;
        this.types = types;
        this.catalogs = catalogs;
        this.messiers = messiers;
        this.offsets = offsets;
        this.strings = strings;
        var largest = 0f;
        for (var i = 0; i < count; i++) {
            var major = numbers[NUMBERS * i + 5];
            if (major > largest) {
                largest = major;
            }
        }
        this.largestDeg = largest / 60.0;
    }

    private DeepSkyObject object(int i) {
        var n = NUMBERS * i;
        return new DeepSkyObject(string(STRINGS * i), Catalog.values()[catalogs[i]], messiers[i], string(STRINGS * i + 1),
                string(STRINGS * i + 2), Type.values()[types[i]],
                round(numbers[n], 1e6), round(numbers[n + 1], 1e6),
                round(numbers[n + 5], 100), round(numbers[n + 6], 100), round(numbers[n + 7], 100), round(numbers[n + 8], 100));
    }

    private String string(int k) {
        return new String(strings, offsets[k], offsets[k + 1] - offsets[k], StandardCharsets.UTF_8);
    }

    /** All the objects of the table. */
    public static List<DeepSkyObject> all() {
        var all = new ArrayList<DeepSkyObject>(TABLE.count);
        for (var i = 0; i < TABLE.count; i++) {
            all.add(TABLE.object(i));
        }
        return all;
    }

    /**
     * The objects which an image contains, the most prominent first: Messier objects, then the
     * ones with a common name, then the others, the largest and brightest first. An object is in
     * the image when its center is, or when its size reaches into the image.
     *
     * @param wcs the solution of the image
     * @param width the width of the image, in pixels
     * @param height the height of the image, in pixels
     */
    public static List<InField> inField(Wcs wcs, int width, int height) {
        var table = TABLE;
        var scale = wcs.pixelScale();
        var center = new double[3];
        wcs.pixelToVector((width - 1) / 2.0, (height - 1) / 2.0, center);
        // objects far from the center are left out before being projected: the largest objects
        // of the table are 20° across
        var maxRadius = Math.toRadians(scale * Math.hypot(width, height) / 2 / 3600 + table.largestDeg / 2);
        var minDot = Math.cos(maxRadius);
        var pixel = new double[2];
        var found = new ArrayList<InField>();
        var numbers = table.numbers;
        for (var i = 0; i < table.count; i++) {
            var n = NUMBERS * i;
            double vx = numbers[n + 2];
            double vy = numbers[n + 3];
            double vz = numbers[n + 4];
            var dot = vx * center[0] + vy * center[1] + vz * center[2];
            if (dot < minDot || !wcs.vectorToPixel(vx, vy, vz, pixel)) {
                continue;
            }
            // the distance between the center of the object and the image, in pixels
            var dx = Math.max(0, Math.max(-0.5 - pixel[0], pixel[0] - (width - 0.5)));
            var dy = Math.max(0, Math.max(-0.5 - pixel[1], pixel[1] - (height - 0.5)));
            var major = numbers[n + 5];
            var reach = Float.isNaN(major) ? 0 : major * 60 / 2 / scale;
            if (Math.hypot(dx, dy) <= reach) {
                found.add(new InField(table.object(i), pixel[0], pixel[1], Math.toDegrees(Math.acos(Math.min(1, dot)))));
            }
        }
        found.sort(PROMINENCE);
        return found;
    }

    private static double round(float value, double scale) {
        return Float.isNaN(value) ? Double.NaN : Math.round(value * scale) / scale;
    }

    private static double nanLast(double value) {
        return Double.isNaN(value) ? Double.MAX_VALUE : value;
    }

    /**
     * Reads the table, see {@code asterion-core/src/dso/GenerateDeepSkyObjects.java}: "DSO3", the
     * number of objects, their numbers as floats (position, unit vector, axes, angle, magnitude),
     * their types, their catalogs, their Messier numbers, then the offsets of their designations,
     * common names and aliases in a block of UTF-8 strings, which follows.
     */
    private static DeepSkyObjects load() {
        try (var resource = DeepSkyObjects.class.getResourceAsStream("deep-sky-objects.bin")) {
            if (resource == null) {
                throw new IOException("Missing table of deep sky objects");
            }
            var buffer = ByteBuffer.wrap(resource.readAllBytes());
            var magic = new byte[4];
            buffer.get(magic);
            if (!new String(magic, StandardCharsets.US_ASCII).equals("DSO3")) {
                throw new IOException("Unexpected format of the table of deep sky objects");
            }
            var count = buffer.getInt();
            var numbers = new float[NUMBERS * count];
            buffer.asFloatBuffer().get(numbers);
            buffer.position(buffer.position() + 4 * numbers.length);
            var types = new byte[count];
            buffer.get(types);
            var catalogs = new byte[count];
            buffer.get(catalogs);
            var messiers = new short[count];
            buffer.asShortBuffer().get(messiers);
            buffer.position(buffer.position() + 2 * count);
            var offsets = new int[STRINGS * count + 1];
            buffer.asIntBuffer().get(offsets);
            buffer.position(buffer.position() + 4 * offsets.length);
            var strings = new byte[buffer.remaining()];
            buffer.get(strings);
            return new DeepSkyObjects(count, numbers, types, catalogs, messiers, offsets, strings);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
