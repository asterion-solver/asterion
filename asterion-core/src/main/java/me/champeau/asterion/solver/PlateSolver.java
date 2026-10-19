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
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private static final double HINT_SEARCH_RADIUS_DEG = 10;
    /**
     * The stars which build quads when searching around a position which is only a hint: a right
     * position solves with the first quads, and a wrong one is given up quickly, before the search
     * goes on without it. The cost of an unsuccessful search grows with the cube of this number.
     */
    private static final int HINT_QUAD_STARS = 30;
    /** Detections wider than this number of times the size of stars aren't stars, see {@link #compactStars}. */
    private static final double EXTENDED_FWHM_FACTOR = 4;
    /** Detections whose light is less concentrated than this, compared to a gaussian star, aren't stars. */
    private static final double MIN_CONCENTRATION = 0.6;
    /** The concentration of the light of point sources, see {@link #pointSources}. */
    private static final double POINT_SOURCE_CONCENTRATION = 0.9;
    /** Point sources are at most this number of times larger than the smallest detections, see {@link #pointSources}. */
    private static final double POINT_SOURCE_FWHM_FACTOR = 1.5;
    /** How much stricter the detection threshold is when an image doesn't solve, for a retry. */
    private static final double RETRY_THRESHOLD_FACTOR = 2;
    /** The number of stars of the quick searches, see {@link #stages}. */
    private static final int SHALLOW_QUAD_STARS = 32;
    /**
     * The number of stars of the searches of wide fields, for each focal length: the brightest
     * stars of these images are the ones of the index.
     */
    private static final int WIDE_FIELD_QUAD_STARS = 100;
    /**
     * The lanes of the searches, for all the indexes together, see {@link QuadSearch#lanes}: the
     * searches which succeed in a few hundred quads cost the dispatch of the lanes, a fraction of
     * a millisecond, and the others are as many times faster.
     */
    private static final int PARALLEL_LANES = ForkJoinPool.getCommonPoolParallelism() + 1;
    /**
     * How many hypotheses the refinement may reject in a stage before the next stage is tried:
     * the search goes on without the quad of a rejected hypothesis, as a weak match of the right
     * field may be found before the strong one, especially by parallel lanes.
     */
    private static final int MAX_REJECTIONS = 3;
    /** The quads and the verifications the deep search of the concentrated stars may try, see {@link #stages}. */
    private static final Budget CONCENTRATED_BUDGET = new Budget(30_000, 3_000);
    /**
     * The quads the search of a wide field may try for each focal length, see {@link #stages}:
     * the fields which solve do so within a few thousand quads of their brightest stars.
     */
    private static final Budget WIDE_FIELD_BUDGET = new Budget(30_000, Long.MAX_VALUE);
    /** The tolerance on the scale of the stereographic projection of a wide field, for a focal length which is tried. */
    private static final double WIDE_FIELD_SCALE_TOLERANCE = 0.03;
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
        var result = solve(stars, image.sourceWidth(), image.sourceHeight(), hints, options, Stages.USUAL);
        var s = result.stats();
        var remaining = options.timeout().minusMillis((long) s.solveMillis());
        if (!result.solved() && !remaining.isNegative() && !remaining.isZero()) {
            // Heavily processed images have hundreds of faint detections which aren't stars, and
            // the few stars they have can be lost among them: a stricter threshold keeps the stars
            // which stand out, and finds the field of some of these images.
            var retryStart = System.nanoTime();
            var detection = options.detection().withThresholdSigma(RETRY_THRESHOLD_FACTOR * options.detection().thresholdSigma());
            var fewer = StarDetector.detect(image, detection);
            detectMillis += (System.nanoTime() - retryStart) / 1e6;
            var retry = solve(fewer, image.sourceWidth(), image.sourceHeight(), hints, options.toBuilder().timeout(remaining).build(),
                    Stages.USUAL);
            var r = retry.stats();
            s = new SolveResult.Stats(s.quads() + r.quads(), s.candidates() + r.candidates(), s.verifications() + r.verifications(), 0, 0,
                    s.solveMillis() + r.solveMillis(), r.usedHints());
            if (retry.solved()) {
                result = retry;
                stars = fewer;
            }
            remaining = options.timeout().minusMillis((long) s.solveMillis());
        }
        if (!result.solved() && !remaining.isNegative() && !remaining.isZero()) {
            // Images taken with wide angle lenses, last: the stars of landscapes are faint but
            // sharp points among the bright lights of the foreground, so they are ranked by peak
            var wideStart = System.nanoTime();
            var points = StarDetector.detect(image, options.detection().withPeakRanking(true));
            detectMillis += (System.nanoTime() - wideStart) / 1e6;
            var wide = solve(points, image.sourceWidth(), image.sourceHeight(), hints, options.toBuilder().timeout(remaining).build(),
                    Stages.WIDE_FIELDS);
            var r = wide.stats();
            s = new SolveResult.Stats(s.quads() + r.quads(), s.candidates() + r.candidates(), s.verifications() + r.verifications(), 0, 0,
                    s.solveMillis() + r.solveMillis(), r.usedHints());
            if (wide.solved()) {
                result = wide;
                stars = points;
            }
        }
        return new SolveResult(result.solution().orElse(null), stars,
                new SolveResult.Stats(s.quads(), s.candidates(), s.verifications(), loadMillis, detectMillis, s.solveMillis(),
                        s.usedHints()),
                result.pixelSizeMicrons().orElse(Double.NaN));
    }

    /** Which stages a search runs, see {@link #stages}. */
    private enum Stages {
        /** The searches of the stars by brightness. */
        USUAL,
        /** The searches of wide fields, see {@link WideField}. */
        WIDE_FIELDS,
        /** All of them: for lists of stars, which can't be detected again. */
        ALL
    }

    /**
     * A search: the stars quads are made of, how many of them, starting with the brightest, and
     * the tolerance on the codes of quads.
     *
     * @param focal for the search of a wide field, the focal length which is tried, in pixels, see
     * {@link WideField}, or 0
     * @param pixels for the search of a wide field, the stars in the pixels of the image, in the
     * same order as the ones of the list, which are where the stereographic projection puts them
     */
    private record Stage(
            StarList list,
            int maxStars,
            double tolerance,
            Budget budget,
            double focal,
            StarList pixels) {
        Stage(StarList list, int maxStars, double tolerance) {
            this(list, maxStars, tolerance, Budget.UNLIMITED);
        }

        Stage(StarList list, int maxStars, double tolerance, Budget budget) {
            this(list, maxStars, tolerance, budget, 0, list);
        }

        Stage with(int maxStars, double tolerance) {
            return new Stage(list, maxStars, tolerance, budget, focal, pixels);
        }
    }

    /** What a search may try before giving up: quads, and verifications of their candidates. */
    private record Budget(
            long quads,
            long verifications) {
        static final Budget UNLIMITED = new Budget(Long.MAX_VALUE, Long.MAX_VALUE);
    }

    /**
     * The searches of an attempt, from the fastest to the most tolerant: each one is tried when the
     * previous ones found nothing.
     * <ol>
     *     <li>The brightest detections which aren't too large to be stars, with the strict
     *     tolerance: most images match with a tolerance which is much smaller than what distorted
     *     images require, and which gives far fewer false matches to verify.</li>
     *     <li>The brightest stars, all of them: in some images the largest detections are the
     *     brightest stars, bloated by processing.</li>
     *     <li>The detections whose light is concentrated like the light of a star, at full depth
     *     but with a budget: in images full of nebulosity, the knots of the nebula
     *     outshine the stars, and this is where the stars are. Bright stars surrounded by
     *     nebulosity or halos don't look concentrated, though, so the search is short in images
     *     without knots, which solve with their brightest stars, and nearly all of them do: a
     *     search of these concentrated stars first would cost every image a search which fails.</li>
     *     <li>The stars which aren't too large, at full depth.</li>
     *     <li>The same, with the tolerance of the options: for images taken with short focal
     *     lengths, which have many bright stars, so it doesn't need to go as deep.</li>
     *     <li>The point sources with the highest peaks, moved to where they would be if the
     *     image had been taken with a wide angle lens of each of a range of focal lengths, see
     *     {@link WideField}: for images, these come after the retry with a stricter detection,
     *     with stars detected for them.</li>
     * </ol>
     */
    private static List<Stage> stages(StarList stars, int width, int height, SolverOptions options, Stages which) {
        var stages = new ArrayList<Stage>();
        if (which != Stages.WIDE_FIELDS) {
            var compact = compactStars(stars);
            var concentrated = concentratedStars(compact);
            stages.add(new Stage(compact, SHALLOW_QUAD_STARS, STRICT_CODE_TOLERANCE));
            stages.add(new Stage(stars, SHALLOW_QUAD_STARS, STRICT_CODE_TOLERANCE));
            if (concentrated != compact) {
                stages.add(new Stage(concentrated, options.maxQuadStars(), STRICT_CODE_TOLERANCE, CONCENTRATED_BUDGET));
            }
            stages.add(new Stage(compact, options.maxQuadStars(), STRICT_CODE_TOLERANCE));
            if (options.codeTolerance() > STRICT_CODE_TOLERANCE) {
                stages.add(new Stage(compact, Math.max(20, options.maxQuadStars() / 2), options.codeTolerance()));
            }
        }
        if (which == Stages.USUAL) {
            return distinctStages(stages);
        }
        var wideStars = Math.min(WIDE_FIELD_QUAD_STARS, options.maxQuadStars());
        var wideTolerance = Math.max(STRICT_CODE_TOLERANCE, options.codeTolerance());
        var points = pointSources(stars);
        for (var focal : WideField.focalLengths(width, height)) {
            stages.add(new Stage(WideField.stereographic(points, focal, width, height), wideStars, wideTolerance, WIDE_FIELD_BUDGET,
                    focal, points));
        }
        return distinctStages(stages);
    }

    /** The stages without the repeated ones: the lists are the same objects when nothing was left out, and such stages are the same search. */
    private static List<Stage> distinctStages(List<Stage> stages) {
        var distinct = new ArrayList<Stage>();
        for (var stage : stages) {
            if (!distinct.contains(stage)) {
                distinct.add(stage);
            }
        }
        return distinct;
    }

    /**
     * The stars which aren't too large to be stars. Galaxies, nebulae and other extended objects
     * are detected as very bright, very large "stars", especially in stretched images, and would
     * be tried first: the quads of the index are made of point sources, so these never match.
     * Saturated stars are larger than the others too, but much less, and they are kept. The list
     * stays sorted by decreasing brightness.
     *
     * @return the stars themselves when none is left out
     */
    static StarList compactStars(StarList stars) {
        var limit = EXTENDED_FWHM_FACTOR * stars.medianFwhm();
        var fwhm = stars.fwhm();
        var keep = new boolean[stars.size()];
        for (var i = 0; i < stars.size(); i++) {
            keep[i] = fwhm[i] <= limit;
        }
        return select(stars, keep);
    }

    /**
     * The point sources, the ones with the highest peak first. In landscape images, the brightest
     * detections are lit windows, lamps, and the sky seen through foliage: they are larger than
     * stars, or their light is spread, and outnumber them, while the stars of these images are
     * sharp, often saturated, points which have a small flux because they are small. The peak of
     * a detection is deduced from its flux, its size and its concentration.
     *
     * @return the stars themselves when too few are point sources
     */
    static StarList pointSources(StarList stars) {
        var n = stars.size();
        if (n < 4) {
            return stars;
        }
        var fwhm = stars.fwhm();
        var sorted = fwhm.clone();
        Arrays.sort(sorted);
        var limit = POINT_SOURCE_FWHM_FACTOR * sorted[n / 10];
        var concentration = stars.concentration();
        var kept = new ArrayList<Integer>();
        for (var i = 0; i < n; i++) {
            if (concentration[i] >= POINT_SOURCE_CONCENTRATION && fwhm[i] <= limit) {
                kept.add(i);
            }
        }
        if (kept.size() < 4) {
            return stars;
        }
        var peak = new double[n];
        for (var i : kept) {
            peak[i] = concentration[i] * stars.flux(i) / (fwhm[i] * fwhm[i]);
        }
        kept.sort((a, b) -> Double.compare(peak[b], peak[a]));
        var k = kept.size();
        var x = new double[k];
        var y = new double[k];
        var flux = new float[k];
        var f = new float[k];
        var c = new float[k];
        for (var j = 0; j < k; j++) {
            var i = kept.get(j);
            x[j] = stars.x(i);
            y[j] = stars.y(i);
            flux[j] = stars.flux(i);
            f[j] = fwhm[i];
            c[j] = concentration[i];
        }
        return new StarList(x, y, flux, f, c);
    }

    /**
     * The stars whose light is concentrated like the light of a star, among the ones which aren't
     * too large: the knots of nebulae and the cores of galaxies are the size of stars in many
     * images, but their light is spread. Saturated stars are kept by the detector.
     *
     * @return the stars themselves when none is left out
     */
    static StarList concentratedStars(StarList stars) {
        var concentration = stars.concentration();
        var keep = new boolean[stars.size()];
        for (var i = 0; i < stars.size(); i++) {
            keep[i] = concentration[i] >= MIN_CONCENTRATION;
        }
        return select(stars, keep);
    }

    /** The stars to keep, in order, or the list itself when all are kept, or too few would be. */
    private static StarList select(StarList stars, boolean[] keep) {
        var kept = 0;
        for (var k : keep) {
            if (k) {
                kept++;
            }
        }
        if (kept == stars.size() || kept < 4) {
            return stars;
        }
        var x = new double[kept];
        var y = new double[kept];
        var flux = new float[kept];
        var fwhm = new float[kept];
        var concentration = new float[kept];
        var k = 0;
        for (var i = 0; i < stars.size(); i++) {
            if (keep[i]) {
                x[k] = stars.x(i);
                y[k] = stars.y(i);
                flux[k] = stars.flux(i);
                fwhm[k] = stars.fwhm()[i];
                concentration[k] = stars.concentration()[i];
                k++;
            }
        }
        return new StarList(x, y, flux, fwhm, concentration);
    }

    /**
     * A search of the plan.
     *
     * @param hinted true if the search uses hints, rather than only the limits of the options
     */
    private record Attempt(
            SolverOptions options,
            boolean hinted) {
    }

    /**
     * Plans the searches. Hints, such as the position and the scale found in an image file, only
     * order the search: the position and the scale of the options are limits, which are never
     * exceeded. The search starts around the hinted position, or the center of the region of the
     * options, at any scale: a right position solves with the first quads whatever the scale, which
     * is less reliable than positions. Then the region, or the whole sky if a blind search is
     * allowed, is searched at the hinted scale, then at any scale.
     */
    private static List<Attempt> plan(ImageHints hints, SolverOptions options) {
        var explicitPosition = options.hasPosition();
        var explicitScale = options.minScale() > 0 || options.maxScale() > 0;
        var attempts = new ArrayList<Attempt>();
        if (explicitPosition && options.searchRadiusDeg() > HINT_SEARCH_RADIUS_DEG) {
            attempts.add(
                    new Attempt(options.toBuilder().position(options.raDeg(), options.decDeg(), HINT_SEARCH_RADIUS_DEG).build(), false));
        } else if (!explicitPosition && options.useImageHints() && hints.hasPosition()) {
            attempts.add(new Attempt(options.toBuilder()
                    .position(hints.raDeg().orElseThrow(), hints.decDeg().orElseThrow(), HINT_SEARCH_RADIUS_DEG)
                    .build(), true));
        }
        // beyond the hinted position: the region of the options, or the whole sky
        var beyond = explicitPosition || options.blindFallback() || attempts.isEmpty();
        if (!explicitScale && options.useImageHints() && hints.pixelScale().isPresent() && beyond) {
            var scale = hints.pixelScale().orElseThrow();
            attempts.add(new Attempt(
                    options.toBuilder().scale(scale * (1 - HINT_SCALE_TOLERANCE), scale * (1 + HINT_SCALE_TOLERANCE)).build(), true));
        }
        if (beyond) {
            attempts.add(new Attempt(options, false));
        }
        if (attempts.size() > 1 && attempts.getFirst().options().searchRadiusDeg() == HINT_SEARCH_RADIUS_DEG
                && attempts.getFirst().options().hasPosition()) {
            // the search around the position is given up quickly when it's wrong
            var first = attempts.getFirst();
            attempts.set(0, new Attempt(first.options().toBuilder()
                    .maxQuadStars(Math.min(options.maxQuadStars(), HINT_QUAD_STARS))
                    .build(), first.hinted()));
        }
        return attempts;
    }

    /**
     * The searches of a stage: parallel lanes which share its budget, each index having its own
     * lanes so that a sparse index isn't slowed down by a dense one, or a single search when there
     * is a single processor.
     */
    private List<QuadSearch> searches(Stage stage, int width, int height, SolverOptions o, double epoch, double[] hint, long deadline,
                                      List<QuadSearch.Rejected> rejected) {
        var x = stage.list().x();
        var y = stage.list().y();
        var n = stage.list().size();
        var minScale = o.minScale() * Sphere.ARCSEC;
        var maxScale = o.maxScale() * Sphere.ARCSEC;
        if (stage.focal() > 0) {
            // the scale of the stereographic projection: the one of the focal length at the
            // center of the image, growing towards its corners
            var corner = Math.atan(Math.hypot(width, height) / 2 / stage.focal());
            var cos = Math.cos(corner / 2);
            var min = (1 - WIDE_FIELD_SCALE_TOLERANCE) / stage.focal();
            var max = (1 + WIDE_FIELD_SCALE_TOLERANCE) / (stage.focal() * cos * cos);
            minScale = Math.max(minScale, min);
            maxScale = maxScale > 0 ? Math.min(maxScale, max) : max;
            if (minScale > maxScale) {
                return List.of();
            }
        }
        var radius = Math.toRadians(o.searchRadiusDeg());
        var searches = new ArrayList<QuadSearch>();
        if (PARALLEL_LANES < 2) {
            var search = new QuadSearch(indexes, x, y, n, width, height, o, epoch, stage.maxStars(), stage.tolerance(), minScale, maxScale,
                    hint, radius, deadline);
            search.maxQuads = stage.budget().quads();
            search.maxVerifications = stage.budget().verifications();
            search.rejected = rejected;
            if (stage.focal() > 0) {
                search.wide(stage.focal(), stage.pixels());
            }
            searches.add(search);
            return searches;
        }
        var found = new AtomicBoolean();
        var lanes = Math.max(1, PARALLEL_LANES / indexes.size());
        for (var index : indexes) {
            for (var lane = 0; lane < lanes; lane++) {
                var search = new QuadSearch(List.of(index), x, y, n, width, height, o, epoch, stage.maxStars(), stage.tolerance(), minScale,
                        maxScale, hint, radius, deadline);
                search.lane = lane;
                search.lanes = lanes;
                search.found = found;
                search.maxQuads = stage.budget().quads() / lanes;
                search.maxVerifications = stage.budget().verifications() / lanes;
                search.rejected = rejected;
                if (stage.focal() > 0) {
                    search.wide(stage.focal(), stage.pixels());
                }
                searches.add(search);
            }
        }
        return searches;
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
        return solve(stars, width, height, hints, options, Stages.ALL);
    }

    private SolveResult solve(StarList stars, int width, int height, ImageHints hints, SolverOptions options, Stages which) {
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
        var attempts = plan(hints, options);
        var stages = stages(stars, width, height, options, which);
        var quads = 0L;
        var candidates = 0L;
        var verifications = 0L;
        Solution solution = null;
        var usedHints = false;
        attempts:
        for (var attempt : attempts) {
            var o = attempt.options();
            var hint = o.hasPosition() ? QuadSearch.vector(o.raDeg(), o.decDeg()) : null;
            Stage previous = null;
            var rejected = new ArrayList<QuadSearch.Rejected>();
            for (var stage : stages) {
                var maxStars = Math.min(stage.maxStars(), o.maxQuadStars());
                var tolerance = Math.min(stage.tolerance(), o.codeTolerance());
                var current = stage.with(maxStars, tolerance);
                if (current.equals(previous)) {
                    // a hinted attempt searches few stars: the stages which don't differ are skipped
                    continue;
                }
                previous = current;
                for (var rejections = 0; rejections <= MAX_REJECTIONS; rejections++) {
                    var searches = searches(current, width, height, o, epoch, hint, deadline, List.copyOf(rejected));
                    var h = searches.size() == 1
                            ? searches.getFirst().run()
                            : searches.parallelStream().map(QuadSearch::run).filter(Objects::nonNull).findAny().orElse(null);
                    var timedOut = false;
                    for (var search : searches) {
                        quads += search.quads;
                        candidates += search.candidates;
                        verifications += search.verifications;
                        timedOut |= search.timedOut;
                    }
                    if (timedOut) {
                        break attempts;
                    }
                    if (h == null) {
                        break;
                    }
                    // the solution is refined with the deepest index, whatever the one which found it
                    var deepest = h.index;
                    for (var index : indexes) {
                        if (index.starCount() > deepest.starCount()) {
                            deepest = index;
                        }
                    }
                    solution = new Refiner(deepest, stars, width, height, o, epoch - deepest.epoch()).refine(h);
                    if (solution != null) {
                        usedHints = attempt.hinted();
                        break attempts;
                    }
                    rejected.add(new QuadSearch.Rejected(h.index, h.quad));
                }
            }
        }
        var solveMillis = (System.nanoTime() - start) / 1e6;
        var pixelSize = options.pixelSizeMicrons() > 0 ? options.pixelSizeMicrons() : hints.pixelSizeMicrons().orElse(Double.NaN);
        return new SolveResult(solution, stars,
                new SolveResult.Stats(quads, candidates, verifications, 0, 0, solveMillis, usedHints), pixelSize);
    }
}
