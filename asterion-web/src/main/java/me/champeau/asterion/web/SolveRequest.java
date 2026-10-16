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
package me.champeau.asterion.web;

import me.champeau.asterion.catalog.DeepSkyObjects;
import me.champeau.asterion.solver.Parity;
import me.champeau.asterion.solver.Solution;
import me.champeau.asterion.solver.SolveResult;
import me.champeau.asterion.solver.SolverOptions;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The parameters of a solve, from the query of a request. They have the meaning of the options of
 * the command line tool with the same names: positions and scales are limits, the header of the
 * image gives hints.
 */
final class SolveRequest {
    private final Map<String, String> parameters;

    private SolveRequest(Map<String, String> parameters) {
        this.parameters = parameters;
    }

    static SolveRequest parse(String query) {
        var parameters = new HashMap<String, String>();
        if (query != null) {
            for (var pair : query.split("&")) {
                var eq = pair.indexOf('=');
                var key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                var value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                if (!value.isBlank()) {
                    parameters.put(key, value.trim());
                }
            }
        }
        return new SolveRequest(parameters);
    }

    /** The name of the image file, which tells its format when it's compressed, such as image.fits.gz. */
    String fileName() {
        var name = parameters.getOrDefault("name", "image");
        // the name of a temporary file: no directory, nothing unusual
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** A parameter, or null if it's missing. */
    String parameter(String name) {
        return parameters.get(name);
    }

    double number(String name, double defaultValue) {
        var value = parameters.get(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid value for " + name + ": " + value);
        }
    }

    private boolean flag(String name, boolean defaultValue) {
        var value = parameters.get(name);
        return value == null ? defaultValue : !value.equalsIgnoreCase("false");
    }

    /**
     * The options of the solver.
     *
     * @throws IllegalArgumentException if a parameter is invalid
     */
    SolverOptions options() {
        var builder = SolverOptions.builder()
                .useImageHints(flag("hints", true))
                .blindFallback(flag("blind", true))
                .sipOrder((int) number("sipOrder", -1))
                .timeout(Duration.ofMillis((long) (number("timeout", 30) * 1000)))
                .binning((int) number("binning", 0));
        var parity = parameters.get("parity");
        if (parity != null) {
            try {
                builder.parity(Parity.valueOf(parity.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid value for parity: " + parity);
            }
        }
        var ra = number("ra", Double.NaN);
        var dec = number("dec", Double.NaN);
        if (Double.isNaN(ra) != Double.isNaN(dec)) {
            throw new IllegalArgumentException("ra and dec must be given together");
        }
        if (!Double.isNaN(ra)) {
            builder.position(ra, dec, number("radius", 10));
        }
        var scaleLow = number("scaleLow", 0);
        var scaleHigh = number("scaleHigh", 0);
        if (scaleLow > 0 || scaleHigh > 0) {
            builder.scale(scaleLow, scaleHigh);
        }
        var fov = number("fov", 0);
        if (fov > 0) {
            // like the command line tool: a 30% error is tolerated
            builder.fieldOfView(fov * 0.7, fov * 1.3);
        }
        return builder.build();
    }

    /**
     * The outline of the image on the sky, as (right ascension, declination) pairs in degrees: its
     * edges are sampled, since they are curved by distortions.
     */
    static Json outline(Solution solution) {
        var wcs = solution.wcs();
        var width = solution.fieldWidthDeg() * 3600 / solution.pixelScale();
        var height = solution.fieldHeightDeg() * 3600 / solution.pixelScale();
        double[][] corners = {{-0.5, -0.5}, {width - 0.5, -0.5}, {width - 0.5, height - 0.5}, {-0.5, height - 0.5}};
        var outline = Json.array();
        var steps = 8;
        for (var c = 0; c < 4; c++) {
            var a = corners[c];
            var b = corners[(c + 1) % 4];
            for (var i = 0; i < steps; i++) {
                var sky = wcs.pixelToSky(a[0] + (b[0] - a[0]) * i / steps, a[1] + (b[1] - a[1]) * i / steps);
                outline.add(Json.array().add(sky[0]).add(sky[1]));
            }
        }
        return outline;
    }

    /** Describes a result, with the fields of the JSON output of the command line tool. */
    static Json describe(String name, SolveResult result) {
        var stats = result.stats();
        var json = Json.object()
                .field("image", name)
                .field("solved", result.solved())
                .field("stars", result.stars().size())
                .field("fwhmPixels", result.stars().medianFwhm());
        result.solution().ifPresent(s -> {
            json.field("ra", s.raDeg())
                    .field("dec", s.decDeg())
                    .field("scale", s.pixelScale());
            result.focalLengthMm().ifPresent(focal -> json
                    .field("focalLength", focal)
                    .field("pixelSize", result.pixelSizeMicrons().orElseThrow()));
            json.field("rotation", s.rotationDeg())
                    .field("flipped", s.flipped())
                    .field("fieldWidth", s.fieldWidthDeg())
                    .field("fieldHeight", s.fieldHeightDeg())
                    .field("fwhmArcsec", result.stars().medianFwhm() * s.pixelScale())
                    .field("matchedStars", s.matchedStars())
                    .field("rmsArcsec", s.rmsArcsec())
                    .field("logOdds", s.logOdds())
                    .field("index", s.indexName())
                    .field("wcs", s.wcs().toFitsKeywords());
            var constellation = Constellations.of(s.raDeg(), s.decDeg());
            json.field("constellation", Json.object()
                    .field("abbreviation", constellation.abbreviation())
                    .field("name", constellation.name()));
            json.field("outline", outline(s));
            var objects = Json.array();
            var width = (int) Math.round(s.fieldWidthDeg() * 3600 / s.pixelScale());
            var height = (int) Math.round(s.fieldHeightDeg() * 3600 / s.pixelScale());
            for (var found : DeepSkyObjects.inField(s.wcs(), width, height)) {
                var o = found.object();
                objects.add(Json.object()
                        .field("name", o.name())
                        .field("label", o.label())
                        .field("designation", o.designation())
                        .field("commonName", o.commonName().isEmpty() ? null : o.commonName())
                        .field("type", o.type().description())
                        .field("ra", o.raDeg())
                        .field("dec", o.decDeg())
                        .field("x", found.x())
                        .field("y", found.y())
                        .field("distance", found.distanceDeg())
                        .field("majorArcmin", o.majorArcmin())
                        .field("minorArcmin", o.minorArcmin())
                        .field("angle", o.angleDeg())
                        .field("magnitude", o.magnitude()));
            }
            json.field("objects", objects);
            var matches = Json.array();
            for (var match : s.matches()) {
                matches.add(Json.array().add(match.raDeg()).add(match.decDeg()));
            }
            json.field("matches", matches);
        });
        return json.field("timing", Json.object()
                .field("readMillis", stats.loadMillis())
                .field("detectMillis", stats.detectMillis())
                .field("solveMillis", stats.solveMillis())
                .field("totalMillis", stats.totalMillis()));
    }
}
