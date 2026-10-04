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
import me.champeau.asterion.math.Sphere;
import me.champeau.asterion.math.TangentPlane;
import me.champeau.asterion.progress.ProgressListener;
import me.champeau.asterion.progress.ProgressTracker;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

/**
 * Builds an index file from a list of stars.
 *
 * @see StarIndex
 */
public final class IndexBuilder {
    private static final int MAX_CANDIDATES = 64;
    private static final int MAX_PAIRS = MAX_CANDIDATES * (MAX_CANDIDATES - 1) / 2;
    private static final int MAX_PAIR_USE = 2;
    private static final int MAX_LEVELS = 60;
    /** The size of a quad in the index: code, stars, diameter and check stars. */
    private static final int QUAD_BYTES = 8 + 16 + 4 + 8;
    /** The cells of the pyramid which are given to a task building quads. */
    private static final int CELLS_PER_TASK = 256;
    private static final long MIN_SORT_MEMORY = 16L << 20;
    private static final int MAX_ARRAY_LENGTH = Integer.MAX_VALUE - 8;

    private final StarData stars;
    private final IndexOptions options;
    private final ProgressListener progress;

    private IndexBuilder(StarData stars, IndexOptions options, ProgressListener progress) {
        this.stars = stars;
        this.options = options;
        this.progress = progress;
    }

    /**
     * Builds an index.
     *
     * @param stars the stars to index
     * @param name the name of the index
     * @param options the builder parameters
     * @param output the file to create, which is replaced atomically if it exists
     * @param progress receives the progress of the build
     */
    public static void build(StarData stars, String name, IndexOptions options, Path output, ProgressListener progress) throws IOException {
        if (stars.size() < 4) {
            throw new IllegalArgumentException("Not enough stars to build an index");
        }
        new IndexBuilder(stars, options, progress).run(name, output);
    }

    private void run(String name, Path output) throws IOException {
        var n = stars.size();
        var perCell = options.starsPerCell();

        var layout = layout();
        var grids = layout.grids();
        var pyramidLevels = layout.pyramidLevels();
        var levels = grids.size();
        var cellStart = layout.cellStart();
        var levelFirst = layout.levelFirst();
        var origOf = layout.origOf();
        var rank = layout.rank();
        var byRank = layout.byRank();
        var magLimit = stars.mag(origOf[byRank[n - 1]]);
        var vec = new double[3 * n];
        IntStream.range(0, n).parallel().forEach(pos -> {
            var idx = origOf[pos];
            var ra = stars.ra(idx);
            var dec = stars.dec(idx);
            var cd = Math.cos(dec);
            vec[3 * pos] = cd * Math.cos(ra);
            vec[3 * pos + 1] = cd * Math.sin(ra);
            vec[3 * pos + 2] = Math.sin(dec);
        });

        // Quads: there are more of them than stars. They are written to a temporary file as they
        // are built, then sorted by cell of the code grid, as many at a time as memory allows
        var spillFile = output.resolveSibling(output.getFileName() + ".quads.tmp");
        var tmp = output.resolveSibling(output.getFileName() + ".tmp");
        Files.createDirectories(output.toAbsolutePath().getParent());
        try (var spill = new QuadSpill(spillFile)) {
            buildQuads(grids, pyramidLevels, levelFirst, origOf, rank, byRank, vec, spill);
            var nQuads = Math.toIntExact(spill.count());
            var bins = Math.clamp(Math.round(Math.pow(0.6 * nQuads, 0.25)), 16, 64);
            var quadCellStart = new int[bins * bins * bins * bins + 1];
            spill.scan((code, _, _, _, _, _, _) -> quadCellStart[codeCell(code, bins) + 1]++);
            for (var c = 0; c < quadCellStart.length - 1; c++) {
                quadCellStart[c + 1] += quadCellStart[c];
            }

            progress.info("Writing %,d stars and %,d quads".formatted(n, nQuads));
            var minQuadDiam = grids.get(pyramidLevels - 1).cellSize();
            var maxQuadDiam = grids.getFirst().cellSize() * Math.sqrt(2);
            try (var channel = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                var w = new Writer(channel);
                long levelTableOffset = StarIndex.HEADER_SIZE;
                var starsOffset = align(levelTableOffset + (long) levels * StarIndex.LEVEL_ENTRY_SIZE);
                var offset = starsOffset + (long) n * StarIndex.STAR_SIZE;
                var levelCellStartOffset = new long[levels];
                for (var l = 0; l < levels; l++) {
                    levelCellStartOffset[l] = offset;
                    offset = align(offset + 4L * cellStart[l].length);
                }
                var quadCellStartOffset = offset;
                var quadCodesOffset = align(quadCellStartOffset + 4L * quadCellStart.length);
                var quadStarsOffset = quadCodesOffset + 8L * nQuads;
                var quadAuxOffset = quadStarsOffset + 16L * nQuads;

                w.putInt(StarIndex.MAGIC);
                w.putInt(StarIndex.VERSION);
                w.putLong(n);
                w.putLong(nQuads);
                w.putInt(levels);
                w.putInt(pyramidLevels);
                w.putInt(bins);
                w.putInt(perCell);
                w.putDouble(stars.epoch());
                w.putDouble(minQuadDiam);
                w.putDouble(maxQuadDiam);
                w.putLong(starsOffset);
                w.putLong(levelTableOffset);
                w.putLong(quadCellStartOffset);
                w.putLong(quadCodesOffset);
                w.putLong(quadStarsOffset);
                w.putLong(quadAuxOffset);
                w.putFloat(magLimit);
                w.padTo(StarIndex.NAME_OFFSET);
                var nameBytes = name.getBytes(StandardCharsets.UTF_8);
                w.putBytes(Arrays.copyOf(nameBytes, Math.min(nameBytes.length, StarIndex.NAME_SIZE - 1)));
                w.padTo(levelTableOffset);
                for (var l = 0; l < levels; l++) {
                    w.putDouble(grids.get(l).cellSize());
                    w.putLong(levelFirst[l]);
                    w.putLong(levelCellStartOffset[l]);
                    w.putInt(grids.get(l).cellCount());
                    w.putInt(0);
                }
                w.padTo(starsOffset);
                for (var pos = 0; pos < n; pos++) {
                    var idx = origOf[pos];
                    w.putInt((int) Math.round(Sphere.normalizeRa(stars.ra(idx)) / StarIndex.RA_UNIT));
                    w.putInt((int) Math.round(stars.dec(idx) / StarIndex.DEC_UNIT));
                    w.putShort(toShort(stars.pmRa(idx) / StarIndex.PM_UNIT_MAS));
                    w.putShort(toShort(stars.pmDec(idx) / StarIndex.PM_UNIT_MAS));
                    w.putShort(toShort(stars.mag(idx) * 1000.0));
                    w.putShort((short) 0);
                }
                for (var l = 0; l < levels; l++) {
                    w.padTo(levelCellStartOffset[l]);
                    for (var v : cellStart[l]) {
                        w.putInt(v);
                    }
                }
                w.padTo(quadCellStartOffset);
                for (var v : quadCellStart) {
                    w.putInt(v);
                }
                w.padTo(quadCodesOffset);
                writeSortedQuads(spill, quadCellStart, bins, w, quadCodesOffset, quadStarsOffset, quadAuxOffset);
                w.flush();
            }
        } finally {
            Files.deleteIfExists(spillFile);
        }
        Files.move(tmp, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * The order of stars in the index: by level of the star pyramid, then by cell, then by
     * brightness. The temporary arrays which compute it are collected once it's known.
     *
     * @param grids the grids of the levels of the pyramid, and of the stars which are left out of it
     * @param pyramidLevels the number of levels of the pyramid
     * @param cellStart for each level, the position of the first star of each cell
     * @param levelFirst the position of the first star of each level
     * @param origOf the index in the catalog of the star at each position
     * @param rank the brightness rank of the star at each position
     * @param byRank the position of the stars, by brightness rank
     */
    private record Layout(
            List<RingGrid> grids,
            int pyramidLevels,
            int[][] cellStart,
            int[] levelFirst,
            int[] origOf,
            int[] rank,
            int[] byRank) {
    }

    private Layout layout() {
        var n = stars.size();
        var perCell = options.starsPerCell();
        var order = brightnessOrder();

        // Star pyramid
        progress.info("Building the star pyramid for %,d stars".formatted(n));
        var level = new byte[n];
        Arrays.fill(level, (byte) -1);
        var cell = new int[n];
        var grids = new ArrayList<RingGrid>();
        var size = Math.toRadians(options.maxCellSizeDeg());
        var remaining = n;
        while (remaining > 0 && grids.size() < MAX_LEVELS) {
            var grid = new RingGrid(size);
            var count = new int[grid.cellCount()];
            var k = (byte) grids.size();
            var assigned = 0;
            for (var idx : order) {
                if (level[idx] >= 0) {
                    continue;
                }
                var c = grid.cellOf(stars.ra(idx), stars.dec(idx));
                if (count[c] < perCell) {
                    count[c]++;
                    level[idx] = k;
                    cell[idx] = c;
                    assigned++;
                }
            }
            grids.add(grid);
            remaining -= assigned;
            var next = size / Math.sqrt(2);
            if (assigned < options.minFill() * perCell * grid.cellCount() || Math.toDegrees(next) < options.minCellSizeDeg()) {
                break;
            }
            size = next;
        }
        var pyramidLevels = grids.size();
        if (remaining > 0) {
            var grid = new RingGrid(size);
            for (var idx = 0; idx < n; idx++) {
                if (level[idx] < 0) {
                    level[idx] = (byte) pyramidLevels;
                    cell[idx] = grid.cellOf(stars.ra(idx), stars.dec(idx));
                }
            }
            grids.add(grid);
        }
        var levels = grids.size();

        // Final order of stars: by level, then by cell, then by brightness
        var cellStart = new int[levels][];
        for (var l = 0; l < levels; l++) {
            cellStart[l] = new int[grids.get(l).cellCount() + 1];
        }
        for (var idx = 0; idx < n; idx++) {
            cellStart[level[idx]][cell[idx] + 1]++;
        }
        var levelFirst = new int[levels + 1];
        var base = 0;
        for (var l = 0; l < levels; l++) {
            levelFirst[l] = base;
            var starts = cellStart[l];
            var running = base;
            for (var c = 0; c < starts.length - 1; c++) {
                var cnt = starts[c + 1];
                starts[c] = running;
                running += cnt;
            }
            starts[starts.length - 1] = running;
            base = running;
        }
        levelFirst[levels] = n;
        // the position of each star in the file, its brightness rank, and the reverse
        var origOf = new int[n];
        var rank = new int[n];
        var byRank = new int[n];
        {
            var cursor = new int[levels][];
            for (var l = 0; l < levels; l++) {
                cursor[l] = cellStart[l].clone();
            }
            for (var r = 0; r < n; r++) {
                var idx = order[r];
                var pos = cursor[level[idx]][cell[idx]]++;
                origOf[pos] = idx;
                rank[pos] = r;
                byRank[r] = pos;
            }
        }
        return new Layout(grids, pyramidLevels, cellStart, levelFirst, origOf, rank, byRank);
    }

    /** The indexes of the stars, sorted by decreasing brightness. */
    private int[] brightnessOrder() {
        var n = stars.size();
        var keys = new long[n];
        for (var i = 0; i < n; i++) {
            keys[i] = ((long) Float.floatToIntBits(stars.mag(i) + 100f) << 32) | i;
        }
        Arrays.parallelSort(keys);
        var order = new int[n];
        for (var i = 0; i < n; i++) {
            order[i] = (int) keys[i];
        }
        return order;
    }

    /**
     * Builds the quads of each level of the pyramid, in parallel. They are written to the spill
     * file in a deterministic order, cell after cell, so that index files are reproducible.
     */
    private void buildQuads(List<RingGrid> grids, int pyramidLevels, int[] levelFirst, int[] origOf, int[] rank, int[] byRank,
                            double[] vec, QuadSpill spill) throws IOException {
        var batchSize = 4 * ForkJoinPool.getCommonPoolParallelism();
        try (var tracker = ProgressTracker.start(progress, "Building quads", pyramidLevels, "levels")) {
            for (var k = 0; k < pyramidLevels; k++) {
                var grid = grids.get(k);
                var cumEnd = levelFirst[k + 1];
                var nCells = grid.cellCount();
                var bandCell = new int[cumEnd];
                IntStream.range(0, cumEnd).parallel().forEach(pos -> {
                    var idx = origOf[pos];
                    bandCell[pos] = grid.cellOf(stars.ra(idx), stars.dec(idx));
                });
                var bandStart = new int[nCells + 1];
                for (var pos = 0; pos < cumEnd; pos++) {
                    bandStart[bandCell[pos] + 1]++;
                }
                for (var c = 0; c < nCells; c++) {
                    bandStart[c + 1] += bandStart[c];
                }
                var bandStars = new int[cumEnd];
                var cursor = bandStart.clone();
                for (var pos : byRank) {
                    if (pos < cumEnd) {
                        bandStars[cursor[bandCell[pos]]++] = pos;
                    }
                }
                var lo = grid.cellSize();
                var hi = lo * Math.sqrt(2);
                var tasks = (nCells + CELLS_PER_TASK - 1) / CELLS_PER_TASK;
                var levelQuads = 0L;
                // a few tasks per thread at a time: only their quads are in memory
                for (var first = 0; first < tasks; first += batchSize) {
                    var batch = IntStream.range(first, Math.min(tasks, first + batchSize)).parallel().mapToObj(t -> {
                        var from = t * CELLS_PER_TASK;
                        var to = Math.min(nCells, from + CELLS_PER_TASK);
                        var chunk = new QuadChunk((to - from) * options.quadsPerCell());
                        var builder = new CellQuadBuilder(grid, lo, hi, bandStart, bandStars, vec, rank, options.quadsPerCell(), chunk);
                        for (var c = from; c < to; c++) {
                            builder.build(c);
                        }
                        return chunk;
                    }).toList();
                    for (var chunk : batch) {
                        spill.write(chunk);
                        levelQuads += chunk.size;
                    }
                }
                tracker.advance(1, "Level %d/%d: quads of %.1f' to %.1f' built from %,d stars: %,d quads".formatted(
                        k + 1, pyramidLevels, Math.toDegrees(lo) * 60, Math.toDegrees(hi) * 60, cumEnd, levelQuads));
            }
        }
    }

    /**
     * Writes the quads sorted by cell of the code grid. Each pass reads all the quads, and sorts
     * those of a range of cells, which fit in the memory available for sorting.
     */
    private void writeSortedQuads(QuadSpill spill, int[] quadCellStart, int bins, Writer w,
                                  long codesOffset, long starsOffset, long auxOffset) throws IOException {
        var cells = quadCellStart.length - 1;
        // the stars of the quads of a pass are in a single array
        var maxPassQuads = Math.clamp(sortMemory(spill.count()) / QUAD_BYTES, 1, MAX_ARRAY_LENGTH / 4);
        // the cells of each pass: a cell is never split
        var passStarts = new ArrayList<Integer>();
        var capacity = 0;
        for (var c0 = 0; c0 < cells;) {
            passStarts.add(c0);
            var c1 = c0 + 1;
            while (c1 < cells && quadCellStart[c1 + 1] - quadCellStart[c0] <= maxPassQuads) {
                c1++;
            }
            capacity = Math.max(capacity, quadCellStart[c1] - quadCellStart[c0]);
            c0 = c1;
        }
        passStarts.add(cells);
        var passes = passStarts.size() - 1;
        var codes = new long[capacity];
        var quadStars = new int[4 * capacity];
        var diams = new float[capacity];
        var checks = new long[capacity];
        try (var tracker = ProgressTracker.start(progress, passes == 1 ? "Sorting quads" : "Sorting quads in %d passes".formatted(passes),
                passes, "passes")) {
            for (var p = 0; p < passes; p++) {
                var c0 = passStarts.get(p);
                var c1 = passStarts.get(p + 1);
                var first = quadCellStart[c0];
                var count = quadCellStart[c1] - first;
                var cursor = Arrays.copyOfRange(quadCellStart, c0, c1);
                spill.scan((code, a, b, c, d, diam, check) -> {
                    var cell = codeCell(code, bins);
                    if (cell >= c0 && cell < c1) {
                        var q = cursor[cell - c0]++ - first;
                        codes[q] = code;
                        quadStars[4 * q] = a;
                        quadStars[4 * q + 1] = b;
                        quadStars[4 * q + 2] = c;
                        quadStars[4 * q + 3] = d;
                        diams[q] = diam;
                        checks[q] = check;
                    }
                });
                w.seek(codesOffset + 8L * first);
                for (var q = 0; q < count; q++) {
                    w.putLong(codes[q]);
                }
                w.seek(starsOffset + 16L * first);
                for (var q = 0; q < 4 * count; q++) {
                    w.putInt(quadStars[q]);
                }
                w.seek(auxOffset + 12L * first);
                for (var q = 0; q < count; q++) {
                    w.putFloat(diams[q]);
                    w.putLong(checks[q]);
                }
                tracker.advance(1);
            }
        }
    }

    /**
     * The memory which can be used to sort quads, in bytes: half of what's available, since the
     * rest is left to the garbage collector and to other programs.
     */
    private long sortMemory(long quads) {
        if (options.sortMemory() > 0) {
            return options.sortMemory();
        }
        var heap = AvailableMemory.heap();
        if (heap < 2 * quads * QUAD_BYTES) {
            // garbage is counted as used memory: it's worth collecting before sorting in several passes
            System.gc();
            heap = AvailableMemory.heap();
        }
        return Math.max(MIN_SORT_MEMORY, Math.min(heap, AvailableMemory.system()) / 2);
    }

    private static long align(long offset) {
        return (offset + 7) & ~7L;
    }

    private static short toShort(double v) {
        return (short) Math.clamp(Math.round(v), -32767, 32767);
    }

    private static int codeCell(long code, int bins) {
        return QuadCode.cell((int) (code & 0xFFFF), (int) ((code >>> 16) & 0xFFFF), (int) ((code >>> 32) & 0xFFFF), (int) (code >>> 48),
                bins);
    }

    /** The quads built by a task. */
    private static final class QuadChunk {
        final long[] codes;
        final int[] stars;
        final float[] diams;
        final long[] checks;
        int size;

        QuadChunk(int capacity) {
            codes = new long[capacity];
            stars = new int[4 * capacity];
            diams = new float[capacity];
            checks = new long[capacity];
        }

        void add(long code, int a, int b, int c, int d, float diam, long check) {
            checks[size] = check;
            codes[size] = code;
            stars[4 * size] = a;
            stars[4 * size + 1] = b;
            stars[4 * size + 2] = c;
            stars[4 * size + 3] = d;
            diams[size] = diam;
            size++;
        }
    }

    /** Receives the quads read from a spill file. */
    @FunctionalInterface
    private interface QuadVisitor {
        void visit(long code, int a, int b, int c, int d, float diam, long check);
    }

    /** A temporary file of quads, which aren't kept in memory while they are built. */
    private static final class QuadSpill implements AutoCloseable {
        private static final int BUFFER_QUADS = 1 << 15;
        private final Path file;
        private final FileChannel channel;
        private final ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_QUADS * QUAD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        private long count;

        QuadSpill(Path file) throws IOException {
            this.file = file;
            this.channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
        }

        long count() {
            return count;
        }

        void write(QuadChunk chunk) throws IOException {
            for (var i = 0; i < chunk.size; i++) {
                if (buffer.remaining() < QUAD_BYTES) {
                    flush();
                }
                buffer.putLong(chunk.codes[i]);
                for (var j = 0; j < 4; j++) {
                    buffer.putInt(chunk.stars[4 * i + j]);
                }
                buffer.putFloat(chunk.diams[i]);
                buffer.putLong(chunk.checks[i]);
            }
            count += chunk.size;
        }

        private void flush() throws IOException {
            buffer.flip();
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            buffer.clear();
        }

        /** Reads all the quads, in the order they were written. */
        void scan(QuadVisitor visitor) throws IOException {
            // the quads which are still in the buffer
            flush();
            var position = 0L;
            var end = count * QUAD_BYTES;
            while (position < end) {
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), end - position));
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer, position + buffer.position()) < 0) {
                        throw new IOException("Unexpected end of " + file);
                    }
                }
                buffer.flip();
                while (buffer.hasRemaining()) {
                    var code = buffer.getLong();
                    var a = buffer.getInt();
                    var b = buffer.getInt();
                    var c = buffer.getInt();
                    var d = buffer.getInt();
                    var diam = buffer.getFloat();
                    var check = buffer.getLong();
                    visitor.visit(code, a, b, c, d, diam, check);
                }
                position += buffer.limit();
            }
            buffer.clear();
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    /**
     * Builds the quads of a cell: quads are made of the brightest stars around the cell, since these
     * are the ones which are the most likely to be detected in an image. A quad belongs to the cell
     * which contains the midpoint of its stars A and B.
     */
    private static final class CellQuadBuilder {
        private final RingGrid grid;
        private final double lo2;
        private final double hi2;
        private final double searchRadius;
        private final int[] bandStart;
        private final int[] bandStars;
        private final double[] vec;
        private final int[] rank;
        private final int quadsPerCell;
        private final int maxStarUse;
        private final QuadChunk out;

        private final int[] ranges;
        private final double[] raDec = new double[2];
        private final double[] center = new double[3];
        private long[] candidates = new long[256];
        private final int[] ids = new int[MAX_CANDIDATES];
        private final double[] vx = new double[MAX_CANDIDATES];
        private final double[] vy = new double[MAX_CANDIDATES];
        private final double[] vz = new double[MAX_CANDIDATES];
        private final int[] use = new int[MAX_CANDIDATES];
        private final int[] pairA = new int[MAX_PAIRS];
        private final int[] pairB = new int[MAX_PAIRS];
        private final int[] pairUse = new int[MAX_PAIRS];
        private final long[] members = new long[MAX_PAIRS];
        private final TangentPlane[] planes = new TangentPlane[MAX_PAIRS];
        private final double[] pairAx = new double[MAX_PAIRS];
        private final double[] pairAy = new double[MAX_PAIRS];
        private final double[] pairDx = new double[MAX_PAIRS];
        private final double[] pairDy = new double[MAX_PAIRS];
        private int cellId;
        private int candidateCount;
        private int pairs;
        private double zr;
        private double zi;

        CellQuadBuilder(RingGrid grid, double lo, double hi, int[] bandStart, int[] bandStars, double[] vec, int[] rank, int quadsPerCell,
                        QuadChunk out) {
            this.grid = grid;
            this.lo2 = Sphere.angleToChord2(lo);
            this.hi2 = Sphere.angleToChord2(hi);
            this.searchRadius = hi / 2 + grid.maxCellRadius();
            this.bandStart = bandStart;
            this.bandStars = bandStars;
            this.vec = vec;
            this.rank = rank;
            this.quadsPerCell = quadsPerCell;
            this.maxStarUse = Math.max(2, (quadsPerCell + 1) / 2);
            this.out = out;
            this.ranges = new int[grid.maxConeRangeInts(searchRadius)];
        }

        void build(int cellId) {
            this.cellId = cellId;
            grid.cellCenter(cellId, raDec);
            Sphere.toVector(raDec[0], raDec[1], center);
            var nr = grid.coneRanges(raDec[0], raDec[1], searchRadius, ranges);
            var maxChord2 = Sphere.angleToChord2(Math.min(Math.PI, searchRadius));
            var count = 0;
            for (var r = 0; r < nr; r += 2) {
                var from = bandStart[ranges[r]];
                var to = bandStart[ranges[r + 1]];
                for (var s = from; s < to; s++) {
                    var pos = bandStars[s];
                    var dx = vec[3 * pos] - center[0];
                    var dy = vec[3 * pos + 1] - center[1];
                    var dz = vec[3 * pos + 2] - center[2];
                    if (dx * dx + dy * dy + dz * dz <= maxChord2) {
                        if (count == candidates.length) {
                            candidates = Arrays.copyOf(candidates, 2 * count);
                        }
                        candidates[count++] = ((long) rank[pos] << 32) | pos;
                    }
                }
            }
            if (count < 4) {
                return;
            }
            Arrays.sort(candidates, 0, count);
            var m = Math.min(count, MAX_CANDIDATES);
            candidateCount = m;
            for (var i = 0; i < m; i++) {
                var pos = (int) candidates[i];
                ids[i] = pos;
                vx[i] = vec[3 * pos];
                vy[i] = vec[3 * pos + 1];
                vz[i] = vec[3 * pos + 2];
                use[i] = 0;
            }
            pairs = 0;
            var found = 0;
            for (var nn = 1; nn < m && found < quadsPerCell; nn++) {
                // the new star is C or D of pairs made of brighter stars
                var existing = pairs;
                for (var p = 0; p < existing && found < quadsPerCell; p++) {
                    if (inCircle(p, nn)) {
                        for (var mem = members[p]; mem != 0 && found < quadsPerCell; mem &= mem - 1) {
                            if (tryAdd(p, Long.numberOfTrailingZeros(mem), nn)) {
                                found++;
                            }
                        }
                        members[p] |= 1L << nn;
                    }
                }
                // the new star is A or B
                for (var i = 0; i < nn && found < quadsPerCell; i++) {
                    if (!newPair(i, nn)) {
                        continue;
                    }
                    var p = pairs - 1;
                    var mem = 0L;
                    for (var j = 0; j < nn; j++) {
                        if (j != i && inCircle(p, j)) {
                            mem |= 1L << j;
                        }
                    }
                    members[p] = mem;
                    for (var m1 = mem; m1 != 0 && found < quadsPerCell; m1 &= m1 - 1) {
                        for (var m2 = m1 & (m1 - 1); m2 != 0 && found < quadsPerCell; m2 &= m2 - 1) {
                            if (tryAdd(p, Long.numberOfTrailingZeros(m1), Long.numberOfTrailingZeros(m2))) {
                                found++;
                            }
                        }
                    }
                }
            }
        }

        private boolean newPair(int a, int b) {
            var dx = vx[a] - vx[b];
            var dy = vy[a] - vy[b];
            var dz = vz[a] - vz[b];
            var chord2 = dx * dx + dy * dy + dz * dz;
            if (chord2 < lo2 || chord2 >= hi2) {
                return false;
            }
            var mx = vx[a] + vx[b];
            var my = vy[a] + vy[b];
            var mz = vz[a] + vz[b];
            var norm = Math.sqrt(mx * mx + my * my + mz * mz);
            if (grid.cellOf(Sphere.ra(mx, my), Sphere.dec(mz / norm)) != cellId) {
                return false;
            }
            var p = pairs++;
            var plane = TangentPlane.at(mx, my, mz);
            planes[p] = plane;
            pairA[p] = a;
            pairB[p] = b;
            pairUse[p] = 0;
            pairAx[p] = plane.xi(vx[a], vy[a], vz[a]);
            pairAy[p] = plane.eta(vx[a], vy[a], vz[a]);
            pairDx[p] = plane.xi(vx[b], vy[b], vz[b]) - pairAx[p];
            pairDy[p] = plane.eta(vx[b], vy[b], vz[b]) - pairAy[p];
            return true;
        }

        /** Computes the coordinates of a star in the frame of a pair, as the complex number (zr, zi). */
        private void code(int p, int star) {
            var plane = planes[p];
            var px = plane.xi(vx[star], vy[star], vz[star]) - pairAx[p];
            var py = plane.eta(vx[star], vy[star], vz[star]) - pairAy[p];
            var dx = pairDx[p];
            var dy = pairDy[p];
            var den = dx * dx + dy * dy;
            zr = (px * dx + py * dy) / den;
            zi = (py * dx - px * dy) / den;
        }

        private boolean inCircle(int p, int star) {
            if (planes[p].dot(vx[star], vy[star], vz[star]) <= 0) {
                return false;
            }
            code(p, star);
            return zr * zr + zi * zi - zr <= 0;
        }

        private boolean tryAdd(int p, int c, int d) {
            var a = pairA[p];
            var b = pairB[p];
            if (pairUse[p] >= MAX_PAIR_USE || use[a] >= maxStarUse || use[b] >= maxStarUse || use[c] >= maxStarUse
                    || use[d] >= maxStarUse) {
                return false;
            }
            code(p, c);
            var cr = zr;
            var ci = zi;
            code(p, d);
            var dr = zr;
            var di = zi;
            var swapped = cr + dr > 1;
            if (swapped) {
                // swap A and B
                var t = a;
                a = b;
                b = t;
                cr = 1 - cr;
                ci = -ci;
                dr = 1 - dr;
                di = -di;
            }
            if (cr > dr) {
                // swap C and D
                var t = c;
                c = d;
                d = t;
                var tr = cr;
                cr = dr;
                dr = tr;
                var ti = ci;
                ci = di;
                di = ti;
            }
            var packed = QuadCode.quantizeX(cr)
                    | ((long) QuadCode.quantizeY(ci) << 16)
                    | ((long) QuadCode.quantizeX(dr) << 32)
                    | ((long) QuadCode.quantizeY(di) << 48);
            var dx = vx[a] - vx[b];
            var dy = vy[a] - vy[b];
            var dz = vz[a] - vz[b];
            var diam = (float) Sphere.chord2ToAngle(dx * dx + dy * dy + dz * dz);
            out.add(packed, ids[a], ids[b], ids[c], ids[d], diam, checkStars(p, a, b, c, d, swapped));
            pairUse[p]++;
            use[pairA[p]]++;
            use[pairB[p]]++;
            use[c]++;
            use[d]++;
            return true;
        }
        /**
         * Finds the two brightest stars which are close to a quad, without being part of it.
         *
         * @param swapped true if the canonical order of stars A and B is not the one of the pair
         * @return the coordinates of the stars in the frame of the quad, packed
         */
        private long checkStars(int p, int a, int b, int c, int d, boolean swapped) {
            var packed = 0L;
            var found = 0;
            for (var star = 0; star < candidateCount && found < 2; star++) {
                if (star == a || star == b || star == c || star == d || planes[p].dot(vx[star], vy[star], vz[star]) <= 0) {
                    continue;
                }
                code(p, star);
                var er = swapped ? 1 - zr : zr;
                var ei = swapped ? -zi : zi;
                // in the circle which is 1.5 times larger than the one of the quad
                if ((er - 0.5) * (er - 0.5) + ei * ei > 0.5625) {
                    continue;
                }
                packed |= ((QuadCode.quantizeCheck(er) & 0xFFFFL) | ((QuadCode.quantizeCheck(ei) & 0xFFFFL) << 16)) << (32 * found);
                found++;
            }
            for (; found < 2; found++) {
                packed |= (QuadCode.NO_CHECK & 0xFFFFL) << (32 * found);
            }
            return packed;
        }
    }

    /** Writes a file sequentially, with the possibility to move to another position. */
    private static final class Writer {
        private final FileChannel channel;
        private final ByteBuffer buffer = ByteBuffer.allocateDirect(1 << 20).order(ByteOrder.LITTLE_ENDIAN);
        /** The position of the start of the buffer in the file. */
        private long flushed;
        private long position;

        Writer(FileChannel channel) {
            this.channel = channel;
        }

        private void ensure(int bytes) throws IOException {
            if (buffer.remaining() < bytes) {
                flush();
            }
            position += bytes;
        }

        void flush() throws IOException {
            buffer.flip();
            while (buffer.hasRemaining()) {
                channel.write(buffer, flushed + buffer.position());
            }
            buffer.clear();
            flushed = position;
        }

        /** Continues writing at another position. */
        void seek(long offset) throws IOException {
            flush();
            flushed = offset;
            position = offset;
        }

        void putInt(int v) throws IOException {
            ensure(4);
            buffer.putInt(v);
        }

        void putLong(long v) throws IOException {
            ensure(8);
            buffer.putLong(v);
        }

        void putShort(short v) throws IOException {
            ensure(2);
            buffer.putShort(v);
        }

        void putFloat(float v) throws IOException {
            ensure(4);
            buffer.putFloat(v);
        }

        void putDouble(double v) throws IOException {
            ensure(8);
            buffer.putDouble(v);
        }

        void putBytes(byte[] bytes) throws IOException {
            ensure(bytes.length);
            buffer.put(bytes);
        }

        void padTo(long offset) throws IOException {
            if (offset < position) {
                throw new IllegalStateException("Section overlap at " + offset);
            }
            while (position < offset) {
                ensure(1);
                buffer.put((byte) 0);
            }
        }
    }
}
