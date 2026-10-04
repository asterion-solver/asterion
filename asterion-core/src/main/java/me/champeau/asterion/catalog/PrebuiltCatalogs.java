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

import me.champeau.asterion.index.StarIndex;
import me.champeau.asterion.progress.ProgressListener;
import me.champeau.asterion.progress.ProgressTracker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * Installs catalogs which are already indexed, from the releases where they are published: it's
 * faster, and needs less memory, than downloading the stars and indexing them.
 */
final class PrebuiltCatalogs {
    /** The releases of prebuilt catalogs, for the index format of this version. */
    static final List<String> RELEASES = List.of(
            "https://github.com/asterion-solver/catalogs/releases/download/catalogs-v" + StarIndex.VERSION + "/");
    /** Each part is tried on every mirror, this number of times. */
    private static final int ROUNDS = 2;
    private static final long MB = 1024 * 1024;

    private final List<String> releases;
    private final ProgressListener progress;

    PrebuiltCatalogs(List<String> releases, ProgressListener progress) {
        this.releases = List.copyOf(releases);
        this.progress = progress;
    }

    /**
     * Installs a prebuilt catalog.
     *
     * @param work where to download files: an interrupted download resumes where it stopped
     * @param target the index file to create
     * @return false if the catalog isn't prebuilt
     */
    boolean install(String name, Path work, Path target) throws IOException {
        var mirrors = new Mirrors<>(releases, PrebuiltCatalogs::host, progress);
        try (var downloader = new Downloader()) {
            var entry = manifest(downloader, mirrors).flatMap(entries -> CatalogArchive.find(entries, name));
            if (entry.isEmpty()) {
                return false;
            }
            var parts = download(downloader, mirrors, entry.get(), work);
            progress.info("Decompressing " + name);
            CatalogArchive.unpack(entry.get(), parts, target);
            return true;
        }
    }

    private Optional<List<CatalogArchive.Entry>> manifest(Downloader downloader, Mirrors<String> mirrors) throws IOException {
        IOException failure = null;
        for (var release : mirrors.ordered()) {
            try {
                var entries = CatalogArchive.readManifest(downloader.text(release + CatalogArchive.MANIFEST));
                mirrors.succeeded(release);
                return Optional.of(entries);
            } catch (IOException e) {
                failure = e;
                mirrors.failed(release, e);
            }
        }
        throw new IOException("No release of prebuilt catalogs is available", failure);
    }

    private List<Path> download(Downloader downloader, Mirrors<String> mirrors, CatalogArchive.Entry entry, Path work) throws IOException {
        Files.createDirectories(work);
        var files = new ArrayList<Path>();
        var description = "Downloading %s (%,d MB)".formatted(entry.name(), entry.packedSize() / (1024 * 1024));
        try (var tracker = ProgressTracker.start(progress, description, (entry.packedSize() + MB - 1) / MB, "MB")) {
            var bytes = new AtomicLong();
            LongConsumer transferred = n -> {
                var total = bytes.addAndGet(n);
                tracker.advance(total / MB - (total - n) / MB, n);
            };
            for (var part : entry.parts()) {
                var file = work.resolve(part.file());
                download(downloader, mirrors, part, file, transferred);
                files.add(file);
            }
        }
        return files;
    }

    private static void download(Downloader downloader, Mirrors<String> mirrors, CatalogArchive.Part part, Path file,
                                 LongConsumer transferred) throws IOException {
        IOException failure = null;
        for (var round = 0; round < ROUNDS; round++) {
            for (var release : mirrors.ordered()) {
                try {
                    // the bytes downloaded by a failed attempt are counted again
                    downloader.resume(release + part.file(), file, transferred);
                    if (CatalogArchive.matches(file, part)) {
                        mirrors.succeeded(release);
                        return;
                    }
                    Files.delete(file);
                    throw new IOException("the checksum of " + part.file() + " is wrong");
                } catch (IOException e) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw e;
                    }
                    failure = e;
                    mirrors.failed(release, e);
                }
            }
        }
        throw new IOException("Unable to download " + part.file(), failure);
    }

    private static String host(String url) {
        var host = url.replaceFirst("^[a-z]+://", "");
        return host.substring(0, host.indexOf('/') < 0 ? host.length() : host.indexOf('/')).toLowerCase(Locale.ROOT);
    }
}
