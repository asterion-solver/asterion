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

/**
 * Geometric hash code of a quad of stars (A, B, C, D), where A and B are the most distant stars.
 * In the frame where A is at (0, 0) and B at (1, 0), the code is made of the coordinates of C and D:
 * (cx, cy, dx, dy). It is invariant to translation, rotation and scaling of the quad. C and D lie in
 * the circle of diameter AB, so cx and dx are in [0, 1] and cy, dy in [-0.5, 0.5].
 * <p>
 * The symmetries of the quad are removed by ordering stars so that {@code cx <= dx} and
 * {@code cx + dx <= 1}.
 */
public final class QuadCode {
    /** Codes are stored as unsigned 16-bit integers. */
    public static final int QUANTUM = 65535;

    /**
     * Check stars are stars which are close to a quad, without being part of it. Their coordinates
     * in the frame of the quad are stored as signed 16-bit integers, in units of 1 / CHECK_SCALE.
     */
    public static final double CHECK_SCALE = 8192;
    /** Marks the absence of a check star. */
    public static final short NO_CHECK = Short.MIN_VALUE;

    private QuadCode() {
    }

    /** Quantizes an abscissa of the code, which lies in [0, 1]. */
    public static int quantizeX(double v) {
        return clamp((int) Math.round(v * QUANTUM));
    }

    /** Quantizes an ordinate of the code, which lies in [-0.5, 0.5]. */
    public static int quantizeY(double v) {
        return clamp((int) Math.round((v + 0.5) * QUANTUM));
    }

    /** Quantizes a coordinate of a check star. */
    public static short quantizeCheck(double v) {
        return (short) Math.clamp(Math.round(v * CHECK_SCALE), -32767, 32767);
    }

    private static int clamp(int q) {
        return q < 0 ? 0 : Math.min(q, QUANTUM);
    }

    /** The bin of a quantized coordinate, when the code space is divided in bins along each axis. */
    public static int bin(int quantized, int bins) {
        return Math.min(bins - 1, quantized * bins / (QUANTUM + 1));
    }

    public static int cell(int cx, int cy, int dx, int dy, int bins) {
        return ((bin(cx, bins) * bins + bin(cy, bins)) * bins + bin(dx, bins)) * bins + bin(dy, bins);
    }
}
