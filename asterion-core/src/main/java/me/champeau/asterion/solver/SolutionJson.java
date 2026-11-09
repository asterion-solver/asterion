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
package me.champeau.asterion.solver;

import me.champeau.asterion.catalog.DeepSkyObjects;

/**
 * The result of a solve as JSON, the format of the command line tool, which the web interface and the
 * programs which use Asterion as a library get too: one format, whatever the way Asterion is called.
 * The fields which aren't known are left out, and numbers which aren't finite are null.
 */
public final class SolutionJson {
    private SolutionJson() {
    }

    /**
     * The result of a solve, as JSON: what {@code asterion --json} prints for each image.
     *
     * @param image the name of the image, or null when it isn't a file, such as pixels in memory
     */
    public static String of(String image, SolveResult result) {
        var sb = new StringBuilder("{");
        var stats = result.stats();
        if (image != null) {
            field(sb, "image", image);
        }
        field(sb, "solved", result.solved());
        field(sb, "stars", result.stars().size());
        field(sb, "fwhmPixels", result.fwhmPixels());
        result.solution().ifPresent(s -> {
            field(sb, "ra", s.raDeg());
            field(sb, "dec", s.decDeg());
            field(sb, "scale", s.pixelScale());
            result.focalLengthMm().ifPresent(focal -> {
                field(sb, "focalLength", focal);
                field(sb, "pixelSize", result.pixelSizeMicrons().orElseThrow());
            });
            field(sb, "rotation", s.rotationDeg());
            field(sb, "flipped", s.flipped());
            field(sb, "fieldWidth", s.fieldWidthDeg());
            field(sb, "fieldHeight", s.fieldHeightDeg());
            field(sb, "fwhmArcsec", result.fwhmPixels() * s.pixelScale());
            field(sb, "matchedStars", s.matchedStars());
            field(sb, "rmsArcsec", s.rmsArcsec());
            field(sb, "logOdds", s.logOdds());
            field(sb, "index", s.indexName());
            sb.append("\"objects\":[");
            var objects = DeepSkyObjects.inField(s.wcs(), s.width(), s.height());
            for (var found : objects) {
                sb.append(deepSkyObject(found.object(), found.x(), found.y(), found.distanceDeg())).append(',');
            }
            if (!objects.isEmpty()) {
                sb.setLength(sb.length() - 1);
            }
            sb.append("],");
            sb.append("\"wcs\":{");
            for (var card : s.wcs().toFitsKeywords().entrySet()) {
                field(sb, card.getKey(), card.getValue());
            }
            sb.setLength(sb.length() - 1);
            sb.append("},");
        });
        sb.append("\"timing\":{");
        field(sb, "readMillis", stats.loadMillis());
        field(sb, "detectMillis", stats.detectMillis());
        field(sb, "solveMillis", stats.solveMillis());
        field(sb, "totalMillis", stats.totalMillis());
        sb.setLength(sb.length() - 1);
        sb.append("}}");
        return sb.toString();
    }

    /**
     * A deep sky object, as the solutions describe it: the fields which aren't known are left out.
     *
     * @param x the abscissa of its center in the image, in pixels, or NaN for an object which isn't one of an image
     * @param y the ordinate of its center in the image, or NaN
     * @param distanceDeg the distance between its center and the one of the image, or another position, in degrees
     */
    public static String deepSkyObject(DeepSkyObjects.DeepSkyObject o, double x, double y, double distanceDeg) {
        var sb = new StringBuilder("{");
        field(sb, "name", o.name());
        field(sb, "label", o.label());
        field(sb, "designation", o.designation());
        if (o.messier() > 0) {
            field(sb, "messier", o.messier());
        }
        if (!o.commonName().isEmpty()) {
            field(sb, "commonName", o.commonName());
        }
        if (!o.aliases().isEmpty()) {
            field(sb, "aliases", o.aliases());
        }
        field(sb, "type", o.type().description());
        field(sb, "ra", o.raDeg());
        field(sb, "dec", o.decDeg());
        if (!Double.isNaN(x)) {
            field(sb, "x", x);
            field(sb, "y", y);
        }
        field(sb, "distance", distanceDeg);
        if (!Double.isNaN(o.majorArcmin())) {
            field(sb, "majorArcmin", o.majorArcmin());
        }
        if (!Double.isNaN(o.minorArcmin())) {
            field(sb, "minorArcmin", o.minorArcmin());
        }
        if (!Double.isNaN(o.angleDeg())) {
            field(sb, "angle", o.angleDeg());
        }
        if (!Double.isNaN(o.magnitude())) {
            field(sb, "magnitude", o.magnitude());
        }
        sb.setLength(sb.length() - 1);
        return sb.append('}').toString();
    }

    /**
     * Appends a field and a comma: a string, a boolean or a number, where numbers which aren't
     * finite become null. The JSON of the command line tool and of the web server use it too.
     */
    public static void field(StringBuilder sb, String name, Object value) {
        sb.append('"').append(name).append("\":");
        switch (value) {
            case null -> sb.append("null");
            case String s -> quote(sb, s);
            case Double d when !Double.isFinite(d) -> sb.append("null");
            case Float f when !Float.isFinite(f) -> sb.append("null");
            default -> sb.append(value);
        }
        sb.append(',');
    }

    /** Appends a JSON string. */
    public static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (var i = 0; i < s.length(); i++) {
            var c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u%04x".formatted((int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
