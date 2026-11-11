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

import me.champeau.asterion.image.DetectionOptions;
import me.champeau.asterion.solver.Parity;
import me.champeau.asterion.solver.Solution;
import me.champeau.asterion.solver.SolutionJson;
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

    /** A number which must be positive and finite, such as a threshold. */
    private double positive(String name, double defaultValue) {
        var value = number(name, defaultValue);
        if (!(value > 0) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("Invalid value for " + name + ": " + parameters.get(name));
        }
        return value;
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
        var detection = DetectionOptions.defaults();
        var builder = SolverOptions.builder()
                .useImageHints(flag("hints", true))
                .blindFallback(flag("blind", true))
                .sipOrder((int) number("sipOrder", -1))
                .timeout(Duration.ofMillis((long) (number("timeout", 30) * 1000)))
                .binning((int) number("binning", 0))
                .detection(detection.withThresholdSigma(positive("sigma", detection.thresholdSigma())));
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
        var width = solution.width();
        var height = solution.height();
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

    /**
     * Describes a result: the JSON output of the command line tool, with what the page shows besides,
     * the constellation of the image, its outline on the sky and the positions of the matched stars.
     */
    static Json describe(String name, SolveResult result) {
        var json = Json.extend(SolutionJson.of(name, result));
        result.solution().ifPresent(s -> {
            var constellation = Constellations.of(s.raDeg(), s.decDeg());
            json.field("constellation", Json.object()
                    .field("abbreviation", constellation.abbreviation())
                    .field("name", constellation.name()));
            json.field("outline", outline(s));
            var matches = Json.array();
            for (var match : s.matches()) {
                matches.add(Json.array().add(match.raDeg()).add(match.decDeg()));
            }
            json.field("matches", matches);
        });
        return json;
    }
}
