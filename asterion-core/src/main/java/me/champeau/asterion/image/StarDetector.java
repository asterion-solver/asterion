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

import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * Extracts stars from an image. The background and its noise are estimated on a grid of tiles,
 * then stars are searched as local maxima of the background subtracted image, smoothed by a small
 * gaussian kernel. Each star is finally measured with an iterative, gaussian weighted centroid.
 */
public final class StarDetector {
    private static final float K0 = 1 / 16f;
    private static final float K1 = 4 / 16f;
    private static final float K2 = 6 / 16f;
    /** Noise reduction factor of the smoothing kernel. */
    private static final double SMOOTHING_NOISE = 70.0 / 256;
    private static final int MAX_DEDUPE_RADIUS = 32;

    private final int w;
    private final int h;
    private final float[] data;
    private final DetectionOptions options;
    private final int tile;
    private final int tilesX;
    private final int tilesY;
    private float[] background;
    private float[] sigma;
    private float[] sub;

    /** True if the pixels of the image may be overwritten, which saves a copy of the image. */
    private final boolean inPlace;

    private StarDetector(GrayImage image, DetectionOptions options, boolean inPlace) {
        this.inPlace = inPlace;
        this.w = image.width();
        this.h = image.height();
        this.data = image.data();
        this.options = options;
        this.tile = Math.max(8, Math.min(options.tileSize(), Math.min(w, h) / 4));
        this.tilesX = Math.max(1, w / tile);
        this.tilesY = Math.max(1, h / tile);
    }

    public static StarList detect(GrayImage image) {
        return detect(image, DetectionOptions.defaults());
    }

    public static StarList detect(GrayImage image, DetectionOptions options) {
        if (image.width() < 16 || image.height() < 16) {
            return StarList.empty();
        }
        return new StarDetector(image, options, false).run(image.binning());
    }

    /**
     * Detects stars, using the pixels of the image as working memory: the image is modified. This
     * is for images which are not used once stars are detected.
     */
    public static StarList detectInPlace(GrayImage image, DetectionOptions options) {
        if (image.width() < 16 || image.height() < 16) {
            return StarList.empty();
        }
        return new StarDetector(image, options, true).run(image.binning());
    }

    private StarList run(int binning) {
        estimateBackground();
        subtractBackground();
        var peaks = findPeaks();
        return measure(peaks, binning);
    }

    private static void parallelRows(int rows, RowBandTask task) {
        parallelRows(rows, 32, task);
    }

    private static void parallelRows(int rows, int minRowsPerBand, RowBandTask task) {
        var bands = Math.clamp(rows / minRowsPerBand, 1, 4 * Runtime.getRuntime().availableProcessors());
        IntStream.range(0, bands).parallel().forEach(b -> {
            var from = (int) ((long) rows * b / bands);
            var to = (int) ((long) rows * (b + 1) / bands);
            task.run(from, to);
        });
    }

    @FunctionalInterface
    private interface RowBandTask {
        void run(int fromRow, int toRow);
    }

    private void estimateBackground() {
        var bg = new float[tilesX * tilesY];
        var sg = new float[tilesX * tilesY];
        parallelRows(tilesY, 1, (from, to) -> {
            var buf = new float[4 * tile * tile];
            var dev = new float[buf.length];
            for (var ty = from; ty < to; ty++) {
                var y0 = ty * tile;
                var y1 = ty == tilesY - 1 ? h : y0 + tile;
                for (var tx = 0; tx < tilesX; tx++) {
                    var x0 = tx * tile;
                    var x1 = tx == tilesX - 1 ? w : x0 + tile;
                    // about 500 samples are enough to estimate the background of a tile
                    var stride = Math.max(1, (int) Math.round(Math.sqrt((x1 - x0) * (y1 - y0) / 500.0)));
                    var n = 0;
                    for (var y = y0; y < y1; y += stride) {
                        var row = y * w;
                        for (var x = x0; x < x1; x += stride) {
                            buf[n++] = data[row + x];
                        }
                    }
                    var med = select(buf, n, n / 2);
                    var sig = 1.4826f * mad(buf, dev, n, med);
                    // reject stars and estimate again
                    var limit = 3 * sig;
                    var m = 0;
                    for (var i = 0; i < n; i++) {
                        if (Math.abs(buf[i] - med) <= limit) {
                            buf[m++] = buf[i];
                        }
                    }
                    if (m > 8) {
                        med = select(buf, m, m / 2);
                        sig = 1.4826f * mad(buf, dev, m, med);
                    }
                    bg[ty * tilesX + tx] = med;
                    sg[ty * tilesX + tx] = sig;
                }
            }
        });
        background = medianFilter(bg);
        sigma = medianFilter(sg);
        var sorted = sigma.clone();
        Arrays.sort(sorted);
        var floor = Math.max(0.25f * sorted[sorted.length / 2], 1e-6f);
        for (var i = 0; i < sigma.length; i++) {
            sigma[i] = Math.max(sigma[i], floor);
        }
    }

    private static float mad(float[] values, float[] dev, int n, float med) {
        for (var i = 0; i < n; i++) {
            dev[i] = Math.abs(values[i] - med);
        }
        return select(dev, n, n / 2);
    }

    /** A 3x3 median filter over the grid of tiles, which removes tiles polluted by large objects. */
    private float[] medianFilter(float[] tiles) {
        var out = new float[tiles.length];
        var win = new float[9];
        for (var ty = 0; ty < tilesY; ty++) {
            for (var tx = 0; tx < tilesX; tx++) {
                var n = 0;
                for (var dy = -1; dy <= 1; dy++) {
                    var yy = ty + dy;
                    if (yy < 0 || yy >= tilesY) {
                        continue;
                    }
                    for (var dx = -1; dx <= 1; dx++) {
                        var xx = tx + dx;
                        if (xx >= 0 && xx < tilesX) {
                            win[n++] = tiles[yy * tilesX + xx];
                        }
                    }
                }
                Arrays.sort(win, 0, n);
                out[ty * tilesX + tx] = win[n / 2];
            }
        }
        return out;
    }

    /** Quickselect: returns the k-th smallest of the n first values, which are reordered. */
    static float select(float[] a, int n, int k) {
        var lo = 0;
        var hi = n - 1;
        while (lo < hi) {
            var pivot = a[(lo + hi) >>> 1];
            var i = lo;
            var j = hi;
            while (i <= j) {
                while (a[i] < pivot) {
                    i++;
                }
                while (a[j] > pivot) {
                    j--;
                }
                if (i <= j) {
                    var t = a[i];
                    a[i] = a[j];
                    a[j] = t;
                    i++;
                    j--;
                }
            }
            if (k <= j) {
                hi = j;
            } else if (k >= i) {
                lo = i;
            } else {
                break;
            }
        }
        return a[k];
    }

    /** Subtracts the background, bilinearly interpolated between the centers of the tiles. */
    private void subtractBackground() {
        sub = inPlace ? data : new float[w * h];
        var half = (tile - 1) / 2.0;
        parallelRows(h, (from, to) -> {
            var rowBg = new float[tilesX];
            for (var y = from; y < to; y++) {
                var gy = (y - half) / tile;
                var ty0 = Math.clamp((int) Math.floor(gy), 0, tilesY - 1);
                var ty1 = Math.min(tilesY - 1, ty0 + 1);
                var fy = (float) Math.clamp(gy - ty0, 0.0, 1.0);
                for (var tx = 0; tx < tilesX; tx++) {
                    rowBg[tx] = background[ty0 * tilesX + tx] * (1 - fy) + background[ty1 * tilesX + tx] * fy;
                }
                var row = y * w;
                var firstCenter = (int) Math.ceil(half);
                for (var x = 0; x < Math.min(w, firstCenter); x++) {
                    sub[row + x] = data[row + x] - rowBg[0];
                }
                for (var tx = 0; tx < tilesX; tx++) {
                    var c0 = tx * tile + half;
                    var xStart = (int) Math.ceil(c0);
                    var xEnd = tx == tilesX - 1 ? w : Math.min(w, (int) Math.ceil(c0 + tile));
                    var left = rowBg[tx];
                    var slope = tx == tilesX - 1 ? 0 : (rowBg[tx + 1] - left) / tile;
                    var offset = (float) (xStart - c0);
                    for (var x = xStart; x < xEnd; x++) {
                        sub[row + x] = data[row + x] - (left + slope * (x - xStart + offset));
                    }
                }
            }
        });
    }

    private static final class Peaks {
        int[] index = new int[1024];
        float[] value = new float[1024];
        int size;

        void add(int idx, float v) {
            if (size == index.length) {
                index = Arrays.copyOf(index, 2 * size);
                value = Arrays.copyOf(value, 2 * size);
            }
            index[size] = idx;
            value[size++] = v;
        }
    }

    /**
     * Finds the local maxima of the background subtracted image, smoothed by a 5x5 gaussian kernel.
     * Smoothing is done on the fly, a band of rows at a time: only the few smoothed rows around
     * the current one are kept, which saves two images worth of memory, and of memory traffic.
     */
    private Peaks findPeaks() {
        var k = (float) (options.thresholdSigma() * SMOOTHING_NOISE);
        var rows = h - 6;
        var bands = Math.clamp(rows / 32, 1, 4 * Runtime.getRuntime().availableProcessors());
        var results = new Peaks[bands];
        IntStream.range(0, bands).parallel().forEach(b -> {
            var from = 3 + (int) ((long) rows * b / bands);
            var to = 3 + (int) ((long) rows * (b + 1) / bands);
            var peaks = new Peaks();
            // horizontally smoothed rows, indexed by y modulo 8
            var horizontal = new float[8][w];
            // smoothed rows y - 1, y and y + 1
            var above = new float[w];
            var current = new float[w];
            var below = new float[w];
            // the smoothed row y needs the horizontally smoothed rows y - 2 to y + 2
            for (var y = from - 3; y <= from + 2; y++) {
                smoothRow(y, horizontal[y & 7]);
            }
            smoothColumn(horizontal, from - 1, above);
            smoothColumn(horizontal, from, current);
            for (var y = from; y < to; y++) {
                smoothRow(y + 3, horizontal[(y + 3) & 7]);
                smoothColumn(horizontal, y + 1, below);
                var ty = Math.min(tilesY - 1, y / tile);
                var row = y * w;
                for (var tx = 0; tx < tilesX; tx++) {
                    var threshold = k * sigma[ty * tilesX + tx];
                    var xStart = Math.max(3, tx * tile);
                    var xEnd = tx == tilesX - 1 ? w - 3 : (tx + 1) * tile;
                    for (var x = xStart; x < xEnd; x++) {
                        var v = current[x];
                        if (v > threshold
                                && v > current[x - 1] && v >= current[x + 1]
                                && v > above[x - 1] && v > above[x] && v > above[x + 1]
                                && v >= below[x - 1] && v >= below[x] && v >= below[x + 1]) {
                            peaks.add(row + x, v);
                        }
                    }
                }
                var t = above;
                above = current;
                current = below;
                below = t;
            }
            results[b] = peaks;
        });
        var all = new Peaks();
        for (var p : results) {
            for (var i = 0; i < p.size; i++) {
                all.add(p.index[i], p.value[i]);
            }
        }
        return all;
    }

    /** Smoothes a row of the background subtracted image horizontally. */
    private void smoothRow(int y, float[] out) {
        var src = sub;
        var row = y * w;
        for (var x = 2; x < w - 2; x++) {
            var i = row + x;
            out[x] = K0 * (src[i - 2] + src[i + 2]) + K1 * (src[i - 1] + src[i + 1]) + K2 * src[i];
        }
    }

    /** Smoothes horizontally smoothed rows vertically, giving the smoothed row y. */
    private void smoothColumn(float[][] horizontal, int y, float[] out) {
        var r0 = horizontal[(y - 2) & 7];
        var r1 = horizontal[(y - 1) & 7];
        var r2 = horizontal[y & 7];
        var r3 = horizontal[(y + 1) & 7];
        var r4 = horizontal[(y + 2) & 7];
        for (var x = 0; x < w; x++) {
            out[x] = K0 * (r0[x] + r4[x]) + K1 * (r1[x] + r3[x]) + K2 * r2[x];
        }
    }

    private StarList measure(Peaks peaks, int binning) {
        // only measure the most significant peaks
        var n = peaks.size;
        var keys = new long[n];
        for (var i = 0; i < n; i++) {
            keys[i] = ((long) Float.floatToIntBits(peaks.value[i]) << 32) | i;
        }
        Arrays.sort(keys);
        // enough peaks to get the requested number of stars, once duplicates and artifacts are removed
        var keep = Math.min(n, Math.max(1000, 2 * options.maxStars()));
        var sx = new double[keep];
        var sy = new double[keep];
        var sflux = new float[keep];
        var sfwhm = new float[keep];
        var ok = new boolean[keep];
        IntStream.range(0, keep).parallel().forEach(j -> {
            var idx = peaks.index[(int) keys[n - 1 - j]];
            var m = measure(idx % w, idx / w);
            if (m != null) {
                sx[j] = m[0];
                sy[j] = m[1];
                sflux[j] = (float) m[2];
                sfwhm[j] = (float) m[3];
                ok[j] = true;
            }
        });
        // sort by flux, and remove duplicates
        var order = new long[keep];
        var m = 0;
        for (var j = 0; j < keep; j++) {
            if (ok[j]) {
                order[m++] = ((long) Float.floatToIntBits(sflux[j]) << 32) | j;
            }
        }
        Arrays.sort(order, 0, m);
        var cellSize = MAX_DEDUPE_RADIUS;
        var gw = w / cellSize + 1;
        var gh = h / cellSize + 1;
        var head = new int[gw * gh];
        Arrays.fill(head, -1);
        var next = new int[m];
        var max = Math.min(m, options.maxStars());
        var ox = new double[max];
        var oy = new double[max];
        var oflux = new float[max];
        var ofwhm = new float[max];
        var count = 0;
        for (var r = m - 1; r >= 0 && count < max; r--) {
            var j = (int) order[r];
            var gx = (int) (sx[j] / cellSize);
            var gy = (int) (sy[j] / cellSize);
            var duplicate = false;
            for (var yy = Math.max(0, gy - 1); yy <= Math.min(gh - 1, gy + 1) && !duplicate; yy++) {
                for (var xx = Math.max(0, gx - 1); xx <= Math.min(gw - 1, gx + 1) && !duplicate; xx++) {
                    for (var o = head[yy * gw + xx]; o >= 0; o = next[o]) {
                        var dx = ox[o] - sx[j];
                        var dy = oy[o] - sy[j];
                        double radius = Math.clamp(ofwhm[o], 3, MAX_DEDUPE_RADIUS);
                        if (dx * dx + dy * dy < radius * radius) {
                            duplicate = true;
                            break;
                        }
                    }
                }
            }
            if (duplicate) {
                continue;
            }
            ox[count] = sx[j];
            oy[count] = sy[j];
            oflux[count] = sflux[j];
            ofwhm[count] = sfwhm[j];
            next[count] = head[gy * gw + gx];
            head[gy * gw + gx] = count;
            count++;
        }
        // back to the coordinates of the source image
        for (var i = 0; i < count; i++) {
            ox[i] = (ox[i] + 0.5) * binning - 0.5;
            oy[i] = (oy[i] + 0.5) * binning - 0.5;
            ofwhm[i] *= binning;
        }
        return new StarList(Arrays.copyOf(ox, count), Arrays.copyOf(oy, count), Arrays.copyOf(oflux, count), Arrays.copyOf(ofwhm, count));
    }

    /**
     * Measures the star which is around a pixel.
     *
     * @return (x, y, flux, fwhm), or null if this isn't a star
     */
    private double[] measure(int px, int py) {
        var mx = px;
        var my = py;
        var peak = sub[py * w + px];
        for (var y = py - 1; y <= py + 1; y++) {
            for (var x = px - 1; x <= px + 1; x++) {
                var v = sub[y * w + x];
                if (v > peak) {
                    peak = v;
                    mx = x;
                    my = y;
                }
            }
        }
        if (peak <= 0 || mx < 2 || my < 2 || mx >= w - 2 || my >= h - 2) {
            return null;
        }
        // hot pixels stand alone
        var around = -peak;
        for (var y = my - 1; y <= my + 1; y++) {
            for (var x = mx - 1; x <= mx + 1; x++) {
                around += sub[y * w + x];
            }
        }
        if (around < 0.32f * peak) {
            return null;
        }
        // size of the star: area above half maximum
        var half = 0.5f * peak;
        var radius = 4;
        int count;
        while (true) {
            count = 0;
            var y0 = Math.max(0, my - radius);
            var y1 = Math.min(h - 1, my + radius);
            var x0 = Math.max(0, mx - radius);
            var x1 = Math.min(w - 1, mx + radius);
            for (var y = y0; y <= y1; y++) {
                var row = y * w;
                for (var x = x0; x <= x1; x++) {
                    if (sub[row + x] > half) {
                        count++;
                    }
                }
            }
            if (radius < 32 && count > (2 * radius + 1) * (2 * radius + 1) / 2) {
                radius *= 2;
            } else {
                break;
            }
        }
        var fwhm = 2 * Math.sqrt(count / Math.PI);
        // gaussian weighted centroid
        var sw = Math.max(1.0, 0.6 * fwhm);
        var rad = (int) Math.min(40, Math.ceil(3 * sw));
        var inv = 1 / (2 * sw * sw);
        double cx = mx;
        double cy = my;
        // the gaussian window is separable: exp(-(dx² + dy²) k) = exp(-dx² k) exp(-dy² k)
        var weightX = new double[2 * rad + 1];
        var weightY = new double[2 * rad + 1];
        for (var iter = 0; iter < 8; iter++) {
            var y0 = Math.max(0, (int) Math.round(cy) - rad);
            var y1 = Math.min(h - 1, (int) Math.round(cy) + rad);
            var x0 = Math.max(0, (int) Math.round(cx) - rad);
            var x1 = Math.min(w - 1, (int) Math.round(cx) + rad);
            for (var x = x0; x <= x1; x++) {
                var dx = x - cx;
                weightX[x - x0] = Math.exp(-dx * dx * inv);
            }
            for (var y = y0; y <= y1; y++) {
                var dy = y - cy;
                weightY[y - y0] = Math.exp(-dy * dy * inv);
            }
            var sum = 0.0;
            var sumX = 0.0;
            var sumY = 0.0;
            for (var y = y0; y <= y1; y++) {
                var row = y * w;
                var dy = y - cy;
                // the sums of the row, weighted along x only
                var rowSum = 0.0;
                var rowSumX = 0.0;
                for (var x = x0; x <= x1; x++) {
                    var v = sub[row + x];
                    if (v > 0) {
                        var wv = v * weightX[x - x0];
                        rowSum += wv;
                        rowSumX += wv * (x - cx);
                    }
                }
                var wy = weightY[y - y0];
                sum += wy * rowSum;
                sumX += wy * rowSumX;
                sumY += wy * rowSum * dy;
            }
            if (sum <= 0) {
                return null;
            }
            // a gaussian window halves the measured shift of a gaussian star of the same size
            var shiftX = 2 * sumX / sum;
            var shiftY = 2 * sumY / sum;
            cx += shiftX;
            cy += shiftY;
            if (Math.abs(cx - mx) > rad || Math.abs(cy - my) > rad) {
                return null;
            }
            if (shiftX * shiftX + shiftY * shiftY < 1e-5) {
                break;
            }
        }
        if (cx < 1 || cy < 1 || cx > w - 2 || cy > h - 2) {
            return null;
        }
        // aperture flux
        var rf = Math.max(2.5, 1.5 * fwhm);
        var ri = (int) Math.ceil(rf);
        var flux = 0.0;
        var y0 = Math.max(0, (int) Math.round(cy) - ri);
        var y1 = Math.min(h - 1, (int) Math.round(cy) + ri);
        var x0 = Math.max(0, (int) Math.round(cx) - ri);
        var x1 = Math.min(w - 1, (int) Math.round(cx) + ri);
        for (var y = y0; y <= y1; y++) {
            var row = y * w;
            var dy = y - cy;
            for (var x = x0; x <= x1; x++) {
                var dx = x - cx;
                if (dx * dx + dy * dy <= rf * rf) {
                    flux += sub[row + x];
                }
            }
        }
        if (flux <= 0) {
            return null;
        }
        return new double[]{cx, cy, flux, fwhm};
    }
}
