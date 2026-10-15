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

import me.champeau.asterion.solver.Solution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * The images which were solved recently, kept in a temporary directory so that they can be
 * downloaded with their solution. The oldest ones are deleted when there are too many, when they
 * take too much space, or when they expire.
 */
final class SolvedImages implements AutoCloseable {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path directory;
    private final int maxImages;
    private final long maxBytes;
    private final Duration lifetime;
    private final Clock clock;
    /** By age, the oldest first. */
    private final Map<String, Image> images = new LinkedHashMap<>();
    private long bytes;

    /** A solved image: the uploaded file, its name, and its solution. */
    record Image(
            Path file,
            String name,
            Solution solution,
            long size,
            Instant created) {
    }

    SolvedImages(int maxImages, long maxBytes, Duration lifetime, Clock clock) throws IOException {
        this.directory = Files.createTempDirectory("asterion-web-");
        this.maxImages = maxImages;
        this.maxBytes = maxBytes;
        this.lifetime = lifetime;
        this.clock = clock;
    }

    /**
     * Keeps a solved image.
     *
     * @param upload the uploaded file, which is moved
     * @return the identifier of the image, which can't be guessed
     */
    synchronized String keep(Path upload, String name, Solution solution) throws IOException {
        var random = new byte[16];
        RANDOM.nextBytes(random);
        var id = HexFormat.of().formatHex(random);
        // the name keeps the extension, which tells compressed FITS files
        var file = directory.resolve(id + "-" + name);
        Files.move(upload, file, StandardCopyOption.REPLACE_EXISTING);
        var size = Files.size(file);
        images.put(id, new Image(file, name, solution, size, clock.instant()));
        bytes += size;
        evict();
        return id;
    }

    /**
     * A solved image.
     *
     * @throws NoSuchElementException if there's no such image, or not anymore
     */
    synchronized Image get(String id) throws IOException {
        evict();
        var image = id == null ? null : images.get(id);
        if (image == null) {
            throw new NoSuchElementException("This image isn't kept anymore: solve it again");
        }
        return image;
    }

    /** A temporary file, in the directory of the images. */
    Path createTempFile(String suffix) throws IOException {
        return Files.createTempFile(directory, "download-", suffix);
    }

    private void evict() throws IOException {
        var expired = clock.instant().minus(lifetime);
        var iterator = images.values().iterator();
        while (iterator.hasNext()) {
            var image = iterator.next();
            if (images.size() <= maxImages && bytes <= maxBytes && image.created().isAfter(expired)) {
                break;
            }
            iterator.remove();
            bytes -= image.size();
            Files.deleteIfExists(image.file());
        }
    }

    @Override
    public synchronized void close() throws IOException {
        images.clear();
        try (var files = Files.list(directory)) {
            for (var file : files.toList()) {
                Files.deleteIfExists(file);
            }
        }
        Files.deleteIfExists(directory);
    }
}
