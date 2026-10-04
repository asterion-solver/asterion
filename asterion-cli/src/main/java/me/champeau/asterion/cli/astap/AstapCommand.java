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

import me.champeau.asterion.catalog.Catalogs;
import me.champeau.asterion.cli.AsterionVersion;
import me.champeau.asterion.cli.WcsWriter;
import me.champeau.asterion.image.ImageLoader;
import me.champeau.asterion.image.LoadedImage;
import me.champeau.asterion.solver.PlateSolver;
import me.champeau.asterion.solver.SolveResult;
import me.champeau.asterion.solver.Solution;
import me.champeau.asterion.solver.SolverOptions;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Solves an image with the options and the output files of ASTAP. This is a <b>best effort</b>
 * emulation, see the {@linkplain me.champeau.asterion.cli.astap package documentation}.
 */
public final class AstapCommand {
    /** The search radius when {@code -r} isn't given, in degrees. */
    private static final double DEFAULT_RADIUS_DEG = 30;
    /** A radius which means the whole sky, in degrees. */
    private static final double WHOLE_SKY_DEG = 180;
    /** The relative error on the expected scale which is tolerated, before a blind search on the scale. */
    private static final double SCALE_TOLERANCE = 0.25;
    /** The relative error on the expected scale above which the user is warned. */
    private static final double SCALE_WARNING = 0.05;
    /** Below this number of stars, a failure is reported as "not enough stars". */
    private static final int MIN_STARS = 10;
    private static final Duration TIMEOUT = Duration.ofMinutes(2);
    /** The size above which the log of the command lines is started again. */
    private static final long MAX_CALL_LOG_SIZE = 1 << 20;

    private final AstapArguments arguments;
    private final String commandLine;
    private final Path catalogDirectory;
    private final PrintStream out;
    private final List<String> logLines = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    AstapCommand(AstapArguments arguments, String commandLine, Path catalogDirectory, PrintStream out) {
        this.arguments = arguments;
        this.commandLine = commandLine;
        this.catalogDirectory = catalogDirectory;
        this.out = out;
    }

    /**
     * Runs the command.
     *
     * @param program the path of the program, as it was invoked, which is part of the command line written in output files
     * @param args the options
     * @return the exit code
     */
    public static int run(String program, List<String> args) {
        var commandLine = commandLine(program, args);
        logCall(commandLine);
        var arguments = AstapArguments.parse(args);
        if (arguments.help() || arguments.image() == null) {
            printUsage(System.out);
            return arguments.help() ? 0 : 1;
        }
        return new AstapCommand(arguments, commandLine, Catalogs.defaultDirectory(), System.out).execute();
    }

    int execute() {
        logLines.add(LocalTime.now().truncatedTo(ChronoUnit.SECONDS) + "  " + commandLine);
        print("Asterion Solver " + AsterionVersion.get() + ", ASTAP compatibility mode (best effort)");
        arguments.problems().forEach(problem -> print("Ignored: " + problem));
        var base = outputBase();
        try {
            // a .wcs file means success to clients: a previous one must not be taken for the result of this run
            Files.deleteIfExists(base.resolveSibling(base.getFileName() + ".wcs"));
        } catch (IOException e) {
            print("Unable to delete the previous .wcs file: " + e.getMessage());
        }
        var outcome = solve();
        if (outcome.solved() && arguments.update()) {
            outcome = update(outcome);
        }
        if (outcome.solved()) {
            var solution = outcome.solution();
            print("Solution found: " + AstapNumbers.ra(solution.raDeg()) + " " + AstapNumbers.dec(solution.decDeg()));
            var summary = "Solved in " + AstapNumbers.fixed("%.1f", outcome.seconds()) + " sec.";
            if (!Double.isNaN(outcome.offsetDeg())) {
                summary += " Offset was " + AstapNumbers.distance(outcome.offsetDeg()) + ".";
            }
            print(summary);
        } else {
            print("No solution found!  :(");
        }
        outcome.warnings().forEach(this::print);
        writeFiles(base, outcome);
        return outcome.status().exitCode();
    }

    private AstapOutcome solve() {
        var start = System.nanoTime();
        if (!arguments.unsupported().isEmpty()) {
            var options = String.join(", ", arguments.unsupported());
            print("Not supported by Asterion: " + options);
            return AstapOutcome.failure(AstapStatus.UNSUPPORTED, "Option not supported by Asterion: " + options + ".", warnings);
        }
        List<Path> catalogs;
        try {
            catalogs = Catalogs.installed(catalogDirectory);
        } catch (IOException e) {
            catalogs = List.of();
        }
        if (catalogs.isEmpty()) {
            print("Error, no star database found at " + catalogDirectory + " ! Install one with: asterion --download gaia-2000");
            return AstapOutcome.failure(AstapStatus.NO_DATABASE, warnings);
        }
        PlateSolver solver;
        try {
            solver = PlateSolver.open(catalogs.toArray(Path[]::new));
        } catch (IOException e) {
            print("Error reading the star database: " + e.getMessage());
            return AstapOutcome.failure(AstapStatus.DATABASE_ERROR, warnings);
        }
        LoadedImage loaded;
        try {
            loaded = ImageLoader.load(arguments.image(), arguments.downsample());
        } catch (IOException | RuntimeException e) {
            print("Error reading image file " + arguments.image() + ": " + e.getMessage());
            return AstapOutcome.failure(AstapStatus.IMAGE_ERROR, warnings);
        }
        var image = loaded.image();
        var hints = loaded.hints();
        var options = SolverOptions.builder()
                // the hints of the image are applied here, with the radius of the command line
                .useImageHints(false)
                .blindFallback(false)
                .sipOrder(arguments.sip() ? -1 : 0)
                .timeout(TIMEOUT);
        var raDeg = arguments.hasPosition() ? arguments.raHours() * 15 : hints.raDeg().orElse(Double.NaN);
        var decDeg = arguments.hasPosition() ? arguments.southPoleDistanceDeg() - 90 : hints.decDeg().orElse(Double.NaN);
        var hasPosition = !Double.isNaN(raDeg) && !Double.isNaN(decDeg);
        var radius = Double.isNaN(arguments.radiusDeg()) ? DEFAULT_RADIUS_DEG : arguments.radiusDeg();
        // ASTAP searches a square around the start position: the cone contains it
        var cone = Math.min(WHOLE_SKY_DEG, radius * Math.sqrt(2));
        if (hasPosition) {
            print("Search radius: " + (cone >= WHOLE_SKY_DEG ? "whole sky" : AstapNumbers.fixed("%.1f", radius) + " degrees"));
            print("Start position: " + AstapNumbers.ra(raDeg) + ", " + AstapNumbers.dec(decDeg));
        } else {
            print("Search radius: whole sky");
        }
        // -fov 0 means an unknown scale, even if the header gives one
        var scale = arguments.fovDeg() > 0 ? arguments.fovDeg() * 3600 / image.sourceHeight()
                : Double.isNaN(arguments.fovDeg()) ? hints.pixelScale().orElse(Double.NaN) : Double.NaN;
        if (!Double.isNaN(scale)) {
            print("Image height: " + AstapNumbers.fixed("%.2f", scale * image.sourceHeight() / 3600) + " degrees");
        } else {
            print("Image height: unknown");
        }
        print("Binning: " + image.binning() + "x" + image.binning());
        print("Image dimensions: " + image.sourceWidth() + "x" + image.sourceHeight());
        // Like ASTAP, the search starts around the start position, and widens up to the radius. Around
        // the start position, the scale is ignored: it doesn't make the search faster, while a wrong
        // field of view, which some programs pass, would make it fail slowly
        var attempts = new ArrayList<SolverOptions>();
        var nearby = hasPosition && cone > PlateSolver.HINT_SEARCH_RADIUS_DEG;
        if (nearby) {
            attempts.add(
                    options.position(raDeg, decDeg, PlateSolver.HINT_SEARCH_RADIUS_DEG).maxQuadStars(PlateSolver.HINT_QUAD_STARS).build());
            options.maxQuadStars(SolverOptions.defaults().maxQuadStars());
        }
        if (hasPosition && cone < WHOLE_SKY_DEG) {
            options.position(raDeg, decDeg, cone);
        } else {
            options.position(Double.NaN, Double.NaN, WHOLE_SKY_DEG);
        }
        if (!Double.isNaN(scale)) {
            attempts.add(options.scale(scale * (1 - SCALE_TOLERANCE), scale * (1 + SCALE_TOLERANCE)).build());
            options.scale(0, 0);
        }
        attempts.add(options.build());
        SolveResult result = null;
        for (var attempt : attempts) {
            if (result == null) {
                result = solver.solve(image, hints, attempt);
                print(result.stars().size() + " stars detected in the image.");
                if (result.stars().size() < MIN_STARS) {
                    break;
                }
            } else {
                print("No solution yet, searching " + (attempt.hasPosition()
                        ? "within " + AstapNumbers.fixed("%.1f", attempt.searchRadiusDeg()) + " degrees of the start position"
                        : "the whole sky")
                        + (attempt.minScale() > 0 ? " at the expected scale." : " at any scale."));
                result = solver.solve(result.stars(), image.sourceWidth(), image.sourceHeight(), hints, attempt);
            }
            if (result.solved()) {
                break;
            }
        }
        var seconds = (System.nanoTime() - start) / 1e9;
        if (!result.solved()) {
            if (result.stars().size() < MIN_STARS) {
                print("Only " + result.stars().size() + " stars found in image. Abort");
                return AstapOutcome.failure(AstapStatus.NOT_ENOUGH_STARS, warnings);
            }
            return AstapOutcome.failure(AstapStatus.NO_SOLUTION, warnings);
        }
        var solution = result.solution().orElseThrow();
        if (!Double.isNaN(scale) && Math.abs(solution.pixelScale() / scale - 1) > SCALE_WARNING) {
            warnings.add(scaleWarning(solution, result, image.sourceHeight()));
        }
        var offset = hasPosition ? distance(raDeg, decDeg, solution.raDeg(), solution.decDeg()) : Double.NaN;
        return new AstapOutcome(AstapStatus.SOLVED, null, solution, List.copyOf(warnings), seconds, offset);
    }

    private static String scaleWarning(Solution solution, SolveResult result, int height) {
        var warning = "Warning scale was inaccurate! Set FOV=" + AstapNumbers.fixed("%.2f", solution.pixelScale() * height / 3600)
                + "d, scale=" + AstapNumbers.fixed("%.1f", solution.pixelScale()) + "\"";
        var focalLength = result.focalLengthMm();
        if (focalLength.isPresent()) {
            warning += ", FL=" + Math.round(focalLength.getAsDouble()) + "mm";
        }
        return warning;
    }

    private AstapOutcome update(AstapOutcome outcome) {
        try {
            WcsWriter.updateHeader(arguments.image(), outcome.solution());
            return outcome;
        } catch (IOException e) {
            print("Error updating " + arguments.image() + ": " + e.getMessage());
            return outcome.withError(AstapStatus.UPDATE_ERROR, AstapStatus.UPDATE_ERROR.error());
        }
    }

    private void writeFiles(Path base, AstapOutcome outcome) {
        try {
            Files.createDirectories(base.getParent());
            if (outcome.solved()) {
                var wcs = AstapWcsFile.format(originalCards(), outcome, commandLine, arguments.sip(), arguments.fitsWcs());
                Files.writeString(base.resolveSibling(base.getFileName() + ".wcs"), wcs, StandardCharsets.ISO_8859_1);
            }
            Files.writeString(base.resolveSibling(base.getFileName() + ".ini"), AstapIni.format(outcome, commandLine),
                    StandardCharsets.UTF_8);
            if (arguments.log()) {
                Files.write(base.resolveSibling(base.getFileName() + ".log"), logLines, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            out.println("Unable to write the output files: " + e.getMessage());
        }
    }

    private List<String> originalCards() {
        try {
            return AstapWcsFile.originalCards(arguments.image());
        } catch (IOException e) {
            print("Unable to read the header of " + arguments.image() + ": " + e.getMessage());
            return List.of();
        }
    }

    /** The output files are named after the image, without its extension, unless {@code -o} is given. */
    Path outputBase() {
        if (arguments.outputBase() != null) {
            return arguments.outputBase().toAbsolutePath();
        }
        var image = arguments.image().toAbsolutePath();
        var name = image.getFileName().toString();
        var dot = name.lastIndexOf('.');
        return dot > 0 ? image.resolveSibling(name.substring(0, dot)) : image;
    }

    private void print(String line) {
        out.println(line);
        logLines.add(LocalTime.now().truncatedTo(ChronoUnit.SECONDS) + "  " + line);
    }

    private static double distance(double ra1, double dec1, double ra2, double dec2) {
        var d1 = Math.toRadians(dec1);
        var d2 = Math.toRadians(dec2);
        var dra = Math.toRadians(ra2 - ra1);
        var a = Math.pow(Math.sin((d2 - d1) / 2), 2) + Math.cos(d1) * Math.cos(d2) * Math.pow(Math.sin(dra / 2), 2);
        return Math.toDegrees(2 * Math.asin(Math.min(1, Math.sqrt(a))));
    }

    /** The command line, as ASTAP writes it in output files: arguments with spaces are quoted. */
    static String commandLine(String program, List<String> args) {
        return program + args.stream()
                .map(arg -> arg.isEmpty() || arg.contains(" ") ? '"' + arg + '"' : arg)
                .collect(Collectors.joining(" ", args.isEmpty() ? "" : " ", ""));
    }

    /**
     * Logs the command lines received in {@code ~/.asterion/astap-calls.log}, to find out how
     * programs call ASTAP. Failures are ignored: logging must not prevent solving.
     */
    private static void logCall(String commandLine) {
        var log = Path.of(System.getProperty("user.home"), ".asterion", "astap-calls.log");
        try {
            Files.createDirectories(log.getParent());
            var append = !Files.exists(log) || Files.size(log) < MAX_CALL_LOG_SIZE;
            var line = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    + "\t" + Path.of("").toAbsolutePath() + "\t" + commandLine + System.lineSeparator();
            Files.writeString(log, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException | RuntimeException _) {
            // nothing to do
        }
    }

    private static void printUsage(PrintStream out) {
        out.println("""
                Asterion Solver %s, ASTAP compatibility mode (best effort)

                Usage: astap -f <image> [options]
                  -f <file>        image to solve: FITS, PNG, JPEG, TIFF
                  -r <degrees>     radius of the search around the start position, 180 for the whole sky (default %s)
                  -fov <degrees>   height of the field of view, 0 for unknown (default: from the FITS header)
                  -ra <hours>      right ascension of the start position (default: from the FITS header)
                  -spd <degrees>   declination + 90 of the start position (default: from the FITS header)
                  -z <factor>      binning applied before solving, 0 for automatic
                  -o <base>        base path of the output files (default: the path of the image without extension)
                  -sip             add SIP distortion coefficients
                  -wcs             write the .wcs file as a FITS header instead of text
                  -update          add the solution to the header of the FITS image
                  -log             write a log file
                Accepted and ignored: -s, -t, -m, -check, -d, -D, -speed, -progress.
                Star catalogs are the ones of Asterion: install them with "asterion --download <catalog>".
                """.formatted(AsterionVersion.get(), AstapNumbers.fixed("%.0f", DEFAULT_RADIUS_DEG)));
    }
}
