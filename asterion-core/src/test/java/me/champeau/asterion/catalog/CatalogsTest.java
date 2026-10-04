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

import me.champeau.asterion.SyntheticSky;
import me.champeau.asterion.index.StarIndex;
import me.champeau.asterion.progress.ProgressListener;
import me.champeau.asterion.progress.ProgressStep;
import me.champeau.asterion.progress.ProgressTracker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogsTest {
    /** A catalog of random stars, which "downloads" them to a file. */
    static final class SyntheticCatalog implements Catalogs.Descriptor {
        @Override
        public String name() {
            return "synthetic";
        }

        @Override
        public String description() {
            return "Random stars";
        }

        @Override
        public StarData fetch(Path workDirectory, ProgressListener progress) throws IOException {
            try (var tracker = ProgressTracker.start(progress, "Downloading random stars", 2, "files")) {
                for (var i = 0; i < 2; i++) {
                    Files.writeString(workDirectory.resolve("part-" + i + ".txt"), "stars");
                    tracker.advance(1, 5);
                }
            }
            return new SyntheticSky(20_000, 7).stars();
        }
    }

    @Test
    void installsCatalogs(@TempDir Path directory) throws IOException {
        var events = Collections.synchronizedList(new ArrayList<String>());
        var listener = new ProgressListener() {
            @Override
            public void started(ProgressStep step) {
                events.add("started " + step.description());
            }

            @Override
            public void finished(ProgressStep step) {
                events.add("finished " + step.description());
            }

            @Override
            public void info(String message) {
                events.add(message);
            }
        };
        var catalog = new SyntheticCatalog();
        // no release of prebuilt catalogs: the index is built
        var index = Catalogs.install(catalog, catalog.indexOptions(), directory, false, List.of(), listener);

        assertEquals(directory.resolve("synthetic" + Catalogs.EXTENSION), index);
        assertEquals(List.of(index), Catalogs.installed(directory));
        assertFalse(Files.exists(directory.resolve("synthetic.download")), "downloaded files are deleted");
        assertEquals("synthetic", StarIndex.open(index).name());
        assertEquals(20_000, StarIndex.open(index).starCount());
        // the steps of the descriptor and of the index builder are reported
        assertTrue(events.containsAll(List.of("started Downloading random stars", "finished Downloading random stars",
                "started Building quads", "finished Building quads")), events.toString());
        assertTrue(events.getLast().startsWith("Installed " + index), events.getLast());
    }
}
