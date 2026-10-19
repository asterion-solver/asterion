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
package me.champeau.asterion.index;

import me.champeau.asterion.catalog.StarData;
import me.champeau.asterion.progress.ProgressListener;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WideQuadsTest {
    private static Path file;
    private static StarIndex index;

    @BeforeAll
    static void buildIndex(@TempDir Path dir) throws IOException {
        var random = new SplittableRandom(7);
        var stars = new StarData(2016.0, 50_000);
        for (var i = 0; i < 50_000; i++) {
            stars.add(random.nextDouble() * 2 * Math.PI, Math.asin(2 * random.nextDouble() - 1), 0, 0,
                    (float) (6 + 8 * random.nextDouble()));
        }
        file = dir.resolve("test.astx");
        IndexBuilder.build(stars, "test", IndexOptions.defaults(), file, ProgressListener.none());
        index = StarIndex.open(file);
    }

    @Test
    void holdsTheWideQuadsOfTheIndexInTheCellsOfTheirCodes() {
        var wide = WideQuads.build(index);
        var expected = 0;
        for (var q = 0; q < index.quadCount(); q++) {
            if (index.quadDiam(q) >= WideQuads.MIN_DIAM) {
                expected++;
            }
        }
        assertTrue(expected > 100, "only " + expected + " wide quads");
        assertEquals(expected, wide.count());
        var bins = wide.bins();
        var cells = bins * bins * bins * bins;
        assertEquals(0, wide.cellStart(0));
        assertEquals(wide.count(), wide.cellStart(cells));
        for (var c = 0; c < cells; c++) {
            for (var j = wide.cellStart(c); j < wide.cellStart(c + 1); j++) {
                var id = wide.id(j);
                assertEquals(index.quadCode(id), wide.code(j));
                assertEquals(index.quadDiam(id), wide.diam(j));
                assertEquals(c, WideQuads.cellOf(wide.code(j), bins));
            }
        }
    }

    @Test
    void keepsTheTableNextToTheIndex() throws IOException {
        var sidecar = file.resolveSibling(file.getFileName() + WideQuads.SUFFIX);
        Files.deleteIfExists(sidecar);
        var built = index.wideQuads();
        assertTrue(Files.isRegularFile(sidecar));
        var loaded = WideQuads.of(index);
        assertEquals(built.count(), loaded.count());
        assertEquals(built.bins(), loaded.bins());
        var cells = built.bins() * built.bins() * built.bins() * built.bins();
        for (var c = 0; c <= cells; c++) {
            assertEquals(built.cellStart(c), loaded.cellStart(c));
        }
        for (var j = 0; j < built.count(); j++) {
            assertEquals(built.id(j), loaded.id(j));
            assertEquals(built.code(j), loaded.code(j));
            assertEquals(built.diam(j), loaded.diam(j));
        }
        // a damaged or outdated table is built again
        var bytes = Files.readAllBytes(sidecar);
        Files.write(sidecar, java.util.Arrays.copyOf(bytes, bytes.length / 2));
        var rebuilt = WideQuads.of(index);
        assertEquals(built.count(), rebuilt.count());
        assertArrayEquals(bytes, Files.readAllBytes(sidecar));
        assertFalse(Files.readString(sidecar, java.nio.charset.StandardCharsets.ISO_8859_1).isEmpty());
    }
}
