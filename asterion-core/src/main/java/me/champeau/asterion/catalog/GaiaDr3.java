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

import me.champeau.asterion.progress.ProgressListener;
import me.champeau.asterion.progress.ProgressTracker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

/**
 * Downloads stars of the Gaia DR3 catalogue (Gaia Collaboration, 2022) from the archive of the
 * European Space Agency, or one of its mirrors. The sky is queried by chunks, and the same number of
 * stars, the brightest ones, is fetched for each of them: the result has the same density all over the
 * sky, which is what solving needs. A magnitude limited selection would be dominated by the stars of
 * the Milky Way.
 */
public final class GaiaDr3 {
    private static final double EPOCH = 2016.0;
    /**
     * Gaia source identifiers start with the index of the level 12 HEALPix cell of the source, so
     * a range of identifiers is a region of the sky. Chunks are level 3 cells.
     */
    static final int CHUNKS = 768;
    private static final long CHUNK_ID_RANGE = (1L << 35) * (1L << 18);
    private static final double CHUNK_AREA = 41252.96 / CHUNKS;
    /** The queries are spread over the servers: each one gets about 2 at a time. */
    private static final int CONNECTIONS = 8;
    private static final double FAINTEST = 21;
    /** Each chunk is tried on every server, this number of times. */
    private static final int ROUNDS = 3;
    /** A server which takes longer is probably overloaded: another one is tried first. */
    private static final Duration FIRST_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration RETRY_TIMEOUT = Duration.ofMinutes(10);

    /** The servers of Gaia DR3: they all have the columns of the archive of ESA, with these names. */
    static final List<Server> SERVERS = List.of(
            new Server("the Gaia archive of ESA", "https://gea.esac.esa.int/tap-server/tap/sync", "gaiadr3.gaia_source"),
            new Server("the Gaia mirror of ARI Heidelberg", "https://gaia.ari.uni-heidelberg.de/tap/sync", "gaiadr3.gaia_source"),
            new Server("GAVO", "https://dc.g-vo.org/tap/sync", "gaia.dr3lite"),
            new Server("NOIRLab Astro Data Lab", "https://datalab.noirlab.edu/tap/sync", "gaia_dr3.gaia_source"),
            new Server("IRSA", "https://irsa.ipac.caltech.edu/TAP/sync", "gaia_dr3_source"));

    /**
     * A TAP service which publishes Gaia DR3.
     *
     * @param name the name of the service, for people
     * @param url the URL of synchronous queries
     * @param table the table of the Gaia DR3 sources
     */
    record Server(
            String name,
            String url,
            String table) {
    }

    private GaiaDr3() {
    }

    /**
     * Downloads the brightest stars of each region of the sky.
     *
     * @param workDirectory where to store the downloaded files: an interrupted download resumes where it stopped
     * @param density the number of stars per square degree
     * @param progress receives the progress of the download
     */
    public static StarData fetch(Path workDirectory, int density, ProgressListener progress) throws IOException {
        return fetch(workDirectory, density, SERVERS, progress);
    }

    static StarData fetch(Path workDirectory, int density, List<Server> servers, ProgressListener progress) throws IOException {
        var perChunk = (int) Math.ceil(density * CHUNK_AREA);
        var description = "Downloading %,d stars of Gaia DR3 from the Gaia archive and its mirrors".formatted((long) perChunk * CHUNKS);
        var gaiaServers = new Mirrors<>(servers, Server::name, progress);
        try (var downloader = new Downloader();
             var pool = new ForkJoinPool(CONNECTIONS);
             var tracker = ProgressTracker.start(progress, description, CHUNKS, "regions")) {
            pool.submit(() -> IntStream.range(0, CHUNKS).parallel().forEach(chunk -> {
                try {
                    var file = workDirectory.resolve("gaia-%03d.csv".formatted(chunk));
                    if (!Files.exists(file)) {
                        download(downloader, gaiaServers, chunk, perChunk, density, file);
                    }
                    tracker.advance(1, Files.size(file));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            })).join();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        progress.info("Reading Gaia DR3");
        var stars = new StarData(EPOCH, perChunk * CHUNKS);
        for (var chunk = 0; chunk < CHUNKS; chunk++) {
            read(workDirectory.resolve("gaia-%03d.csv".formatted(chunk)), stars);
        }
        return stars;
    }

    /** Downloads a chunk from a random server, trying the other ones when it fails. */
    private static void download(Downloader downloader, Mirrors<Server> servers, int chunk, int count, int density,
                                 Path file) throws IOException {
        var part = file.resolveSibling(file.getFileName() + ".part");
        IOException failure = null;
        for (var round = 0; round < ROUNDS; round++) {
            var timeout = round == 0 ? FIRST_TIMEOUT : RETRY_TIMEOUT;
            for (var server : servers.ordered()) {
                try {
                    download(downloader, server, timeout, chunk, count, density, part);
                    servers.succeeded(server);
                    Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
                    return;
                } catch (IOException e) {
                    if (Thread.currentThread().isInterrupted()) {
                        // cancelled: no other server must be tried
                        throw e;
                    }
                    servers.failed(server, e);
                    var described = new IOException("%s: %s".formatted(server.name(), e.getMessage()), e);
                    if (failure == null) {
                        failure = described;
                    } else {
                        failure.addSuppressed(described);
                    }
                }
            }
            if (round == ROUNDS - 1) {
                break;
            }
            try {
                Thread.sleep(Duration.ofSeconds(10L * (round + 1)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Download interrupted", e);
            }
        }
        throw new IOException("Unable to download stars of Gaia DR3 from any server", failure);
    }

    private static void download(Downloader downloader, Server server, Duration timeout, int chunk, int count, int density,
                                 Path target) throws IOException {
        // Sorting all the stars of a region of the Milky Way would be slow: stars are first searched
        // down to a magnitude which is enough for dense regions, then deeper if needed
        var limit = 13 + 2.5 * Math.log10(Math.max(1, density / 100.0));
        var previous = -1L;
        while (true) {
            limit = Math.min(limit, FAINTEST);
            var query = "SELECT TOP " + count + " ra,dec,pmra,pmdec,phot_g_mean_mag FROM " + server.table()
                    + " WHERE source_id BETWEEN " + chunk * CHUNK_ID_RANGE + " AND " + ((chunk + 1) * CHUNK_ID_RANGE - 1)
                    + " AND phot_g_mean_mag < " + limit + " ORDER BY phot_g_mean_mag";
            var rows = query(downloader, server, timeout, query, count, target);
            if (rows >= count || limit >= FAINTEST) {
                return;
            }
            if (rows == previous) {
                // a fainter limit always adds stars: the server returns fewer rows than it's asked for
                throw new IOException("results are limited to " + rows + " rows");
            }
            previous = rows;
            limit += 1.5;
        }
    }

    /**
     * Runs a query, the result of which is saved to a file.
     *
     * @param maxRows the number of rows the server must be able to return: some servers return fewer by default
     * @return the number of rows of the result
     */
    private static long query(Downloader downloader, Server server, Duration timeout, String adql, int maxRows,
                              Path target) throws IOException {
        var form = "REQUEST=doQuery&LANG=ADQL&FORMAT=csv&MAXREC=" + maxRows + "&QUERY=" + URLEncoder.encode(adql, StandardCharsets.UTF_8);
        var request = HttpRequest.newBuilder(URI.create(server.url()))
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try {
            var response = downloader.client().send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP status " + response.statusCode());
                }
                Files.copy(body, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (HttpTimeoutException e) {
            throw new IOException("no answer after " + timeout.toMinutes() + " minutes", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        }
        try (var reader = Files.newBufferedReader(target, StandardCharsets.UTF_8)) {
            var header = reader.readLine();
            if (header == null || !header.startsWith("ra,dec")) {
                throw new IOException("unexpected answer: " + header);
            }
            return reader.lines().count();
        }
    }

    static void read(Path file, StarData stars) throws IOException {
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            // the first line is the header
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                // ra, dec, pmra, pmdec, magnitude: proper motions are missing for some stars
                var c1 = line.indexOf(',');
                var c2 = line.indexOf(',', c1 + 1);
                var c3 = line.indexOf(',', c2 + 1);
                var c4 = line.indexOf(',', c3 + 1);
                if (c4 < 0 || c4 == line.length() - 1) {
                    continue;
                }
                var ra = Double.parseDouble(line.substring(0, c1));
                var dec = Double.parseDouble(line.substring(c1 + 1, c2));
                var pmRa = properMotion(line.substring(c2 + 1, c3));
                var pmDec = properMotion(line.substring(c3 + 1, c4));
                var mag = Float.parseFloat(line.substring(c4 + 1));
                stars.add(Math.toRadians(ra), Math.toRadians(dec), pmRa, pmDec, mag);
            }
        }
    }

    /** Parses a proper motion, which is missing for some stars: servers leave it empty, or write NaN. */
    private static float properMotion(String value) {
        if (value.isEmpty()) {
            return 0;
        }
        var pm = Float.parseFloat(value);
        return Float.isNaN(pm) ? 0 : pm;
    }
}
