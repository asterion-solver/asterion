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

import me.champeau.asterion.index.IndexBuilder;
import me.champeau.asterion.index.IndexOptions;
import me.champeau.asterion.progress.ProgressListener;
import me.champeau.asterion.progress.ProgressTracker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Pattern;

/**
 * Manages the catalogs which are installed on this machine. Installing a catalog means downloading
 * the star catalogue from its publisher, then building an index file from it.
 */
public final class Catalogs {
    /** The extension of index files. */
    public static final String EXTENSION = ".astx";

    private Catalogs() {
    }

    /**
     * A catalog which can be installed.
     */
    public interface Descriptor {
        String name();

        String description();

        /**
         * Downloads the stars of the catalog, using a directory to store temporary files.
         * {@link ProgressTracker} makes it easy to report the progress of the download.
         */
        StarData fetch(Path workDirectory, ProgressListener progress) throws IOException;

        default IndexOptions indexOptions() {
            return IndexOptions.defaults();
        }
    }

    /**
     * The directory where catalogs are installed by default: the value of the
     * {@code ASTERION_CATALOGS} environment variable, or {@code ~/.asterion/catalogs}.
     */
    public static Path defaultDirectory() {
        var env = System.getenv("ASTERION_CATALOGS");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        return Path.of(System.getProperty("user.home"), ".asterion", "catalogs");
    }

    /**
     * The catalogs which can be installed. Gaia catalogs of any density can also be installed,
     * using a name such as {@code gaia-1000} for 1000 stars per square degree.
     */
    public static List<Descriptor> available() {
        return List.of(TYCHO2, new Gaia(500), new Gaia(1000), new Gaia(2000));
    }

    public static Optional<Descriptor> find(String name) {
        var gaia = GAIA_NAME.matcher(name.toLowerCase(Locale.ROOT));
        if (gaia.matches()) {
            var density = Integer.parseInt(gaia.group(1));
            if (density >= 10 && density <= 10000) {
                return Optional.of(new Gaia(density));
            }
        }
        return available().stream().filter(d -> d.name().equalsIgnoreCase(name)).findFirst();
    }

    /** The index files found in a directory. */
    public static List<Path> installed(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var files = Files.list(directory)) {
            return files.filter(f -> f.getFileName().toString().endsWith(EXTENSION))
                    .sorted(Comparator.comparing(f -> f.getFileName().toString()))
                    .toList();
        }
    }

    /**
     * Installs a catalog: downloads it already indexed when it's published this way, or downloads
     * its stars and indexes them.
     *
     * @param progress receives the progress of the installation
     * @return the index file
     */
    public static Path install(Descriptor descriptor, Path directory, ProgressListener progress) throws IOException {
        return install(descriptor, descriptor.indexOptions(), directory, false, false, progress);
    }

    /**
     * Installs a catalog, with custom parameters. A prebuilt index is only used when the
     * parameters are the default ones of the catalog.
     *
     * @param keepDownloads true to keep the downloaded files, which makes it possible to build the index again
     * @param fromSources true to download the stars and index them, even if the catalog is published already indexed
     * @param progress receives the progress of the installation
     * @return the index file
     */
    public static Path install(Descriptor descriptor, IndexOptions options, Path directory, boolean keepDownloads, boolean fromSources,
                               ProgressListener progress) throws IOException {
        return install(descriptor, options, directory, keepDownloads, fromSources ? List.of() : PrebuiltCatalogs.RELEASES, progress);
    }

    static Path install(Descriptor descriptor, IndexOptions options, Path directory, boolean keepDownloads, List<String> releases,
                        ProgressListener progress) throws IOException {
        Files.createDirectories(directory);
        var work = directory.resolve(descriptor.name() + ".download");
        Files.createDirectories(work);
        var index = directory.resolve(descriptor.name() + EXTENSION);
        if (!installPrebuilt(descriptor, options, releases, work, index, progress)) {
            var stars = descriptor.fetch(work, progress);
            IndexBuilder.build(stars, descriptor.name(), options, index, progress);
        }
        if (!keepDownloads) {
            deleteRecursively(work);
        }
        progress.info("Installed " + index + " (" + Files.size(index) / (1024 * 1024) + " MB)");
        return index;
    }

    /**
     * Installs a prebuilt index, if there's one.
     *
     * @return false if the index must be built
     */
    private static boolean installPrebuilt(Descriptor descriptor, IndexOptions options, List<String> releases, Path work, Path index,
                                           ProgressListener progress) throws IOException {
        if (releases.isEmpty() || !options.equals(descriptor.indexOptions())) {
            return false;
        }
        try {
            if (new PrebuiltCatalogs(releases, progress).install(descriptor.name(), work, index)) {
                return true;
            }
            progress.info(descriptor.name() + " isn't published already indexed: it is built from its sources");
        } catch (IOException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw e;
            }
            progress.info(
                    "Unable to download the prebuilt " + descriptor.name() + " (" + e.getMessage() + "): it is built from its sources");
        }
        return false;
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (var files = Files.walk(directory)) {
            for (var file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }
    }

    private static final Pattern GAIA_NAME = Pattern.compile("gaia-(\\d{2,5})");

    /**
     * A selection of the stars of Gaia DR3, with the same density all over the sky.
     *
     * @param density the number of stars per square degree
     */
    private record Gaia(
            int density) implements Descriptor {
        @Override
        public String name() {
            return "gaia-" + density;
        }

        @Override
        public String description() {
            var stars = Math.round(density * 41253.0);
            // about 8 stars are needed in a circle which fits in the image: the factor matches the smallest
            // fields which gaia-500 and gaia-2000 solve, measured on images of the Digitized Sky Survey
            var field = 1.4 * Math.sqrt(8 / (Math.PI / 4 * density));
            return String.format(Locale.ROOT,
                    "Gaia DR3, %d stars per square degree: %d million stars, for fields of view larger than %.0f arcminutes",
                    density, Math.round(stars / 1e6), field * 60);
        }

        @Override
        public StarData fetch(Path workDirectory, ProgressListener progress) throws IOException {
            return GaiaDr3.fetch(workDirectory, density, progress);
        }

        @Override
        public IndexOptions indexOptions() {
            // dense catalogs make large indexes: fewer quads keep them reasonable
            return IndexOptions.defaults().withQuadsPerCell(4);
        }
    }

    private static final Descriptor TYCHO2 = new Descriptor() {
        @Override
        public String name() {
            return "tycho2";
        }

        @Override
        public String description() {
            return "Tycho-2: 2.5 million stars to magnitude 12, for fields of view larger than 0.7 degree";
        }

        @Override
        public StarData fetch(Path workDirectory, ProgressListener progress) throws IOException {
            var files = Tycho2.files();
            // the CDS doesn't like too many simultaneous connections
            try (var downloader = new Downloader();
                 var pool = new ForkJoinPool(4);
                 var tracker = ProgressTracker.start(progress, "Downloading Tycho-2 from " + Tycho2.BASE_URL, files.size(), "files")) {
                pool.submit(() -> files.parallelStream().forEach(name -> {
                    try {
                        var size = downloader.download(Tycho2.BASE_URL + name, workDirectory.resolve(name));
                        tracker.advance(1, size);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })).join();
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
            progress.info("Reading Tycho-2");
            return Tycho2.read(workDirectory);
        }
    };
}
