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

import me.champeau.asterion.image.ImageLoader;
import me.champeau.asterion.image.GrayImage;
import me.champeau.asterion.image.ImageHints;
import me.champeau.asterion.image.StarDetector;
import me.champeau.asterion.image.StarList;
import me.champeau.asterion.index.StarIndex;
import me.champeau.asterion.math.Sphere;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds where an image of the sky was taken, its scale and its orientation, from the stars it
 * contains. A solver uses one or more {@link StarIndex indexes}, and is thread-safe: several images
 * can be solved concurrently with the same instance.
 * <pre>{@code
 * PlateSolver solver = PlateSolver.open(Path.of("tycho2.astx"));
 * SolveResult result = solver.solve(Path.of("image.fits"), SolverOptions.defaults());
 * result.solution().ifPresent(s -> System.out.println(s.raDeg() + " " + s.decDeg()));
 * }</pre>
 */
public final class PlateSolver {
    private static final double STRICT_CODE_TOLERANCE = 0.004;
    /** The radius of the search around a position which is only a hint, such as one of an image file, in degrees. */
    public static final double HINT_SEARCH_RADIUS_DEG = 10;
    /**
     * The stars which build quads when searching around a position which is only a hint: a right
     * position solves with the first quads, and a wrong one is given up quickly, before the search
     * goes on without it. The cost of an unsuccessful search grows with the cube of this number.
     */
    public static final int HINT_QUAD_STARS = 30;
    /** The relative error which is tolerated on the scale found in image files. */
    private static final double HINT_SCALE_TOLERANCE = 0.25;

    private final List<StarIndex> indexes;

    public PlateSolver(List<StarIndex> indexes) {
        if (indexes.isEmpty()) {
            throw new IllegalArgumentException("At least one index is required");
        }
        this.indexes = List.copyOf(indexes);
    }

    /** Creates a solver which uses the given index files. */
    public static PlateSolver open(Path... indexFiles) throws IOException {
        var indexes = new ArrayList<StarIndex>();
        for (var file : indexFiles) {
            indexes.add(StarIndex.open(file));
        }
        return new PlateSolver(indexes);
    }

    public List<StarIndex> indexes() {
        return indexes;
    }

    /** Solves an image file: FITS, PNG, JPEG, TIFF... */
    public SolveResult solve(Path file, SolverOptions options) throws IOException {
        var start = System.nanoTime();
        var image = ImageLoader.load(file, options.binning());
        var loadMillis = (System.nanoTime() - start) / 1e6;
        // nobody else sees the pixels of the image: they can be used as working memory
        return solve(image.image(), image.hints(), options, loadMillis, true);
    }

    /** Solves an image. The image is not modified. */
    public SolveResult solve(GrayImage image, ImageHints hints, SolverOptions options) {
        return solve(image, hints, options, 0, false);
    }

    private SolveResult solve(GrayImage image, ImageHints hints, SolverOptions options, double loadMillis, boolean ownsImage) {
        var start = System.nanoTime();
        var stars = ownsImage ? StarDetector.detectInPlace(image, options.detection()) : StarDetector.detect(image, options.detection());
        var detectMillis = (System.nanoTime() - start) / 1e6;
        var result = solve(stars, image.sourceWidth(), image.sourceHeight(), hints, options);
        var s = result.stats();
        return new SolveResult(result.solution().orElse(null), stars,
                new SolveResult.Stats(s.quads(), s.candidates(), s.verifications(), loadMillis, detectMillis, s.solveMillis(),
                        s.usedHints()),
                result.pixelSizeMicrons().orElse(Double.NaN));
    }

    /**
     * Solves a list of stars.
     *
     * @param stars the stars, sorted by decreasing brightness
     * @param width the width of the image, in pixels
     * @param height the height of the image, in pixels
     * @param hints what is known about the image, see {@link ImageHints#none()}
     */
    public SolveResult solve(StarList stars, int width, int height, ImageHints hints, SolverOptions options) {
        var start = System.nanoTime();
        var deadline = start + options.timeout().toNanos();
        var epoch = options.epoch();
        if (Double.isNaN(epoch)) {
            var today = LocalDate.now();
            epoch = hints.epoch().orElse(today.getYear() + (today.getDayOfYear() - 1) / 365.25);
        }
        if (options.minFieldWidthDeg() > 0 || options.maxFieldWidthDeg() > 0) {
            // a field of view is a scale, now that the size of the image is known
            var min = Math.max(options.minScale(), options.minFieldWidthDeg() * 3600 / width);
            var max = options.maxFieldWidthDeg() * 3600 / width;
            if (options.maxScale() > 0) {
                max = max > 0 ? Math.min(max, options.maxScale()) : options.maxScale();
            }
            options = options.toBuilder().fieldOfView(0, 0).scale(min, max).build();
        }
        // a first attempt with the hints of the image, then a second one without
        var explicitPosition = options.hasPosition();
        var explicitScale = options.minScale() > 0 || options.maxScale() > 0;
        var attempts = new ArrayList<SolverOptions>();
        var hinted = false;
        if (options.useImageHints()) {
            var b = options.toBuilder();
            if (!explicitPosition && hints.hasPosition()) {
                b.position(hints.raDeg().orElseThrow(), hints.decDeg().orElseThrow(), HINT_SEARCH_RADIUS_DEG);
                hinted = true;
            }
            if (!explicitScale && hints.pixelScale().isPresent()) {
                var scale = hints.pixelScale().orElseThrow();
                b.scale(scale * (1 - HINT_SCALE_TOLERANCE), scale * (1 + HINT_SCALE_TOLERANCE));
                hinted = true;
            }
            if (hinted) {
                if (options.blindFallback()) {
                    b.maxQuadStars(Math.min(options.maxQuadStars(), HINT_QUAD_STARS));
                }
                attempts.add(b.build());
            }
        }
        if (!hinted || options.blindFallback()) {
            attempts.add(options);
        }
        var x = stars.x();
        var y = stars.y();
        var quads = 0L;
        var candidates = 0L;
        var verifications = 0L;
        Solution solution = null;
        var usedHints = false;
        attempts:
        for (var attempt = 0; attempt < attempts.size(); attempt++) {
            var o = attempts.get(attempt);
            var hint = o.hasPosition() ? QuadSearch.vector(o.raDeg(), o.decDeg()) : null;
            // Most images match with a tolerance which is much smaller than what distorted images
            // require, and which gives far fewer false matches to verify: when the whole sky is
            // searched, it is worth trying it first. The second pass is for images taken with short
            // focal lengths, which have many bright stars: it doesn't need to go as deep.
            var twoPasses = hint == null && o.codeTolerance() > STRICT_CODE_TOLERANCE;
            for (var pass = 0; pass < (twoPasses ? 2 : 1); pass++) {
                var tolerance = twoPasses && pass == 0 ? STRICT_CODE_TOLERANCE : o.codeTolerance();
                var maxStars = twoPasses && pass == 1 ? Math.max(20, o.maxQuadStars() / 2) : o.maxQuadStars();
                var search = new QuadSearch(indexes, x, y, stars.size(), width, height, o, epoch, maxStars, tolerance,
                        o.minScale() * Sphere.ARCSEC, o.maxScale() * Sphere.ARCSEC, hint, Math.toRadians(o.searchRadiusDeg()), deadline);
                var h = search.run();
                quads += search.quads;
                candidates += search.candidates;
                verifications += search.verifications;
                if (h != null) {
                    // the solution is refined with the deepest index, whatever the one which found it
                    var deepest = h.index;
                    for (var index : indexes) {
                        if (index.starCount() > deepest.starCount()) {
                            deepest = index;
                        }
                    }
                    solution = new Refiner(deepest, stars, width, height, o, epoch - deepest.epoch()).refine(h);
                    if (solution != null) {
                        usedHints = hinted && attempt == 0;
                        break attempts;
                    }
                }
                if (search.timedOut) {
                    break attempts;
                }
            }
        }
        var solveMillis = (System.nanoTime() - start) / 1e6;
        var pixelSize = options.pixelSizeMicrons() > 0 ? options.pixelSizeMicrons() : hints.pixelSizeMicrons().orElse(Double.NaN);
        return new SolveResult(solution, stars,
                new SolveResult.Stats(quads, candidates, verifications, 0, 0, solveMillis, usedHints), pixelSize);
    }
}
