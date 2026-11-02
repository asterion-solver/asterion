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

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RawPixelsTest {
    private static final int WIDTH = 41;
    private static final int HEIGHT = 34;

    /** The value of a pixel: a gradient, with values beyond 8 bits for 16-bit samples. */
    private static int value(int x, int y, int max) {
        return (37 * x + 101 * y * y + 7) % max;
    }

    /** Pixels in memory, with rows padded to a stride. */
    private static MemorySegment pixels(Arena arena, RawPixels.SampleType type, long stride) {
        var segment = arena.allocate(stride * HEIGHT);
        for (var y = 0; y < HEIGHT; y++) {
            for (var x = 0; x < WIDTH; x++) {
                var offset = y * stride + (long) x * type.bytes();
                switch (type) {
                    case UINT8 -> segment.set(ValueLayout.JAVA_BYTE, offset, (byte) value(x, y, 256));
                    case UINT16 -> segment.set(ValueLayout.JAVA_SHORT_UNALIGNED, offset, (short) value(x, y, 65536));
                    case FLOAT32 -> segment.set(ValueLayout.JAVA_FLOAT_UNALIGNED, offset, value(x, y, 65536) + 0.25f);
                }
            }
        }
        return segment;
    }

    /** The image the pixels give, added row by row, as decoders do. */
    private static GrayImage expected(RawPixels.SampleType type, int binning) {
        var binner = new GrayBinner(WIDTH, HEIGHT, binning);
        for (var y = 0; y < HEIGHT; y++) {
            var row = new float[WIDTH];
            for (var x = 0; x < WIDTH; x++) {
                row[x] = switch (type) {
                    case UINT8 -> value(x, y, 256);
                    case UINT16 -> value(x, y, 65536);
                    case FLOAT32 -> value(x, y, 65536) + 0.25f;
                };
            }
            binner.row(row);
        }
        return binner.image();
    }

    @Test
    void copiesTheSamplesOfEachType() {
        try (var arena = Arena.ofShared()) {
            for (var type : RawPixels.SampleType.values()) {
                // rows padded to 8 bytes, the way the buffers of cameras often are
                var stride = (WIDTH * type.bytes() + 7) / 8 * 8;
                var image = RawPixels.read(pixels(arena, type, stride), type, WIDTH, HEIGHT, stride, false, 0);
                var expected = expected(type, 1);
                assertEquals(WIDTH, image.width(), type.name());
                assertEquals(HEIGHT, image.height(), type.name());
                assertArrayEquals(expected.data(), image.data(), type.name());
            }
        }
    }

    @Test
    void binsTheRawFramesOfColorSensors() {
        try (var arena = Arena.ofShared()) {
            var type = RawPixels.SampleType.UINT16;
            var stride = WIDTH * type.bytes();
            var image = RawPixels.read(pixels(arena, type, stride), type, WIDTH, HEIGHT, stride, true, 0);
            assertEquals(2, image.binning());
            assertEquals(WIDTH / 2, image.width());
            assertEquals(HEIGHT / 2, image.height());
            assertEquals(WIDTH, image.sourceWidth());
            assertArrayEquals(expected(type, 2).data(), image.data());
            // an explicit binning wins
            assertEquals(1, RawPixels.read(pixels(arena, type, stride), type, WIDTH, HEIGHT, stride, true, 1).binning());
        }
    }

    @Test
    void rejectsBuffersWhichDontMatchTheImage() {
        try (var arena = Arena.ofShared()) {
            var type = RawPixels.SampleType.UINT16;
            var segment = arena.allocate((long) WIDTH * HEIGHT * type.bytes());
            assertThrows(IllegalArgumentException.class, () -> RawPixels.read(segment, type, WIDTH, HEIGHT, WIDTH, false, 0));
            assertThrows(IllegalArgumentException.class, () -> RawPixels.read(segment, type, WIDTH, HEIGHT + 1, WIDTH * 2L, false, 0));
            assertThrows(IllegalArgumentException.class, () -> RawPixels.read(segment, type, 0, HEIGHT, WIDTH * 2L, false, 0));
        }
    }
}
