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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The options of an ASTAP command line. Numbers which aren't given are NaN.
 *
 * @param image the image to solve ({@code -f}), or null
 * @param radiusDeg the radius of the square search pattern around the start position ({@code -r}), in degrees
 * @param fovDeg the height of the field of view ({@code -fov}) in degrees, 0 for unknown
 * @param raHours the right ascension of the start position ({@code -ra}), in hours
 * @param southPoleDistanceDeg the declination of the start position + 90 ({@code -spd}), in degrees
 * @param downsample the binning applied before solving ({@code -z}), 0 for automatic
 * @param outputBase the base path of the output files ({@code -o}), or null for the path of the image
 * @param sip true to add SIP distortion coefficients ({@code -sip})
 * @param fitsWcs true to write the .wcs file as a standard FITS header ({@code -wcs})
 * @param update true to add the solution to the header of the image ({@code -update})
 * @param log true to write a log file ({@code -log})
 * @param help true to print the usage ({@code -h})
 * @param unsupported the options which can't be emulated, such as {@code -analyse}
 * @param problems the options which were ignored because they are unknown or invalid
 */
public record AstapArguments(
        Path image,
        double radiusDeg,
        double fovDeg,
        double raHours,
        double southPoleDistanceDeg,
        int downsample,
        Path outputBase,
        boolean sip,
        boolean fitsWcs,
        boolean update,
        boolean log,
        boolean help,
        List<String> unsupported,
        List<String> problems) {
    /**
     * The options which tune the algorithm or the star database of ASTAP: accepted, and ignored.
     * {@code -d} and {@code -D} are the directory and the name of an ASTAP star database.
     */
    private static final Set<String> IGNORED_WITH_VALUE = Set.of("-s", "-t", "-m", "-d", "-speed");
    private static final Set<String> IGNORED_FLAGS = Set.of("-progress");
    /** The options which produce something else than a solution. */
    private static final Set<String> UNSUPPORTED_WITH_VALUE = Set.of("-analyse", "-extract", "-extract2", "-sqm", "-tofits", "-stack",
            "-p");
    private static final Set<String> UNSUPPORTED_FLAGS = Set.of("-annotate", "-debug");

    /** True if a start position is given. */
    public boolean hasPosition() {
        return !Double.isNaN(raHours) && !Double.isNaN(southPoleDistanceDeg);
    }

    /** Parses a command line, never failing: like ASTAP, unknown or invalid options are ignored. */
    public static AstapArguments parse(List<String> args) {
        return new Parser(args).parse();
    }

    private static final class Parser {
        private final List<String> args;
        private final List<String> unsupported = new ArrayList<>();
        private final List<String> problems = new ArrayList<>();
        private int position;

        Parser(List<String> args) {
            this.args = args;
        }

        AstapArguments parse() {
            Path image = null;
            Path output = null;
            var radius = Double.NaN;
            var fov = Double.NaN;
            var ra = Double.NaN;
            var spd = Double.NaN;
            var downsample = 0;
            var sip = false;
            var fitsWcs = false;
            var update = false;
            var log = false;
            var help = false;
            while (position < args.size()) {
                var option = args.get(position++);
                switch (option.toLowerCase(Locale.ROOT)) {
                    case "-f" -> image = path(option, image);
                    case "-o" -> output = path(option, output);
                    case "-r" -> radius = number(option);
                    case "-fov" -> fov = number(option);
                    case "-ra" -> ra = number(option);
                    case "-spd" -> spd = number(option);
                    case "-z" -> downsample = (int) Math.max(0, Math.round(number(option)));
                    // the manual gives -sip a y/n value, astap_cli takes none: both are accepted
                    case "-sip" -> sip = yesNo();
                    case "-check" -> yesNo();
                    case "-wcs" -> fitsWcs = true;
                    case "-update" -> update = true;
                    case "-log" -> log = true;
                    case "-h", "-help", "--help", "/?" -> help = true;
                    default -> other(option);
                }
            }
            return new AstapArguments(image, radius, fov, ra, spd, downsample, output, sip, fitsWcs, update, log, help,
                    List.copyOf(unsupported), List.copyOf(problems));
        }

        private void other(String option) {
            var name = option.toLowerCase(Locale.ROOT);
            if (IGNORED_WITH_VALUE.contains(name)) {
                value(option);
            } else if (UNSUPPORTED_WITH_VALUE.contains(name) || name.startsWith("-focus")) {
                value(option);
                unsupported.add(option);
            } else if (UNSUPPORTED_FLAGS.contains(name)) {
                unsupported.add(option);
            } else if (!IGNORED_FLAGS.contains(name)) {
                problems.add("Unknown option " + option);
            }
        }

        private String value(String option) {
            if (position < args.size()) {
                return args.get(position++);
            }
            problems.add("Missing value for " + option);
            return null;
        }

        private Path path(String option, Path current) {
            var value = value(option);
            return value == null ? current : Path.of(value);
        }

        private double number(String option) {
            var value = value(option);
            if (value == null) {
                return Double.NaN;
            }
            try {
                // some locales write decimal commas
                return Double.parseDouble(value.trim().replace(',', '.'));
            } catch (NumberFormatException _) {
                problems.add("Invalid value for " + option + ": " + value);
                return Double.NaN;
            }
        }

        /** Reads an optional y/n value: an option without value means yes. */
        private boolean yesNo() {
            if (position < args.size()) {
                var next = args.get(position).toLowerCase(Locale.ROOT);
                if (next.equals("y") || next.equals("n")) {
                    position++;
                    return next.equals("y");
                }
            }
            return true;
        }
    }
}
