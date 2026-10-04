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
package me.champeau.asterion.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares the decoders of Asterion with ImageIO on a list of image files, given by the
 * {@code asterion.imageCorpus} system property: {@code ./gradlew :asterion-core:test
 * --tests '*ImageCorpusTest' -PimageCorpus=<file listing one image per line>}. The report is
 * written to {@code build/reports/image-corpus.txt}.
 */
@EnabledIfSystemProperty(named = "asterion.imageCorpus", matches = ".+")
class ImageCorpusTest {
    private record Stats(
            int files,
            long referenceNanos,
            long asterionNanos,
            long pixels) {
        Stats add(long reference, long asterion, long count) {
            return new Stats(files + 1, referenceNanos + reference, asterionNanos + asterion, pixels + count);
        }
    }

    @Test
    void decodesLikeImageIo() throws IOException {
        var files = Files.readAllLines(Path.of(System.getProperty("asterion.imageCorpus"))).stream()
                .filter(line -> !line.isBlank())
                .map(Path::of)
                .toList();
        var report = new StringBuilder();
        var failures = new ArrayList<String>();
        var differences = new ArrayList<String>();
        var unreadable = new ArrayList<String>();
        Map<String, Stats> stats = new TreeMap<>();
        var onlyAsterion = 0;
        var neither = 0;
        for (var file : files) {
            var start = System.nanoTime();
            var reference = ImageIoReference.gray(file);
            var referenceNanos = System.nanoTime() - start;
            GrayImage ours = null;
            String error = null;
            start = System.nanoTime();
            try {
                ours = ImageLoader.load(file, 1).image();
            } catch (IOException | RuntimeException e) {
                error = e.getMessage();
            }
            var asterionNanos = System.nanoTime() - start;
            if (reference == null) {
                if (ours != null) {
                    onlyAsterion++;
                } else {
                    neither++;
                    unreadable.add(file + ": " + error);
                }
                continue;
            }
            if (ours == null) {
                failures.add(file + ": " + error);
                continue;
            }
            var data = ours.data();
            if (data.length != reference.length) {
                differences.add(file + ": " + ours.width() + "x" + ours.height() + ", " + reference.length + " pixels with ImageIO");
                continue;
            }
            var different = 0;
            var maxDiff = 0f;
            var first = new StringBuilder();
            for (var i = 0; i < data.length; i++) {
                var diff = Math.abs(data[i] - reference[i]);
                if (diff > 0) {
                    if (different < 5) {
                        first.append(String.format(Locale.ROOT, " (%d,%d) %.0f/%.0f", i % ours.width(), i / ours.width(), data[i],
                                reference[i]));
                    }
                    different++;
                    maxDiff = Math.max(maxDiff, diff);
                }
            }
            if (different > 0) {
                differences.add(String.format(Locale.ROOT, "%s: %dx%d, %d pixels differ, by %.0f at most, first:%s", file, ours.width(),
                        ours.height(), different, maxDiff, first));
            }
            stats.merge(kind(file), new Stats(1, referenceNanos, asterionNanos, data.length),
                    (a, b) -> a.add(b.referenceNanos, b.asterionNanos, b.pixels));
        }
        report.append(String.format(Locale.ROOT, "%d files: %d decoded by both, %d failed with Asterion only, %d differ, "
                + "%d read by Asterion only, %d read by neither%n",
                files.size(), stats.values().stream().mapToInt(Stats::files).sum(), failures.size(), differences.size(), onlyAsterion,
                neither));
        stats.forEach((kind, s) -> report
                .append(String.format(Locale.ROOT, "%-5s %5d files, %7.1f megapixels: ImageIO %6.1f s, Asterion %6.1f s (%.2fx)%n",
                        kind, s.files, s.pixels / 1e6, s.referenceNanos / 1e9, s.asterionNanos / 1e9,
                        (double) s.referenceNanos / s.asterionNanos)));
        report.append("\nFailures:\n");
        failures.forEach(f -> report.append(f).append('\n'));
        report.append("\nRead by neither:\n");
        unreadable.forEach(f -> report.append(f).append('\n'));
        report.append("\nDifferences:\n");
        differences.forEach(f -> report.append(f).append('\n'));
        var out = Path.of("build/reports/image-corpus.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
        System.out.println(report.substring(0, Math.min(report.length(), 4000)));
        assertTrue(failures.isEmpty() && differences.isEmpty(), "see " + out.toAbsolutePath());
    }

    private static String kind(Path file) {
        var name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.substring(name.lastIndexOf('.') + 1).replace("jpeg", "jpg").replace("tiff", "tif");
    }
}
