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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogArchiveTest {
    private static Path randomFile(Path directory, int size) throws IOException {
        var bytes = new byte[size];
        new Random(1).nextBytes(bytes);
        // like indexes, partly compressible
        for (var i = 0; i < size; i += 4) {
            bytes[i] = 0;
        }
        var file = directory.resolve("test.astx");
        Files.write(file, bytes);
        return file;
    }

    private static List<Path> files(Path directory, CatalogArchive.Entry entry) {
        return entry.parts().stream().map(part -> directory.resolve(part.file())).toList();
    }

    @Test
    void packsAndUnpacksInSeveralParts(@TempDir Path dir) throws IOException {
        var index = randomFile(dir, 1_000_000);
        var packed = dir.resolve("packed");
        var entry = CatalogArchive.pack(index, "test", "A test", packed, 200_000);
        assertTrue(entry.parts().size() > 1, entry::toString);
        assertTrue(entry.packedSize() < Files.size(index), "the index is compressed");
        assertEquals("test.astx.gz.000", entry.parts().getFirst().file());
        for (var part : entry.parts()) {
            assertTrue(CatalogArchive.matches(packed.resolve(part.file()), part));
        }
        var target = dir.resolve("unpacked.astx");
        CatalogArchive.unpack(entry, files(packed, entry), target);
        assertArrayEquals(Files.readAllBytes(index), Files.readAllBytes(target));
    }

    @Test
    void rejectsCorruptDownloads(@TempDir Path dir) throws IOException {
        var index = randomFile(dir, 300_000);
        var packed = dir.resolve("packed");
        var entry = CatalogArchive.pack(index, "test", "A test", packed, Long.MAX_VALUE);
        assertEquals(1, entry.parts().size());
        var part = packed.resolve(entry.parts().getFirst().file());
        // a valid gzip stream, of other data
        var other = randomFile(Files.createDirectories(dir.resolve("other")), 300_001);
        var otherEntry = CatalogArchive.pack(other, "test", "", dir.resolve("other-packed"), Long.MAX_VALUE);
        Files.copy(dir.resolve("other-packed").resolve(otherEntry.parts().getFirst().file()), part, StandardCopyOption.REPLACE_EXISTING);
        assertFalse(CatalogArchive.matches(part, entry.parts().getFirst()));
        var target = dir.resolve("unpacked.astx");
        assertThrows(IOException.class, () -> CatalogArchive.unpack(entry, List.of(part), target));
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(dir.resolve("unpacked.astx.tmp")));
    }

    @Test
    void writesAndReadsManifests(@TempDir Path dir) throws IOException {
        var index = randomFile(dir, 500_000);
        var entry = CatalogArchive.pack(index, "gaia-500", "Gaia DR3, 500 stars per square degree", dir.resolve("packed"), 100_000);
        var writer = new StringWriter();
        CatalogArchive.writeManifest(List.of(entry), writer);
        var entries = CatalogArchive.readManifest(writer.toString());
        assertEquals(List.of(entry), entries);
        assertEquals(entry, CatalogArchive.find(entries, "gaia-500").orElseThrow());
        assertTrue(CatalogArchive.find(entries, "tycho2").isEmpty());
        assertThrows(IOException.class, () -> CatalogArchive.readManifest("<html>Not found</html>"));
    }
}
