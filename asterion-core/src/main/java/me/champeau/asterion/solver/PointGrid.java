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

import java.util.Arrays;

/** A grid of points of an image, to find quickly the point which is the closest to a position. */
final class PointGrid {
    private final double[] x;
    private final double[] y;
    private final double cellSize;
    private final int gw;
    private final int gh;
    private final int[] cellStart;
    private final int[] points;
    /** The squared distance of the last point which was found. */
    double distance2;

    PointGrid(double[] x, double[] y, int n, int width, int height) {
        this.x = x;
        this.y = y;
        this.cellSize = Math.max(8, Math.sqrt((double) width * height / Math.max(1, n)));
        this.gw = (int) (width / cellSize) + 1;
        this.gh = (int) (height / cellSize) + 1;
        this.cellStart = new int[gw * gh + 1];
        var cell = new int[n];
        for (var i = 0; i < n; i++) {
            cell[i] = cellOf(x[i], y[i]);
            cellStart[cell[i] + 1]++;
        }
        for (var c = 0; c < gw * gh; c++) {
            cellStart[c + 1] += cellStart[c];
        }
        this.points = new int[n];
        var cursor = Arrays.copyOf(cellStart, gw * gh);
        for (var i = 0; i < n; i++) {
            points[cursor[cell[i]]++] = i;
        }
    }

    private int clampX(double v) {
        var c = (int) Math.floor(v / cellSize);
        return c < 0 ? 0 : Math.min(c, gw - 1);
    }

    private int clampY(double v) {
        var c = (int) Math.floor(v / cellSize);
        return c < 0 ? 0 : Math.min(c, gh - 1);
    }

    private int cellOf(double px, double py) {
        return clampY(py) * gw + clampX(px);
    }

    /**
     * Finds the closest point within a radius.
     *
     * @return the index of the point, or -1 if there's none
     */
    int nearest(double px, double py, double radius) {
        var x0 = clampX(px - radius);
        var x1 = clampX(px + radius);
        var y0 = clampY(py - radius);
        var y1 = clampY(py + radius);
        var best = radius * radius;
        var found = -1;
        for (var cy = y0; cy <= y1; cy++) {
            var from = cellStart[cy * gw + x0];
            var to = cellStart[cy * gw + x1 + 1];
            for (var k = from; k < to; k++) {
                var i = points[k];
                var dx = x[i] - px;
                var dy = y[i] - py;
                var d2 = dx * dx + dy * dy;
                if (d2 <= best) {
                    best = d2;
                    found = i;
                }
            }
        }
        distance2 = best;
        return found;
    }
}
