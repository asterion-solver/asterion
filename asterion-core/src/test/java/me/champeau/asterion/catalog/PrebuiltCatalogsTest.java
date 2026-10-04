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

import com.sun.net.httpserver.HttpServer;
import me.champeau.asterion.progress.ProgressListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrebuiltCatalogsTest {
    private final List<HttpServer> servers = new ArrayList<>();
    /** The requests received by the servers: path, and range if any. */
    private final ConcurrentLinkedQueue<String> requests = new ConcurrentLinkedQueue<>();
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private final ProgressListener listener = ProgressListener.text(events::add);
    private final CatalogsTest.SyntheticCatalog catalog = new CatalogsTest.SyntheticCatalog();
    @TempDir
    private Path dir;
    private Path original;
    private Path release;

    @BeforeEach
    void publish() throws IOException {
        original = Catalogs.install(catalog, catalog.indexOptions(), dir.resolve("built"), false, List.of(), ProgressListener.none());
        release = dir.resolve("release");
        var entry = CatalogArchive.pack(original, catalog.name(), catalog.description(), release, Files.size(original) / 3);
        try (var writer = Files.newBufferedWriter(release.resolve(CatalogArchive.MANIFEST))) {
            CatalogArchive.writeManifest(List.of(entry), writer);
        }
    }

    @AfterEach
    void stopServers() {
        servers.forEach(server -> server.stop(0));
    }

    /** A server of the files of a release, which supports ranges like GitHub, or always fails. */
    private String server(Path files, int failure) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/release/", exchange -> {
            var name = exchange.getRequestURI().getPath().substring("/release/".length());
            var range = exchange.getRequestHeaders().getFirst("Range");
            requests.add(name + (range == null ? "" : " " + range));
            var file = files.resolve(name);
            if (failure != 0 || !Files.isRegularFile(file)) {
                exchange.sendResponseHeaders(failure != 0 ? failure : 404, -1);
                exchange.close();
                return;
            }
            var bytes = Files.readAllBytes(file);
            var status = 200;
            if (range != null) {
                var start = Integer.parseInt(range.substring("bytes=".length(), range.indexOf('-')));
                if (start >= bytes.length) {
                    exchange.sendResponseHeaders(416, -1);
                    exchange.close();
                    return;
                }
                bytes = Arrays.copyOfRange(bytes, start, bytes.length);
                status = 206;
            }
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        servers.add(server);
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/release/";
    }

    private Path install(List<String> releases) throws IOException {
        return Catalogs.install(catalog, catalog.indexOptions(), dir.resolve("installed"), false, releases, listener);
    }

    private boolean built() {
        return events.stream().anyMatch(event -> event.contains("Downloading random stars"));
    }

    @Test
    void installsPrebuiltIndexes() throws IOException {
        var index = install(List.of(server(release, 0)));
        assertArrayEquals(Files.readAllBytes(original), Files.readAllBytes(index));
        assertFalse(built(), events::toString);
        assertFalse(Files.exists(dir.resolve("installed/synthetic.download")), "downloaded files are deleted");
        assertTrue(requests.contains("synthetic.astx.gz.002"), requests::toString);
    }

    @Test
    void resumesInterruptedDownloads() throws IOException {
        var part = release.resolve("synthetic.astx.gz.001");
        var work = Files.createDirectories(dir.resolve("installed/synthetic.download"));
        var bytes = Files.readAllBytes(part);
        Files.write(work.resolve(part.getFileName()), Arrays.copyOf(bytes, bytes.length / 2));
        var index = install(List.of(server(release, 0)));
        assertArrayEquals(Files.readAllBytes(original), Files.readAllBytes(index));
        assertTrue(requests.contains("synthetic.astx.gz.001 bytes=" + bytes.length / 2 + "-"), requests::toString);
    }

    @Test
    void usesTheMirrorsWhichWork() throws IOException {
        var index = install(List.of(server(release, 500), server(release, 0), server(release, 503)));
        assertArrayEquals(Files.readAllBytes(original), Files.readAllBytes(index));
        assertFalse(built(), events::toString);
    }

    @Test
    void replacesCorruptDownloads() throws IOException {
        // a mirror which serves a part of another catalog
        var corrupt = Files.createDirectories(dir.resolve("corrupt"));
        for (var file : List.of(CatalogArchive.MANIFEST, "synthetic.astx.gz.000", "synthetic.astx.gz.002")) {
            Files.copy(release.resolve(file), corrupt.resolve(file));
        }
        Files.write(corrupt.resolve("synthetic.astx.gz.001"), new byte[1000]);
        var index = install(List.of(server(corrupt, 0), server(release, 0)));
        assertArrayEquals(Files.readAllBytes(original), Files.readAllBytes(index));
    }

    @Test
    void buildsIndexesWhichArentPublished() throws IOException {
        Files.writeString(release.resolve(CatalogArchive.MANIFEST), "catalogs=tycho2\\n");
        var index = install(List.of(server(release, 0)));
        assertTrue(built(), events::toString);
        assertArrayEquals(Files.readAllBytes(original), Files.readAllBytes(index));
    }

    @Test
    void buildsIndexesWhenNoReleaseIsAvailable() throws IOException {
        var index = install(List.of(server(release, 404)));
        assertTrue(built(), events::toString);
        assertTrue(events.stream().anyMatch(event -> event.startsWith("Unable to download the prebuilt synthetic")), events::toString);
        assertArrayEquals(Files.readAllBytes(original), Files.readAllBytes(index));
    }
}
