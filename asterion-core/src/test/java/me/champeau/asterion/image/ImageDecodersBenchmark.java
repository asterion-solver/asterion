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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;

/**
 * Compares the time to decode images with ImageIO, the way Asterion did before, and with its own
 * decoders, on a list of image files given by the {@code asterion.imageBenchmark} system property:
 * {@code ./gradlew :asterion-core:test --tests '*ImageDecodersBenchmark' -PimageBenchmark=<file>}.
 * Files are read in memory first, the first ones warm the JIT up, and each decoder is timed twice
 * per file, the fastest run being kept.
 */
@EnabledIfSystemProperty(named = "asterion.imageBenchmark", matches = ".+")
class ImageDecodersBenchmark {
    private static final int WARMUP_FILES = 30;

    private record Totals(
            int files,
            double megapixels,
            long imageIoNanos,
            long asterionNanos) {
    }

    @Test
    void benchmark() throws IOException {
        var files = Files.readAllLines(Path.of(System.getProperty("asterion.imageBenchmark"))).stream()
                .filter(line -> !line.isBlank())
                .map(Path::of)
                .toList();
        Map<String, Totals> totals = new TreeMap<>();
        var index = 0;
        for (var file : files) {
            var data = Files.readAllBytes(file);
            long imageIo = Long.MAX_VALUE;
            long asterion = Long.MAX_VALUE;
            GrayImage image = null;
            try {
                for (var run = 0; run < 2; run++) {
                    var start = System.nanoTime();
                    var decoded = ImageIO.read(new ByteArrayInputStream(data));
                    if (decoded == null) {
                        throw new IOException("unsupported");
                    }
                    toGray(decoded, 0);
                    imageIo = Math.min(imageIo, System.nanoTime() - start);
                    start = System.nanoTime();
                    image = ImageLoader.decode(data, 0);
                    asterion = Math.min(asterion, System.nanoTime() - start);
                }
            } catch (IOException | RuntimeException e) {
                continue;
            }
            if (index++ < WARMUP_FILES) {
                continue;
            }
            var megapixels = (double) image.sourceWidth() * image.sourceHeight() / 1e6;
            totals.merge(kind(file), new Totals(1, megapixels, imageIo, asterion), (a, b) -> new Totals(a.files + b.files,
                    a.megapixels + b.megapixels, a.imageIoNanos + b.imageIoNanos, a.asterionNanos + b.asterionNanos));
        }
        var report = new StringBuilder();
        totals.forEach((kind, t) -> report.append(String.format(Locale.ROOT,
                "%-4s %5d files, %8.1f megapixels: ImageIO %6.2f s (%5.1f ms/MP), Asterion %6.2f s (%5.1f ms/MP), %.2fx%n",
                kind, t.files, t.megapixels, t.imageIoNanos / 1e9, t.imageIoNanos / 1e6 / t.megapixels, t.asterionNanos / 1e9,
                t.asterionNanos / 1e6 / t.megapixels, (double) t.imageIoNanos / t.asterionNanos)));
        var out = Path.of("build/reports/image-benchmark.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
        System.out.println(report);
    }

    private static String kind(Path file) {
        var name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.substring(name.lastIndexOf('.') + 1).replace("jpeg", "jpg").replace("tiff", "tif");
    }

    /** How Asterion converted the images read by ImageIO before it had its own decoders. */
    private static float[] toGray(BufferedImage image, int binning) {
        var width = image.getWidth();
        var height = image.getHeight();
        var b = GrayBinner.binningFor(width, height, binning);
        var w = width / b;
        var h = height / b;
        var raster = image.getRaster();
        var bands = raster.getNumBands();
        var colors = image.getColorModel().hasAlpha() ? bands - 1 : bands;
        var out = new float[w * h];
        var tasks = Math.clamp(h / 16, 1, 4 * Runtime.getRuntime().availableProcessors());
        IntStream.range(0, tasks).parallel().forEach(t -> {
            var from = (int) ((long) h * t / tasks);
            var to = (int) ((long) h * (t + 1) / tasks);
            var row = new int[width * bands];
            for (var y = from; y < to; y++) {
                for (var sy = 0; sy < b; sy++) {
                    raster.getPixels(0, y * b + sy, width, 1, row);
                    for (var x = 0; x < w * b; x++) {
                        var sum = 0;
                        for (var c = 0; c < colors; c++) {
                            sum += row[x * bands + c];
                        }
                        out[y * w + x / b] += sum;
                    }
                }
            }
        });
        return out;
    }
}
