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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * The quads of an index which are wide enough for the search of wide fields, in their own hash
 * grid: a few hundred thousand of the tens of millions of quads of a large index. The grid of the
 * index spreads its quads over millions of cells, and a lookup visits dozens of them, each a
 * random access to a table of hundreds of megabytes; the quads of wide fields are so few that
 * their table fits in the cache of the processor, where lookups cost next to nothing. The table is
 * built by a scan of the index, which takes a second, and kept in a file next to it, so that the
 * scan happens once.
 */
public final class WideQuads {
    /** The smallest quads of the table: the widest fields are searched with quads of several degrees. */
    public static final double MIN_DIAM = Math.toRadians(1);
    static final int MAGIC = 0x45444957;
    static final int VERSION = 1;
    private static final int HEADER_SIZE = 48;
    /** The suffix of the file which keeps the table, next to the index file. */
    public static final String SUFFIX = ".wide";

    private final int bins;
    private final int[] cellStart;
    private final int[] ids;
    private final long[] codes;
    private final float[] diams;

    private WideQuads(int bins, int[] cellStart, int[] ids, long[] codes, float[] diams) {
        this.bins = bins;
        this.cellStart = cellStart;
        this.ids = ids;
        this.codes = codes;
        this.diams = diams;
    }

    /** The number of quads of the table. */
    public int count() {
        return ids.length;
    }

    /** The number of bins of each axis of the hash grid of the codes. */
    public int bins() {
        return bins;
    }

    /** The first quad of a cell of the grid, see {@link QuadCode#cell}: the quads of the cell are the ones up to the start of the next cell. */
    public int cellStart(int cell) {
        return cellStart[cell];
    }

    /** The quad in the index. */
    public int id(int quad) {
        return ids[quad];
    }

    /** The code of the quad, see {@link StarIndex#quadCode}. */
    public long code(int quad) {
        return codes[quad];
    }

    /** The diameter of the quad, in radians. */
    public float diam(int quad) {
        return diams[quad];
    }

    /**
     * The table of an index: the one kept next to the index file when it's there and up to date,
     * otherwise it is built by a scan of the index, and kept for the next time when the directory
     * of the index can be written.
     */
    public static WideQuads of(StarIndex index) {
        var path = index.path();
        var sidecar = path.resolveSibling(path.getFileName() + SUFFIX);
        try {
            var loaded = load(sidecar, index);
            if (loaded != null) {
                return loaded;
            }
        } catch (IOException e) {
            // a damaged file: built again below
        }
        var built = build(index);
        try {
            built.save(sidecar, index);
        } catch (IOException e) {
            // read-only installations build the table every time
        }
        return built;
    }

    /** Builds the table by a scan of the quads of the index. */
    static WideQuads build(StarIndex index) {
        var n = index.quadCount();
        var threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        var chunks = Math.min(n, 64 * threads);
        var collected = IntStream.range(0, chunks).parallel().mapToObj(c -> {
            var from = (int) ((long) n * c / chunks);
            var to = (int) ((long) n * (c + 1) / chunks);
            var found = new ArrayList<Integer>();
            for (var q = from; q < to; q++) {
                if (index.quadDiam(q) >= MIN_DIAM) {
                    found.add(q);
                }
            }
            return found;
        }).toList();
        var count = collected.stream().mapToInt(ArrayList::size).sum();
        var unsorted = new int[count];
        var k = 0;
        for (var found : collected) {
            for (var q : found) {
                unsorted[k++] = q;
            }
        }
        return of(index, unsorted);
    }

    /** Lays the quads out in a hash grid of their codes, with a few quads per cell. */
    private static WideQuads of(StarIndex index, int[] unsorted) {
        var count = unsorted.length;
        var bins = Math.clamp((int) Math.pow(count / 4.0, 0.25), 2, 64);
        var cells = bins * bins * bins * bins;
        var cellStart = new int[cells + 1];
        var cell = new int[count];
        var unsortedCodes = new long[count];
        for (var i = 0; i < count; i++) {
            var code = index.quadCode(unsorted[i]);
            unsortedCodes[i] = code;
            cell[i] = cellOf(code, bins);
            cellStart[cell[i] + 1]++;
        }
        for (var c = 0; c < cells; c++) {
            cellStart[c + 1] += cellStart[c];
        }
        var cursor = Arrays.copyOf(cellStart, cells);
        var ids = new int[count];
        var codes = new long[count];
        var diams = new float[count];
        for (var i = 0; i < count; i++) {
            var pos = cursor[cell[i]]++;
            ids[pos] = unsorted[i];
            codes[pos] = unsortedCodes[i];
            diams[pos] = index.quadDiam(unsorted[i]);
        }
        return new WideQuads(bins, cellStart, ids, codes, diams);
    }

    /** The cell of a code, see {@link StarIndex#quadCode} for its layout. */
    public static int cellOf(long code, int bins) {
        return QuadCode.cell((int) (code & 0xFFFF), (int) ((code >>> 16) & 0xFFFF), (int) ((code >>> 32) & 0xFFFF), (int) (code >>> 48),
                bins);
    }

    /**
     * Reads the table from its file.
     *
     * @return null if the file isn't there, or was built from another version of the index
     */
    private static WideQuads load(Path sidecar, StarIndex index) throws IOException {
        if (!Files.isRegularFile(sidecar)) {
            return null;
        }
        try (var channel = FileChannel.open(sidecar, StandardOpenOption.READ)) {
            var size = channel.size();
            if (size < HEADER_SIZE) {
                return null;
            }
            var header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            channel.read(header, 0);
            header.flip();
            if (header.getInt() != MAGIC || header.getInt() != VERSION) {
                return null;
            }
            var indexSize = header.getLong();
            var indexTime = header.getLong();
            var quadCount = header.getInt();
            var bins = header.getInt();
            var count = header.getInt();
            var minDiam = header.getDouble();
            if (indexSize != Files.size(index.path()) || indexTime != Files.getLastModifiedTime(index.path()).toMillis()
                    || quadCount != index.quadCount() || minDiam != MIN_DIAM) {
                return null;
            }
            var cells = bins * bins * bins * bins;
            var expected = HEADER_SIZE + 4L * (cells + 1) + 16L * count;
            if (size != expected) {
                return null;
            }
            var body = ByteBuffer.allocate((int) (size - HEADER_SIZE)).order(ByteOrder.LITTLE_ENDIAN);
            channel.read(body, HEADER_SIZE);
            body.flip();
            var cellStart = new int[cells + 1];
            body.asIntBuffer().get(cellStart);
            body.position(body.position() + 4 * cellStart.length);
            var ids = new int[count];
            body.asIntBuffer().get(ids);
            body.position(body.position() + 4 * count);
            var codes = new long[count];
            body.asLongBuffer().get(codes);
            body.position(body.position() + 8 * count);
            var diams = new float[count];
            body.asFloatBuffer().get(diams);
            return new WideQuads(bins, cellStart, ids, codes, diams);
        }
    }

    /** Writes the table next to the index, through a temporary file so that a reader never sees a partial one. */
    private void save(Path sidecar, StarIndex index) throws IOException {
        var count = ids.length;
        var buffer = ByteBuffer.allocate((int) (HEADER_SIZE + 4L * cellStart.length + 16L * count)).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(MAGIC);
        buffer.putInt(VERSION);
        buffer.putLong(Files.size(index.path()));
        buffer.putLong(Files.getLastModifiedTime(index.path()).toMillis());
        buffer.putInt(index.quadCount());
        buffer.putInt(bins);
        buffer.putInt(count);
        buffer.putDouble(MIN_DIAM);
        buffer.position(HEADER_SIZE);
        buffer.asIntBuffer().put(cellStart);
        buffer.position(buffer.position() + 4 * cellStart.length);
        buffer.asIntBuffer().put(ids);
        buffer.position(buffer.position() + 4 * count);
        buffer.asLongBuffer().put(codes);
        buffer.position(buffer.position() + 8 * count);
        buffer.asFloatBuffer().put(diams);
        buffer.position(buffer.position() + 4 * count);
        buffer.flip();
        var temporary = Files.createTempFile(sidecar.getParent(), sidecar.getFileName().toString(), ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
            }
            try {
                // temporary files are private: the table is as readable as the index
                Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(index.path()));
            } catch (UnsupportedOperationException | IOException e) {
                // not a POSIX file system, or not allowed: the file stays private
            }
            Files.move(temporary, sidecar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
