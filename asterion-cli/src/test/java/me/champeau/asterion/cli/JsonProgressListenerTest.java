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
import me.champeau.asterion.progress.ProgressStep;
import me.champeau.asterion.progress.ProgressUpdate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonProgressListenerTest {
    @Test
    void describesTheProgressAsAJsonObjectPerLine() {
        var lines = new ArrayList<String>();
        var listener = new JsonProgressListener(lines::add);
        var download = new ProgressStep("Downloading tycho2 (280 MB)", 281, "MB");
        listener.started(download);
        listener.advanced(new ProgressUpdate(download, 0, 0, ""));
        listener.advanced(new ProgressUpdate(download, 140, 140L << 20, ""));
        listener.advanced(new ProgressUpdate(download, 140, 140L << 20, "from \"a\" mirror"));
        listener.finished(download);
        var indexing = new ProgressStep("Indexing", -1, "stars");
        listener.advanced(new ProgressUpdate(indexing, 1200, -1, ""));
        listener.info("Decompressing tycho2\n");

        assertEquals(List.of(
                "{\"event\":\"step\",\"step\":\"Downloading tycho2 (280 MB)\",\"total\":281,\"unit\":\"MB\"}",
                "{\"event\":\"progress\",\"step\":\"Downloading tycho2 (280 MB)\",\"completed\":0,\"total\":281,\"unit\":\"MB\","
                        + "\"fraction\":0.0,\"bytes\":0}",
                "{\"event\":\"progress\",\"step\":\"Downloading tycho2 (280 MB)\",\"completed\":140,\"total\":281,\"unit\":\"MB\","
                        + "\"fraction\":" + 140d / 281 + ",\"bytes\":146800640}",
                "{\"event\":\"detail\",\"step\":\"Downloading tycho2 (280 MB)\",\"message\":\"from \\\"a\\\" mirror\"}",
                "{\"event\":\"done\",\"step\":\"Downloading tycho2 (280 MB)\"}",
                "{\"event\":\"progress\",\"step\":\"Indexing\",\"completed\":1200,\"unit\":\"stars\"}",
                "{\"event\":\"info\",\"message\":\"Decompressing tycho2\\n\"}"), lines);
    }

    @Test
    void reportsEveryPercentOnly() {
        var lines = new ArrayList<String>();
        var listener = JsonProgressListener.create(lines::add);
        var step = new ProgressStep("Indexing", 100_000, "stars");
        for (var completed = 0; completed <= 100_000; completed += 10) {
            listener.advanced(new ProgressUpdate(step, completed, -1, ""));
        }
        // each percent from 0 to 99, and the end
        assertEquals(101, lines.size());
        assertEquals(
                "{\"event\":\"progress\",\"step\":\"Indexing\",\"completed\":100000,\"total\":100000,\"unit\":\"stars\",\"fraction\":1.0}",
                lines.getLast());
    }

    @ParameterizedTest
    @FieldSource("me.champeau.asterion.Locales#UNUSUAL")
    void writesTheSameJsonWhateverTheLocale(String locale) {
        var step = new ProgressStep("Downloading gaia-1000 (2,919 MB)", 2920, "MB");
        var update = new ProgressUpdate(step, 1234, 1234L << 20, "");
        assertEquals(progressOf(update), Locales.with(locale, () -> progressOf(update)));
    }

    private static String progressOf(ProgressUpdate update) {
        var lines = new ArrayList<String>();
        new JsonProgressListener(lines::add).advanced(update);
        return lines.getFirst();
    }

    @Test
    void writesValidJson() {
        assertEquals("{\"a\":\"\\u0001\\t\\\\\",\"b\":null,\"c\":true,\"d\":1.5}",
                Json.object().field("a", "\u0001\t\\").field("b", Double.NaN).field("c", true).field("d", 1.5).toString());
    }
}
