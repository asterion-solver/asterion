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

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Decodes rows of pixels which are in memory, and adds them to a row of the image, binned: the
 * uncompressed images of FITS files, mapped from the file, and the pixels which programs give, see
 * {@link RawPixels}. Each type of sample has its own loops: the sum of the binned pixels is computed
 * straight from the decoded values, without a row of floats in between.
 */
abstract class RowDecoder {
    private static final ValueLayout.OfShort SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    /**
     * Decodes a row at a position of the memory, and adds its pixels, binned, to a row of the image.
     *
     * @param first true if the row of the image must be set rather than added to
     */
    abstract void accumulate(MemorySegment segment, long position, float[] out, int base, int binning, boolean first);

    /**
     * Decodes an image in parallel bands of rows, each with its own decoder.
     *
     * @param planes the number of planes, which are added: the channels of color images
     * @param planeSize the distance between the planes, in bytes
     * @param rowStride the distance between the rows, in bytes
     * @param scale the factor applied to the sums of the pixels
     * @param zero the value added to the binned pixels, after scaling
     * @return the pixels of the binned image, row after row
     */
    static float[] decode(MemorySegment segment, Supplier<RowDecoder> decoders, int width, int height, int planes, long planeSize,
                          long rowStride, int binning, float scale, float zero) {
        var w = width / binning;
        var h = height / binning;
        var out = new float[w * h];
        var bands = Math.clamp(h / 16, 1, 2 * Runtime.getRuntime().availableProcessors());
        IntStream.range(0, bands).parallel().forEach(b -> {
            var from = (int) ((long) h * b / bands);
            var to = (int) ((long) h * (b + 1) / bands);
            var decoder = decoders.get();
            for (var y = from; y < to; y++) {
                var base = y * w;
                var first = true;
                for (var p = 0; p < planes; p++) {
                    for (var sy = 0; sy < binning; sy++) {
                        decoder.accumulate(segment, p * planeSize + ((long) y * binning + sy) * rowStride, out, base, binning, first);
                        first = false;
                    }
                }
                if (scale != 1 || zero != 0) {
                    for (var x = base; x < base + w; x++) {
                        out[x] = out[x] * scale + zero;
                    }
                }
            }
        });
        return out;
    }

    /** The decoder of the big-endian samples of FITS files, by their BITPIX. */
    static RowDecoder fits(int bitpix, int width) {
        return switch (bitpix) {
            case 8 -> new Unsigned8(width);
            case 16 -> new Signed16(width);
            case 32 -> new Signed32(width);
            case -32 -> new Float32(width, FLOAT);
            case -64 -> new Float64(width);
            default -> throw new IllegalArgumentException("Unsupported BITPIX " + bitpix);
        };
    }

    /** The decoder of samples in the native byte order. */
    static RowDecoder raw(RawPixels.SampleType type, int width) {
        return switch (type) {
            case UINT8 -> new Unsigned8(width);
            case UINT16 -> new Unsigned16(width);
            case FLOAT32 -> new Float32(width, ValueLayout.JAVA_FLOAT_UNALIGNED);
        };
    }

    private static final class Unsigned8 extends RowDecoder {
        private final byte[] values;

        Unsigned8(int width) {
            values = new byte[width];
        }

        @Override
        void accumulate(MemorySegment segment, long position, float[] out, int base, int binning, boolean first) {
            var width = values.length;
            MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, position, values, 0, width);
            var w = width / binning;
            if (first) {
                Arrays.fill(out, base, base + w, 0);
            }
            if (binning == 1) {
                for (var x = 0; x < w; x++) {
                    out[base + x] += values[x] & 0xFF;
                }
            } else if (binning == 2) {
                for (var x = 0; x < w; x++) {
                    out[base + x] += (values[2 * x] & 0xFF) + (values[2 * x + 1] & 0xFF);
                }
            } else {
                for (var x = 0; x < w; x++) {
                    var sum = 0f;
                    for (var sx = x * binning; sx < (x + 1) * binning; sx++) {
                        sum += values[sx] & 0xFF;
                    }
                    out[base + x] += sum;
                }
            }
        }
    }

    private static final class Signed16 extends RowDecoder {
        private final short[] values;

        Signed16(int width) {
            values = new short[width];
        }

        @Override
        void accumulate(MemorySegment segment, long position, float[] out, int base, int binning, boolean first) {
            var width = values.length;
            MemorySegment.copy(segment, SHORT, position, values, 0, width);
            var w = width / binning;
            if (first) {
                Arrays.fill(out, base, base + w, 0);
            }
            if (binning == 1) {
                for (var x = 0; x < w; x++) {
                    out[base + x] += values[x];
                }
            } else if (binning == 2) {
                for (var x = 0; x < w; x++) {
                    out[base + x] += values[2 * x] + values[2 * x + 1];
                }
            } else {
                for (var x = 0; x < w; x++) {
                    var sum = 0f;
                    for (var sx = x * binning; sx < (x + 1) * binning; sx++) {
                        sum += values[sx];
                    }
                    out[base + x] += sum;
                }
            }
        }
    }

    /** Unsigned 16-bit samples, read as chars: the unsigned 16-bit type of Java. */
    private static final class Unsigned16 extends RowDecoder {
        private final char[] values;

        Unsigned16(int width) {
            values = new char[width];
        }

        @Override
        void accumulate(MemorySegment segment, long position, float[] out, int base, int binning, boolean first) {
            var width = values.length;
            MemorySegment.copy(segment, ValueLayout.JAVA_CHAR_UNALIGNED, position, values, 0, width);
            var w = width / binning;
            if (first) {
                Arrays.fill(out, base, base + w, 0);
            }
            if (binning == 1) {
                for (var x = 0; x < w; x++) {
                    out[base + x] += values[x];
                }
            } else if (binning == 2) {
                for (var x = 0; x < w; x++) {
                    out[base + x] += values[2 * x] + values[2 * x + 1];
                }
            } else {
                for (var x = 0; x < w; x++) {
                    var sum = 0f;
                    for (var sx = x * binning; sx < (x + 1) * binning; sx++) {
                        sum += values[sx];
                    }
                    out[base + x] += sum;
                }
            }
        }
    }

    private static final class Signed32 extends RowDecoder {
        private final int[] values;

        Signed32(int width) {
            values = new int[width];
        }

        @Override
        void accumulate(MemorySegment segment, long position, float[] out, int base, int binning, boolean first) {
            var width = values.length;
            MemorySegment.copy(segment, INT, position, values, 0, width);
            var w = width / binning;
            if (first) {
                Arrays.fill(out, base, base + w, 0);
            }
            if (binning == 1) {
                for (var x = 0; x < w; x++) {
                    out[base + x] += values[x];
                }
            } else if (binning == 2) {
                for (var x = 0; x < w; x++) {
                    out[base + x] += (float) values[2 * x] + values[2 * x + 1];
                }
            } else {
                for (var x = 0; x < w; x++) {
                    var sum = 0f;
                    for (var sx = x * binning; sx < (x + 1) * binning; sx++) {
                        sum += values[sx];
                    }
                    out[base + x] += sum;
                }
            }
        }
    }

    /** Floats, whose NaNs, the pixels without data, are 0. */
    private static final class Float32 extends RowDecoder {
        private final float[] values;
        private final ValueLayout.OfFloat layout;

        Float32(int width, ValueLayout.OfFloat layout) {
            values = new float[width];
            this.layout = layout;
        }

        @Override
        void accumulate(MemorySegment segment, long position, float[] out, int base, int binning, boolean first) {
            var width = values.length;
            MemorySegment.copy(segment, layout, position, values, 0, width);
            for (var i = 0; i < width; i++) {
                if (Float.isNaN(values[i])) {
                    values[i] = 0;
                }
            }
            var w = width / binning;
            if (first) {
                Arrays.fill(out, base, base + w, 0);
            }
            if (binning == 1) {
                for (var x = 0; x < w; x++) {
                    out[base + x] += values[x];
                }
            } else if (binning == 2) {
                for (var x = 0; x < w; x++) {
                    out[base + x] += values[2 * x] + values[2 * x + 1];
                }
            } else {
                for (var x = 0; x < w; x++) {
                    var sum = 0f;
                    for (var sx = x * binning; sx < (x + 1) * binning; sx++) {
                        sum += values[sx];
                    }
                    out[base + x] += sum;
                }
            }
        }
    }

    private static final class Float64 extends RowDecoder {
        private final double[] values;

        Float64(int width) {
            values = new double[width];
        }

        @Override
        void accumulate(MemorySegment segment, long position, float[] out, int base, int binning, boolean first) {
            var width = values.length;
            MemorySegment.copy(segment, DOUBLE, position, values, 0, width);
            var w = width / binning;
            if (first) {
                Arrays.fill(out, base, base + w, 0);
            }
            for (var x = 0; x < w; x++) {
                var sum = 0f;
                for (var sx = x * binning; sx < (x + 1) * binning; sx++) {
                    var v = values[sx];
                    sum += Double.isNaN(v) ? 0 : (float) v;
                }
                out[base + x] += sum;
            }
        }
    }
}
