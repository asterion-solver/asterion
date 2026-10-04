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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Index files packed for distribution: compressed with gzip, and split in parts small enough for
 * any host. A manifest describes the catalogs of a release, with checksums.
 */
public final class CatalogArchive {
    /** The name of the manifest of a release. */
    public static final String MANIFEST = "catalogs.properties";
    /** GitHub doesn't accept files of 2 GiB or more. */
    public static final long MAX_PART_SIZE = 2_000_000_000L;
    private static final int BUFFER = 1 << 16;

    private CatalogArchive() {
    }

    /**
     * A packed catalog.
     *
     * @param name the name of the catalog, such as {@code gaia-500}
     * @param description the description of the catalog
     * @param size the size of the index file
     * @param sha256 the SHA-256 checksum of the index file
     * @param parts the compressed parts, in order
     */
    public record Entry(
            String name,
            String description,
            long size,
            String sha256,
            List<Part> parts) {
        public Entry {
            parts = List.copyOf(parts);
        }

        /** The size of the download. */
        public long packedSize() {
            return parts.stream().mapToLong(Part::size).sum();
        }
    }

    /**
     * A file of a packed catalog.
     *
     * @param file the name of the file
     * @param size the size of the file
     * @param sha256 the SHA-256 checksum of the file
     */
    public record Part(
            String file,
            long size,
            String sha256) {
    }

    /**
     * Packs an index file.
     *
     * @param index the index file
     * @param name the name of the catalog
     * @param directory where to write the parts
     * @param maxPartSize the maximum size of a part
     */
    public static Entry pack(Path index, String name, String description, Path directory, long maxPartSize) throws IOException {
        Files.createDirectories(directory);
        var indexDigest = sha256();
        try (var in = new DigestInputStream(Files.newInputStream(index), indexDigest);
             var parts = new PartsOutputStream(directory, name + Catalogs.EXTENSION + ".gz", maxPartSize)) {
            try (var gzip = new GZIPOutputStream(parts, BUFFER)) {
                in.transferTo(gzip);
            }
            return new Entry(name, description, Files.size(index), HexFormat.of().formatHex(indexDigest.digest()), parts.parts());
        }
    }

    /**
     * Unpacks a catalog, which is moved to its target once its checksum is verified.
     *
     * @param parts the downloaded parts, in order
     * @param target the index file to create
     */
    static void unpack(Entry entry, List<Path> parts, Path target) throws IOException {
        var tmp = target.resolveSibling(target.getFileName() + ".tmp");
        var digest = sha256();
        var streams = new ArrayList<InputStream>();
        try {
            for (var part : parts) {
                streams.add(Files.newInputStream(part));
            }
            try (var in = new GZIPInputStream(new SequenceInputStream(Collections.enumeration(streams)), BUFFER);
                 var out = new DigestOutputStream(Files.newOutputStream(tmp), digest)) {
                in.transferTo(out);
            }
            var sha256 = HexFormat.of().formatHex(digest.digest());
            if (Files.size(tmp) != entry.size() || !sha256.equals(entry.sha256())) {
                throw new IOException("The checksum of " + entry.name() + " is wrong: the download is corrupt");
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            for (var stream : streams) {
                stream.close();
            }
            Files.deleteIfExists(tmp);
        }
    }

    /** Tells if a file has the size and the checksum of a part. */
    static boolean matches(Path file, Part part) throws IOException {
        return Files.isRegularFile(file) && Files.size(file) == part.size() && sha256(file).equals(part.sha256());
    }

    static String sha256(Path file) throws IOException {
        var digest = sha256();
        try (var in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Writes the manifest of a release. */
    public static void writeManifest(List<Entry> entries, Writer writer) throws IOException {
        var properties = new Properties();
        properties.setProperty("catalogs", String.join(",", entries.stream().map(Entry::name).toList()));
        for (var entry : entries) {
            var key = entry.name() + ".";
            properties.setProperty(key + "description", entry.description());
            properties.setProperty(key + "size", Long.toString(entry.size()));
            properties.setProperty(key + "sha256", entry.sha256());
            properties.setProperty(key + "parts", String.join(",", entry.parts().stream().map(Part::file).toList()));
            for (var part : entry.parts()) {
                properties.setProperty(part.file() + ".size", Long.toString(part.size()));
                properties.setProperty(part.file() + ".sha256", part.sha256());
            }
        }
        properties.store(writer, "Catalogs of Asterion Solver");
    }

    /** Reads the manifest of a release. */
    static List<Entry> readManifest(String content) throws IOException {
        var properties = new Properties();
        properties.load(new StringReader(content));
        var catalogs = properties.getProperty("catalogs");
        if (catalogs == null) {
            throw new IOException("Not a catalog manifest");
        }
        try {
            return Arrays.stream(catalogs.split(","))
                    .filter(name -> !name.isBlank())
                    .map(name -> new Entry(name, required(properties, name + ".description"),
                            Long.parseLong(required(properties, name + ".size")), required(properties, name + ".sha256"),
                            Arrays.stream(required(properties, name + ".parts").split(","))
                                    .map(file -> new Part(file, Long.parseLong(required(properties, file + ".size")),
                                            required(properties, file + ".sha256")))
                                    .toList()))
                    .toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        } catch (NumberFormatException e) {
            throw new IOException("Invalid catalog manifest: " + e.getMessage(), e);
        }
    }

    /** Finds a catalog in a manifest. */
    static Optional<Entry> find(List<Entry> entries, String name) {
        return entries.stream().filter(entry -> entry.name().equals(name)).findFirst();
    }

    private static String required(Properties properties, String key) {
        var value = properties.getProperty(key);
        if (value == null) {
            throw new UncheckedIOException(new IOException("Invalid catalog manifest: no " + key));
        }
        return value;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Writes to numbered files, starting a new one when a file reaches the maximum size. */
    private static final class PartsOutputStream extends OutputStream {
        private final Path directory;
        private final String prefix;
        private final long maxSize;
        private final List<Part> parts = new ArrayList<>();
        private OutputStream current;
        private MessageDigest digest;
        private Path file;
        private long size;

        PartsOutputStream(Path directory, String prefix, long maxSize) {
            this.directory = directory;
            this.prefix = prefix;
            this.maxSize = maxSize;
        }

        List<Part> parts() {
            return List.copyOf(parts);
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            while (length > 0) {
                if (current == null || size == maxSize) {
                    next();
                }
                var n = (int) Math.min(length, maxSize - size);
                current.write(bytes, offset, n);
                size += n;
                offset += n;
                length -= n;
            }
        }

        private void next() throws IOException {
            finish();
            file = directory.resolve("%s.%03d".formatted(prefix, parts.size()));
            digest = sha256();
            current = new DigestOutputStream(Files.newOutputStream(file), digest);
            size = 0;
            // the part is added when it's complete
            parts.add(null);
        }

        private void finish() throws IOException {
            if (current != null) {
                current.close();
                parts.set(parts.size() - 1, new Part(file.getFileName().toString(), size, HexFormat.of().formatHex(digest.digest())));
                current = null;
            }
        }

        @Override
        public void close() throws IOException {
            if (parts.isEmpty()) {
                next();
            }
            finish();
        }
    }
}
