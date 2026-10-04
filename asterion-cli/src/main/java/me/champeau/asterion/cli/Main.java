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
package me.champeau.asterion.cli;

import me.champeau.asterion.catalog.CatalogArchive;
import me.champeau.asterion.catalog.Catalogs;
import me.champeau.asterion.cli.astap.AstapCommand;
import me.champeau.asterion.image.DetectionOptions;
import me.champeau.asterion.index.StarIndex;
import me.champeau.asterion.progress.ProgressListener;
import me.champeau.asterion.progress.ProgressTracker;
import me.champeau.asterion.solver.Parity;
import me.champeau.asterion.solver.PlateSolver;
import me.champeau.asterion.solver.SolveResult;
import me.champeau.asterion.solver.SolverOptions;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

@Command(name = "asterion",
        mixinStandardHelpOptions = true,
        versionProvider = Main.Version.class,
        sortOptions = false,
        description = "Finds the celestial coordinates, scale and orientation of astronomical images.",
        footer = {
                "",
                "Examples:",
                "  asterion --download-catalog tycho2",
                "  asterion image.fits",
                "  asterion --ra 10:45:03 --dec -59:41:04 --radius 5 --wcs *.fits"
        })
// fields are injected by picocli
@SuppressWarnings({"unused", "FieldMayBeFinal", "MismatchedQueryAndUpdateOfCollection"})
public final class Main implements Callable<Integer> {
    @Parameters(paramLabel = "IMAGE", description = "The images to solve: FITS, PNG, JPEG or TIFF. Wildcards are supported.")
    private List<String> imageArguments = new ArrayList<>();

    @Option(names = "--download-catalog", paramLabel = "NAME", split = ",",
            description = "Downloads and installs a star catalog. See --list-catalogs.")
    private List<String> download = new ArrayList<>();

    @Option(names = "--quads-per-cell", paramLabel = "N", hidden = true,
            description = "Number of quads per cell of the catalogs which are installed: more quads make a larger but more robust index.")
    private int quadsPerCell;

    @Option(names = "--from-sources", hidden = true,
            description = "Builds the indexes of the catalogs which are installed, even if they are published already indexed.")
    private boolean fromSources;

    @Option(names = "--pack-catalogs", paramLabel = "DIR", hidden = true,
            description = "Packs the installed catalogs for publication: compressed parts and their manifest.")
    private Path packDirectory;

    @Option(names = "--keep-downloads", hidden = true, description = "Keeps the files downloaded to install a catalog.")
    private boolean keepDownloads;

    @Option(names = "--list-catalogs", description = "Lists the catalogs which are installed, and the ones which can be downloaded.")
    private boolean listCatalogs;

    @Option(names = "--catalog-dir", paramLabel = "DIR",
            description = "The directory where catalogs are installed. Defaults to $ASTERION_CATALOGS, or ~/.asterion/catalogs.")
    private Path catalogDir;

    @Option(names = {"-c", "--catalog"}, paramLabel = "NAME", split = ",",
            description = "The catalogs to use, by name or by path of an index file. Defaults to all the installed catalogs.")
    private List<String> catalogs = new ArrayList<>();

    @Option(names = "--ra", paramLabel = "RA", description = "Approximate right ascension of the image: degrees, or HH:MM:SS.")
    private String ra;

    @Option(names = "--dec", paramLabel = "DEC", description = "Approximate declination of the image: degrees, or DD:MM:SS.")
    private String dec;

    @Option(names = "--radius", paramLabel = "DEG", defaultValue = "10",
            description = "Search radius around the approximate position, in degrees (default: ${DEFAULT-VALUE}).")
    private double radius;

    @Option(names = "--scale-low", paramLabel = "ARCSEC", description = "Minimum scale of the image, in arcseconds per pixel.")
    private double scaleLow;

    @Option(names = "--scale-high", paramLabel = "ARCSEC", description = "Maximum scale of the image, in arcseconds per pixel.")
    private double scaleHigh;

    @Option(names = "--fov", paramLabel = "DEG",
            description = "Approximate width of the field of view, in degrees. A 30%% error is tolerated.")
    private double fov;

    @Option(names = "--pixel-size", paramLabel = "MICRONS",
            description = "Size of the pixels of the camera, binning included, to compute the focal length. Read from the FITS header (XPIXSZ) by default.")
    private double pixelSize;

    @Option(names = "--no-hints", description = "Ignores the position and scale found in the header of images: the solve is fully blind.")
    private boolean noHints;

    @Option(names = "--no-blind", description = "Doesn't try a blind solve when solving with the hints of the image header fails.")
    private boolean noBlind;

    @Option(names = "--parity", paramLabel = "PARITY", defaultValue = "BOTH",
            description = "Handedness of the image: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).")
    private Parity parity;

    @Option(names = "--sip-order", paramLabel = "N", defaultValue = "-1",
            description = "Order of the SIP distortion polynomials: 0 to disable, 2 to 5, or -1 to choose automatically (default).")
    private int sipOrder;

    @Option(names = "--timeout", paramLabel = "SECONDS", defaultValue = "30",
            description = "Gives up after this time (default: ${DEFAULT-VALUE}).")
    private double timeout;

    @Option(names = "--binning", paramLabel = "N", defaultValue = "0",
            description = "Bins the image before detecting stars. By default, raw frames of color sensors and very large images are binned.")
    private int binning;

    @Option(names = "--sigma", paramLabel = "N", defaultValue = "5",
            description = "Star detection threshold, in units of background noise (default: ${DEFAULT-VALUE}).")
    private double sigma;

    @Option(names = "--max-stars", paramLabel = "N", defaultValue = "1000",
            description = "Maximum number of stars to extract from the image (default: ${DEFAULT-VALUE}).")
    private int maxStars;

    @Option(names = "--quad-stars", paramLabel = "N", defaultValue = "100",
            description = "Number of stars, starting with the brightest, used to search for a match (default: ${DEFAULT-VALUE}).")
    private int quadStars;

    @Option(names = "--code-tolerance", paramLabel = "TOL", hidden = true,
            description = "Maximum distance between the geometric hash codes of matching quads.")
    private double codeTolerance;

    @Option(names = "--wcs", description = "Writes the solution next to each image, as a FITS header in a .wcs file.")
    private boolean writeWcs;

    @Option(names = "--matches", description = "Writes the stars which were matched with the catalog next to each image, as a CSV file.")
    private boolean writeMatches;

    @Option(names = {"-o", "--output"}, paramLabel = "PATH",
            description = "Writes a copy of each image with the solution in its header, leaving the image untouched. "
                    + "With several images, PATH is a directory. Compressed images are written uncompressed.")
    private Path output;

    @Option(names = {"-u", "--update"}, description = "Writes the solution in the header of the image (FITS only).")
    private boolean update;

    @Option(names = "--plain", description = "Prints progress as plain lines of text, instead of a live display. "
            + "Output is plain anyway when it isn't an interactive terminal.")
    private boolean plain;

    @Option(names = "--json", description = "Prints results as JSON.")
    private boolean json;

    @Option(names = {"-v", "--verbose"}, description = "Prints more details.")
    private boolean verbose;

    public static void main(String[] args) {
        // images are decoded with the AWT image readers, which must not look for a display
        System.setProperty("java.awt.headless", "true");
        // nom-tam-fits logs the oddities of FITS files, which are reported as errors when they matter
        Logger.getLogger("nom.tam").setLevel(Level.OFF);
        // the ASTAP compatibility mode, for programs which call ASTAP: when installed as astap, or with "asterion astap"
        if (ProgramName.current().startsWith("astap")) {
            System.exit(AstapCommand.run(ProgramName.invoked(), List.of(args)));
        }
        if (args.length > 0 && args[0].equals("astap")) {
            System.exit(AstapCommand.run(ProgramName.invoked() + " astap", List.of(args).subList(1, args.length)));
        }
        var main = new Main();
        var commandLine = new CommandLine(main)
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setExecutionExceptionHandler((e, cmd, _) -> {
                    if (main.verbose) {
                        e.printStackTrace(cmd.getErr());
                    } else {
                        cmd.getErr().println("Error: " + (e.getMessage() == null ? e : e.getMessage()));
                    }
                    return 2;
                });
        System.exit(commandLine.execute(args));
    }

    @Override
    public Integer call() throws Exception {
        var directory = catalogDir != null ? catalogDir : Catalogs.defaultDirectory();
        var log = json ? System.err : System.out;
        // everything is checked before starting any work
        var descriptors = new ArrayList<Catalogs.Descriptor>();
        for (var name : download) {
            var descriptor = Catalogs.find(name);
            if (descriptor.isEmpty()) {
                System.err.println("Unknown catalog '" + name + "'. Use --list-catalogs to see which catalogs can be downloaded.");
                return 2;
            }
            descriptors.add(descriptor.get());
        }
        var images = expand(imageArguments);
        if (output != null && images.size() > 1 && Files.exists(output) && !Files.isDirectory(output)) {
            System.err.println("--output must be a directory when several images are solved");
            return 2;
        }
        if (images.isEmpty() && descriptors.isEmpty() && !listCatalogs && packDirectory == null) {
            new CommandLine(this).usage(System.err);
            return 2;
        }
        // the live display is worth it for long work only
        var live = !plain && !json && (!descriptors.isEmpty() || images.size() > 1);
        var console = Console.create(live, log);
        try {
            return console.run(() -> execute(console, directory, descriptors, images));
        } catch (Console.CancelledException _) {
            System.err.println("Cancelled");
            return 130;
        }
    }

    private int execute(Console console, Path directory, List<Catalogs.Descriptor> descriptors, List<Path> images) throws IOException {
        for (var descriptor : descriptors) {
            var indexOptions = descriptor.indexOptions();
            if (quadsPerCell > 0) {
                indexOptions = indexOptions.withQuadsPerCell(quadsPerCell);
            }
            Catalogs.install(descriptor, indexOptions, directory, keepDownloads, fromSources, console.progress());
        }
        if (packDirectory != null) {
            packCatalogs(directory, console);
        }
        if (listCatalogs) {
            listCatalogs(directory, console::println);
        }
        if (images.isEmpty()) {
            return 0;
        }
        var indexes = openIndexes(directory);
        if (indexes.isEmpty()) {
            console.error("No catalog is installed in " + directory + ". Install one with: asterion --download-catalog tycho2");
            return 2;
        }
        if (verbose) {
            indexes.forEach(index -> console.println("Using " + index));
        }
        var solver = new PlateSolver(indexes);
        var options = options();
        var failures = 0;
        var records = new ArrayList<String>();
        var written = new HashSet<Path>();
        // in plain output, the report of each image tells the progress already
        var batchProgress = console instanceof RichConsole && images.size() > 1 ? console.progress() : ProgressListener.none();
        try (var tracker = ProgressTracker.start(batchProgress, "Solving %d images".formatted(images.size()), images.size(), "images")) {
            for (var image : images) {
                if (!solve(console, solver, options, image, images.size(), records, written)) {
                    failures++;
                }
                tracker.advance(1);
            }
        }
        if (json) {
            System.out.println(records.size() == 1 ? records.getFirst() : "[" + String.join(",\n", records) + "]");
        }
        return failures == 0 ? 0 : 1;
    }

    /**
     * Solves an image, and writes the requested outputs.
     *
     * @return true if the image was solved and its outputs written
     */
    private boolean solve(Console console, PlateSolver solver, SolverOptions options, Path image, int imageCount,
                          List<String> records, Set<Path> written) {
        SolveResult result;
        try {
            result = solver.solve(image, options);
        } catch (IOException | RuntimeException e) {
            // an image which can't be read mustn't prevent the others from being solved
            console.error(image + ": " + (e.getMessage() == null ? e : e.getMessage()));
            if (verbose) {
                e.printStackTrace(System.err);
            }
            return false;
        }
        if (json) {
            records.add(Report.json(image, result));
        } else {
            Report.print(console::println, image, result, verbose);
        }
        if (result.solution().isEmpty()) {
            return false;
        }
        var solution = result.solution().orElseThrow();
        try {
            if (writeWcs) {
                WcsWriter.writeWcsFile(image, solution);
            }
            if (writeMatches) {
                WcsWriter.writeMatches(image, solution);
            }
            if (output != null) {
                var target = outputFor(image, imageCount);
                if (!written.add(target.toAbsolutePath().normalize())) {
                    throw new IOException("Another image was already written to " + target);
                }
                WcsWriter.writeCopy(image, solution, target);
            }
            if (update) {
                WcsWriter.updateHeader(image, solution);
            }
            return true;
        } catch (IOException e) {
            // the other images are still processed
            console.error(image + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * The file where the copy of an image is written: PATH itself for a single image, unless it is
     * a directory, or the image file name in the PATH directory.
     */
    private Path outputFor(Path image, int imageCount) {
        if (imageCount == 1 && !Files.isDirectory(output)) {
            return output;
        }
        var name = image.getFileName().toString();
        // copies are written uncompressed
        for (var suffix : new String[]{".fz", ".gz"}) {
            if (name.toLowerCase(Locale.ROOT).endsWith(suffix)) {
                name = name.substring(0, name.length() - suffix.length());
            }
        }
        return output.resolve(name);
    }

    /** Expands wildcards, for shells which don't. */
    private static List<Path> expand(List<String> arguments) throws IOException {
        var files = new ArrayList<Path>();
        for (var argument : arguments) {
            var separator = Math.max(argument.lastIndexOf('/'), argument.lastIndexOf('\\'));
            var name = argument.substring(separator + 1);
            if (name.contains("*") || name.contains("?")) {
                var directory = separator < 0 ? Path.of("") : Path.of(argument.substring(0, separator + 1));
                var matches = new ArrayList<Path>();
                try (var stream = Files.newDirectoryStream(directory, name)) {
                    stream.forEach(matches::add);
                }
                matches.sort(null);
                files.addAll(matches);
            } else {
                files.add(Path.of(argument));
            }
        }
        return files;
    }

    /** Packs the installed catalogs for publication, with their manifest. */
    private void packCatalogs(Path directory, Console console) throws IOException {
        var entries = new ArrayList<CatalogArchive.Entry>();
        for (var index : Catalogs.installed(directory)) {
            var file = index.getFileName().toString();
            var name = file.substring(0, file.length() - Catalogs.EXTENSION.length());
            var description = Catalogs.find(name).map(Catalogs.Descriptor::description).orElse(name);
            console.println("Packing " + name);
            entries.add(CatalogArchive.pack(index, name, description, packDirectory, CatalogArchive.MAX_PART_SIZE));
        }
        try (var writer = Files.newBufferedWriter(packDirectory.resolve(CatalogArchive.MANIFEST), StandardCharsets.UTF_8)) {
            CatalogArchive.writeManifest(entries, writer);
        }
        console.println(
                "Packed %d catalogs in %s, for the release catalogs-v%d".formatted(entries.size(), packDirectory, StarIndex.VERSION));
    }

    private void listCatalogs(Path directory, Consumer<String> out) throws IOException {
        out.accept("Installed in " + directory + ":");
        var installed = Catalogs.installed(directory);
        if (installed.isEmpty()) {
            out.accept("  (none)");
        }
        for (var file : installed) {
            try {
                out.accept("  " + StarIndex.open(file));
            } catch (IOException e) {
                out.accept("  " + file.getFileName() + ": " + e.getMessage());
            }
        }
        out.accept("Available for download:");
        for (var descriptor : Catalogs.available()) {
            out.accept("  " + descriptor.name() + ": " + descriptor.description());
        }
        out.accept("  gaia-<N>: Gaia DR3 with any other density, N being the number of stars per square degree");
    }

    private List<StarIndex> openIndexes(Path directory) throws IOException {
        var indexes = new ArrayList<StarIndex>();
        if (catalogs.isEmpty()) {
            for (var file : Catalogs.installed(directory)) {
                indexes.add(StarIndex.open(file));
            }
        } else {
            for (var name : catalogs) {
                var file = Path.of(name);
                if (!Files.isRegularFile(file)) {
                    file = directory.resolve(name + Catalogs.EXTENSION);
                }
                if (!Files.isRegularFile(file)) {
                    throw new IOException("Catalog '" + name + "' is not installed");
                }
                indexes.add(StarIndex.open(file));
            }
        }
        return indexes;
    }

    private SolverOptions options() {
        var builder = SolverOptions.builder()
                .useImageHints(!noHints)
                .blindFallback(!noBlind)
                .parity(parity)
                .sipOrder(sipOrder)
                .timeout(Duration.ofMillis((long) (timeout * 1000)))
                .binning(binning)
                .maxQuadStars(quadStars)
                .detection(DetectionOptions.defaults().withThresholdSigma(sigma).withMaxStars(maxStars));
        if (pixelSize > 0) {
            builder.pixelSize(pixelSize);
        }
        if (codeTolerance > 0) {
            builder.codeTolerance(codeTolerance);
        }
        if (ra != null && dec != null) {
            builder.position(Angles.parseRa(ra), Angles.parseDec(dec), radius);
        } else if (ra != null || dec != null) {
            throw new CommandLine.ParameterException(new CommandLine(this), "--ra and --dec must be used together");
        }
        if (scaleLow > 0 || scaleHigh > 0) {
            builder.scale(scaleLow, scaleHigh);
        }
        if (fov > 0) {
            builder.fieldOfView(fov * 0.7, fov * 1.3);
        }
        return builder.build();
    }

    static final class Version implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[]{"Asterion Solver " + AsterionVersion.get()};
        }
    }

    static String format(String pattern, Object... args) {
        return String.format(Locale.ROOT, pattern, args);
    }
}
