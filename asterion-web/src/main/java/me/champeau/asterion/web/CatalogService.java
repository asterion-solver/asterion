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

import me.champeau.asterion.catalog.Catalogs;
import me.champeau.asterion.progress.ProgressListener;
import me.champeau.asterion.progress.ProgressStep;
import me.champeau.asterion.progress.ProgressUpdate;
import me.champeau.asterion.solver.PlateSolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The catalogs of the web interface: the ones which are installed, the installations in progress,
 * and the solver which uses the installed ones, but the ones which were disabled in the
 * preferences.
 */
final class CatalogService {
    private final Path directory;
    private final Preferences preferences;
    private final Map<String, Installation> installations = new LinkedHashMap<>();
    private PlateSolver solver;

    CatalogService(Path directory, Preferences preferences) {
        this.directory = directory;
        this.preferences = preferences;
    }

    Path directory() {
        return directory;
    }

    /** The solver, with the installed catalogs which are enabled, or null if there is none. */
    synchronized PlateSolver solver() throws IOException {
        if (solver == null) {
            var files = enabledFiles();
            if (!files.isEmpty()) {
                solver = PlateSolver.open(files.toArray(Path[]::new));
            }
        }
        return solver;
    }

    /**
     * The solver.
     *
     * @throws IllegalStateException if no catalog is installed, or none is enabled
     */
    synchronized PlateSolver requireSolver() throws IOException {
        var solver = solver();
        if (solver == null) {
            if (Catalogs.installed(directory).isEmpty()) {
                throw new IllegalStateException("No catalog is installed in " + directory + ": install one first");
            }
            throw new IllegalStateException("No catalog is enabled: enable one of the installed catalogs");
        }
        return solver;
    }

    /** The files of the installed catalogs which aren't disabled. */
    private List<Path> enabledFiles() throws IOException {
        var disabled = preferences.disabledCatalogs();
        var files = new ArrayList<Path>();
        for (var file : Catalogs.installed(directory)) {
            if (!disabled.contains(nameOf(file))) {
                files.add(file);
            }
        }
        return files;
    }

    private static String nameOf(Path file) {
        var name = file.getFileName().toString();
        return name.substring(0, name.length() - Catalogs.EXTENSION.length());
    }

    /**
     * Enables or disables an installed catalog: a disabled catalog stays installed, but isn't
     * used to solve. The choice is kept in the preferences.
     *
     * @throws IllegalArgumentException if the catalog isn't installed
     */
    synchronized void setEnabled(String name, boolean enabled) throws IOException {
        var installed = Catalogs.installed(directory).stream().map(CatalogService::nameOf).toList();
        if (!installed.contains(name)) {
            throw new IllegalArgumentException("The catalog " + name + " isn't installed");
        }
        var disabled = preferences.disabledCatalogs();
        if (enabled) {
            disabled.remove(name);
        } else {
            disabled.add(name);
        }
        preferences.saveDisabledCatalogs(disabled);
        solver = null;
    }

    /**
     * The brightest stars of a catalog around a position, for a chart: the levels of the index are
     * read from the brightest, until there are enough stars.
     *
     * @param index the name of the catalog, or null for the first installed one
     * @param raDeg the right ascension of the center, in degrees
     * @param decDeg the declination of the center, in degrees
     * @param radiusDeg the radius of the chart, in degrees
     * @param count the number of stars which is enough
     * @return the stars, as (right ascension, declination, level), the level telling their brightness
     */
    Json stars(String index, double raDeg, double decDeg, double radiusDeg, int count) throws IOException {
        var solver = requireSolver();
        var catalog = solver.indexes().stream()
                .filter(i -> index == null || i.name().equals(index))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown catalog " + index));
        var ra = Math.toRadians(raDeg);
        var dec = Math.toRadians(decDeg);
        var radius = Math.toRadians(radiusDeg);
        var center = new double[]{Math.cos(dec) * Math.cos(ra), Math.cos(dec) * Math.sin(ra), Math.sin(dec)};
        var minDot = Math.cos(radius);
        var stars = Json.array();
        var vec = new double[3];
        var found = 0;
        var level = 0;
        for (; level < catalog.levelCount() && found < count; level++) {
            var ranges = new int[catalog.grid(level).maxConeRangeInts(radius)];
            var n = catalog.coneStarRanges(level, ra, dec, radius, ranges);
            for (var i = 0; i < n; i += 2) {
                for (var star = ranges[i]; star < ranges[i + 1]; star++) {
                    catalog.starVector(star, 0, vec);
                    if (vec[0] * center[0] + vec[1] * center[1] + vec[2] * center[2] >= minDot) {
                        var starRa = Math.toDegrees(Math.atan2(vec[1], vec[0]));
                        stars.add(round(starRa < 0 ? starRa + 360 : starRa))
                                .add(round(Math.toDegrees(Math.asin(vec[2]))))
                                .add(level);
                        found++;
                    }
                }
            }
        }
        return Json.object()
                .field("catalog", catalog.name())
                .field("levels", level)
                .field("stars", stars);
    }

    /** Rounds to 1/10000 degree, 0.36″: charts don't need more. */
    private static double round(double degrees) {
        return Math.round(degrees * 1e4) / 1e4;
    }

    /** Describes the installed catalogs, the ones which can be installed, and the installations. */
    synchronized Json describe() throws IOException {
        var installed = Json.array();
        var names = new TreeSet<String>();
        var disabled = preferences.disabledCatalogs();
        for (var file : Catalogs.installed(directory)) {
            var name = nameOf(file);
            names.add(name);
            installed.add(Json.object().field("name", name).field("size", Files.size(file)).field("enabled", !disabled.contains(name)));
        }
        var available = Json.array();
        for (var descriptor : Catalogs.available()) {
            available.add(Json.object()
                    .field("name", descriptor.name())
                    .field("description", descriptor.description())
                    .field("installed", names.contains(descriptor.name())));
        }
        var jobs = Json.array();
        installations.values().forEach(installation -> jobs.add(installation.describe()));
        return Json.object()
                .field("directory", directory.toAbsolutePath().toString())
                .field("installed", installed)
                .field("available", available)
                .field("installations", jobs);
    }

    /**
     * Starts the installation of a catalog, in the background.
     *
     * @throws IllegalArgumentException if the catalog is unknown
     * @throws IllegalStateException if it's being installed
     */
    synchronized void install(String name) {
        var descriptor = Catalogs.find(name).orElseThrow(() -> new IllegalArgumentException("Unknown catalog " + name));
        var current = installations.get(descriptor.name());
        if (current != null && current.running) {
            throw new IllegalStateException(descriptor.name() + " is being installed");
        }
        var installation = new Installation(descriptor.name());
        installations.put(descriptor.name(), installation);
        Thread.ofVirtual().name("install-" + descriptor.name()).start(() -> {
            try {
                Catalogs.install(descriptor, directory, installation);
                installation.finish(null);
            } catch (IOException | RuntimeException e) {
                installation.finish(e.getMessage() == null ? e.toString() : e.getMessage());
            }
            synchronized (this) {
                // the next solve uses the new catalog
                solver = null;
            }
        });
    }

    /** An installation, which records its progress. */
    private static final class Installation implements ProgressListener {
        private final String name;
        private volatile boolean running = true;
        private volatile String step = "Starting";
        private volatile double fraction = Double.NaN;
        private volatile long bytes = -1;
        private volatile String message;
        private volatile String error;
        private final List<String> log = new ArrayList<>();

        Installation(String name) {
            this.name = name;
        }

        @Override
        public void started(ProgressStep started) {
            step = started.description();
            fraction = started.hasTotal() ? 0 : Double.NaN;
            bytes = -1;
        }

        @Override
        public void advanced(ProgressUpdate update) {
            fraction = update.fraction();
            bytes = update.bytes();
        }

        @Override
        public void info(String text) {
            message = text;
            synchronized (log) {
                log.add(text);
            }
        }

        void finish(String failure) {
            error = failure;
            running = false;
        }

        Json describe() {
            var messages = Json.array();
            synchronized (log) {
                log.forEach(messages::add);
            }
            return Json.object()
                    .field("name", name)
                    .field("running", running)
                    .field("step", step)
                    .field("fraction", running ? fraction : Double.NaN)
                    .field("bytes", bytes)
                    .field("message", message)
                    .field("error", error)
                    .field("log", messages);
        }
    }
}
