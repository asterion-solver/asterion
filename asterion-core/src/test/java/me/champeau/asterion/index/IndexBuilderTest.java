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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexBuilderTest {
    private static StarData randomSky(int count) {
        var random = new SplittableRandom(42);
        var stars = new StarData(2016.0, count);
        for (var i = 0; i < count; i++) {
            var ra = random.nextDouble() * 2 * Math.PI;
            var dec = Math.asin(2 * random.nextDouble() - 1);
            stars.add(ra, dec, 0, 0, (float) (6 + 8 * random.nextDouble()));
        }
        return stars;
    }

    @Test
    void sortingQuadsInSeveralPassesGivesTheSameIndex(@TempDir Path dir) throws Exception {
        var stars = randomSky(50_000);
        var oneFile = dir.resolve("one.astx");
        var severalFile = dir.resolve("several.astx");
        IndexBuilder.build(stars, "test", IndexOptions.defaults(), oneFile, ProgressListener.none());
        var passes = new StringBuilder();
        IndexBuilder.build(stars, "test", IndexOptions.defaults().withSortMemory(1 << 20), severalFile,
                ProgressListener.text(passes::append));
        assertTrue(passes.toString().contains("passes"), passes::toString);
        assertArrayEquals(Files.readAllBytes(oneFile), Files.readAllBytes(severalFile));
        // the temporary files are deleted
        try (var files = Files.list(dir)) {
            assertEquals(2, files.count());
        }
    }
}
