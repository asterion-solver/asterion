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
package me.champeau.asterion.cli;

import me.champeau.asterion.Locales;
import me.champeau.asterion.catalog.Catalogs;
import me.champeau.asterion.catalog.StarData;
import me.champeau.asterion.index.IndexBuilder;
import me.champeau.asterion.index.IndexOptions;
import me.champeau.asterion.progress.ProgressListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportTest {
    /** Installs a small catalog in the directory, and a broken one. */
    private static Path installCatalogs(Path directory) throws IOException {
        var random = new SplittableRandom(3);
        var stars = new StarData(2016.0, 20_000);
        for (var i = 0; i < 20_000; i++) {
            stars.add(random.nextDouble() * 2 * Math.PI, Math.asin(2 * random.nextDouble() - 1), 0, 0,
                    (float) (6 + 6 * random.nextDouble()));
        }
        var file = directory.resolve("tycho2" + Catalogs.EXTENSION);
        IndexBuilder.build(stars, "tycho2", IndexOptions.defaults(), file, ProgressListener.none());
        Files.writeString(directory.resolve("broken" + Catalogs.EXTENSION), "not an index");
        return file;
    }

    private static String catalogsJson(Path directory) {
        try {
            return Report.catalogsJson(directory, Catalogs.installed(directory), Catalogs.available(), "1.2.3");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @ParameterizedTest
    @FieldSource("me.champeau.asterion.Locales#UNUSUAL")
    void describesTheCatalogsWhateverTheLocale(String locale, @TempDir Path directory) throws Exception {
        installCatalogs(directory);
        assertEquals(catalogsJson(directory), Locales.with(locale, () -> catalogsJson(directory)));
    }

    @Test
    void describesTheCatalogsAsJson(@TempDir Path directory) throws Exception {
        var file = installCatalogs(directory);

        var json = catalogsJson(directory);

        assertTrue(
                json.startsWith(
                        "{\"directory\":\"" + directory.toString().replace("\\", "\\\\") + "\",\"version\":\"1.2.3\",\"installed\":["),
                json);
        assertTrue(json.contains("{\"name\":\"tycho2\",\"size\":" + Files.size(file) + ",\"stars\":20000,\"magnitudeLimit\":"), json);
        assertTrue(json.contains("\"quads\":"), json);
        assertTrue(json.contains("{\"name\":\"broken\",\"size\":12,\"error\":\""), json);
        assertTrue(json.contains("{\"name\":\"tycho2\",\"description\":\"Tycho-2"), json);
        assertTrue(json.contains("\"installed\":true}"), json);
        assertTrue(json.contains("{\"name\":\"gaia-500\",\"description\":\"Gaia DR3"), json);
        assertTrue(json.endsWith("\"installed\":false}]}"), json);
        // no catalog at all
        var empty = Report.catalogsJson(directory.resolve("none"), List.of(), List.of(), "1.2.3");
        assertEquals("{\"directory\":\"" + directory.resolve("none").toString().replace("\\", "\\\\")
                + "\",\"version\":\"1.2.3\",\"installed\":[],\"available\":[]}", empty);
    }
}
