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

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Decoding of 16-bit big-endian FITS pixels, binned 2x2, as {@link FitsImageLoader} does it: a
 * band of rows of a 61 megapixel image, on one thread.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class DecodeBenchmark {
    private static final ValueLayout.OfShort SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    static final int WIDTH = 9576;
    static final int ROWS = 64;

    private MemorySegment segment;
    private final short[] values = new short[WIDTH];
    private final float[] row = new float[WIDTH];
    private final float[] out = new float[WIDTH / 2 * ROWS / 2];

    @Setup
    public void setup() {
        var random = new Random(42);
        segment = Arena.ofAuto().allocate((long) WIDTH * ROWS * 2);
        for (long i = 0; i < (long) WIDTH * ROWS; i++) {
            segment.set(SHORT, 2 * i, (short) (random.nextInt(2000) - 32768 + 1000));
        }
    }

    /** What the loader did before: a bulk copy to shorts, a conversion to a float row, then the sum of the binned pixels. */
    @Benchmark
    public float[] current() {
        var w = WIDTH / 2;
        var rowBytes = (long) WIDTH * 2;
        java.util.Arrays.fill(out, 0);
        for (var y = 0; y < ROWS / 2; y++) {
            var base = y * w;
            for (var sy = 0; sy < 2; sy++) {
                MemorySegment.copy(segment, SHORT, ((long) y * 2 + sy) * rowBytes, values, 0, WIDTH);
                for (var i = 0; i < WIDTH; i++) {
                    row[i] = values[i];
                }
                for (var x = 0; x < w; x++) {
                    out[base + x] += row[2 * x] + row[2 * x + 1];
                }
            }
            for (var x = base; x < base + w; x++) {
                out[x] = out[x] * 1 + 32768 * 4;
            }
        }
        return out;
    }

    /** What the loader does now: the bulk copy to shorts, then the binned sum straight from the shorts. */
    @Benchmark
    public float[] copyThenBin() {
        var w = WIDTH / 2;
        var rowBytes = (long) WIDTH * 2;
        for (var y = 0; y < ROWS / 2; y++) {
            var base = y * w;
            MemorySegment.copy(segment, SHORT, (long) y * 2 * rowBytes, values, 0, WIDTH);
            for (var x = 0; x < w; x++) {
                out[base + x] = values[2 * x] + values[2 * x + 1];
            }
            MemorySegment.copy(segment, SHORT, ((long) y * 2 + 1) * rowBytes, values, 0, WIDTH);
            for (var x = 0; x < w; x++) {
                out[base + x] += values[2 * x] + values[2 * x + 1] + 32768 * 4;
            }
        }
        return out;
    }

    /** Pixels read one by one from the segment. */
    @Benchmark
    public float[] segmentGet() {
        var w = WIDTH / 2;
        var rowBytes = (long) WIDTH * 2;
        for (var y = 0; y < ROWS / 2; y++) {
            var base = y * w;
            var p0 = (long) y * 2 * rowBytes;
            var p1 = p0 + rowBytes;
            for (var x = 0; x < w; x++) {
                out[base + x] = segment.get(SHORT, p0 + 4L * x) + segment.get(SHORT, p0 + 4L * x + 2)
                        + segment.get(SHORT, p1 + 4L * x) + segment.get(SHORT, p1 + 4L * x + 2) + 32768 * 4;
            }
        }
        return out;
    }

    /** Four pixels at a time, as a little-endian long whose bytes are swapped by hand. */
    @Benchmark
    public float[] longSwap() {
        var w = WIDTH / 2;
        var rowBytes = (long) WIDTH * 2;
        for (var y = 0; y < ROWS / 2; y++) {
            var base = y * w;
            var p0 = (long) y * 2 * rowBytes;
            var p1 = p0 + rowBytes;
            var x = 0;
            for (; x + 2 <= w; x += 2) {
                var a = segment.get(LONG, p0 + 4L * x);
                var b = segment.get(LONG, p1 + 4L * x);
                out[base + x] = pixel(a, 0) + pixel(a, 1) + pixel(b, 0) + pixel(b, 1) + 32768 * 4;
                out[base + x + 1] = pixel(a, 2) + pixel(a, 3) + pixel(b, 2) + pixel(b, 3) + 32768 * 4;
            }
            for (; x < w; x++) {
                out[base + x] = segment.get(SHORT, p0 + 4L * x) + segment.get(SHORT, p0 + 4L * x + 2)
                        + segment.get(SHORT, p1 + 4L * x) + segment.get(SHORT, p1 + 4L * x + 2) + 32768 * 4;
            }
        }
        return out;
    }

    /** The k-th big-endian 16-bit pixel of 4 bytes read as a little-endian long. */
    private static float pixel(long packed, int k) {
        var v = (int) (packed >>> (16 * k)) & 0xFFFF;
        return (short) ((v << 8) | (v >>> 8));
    }
}
