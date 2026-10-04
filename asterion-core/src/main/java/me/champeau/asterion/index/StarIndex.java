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

import me.champeau.asterion.math.Sphere;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A memory-mapped index file. An index is made of:
 * <ul>
 *     <li>a star table, organized as a pyramid: level 0 holds the few brightest stars of each cell
 *     of a coarse grid, and each following level holds the next brightest stars of the cells of a grid
 *     which is √2 finer. The stars of levels 0 to n therefore sample the sky uniformly, with a density
 *     which doubles at each level. A last level stores the stars which didn't fit in the pyramid.</li>
 *     <li>a table of quads of stars, stored in a 4-dimensional hash grid of their geometric code,
 *     each quad coming with two check stars which make it possible to reject false matches quickly.</li>
 * </ul>
 * Opening an index is immediate, whatever its size: pages are loaded by the operating system when
 * they are first accessed. Instances are thread-safe.
 */
public final class StarIndex {
    static final int MAGIC = 0x58495342;
    /** The version of the format of index files. */
    public static final int VERSION = 1;
    static final int QUAD_AUX_SIZE = 12;
    static final int HEADER_SIZE = 256;
    static final int LEVEL_ENTRY_SIZE = 32;
    static final int STAR_SIZE = 16;
    static final int NAME_OFFSET = 128;
    static final int NAME_SIZE = 64;
    static final double RA_UNIT = Sphere.TWO_PI / 4294967296.0;
    static final double DEC_UNIT = Sphere.HALF_PI / 2147483647.0;
    /** Proper motions are stored in units of 0.25 mas/year. */
    static final double PM_UNIT_MAS = 0.25;
    private static final double PM_UNIT = PM_UNIT_MAS * Sphere.ARCSEC / 1000;

    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfShort SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final Path path;
    private final MemorySegment data;
    private final String name;
    private final int starCount;
    private final int quadCount;
    private final int levelCount;
    private final int codeBins;
    private final double epoch;
    private final double minQuadDiam;
    private final double maxQuadDiam;
    private final float magLimit;
    private final long starsOffset;
    private final long quadCellStartOffset;
    private final long quadCodesOffset;
    private final long quadStarsOffset;
    private final long quadAuxOffset;
    private final RingGrid[] grids;
    private final int[] levelFirstStar;
    private final long[] levelCellStartOffset;

    private StarIndex(Path path, MemorySegment data) throws IOException {
        this.path = path;
        this.data = data;
        if (data.byteSize() < HEADER_SIZE || data.get(INT, 0) != MAGIC) {
            throw new IOException(path + " is not a star index file");
        }
        var version = data.get(INT, 4);
        if (version != VERSION) {
            throw new IOException(
                    "Unsupported index version " + version + " in " + path + ", please rebuild or download the catalog again");
        }
        this.starCount = Math.toIntExact(data.get(LONG, 8));
        this.quadCount = Math.toIntExact(data.get(LONG, 16));
        this.levelCount = data.get(INT, 24);
        this.codeBins = data.get(INT, 32);
        this.epoch = data.get(DOUBLE, 40);
        this.minQuadDiam = data.get(DOUBLE, 48);
        this.maxQuadDiam = data.get(DOUBLE, 56);
        this.starsOffset = data.get(LONG, 64);
        var levelTableOffset = data.get(LONG, 72);
        this.quadCellStartOffset = data.get(LONG, 80);
        this.quadCodesOffset = data.get(LONG, 88);
        this.quadStarsOffset = data.get(LONG, 96);
        this.quadAuxOffset = data.get(LONG, 104);
        this.magLimit = data.get(FLOAT, 112);
        var nameBytes = data.asSlice(NAME_OFFSET, NAME_SIZE).toArray(ValueLayout.JAVA_BYTE);
        var len = 0;
        while (len < nameBytes.length && nameBytes[len] != 0) {
            len++;
        }
        this.name = new String(nameBytes, 0, len, StandardCharsets.UTF_8);
        this.grids = new RingGrid[levelCount];
        this.levelFirstStar = new int[levelCount + 1];
        this.levelCellStartOffset = new long[levelCount];
        for (var i = 0; i < levelCount; i++) {
            var entry = levelTableOffset + (long) i * LEVEL_ENTRY_SIZE;
            grids[i] = new RingGrid(data.get(DOUBLE, entry));
            levelFirstStar[i] = Math.toIntExact(data.get(LONG, entry + 8));
            levelCellStartOffset[i] = data.get(LONG, entry + 16);
            if (grids[i].cellCount() != data.get(INT, entry + 24)) {
                throw new IOException("Corrupt index file " + path);
            }
        }
        levelFirstStar[levelCount] = starCount;
        var expectedSize = quadAuxOffset + (long) QUAD_AUX_SIZE * quadCount;
        if (data.byteSize() < expectedSize) {
            throw new IOException("Truncated index file " + path + ", please rebuild or download the catalog again");
        }
    }

    public static StarIndex open(Path path) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
            var segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), Arena.ofAuto());
            return new StarIndex(path, segment);
        }
    }

    public Path path() {
        return path;
    }

    public String name() {
        return name;
    }

    public int starCount() {
        return starCount;
    }

    public int quadCount() {
        return quadCount;
    }

    /** The total number of star levels, including the last one which is not part of the pyramid. */
    public int levelCount() {
        return levelCount;
    }

    public int codeBins() {
        return codeBins;
    }

    /** The epoch of star positions, as a Julian year. */
    public double epoch() {
        return epoch;
    }

    /** The angular diameter of the smallest quads of this index, in radians. */
    public double minQuadDiam() {
        return minQuadDiam;
    }

    /** The angular diameter of the largest quads of this index, in radians. */
    public double maxQuadDiam() {
        return maxQuadDiam;
    }

    /** The number of stars in levels 0 to the given one, inclusive. */
    public int cumulativeStars(int level) {
        return levelFirstStar[level + 1];
    }

    public RingGrid grid(int level) {
        return grids[level];
    }

    /**
     * Finds the stars of a level which may be in a cone. The search is conservative: stars
     * outside of the cone are returned too.
     *
     * @param out receives pairs of (first star, last star exclusive), must be sized according to
     * {@link RingGrid#maxConeRangeInts(double)}
     * @return the number of ints written to the output array
     */
    public int coneStarRanges(int level, double ra, double dec, double radius, int[] out) {
        var n = grids[level].coneRanges(ra, dec, radius, out);
        var base = levelCellStartOffset[level];
        for (var i = 0; i < n; i++) {
            out[i] = data.get(INT, base + 4L * out[i]);
        }
        return n;
    }

    /**
     * Computes the unit vector of a star, at a given date.
     *
     * @param years the number of years elapsed since the epoch of the index
     */
    public void starVector(int star, double years, double[] out) {
        var offset = starsOffset + (long) star * STAR_SIZE;
        var ra = (data.get(INT, offset) & 0xFFFFFFFFL) * RA_UNIT;
        var dec = data.get(INT, offset + 4) * DEC_UNIT;
        var cd = Math.cos(dec);
        var pmRa = data.get(SHORT, offset + 8);
        var pmDec = data.get(SHORT, offset + 10);
        if ((pmRa | pmDec) != 0 && years != 0) {
            dec += pmDec * PM_UNIT * years;
            ra += pmRa * PM_UNIT * years / Math.max(cd, 1e-6);
            cd = Math.cos(dec);
        }
        out[0] = cd * Math.cos(ra);
        out[1] = cd * Math.sin(ra);
        out[2] = Math.sin(dec);
    }

    /** The index of the first quad of a cell of the code hash grid. */
    public int quadCellStart(int cell) {
        return data.get(INT, quadCellStartOffset + 4L * cell);
    }

    /** The 4 quantized coordinates of the code of a quad, packed as 16-bit fields (cx is the lowest). */
    public long quadCode(int quad) {
        return data.get(LONG, quadCodesOffset + 8L * quad);
    }

    public int quadStar(int quad, int i) {
        return data.get(INT, quadStarsOffset + 16L * quad + 4L * i);
    }

    /** The angular distance between stars A and B of a quad, in radians. */
    public float quadDiam(int quad) {
        return data.get(FLOAT, quadAuxOffset + (long) QUAD_AUX_SIZE * quad);
    }

    /**
     * The check stars of a quad: two bright stars which are close to the quad, and are therefore
     * expected in any image which contains it. Their coordinates in the frame of the quad, where A
     * is at (0, 0) and B at (1, 0), are packed as 16-bit fields (x1, y1, x2, y2), in units of
     * 1 / {@link QuadCode#CHECK_SCALE}. A missing star has an abscissa of {@link QuadCode#NO_CHECK}.
     */
    public long quadChecks(int quad) {
        return data.get(LONG, quadAuxOffset + (long) QUAD_AUX_SIZE * quad + 4);
    }

    @Override
    public String toString() {
        return "%s (%,d stars to magnitude %.1f, %,d quads from %.1f' to %.1f°)".formatted(
                name, starCount, magLimit, quadCount, Math.toDegrees(minQuadDiam) * 60, Math.toDegrees(maxQuadDiam));
    }
}
