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
package me.champeau.asterion.solver;

import me.champeau.asterion.SyntheticSky;
import me.champeau.asterion.image.ImageHints;
import me.champeau.asterion.image.StarList;
import me.champeau.asterion.index.IndexBuilder;
import me.champeau.asterion.index.IndexOptions;
import me.champeau.asterion.progress.ProgressListener;
import me.champeau.asterion.wcs.Wcs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlateSolverTest {
    private static final int WIDTH = 1600;
    /** The number of stars of the lists given to the solver. */
    private static final int STAR_LIST_SIZE = 150;
    private static final int HEIGHT = 1200;
    private static SyntheticSky sky;
    private static PlateSolver solver;

    @BeforeAll
    static void buildIndex(@TempDir Path directory) throws IOException {
        // 7 stars per square degree
        sky = new SyntheticSky(300_000, 42);
        var file = directory.resolve("synthetic.astx");
        IndexBuilder.build(sky.stars(), "synthetic", IndexOptions.defaults(), file, ProgressListener.none());
        solver = PlateSolver.open(file);
    }

    @Test
    void indexDescribesItself() {
        var index = solver.indexes().getFirst();
        assertEquals("synthetic", index.name());
        assertEquals(300_000, index.starCount());
        assertEquals(SyntheticSky.EPOCH, index.epoch());
        assertTrue(index.quadCount() > 100_000);
        assertTrue(Math.toDegrees(index.maxQuadDiam()) > 20);
        assertTrue(Math.toDegrees(index.minQuadDiam()) < 2);
    }

    @ParameterizedTest
    @CsvSource({
            // ra, dec, scale, rotation, flipped
            "83.8, -5.4, 20, 30, false",
            "0.02, 12, 25, -170, true",
            "359.9, -45, 12, 95, false",
            "200, 89.6, 18, 10, false",
            "310, -89.9, 30, -60, true",
            "120, 60, 60, 0, false",
            "45, 20, 150, 45, true",
    })
    void solvesImagesBlindly(double ra, double dec, double scale, double rotation, boolean flipped) {
        var truth = SyntheticSky.wcs(WIDTH, HEIGHT, ra, dec, scale, rotation, flipped);
        var image = sky.render(truth, WIDTH, HEIGHT, 7);
        var result = solver.solve(image, ImageHints.none(), SolverOptions.defaults());
        assertTrue(result.solved(), "not solved with " + result.stars().size() + " stars");
        var solution = result.solution().orElseThrow();
        assertEquals(scale, solution.pixelScale(), scale * 1e-3);
        assertEquals(flipped, solution.flipped());
        assertEquals(0, angleDifference(rotation, solution.rotationDeg()), 0.02);
        assertTrue(solution.matchedStars() >= 10);
        // the solution is accurate to a small fraction of a pixel all over the image
        for (var p : new double[][]{{0, 0}, {WIDTH - 1, 0}, {WIDTH / 2.0, HEIGHT / 2.0}, {0, HEIGHT - 1}, {WIDTH - 1, HEIGHT - 1}}) {
            var error = SyntheticSky.separation(truth, solution.wcs(), p[0], p[1]);
            assertTrue(error < 0.2 * scale, "error of " + error + "\" at " + p[0] + ", " + p[1]);
        }
        assertTrue(solution.rmsArcsec() < 0.15 * scale, "RMS of " + solution.rmsArcsec() + "\"");
    }

    @Test
    void solvesStarLists() {
        var truth = SyntheticSky.wcs(WIDTH, HEIGHT, 150, 35, 22, 77, false);
        var result = solver.solve(starList(truth, 0.2, 0), WIDTH, HEIGHT, ImageHints.none(), SolverOptions.defaults());
        assertTrue(result.solved());
        assertTrue(SyntheticSky.separation(truth, result.solution().orElseThrow().wcs(), 800, 600) < 3);
        assertTrue(result.focalLengthMm().isEmpty());
        // 22"/px with 9 µm pixels is a focal length of 84.4 mm
        var withPixelSize = solver.solve(starList(truth, 0.2, 0), WIDTH, HEIGHT, ImageHints.none(),
                SolverOptions.builder().pixelSize(9).build());
        assertEquals(206.264806 * 9 / 22, withPixelSize.focalLengthMm().orElseThrow(), 0.1);
    }

    @Test
    void searchesAroundTheHintedPositionWhateverTheHintedScale() {
        var truth = SyntheticSky.wcs(WIDTH, HEIGHT, 150, 35, 22, 77, false);
        // a scale which is 5 times too small, like a wrong focal length in a FITS header
        var hints = ImageHints.builder().position(152, 33).pixelScale(22 / 5.0).build();
        var result = solver.solve(starList(truth, 0.2, 0), WIDTH, HEIGHT, hints, SolverOptions.defaults());
        assertTrue(result.solved());
        assertTrue(result.stats().usedHints(), "solved around the hinted position");
        assertTrue(SyntheticSky.separation(truth, result.solution().orElseThrow().wcs(), 800, 600) < 3);
    }

    @Test
    void searchesBeyondAWrongHintedPosition() {
        var truth = SyntheticSky.wcs(WIDTH, HEIGHT, 150, 35, 22, 77, false);
        var hints = ImageHints.builder().position(300, -40).pixelScale(22).build();
        var result = solver.solve(starList(truth, 0.2, 0), WIDTH, HEIGHT, hints, SolverOptions.defaults());
        assertTrue(result.solved());
        assertTrue(SyntheticSky.separation(truth, result.solution().orElseThrow().wcs(), 800, 600) < 3);
        // without a blind search, the hinted position is the only one which is searched
        var withoutFallback = solver.solve(starList(truth, 0.2, 0), WIDTH, HEIGHT, hints,
                SolverOptions.builder().blindFallback(false).build());
        assertFalse(withoutFallback.solved());
    }

    @Test
    void neverSearchesBeyondTheRegionOfTheOptions() {
        var truth = SyntheticSky.wcs(WIDTH, HEIGHT, 150, 35, 22, 77, false);
        var outside = SolverOptions.builder().position(300, -40, 5).build();
        assertFalse(solver.solve(starList(truth, 0.2, 0), WIDTH, HEIGHT, ImageHints.none(), outside).solved());
        // a wide region is searched from its center first, then entirely
        var wide = SolverOptions.builder().position(170, 20, 40).build();
        var result = solver.solve(starList(truth, 0.2, 0), WIDTH, HEIGHT, ImageHints.none(), wide);
        assertTrue(result.solved());
        assertFalse(result.stats().usedHints(), "a region of the options isn't a hint");
    }

    @Test
    void toleratesSpuriousAndMissingStars() {
        var truth = SyntheticSky.wcs(WIDTH, HEIGHT, 222, -62, 22, 200, true);
        // one star out of four is missing, and there are as many artifacts as stars
        var stars = starList(truth, 0.3, 0.25);
        var random = new Random(9);
        var x = new double[2 * stars.size()];
        var y = new double[2 * stars.size()];
        for (var i = 0; i < stars.size(); i++) {
            x[2 * i] = stars.x(i);
            y[2 * i] = stars.y(i);
            x[2 * i + 1] = random.nextDouble() * WIDTH;
            y[2 * i + 1] = random.nextDouble() * HEIGHT;
        }
        var result = solver.solve(StarList.of(x, y), WIDTH, HEIGHT, ImageHints.none(), SolverOptions.defaults());
        assertTrue(result.solved());
        assertTrue(SyntheticSky.separation(truth, result.solution().orElseThrow().wcs(), 800, 600) < 5);
    }

    @Test
    void doesNotSolveRandomStars() {
        var random = new Random(5);
        for (var trial = 0; trial < 5; trial++) {
            var x = new double[100];
            var y = new double[100];
            for (var i = 0; i < x.length; i++) {
                x[i] = random.nextDouble() * WIDTH;
                y[i] = random.nextDouble() * HEIGHT;
            }
            var result = solver.solve(StarList.of(x, y), WIDTH, HEIGHT, ImageHints.none(),
                    SolverOptions.builder().maxQuadStars(40).timeout(Duration.ofSeconds(20)).build());
            assertFalse(result.solved(), "random stars were solved");
            assertTrue(result.stats().quads() > 1000);
        }
    }

    @Test
    void usesHintsThenFallsBackToBlind() {
        var truth = SyntheticSky.wcs(WIDTH, HEIGHT, 150, 35, 22, 77, false);
        var stars = starList(truth, 0.2, 0);
        var good = ImageHints.builder().position(151, 34).pixelScale(21).build();
        var hinted = solver.solve(stars, WIDTH, HEIGHT, good, SolverOptions.defaults());
        assertTrue(hinted.solved());
        assertTrue(hinted.stats().usedHints());

        var wrong = ImageHints.builder().position(20, -70).pixelScale(3).build();
        var fallback = solver.solve(stars, WIDTH, HEIGHT, wrong, SolverOptions.defaults());
        assertTrue(fallback.solved());
        assertFalse(fallback.stats().usedHints());

        var noFallback = solver.solve(stars, WIDTH, HEIGHT, wrong, SolverOptions.builder().blindFallback(false).build());
        assertFalse(noFallback.solved());
    }

    @Test
    void honorsExplicitConstraints() {
        var truth = SyntheticSky.wcs(WIDTH, HEIGHT, 150, 35, 22, 77, false);
        var stars = starList(truth, 0.2, 0);
        assertTrue(solver.solve(stars, WIDTH, HEIGHT, ImageHints.none(),
                SolverOptions.builder().position(149, 36, 5).scale(20, 24).parity(Parity.NORMAL).build()).solved());
        assertTrue(solver.solve(stars, WIDTH, HEIGHT, ImageHints.none(),
                SolverOptions.builder().fieldOfView(9, 11).build()).solved());
        assertFalse(solver.solve(stars, WIDTH, HEIGHT, ImageHints.none(),
                SolverOptions.builder().position(10, -40, 5).build()).solved());
        assertFalse(solver.solve(stars, WIDTH, HEIGHT, ImageHints.none(),
                SolverOptions.builder().scale(40, 60).build()).solved());
        assertFalse(solver.solve(stars, WIDTH, HEIGHT, ImageHints.none(),
                SolverOptions.builder().parity(Parity.FLIPPED).build()).solved());
    }

    /**
     * The {@value STAR_LIST_SIZE} brightest stars of an image, with noisy positions.
     *
     * @param dropRatio the proportion of stars which are removed
     */
    private static StarList starList(Wcs wcs, double noise, double dropRatio) {
        var projected = sky.project(wcs, WIDTH, HEIGHT);
        var random = new Random(3);
        var n = Math.min(STAR_LIST_SIZE, projected.length / 3);
        var x = new double[n];
        var y = new double[n];
        var kept = 0;
        for (var i = 0; i < n; i++) {
            if (random.nextDouble() < dropRatio) {
                continue;
            }
            x[kept] = projected[3 * i] + noise * random.nextGaussian();
            y[kept] = projected[3 * i + 1] + noise * random.nextGaussian();
            kept++;
        }
        return StarList.of(java.util.Arrays.copyOf(x, kept), java.util.Arrays.copyOf(y, kept));
    }

    private static double angleDifference(double a, double b) {
        var d = (a - b) % 360;
        if (d > 180) {
            d -= 360;
        } else if (d < -180) {
            d += 360;
        }
        return d;
    }
}
