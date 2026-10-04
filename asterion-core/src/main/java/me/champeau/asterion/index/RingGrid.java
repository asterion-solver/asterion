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

/**
 * A partition of the celestial sphere into cells of roughly equal area: the sphere is cut into
 * rings of constant declination height, and each ring is split into a number of cells proportional
 * to its circumference. Cells are numbered south to north, then by increasing right ascension, so a
 * cone search translates into a small number of contiguous cell ranges.
 */
public final class RingGrid {
    private final double cellSize;
    private final int nRings;
    private final double ringHeight;
    private final int[] ringStart;

    /**
     * @param cellSize the requested side of a cell, in radians
     */
    public RingGrid(double cellSize) {
        this.cellSize = cellSize;
        this.nRings = Math.max(1, (int) Math.round(Math.PI / cellSize));
        this.ringHeight = Math.PI / nRings;
        this.ringStart = new int[nRings + 1];
        for (var r = 0; r < nRings; r++) {
            var decMid = -Sphere.HALF_PI + (r + 0.5) * ringHeight;
            var n = Math.max(1, (int) Math.ceil(Sphere.TWO_PI * Math.cos(decMid) / ringHeight - 1e-9));
            ringStart[r + 1] = ringStart[r] + n;
        }
    }

    /** The cell size which was requested when creating the grid, in radians. */
    public double cellSize() {
        return cellSize;
    }

    public int cellCount() {
        return ringStart[nRings];
    }

    /** An upper bound of the angular distance between the center of a cell and any of its points. */
    public double maxCellRadius() {
        return 0.8 * ringHeight;
    }

    private int ringOf(double dec) {
        var r = (int) ((dec + Sphere.HALF_PI) / ringHeight);
        return r < 0 ? 0 : Math.min(r, nRings - 1);
    }

    public int cellOf(double ra, double dec) {
        var r = ringOf(dec);
        var n = ringStart[r + 1] - ringStart[r];
        var c = (int) (Sphere.normalizeRa(ra) / Sphere.TWO_PI * n);
        return ringStart[r] + Math.min(c, n - 1);
    }

    /** Writes the (ra, dec) of the center of a cell into the output array. */
    public void cellCenter(int cell, double[] raDec) {
        var lo = 0;
        var hi = nRings - 1;
        while (lo < hi) {
            var mid = (lo + hi + 1) >>> 1;
            if (ringStart[mid] <= cell) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        var n = ringStart[lo + 1] - ringStart[lo];
        raDec[0] = (cell - ringStart[lo] + 0.5) * Sphere.TWO_PI / n;
        raDec[1] = -Sphere.HALF_PI + (lo + 0.5) * ringHeight;
    }

    /** The size required for the output array of {@link #coneRanges} for a given radius. */
    public int maxConeRangeInts(double radius) {
        var rings = Math.min(nRings, (int) (2 * radius / ringHeight) + 2);
        return 4 * rings;
    }

    /**
     * Computes a conservative cover of a cone as cell ranges.
     *
     * @param out receives pairs of (first cell, last cell exclusive)
     * @return the number of ints written to the output array
     */
    public int coneRanges(double ra, double dec, double radius, int[] out) {
        var decLo = dec - radius;
        var decHi = dec + radius;
        var r0 = ringOf(decLo);
        var r1 = ringOf(decHi);
        double dAlpha;
        if (decHi >= Sphere.HALF_PI || decLo <= -Sphere.HALF_PI) {
            dAlpha = Math.PI;
        } else {
            var s = Math.sin(radius) / Math.cos(dec);
            dAlpha = s >= 1 ? Math.PI : Math.asin(s);
        }
        ra = Sphere.normalizeRa(ra);
        var k = 0;
        for (var r = r0; r <= r1; r++) {
            var base = ringStart[r];
            var n = ringStart[r + 1] - base;
            var c0 = (long) Math.floor((ra - dAlpha) / Sphere.TWO_PI * n);
            var c1 = (long) Math.floor((ra + dAlpha) / Sphere.TWO_PI * n);
            if (c1 - c0 + 1 >= n) {
                out[k++] = base;
                out[k++] = base + n;
                continue;
            }
            var m0 = (int) Math.floorMod(c0, (long) n);
            var m1 = (int) Math.floorMod(c1, (long) n);
            if (m0 <= m1) {
                out[k++] = base + m0;
                out[k++] = base + m1 + 1;
            } else {
                out[k++] = base;
                out[k++] = base + m1 + 1;
                out[k++] = base + m0;
                out[k++] = base + n;
            }
        }
        return k;
    }
}
