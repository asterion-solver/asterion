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

/**
 * Builds a gray image from the rows of a decoded image, binning them on the fly so that the full
 * resolution image is never in memory. Rows must be given in order.
 */
final class GrayBinner {
    private static final long AUTO_BINNING_PIXELS = 20_000_000;

    private final int width;
    private final int height;
    private final int binning;
    private final int binnedWidth;
    private final int binnedHeight;
    private final float[] out;
    private int nextRow;

    /**
     * @param binning the binning factor, or 0 to select it automatically
     */
    GrayBinner(int width, int height, int binning) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid image dimensions " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        this.binning = binningFor(width, height, binning);
        this.binnedWidth = width / this.binning;
        this.binnedHeight = height / this.binning;
        if ((long) binnedWidth * binnedHeight > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("Image too large: " + width + "x" + height);
        }
        this.out = new float[binnedWidth * binnedHeight];
    }

    /** The binning which is applied to an image: 0 selects it automatically. */
    static int binningFor(int width, int height, int binning) {
        var b = binning;
        if (b <= 0) {
            b = 1;
            while ((long) (width / b) * (height / b) > AUTO_BINNING_PIXELS) {
                b++;
            }
        }
        return Math.max(1, Math.min(b, Math.min(width, height) / 16));
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    /**
     * Adds the next row of the image.
     *
     * @param values the brightness of each pixel: the sum of its color channels
     */
    void row(float[] values) {
        var y = nextRow++;
        var by = y / binning;
        if (by >= binnedHeight) {
            // the last rows which don't make a full binned row are dropped
            return;
        }
        var offset = by * binnedWidth;
        var limit = binnedWidth * binning;
        for (var x = 0; x < limit; x++) {
            out[offset + x / binning] += values[x];
        }
    }

    GrayImage image() {
        if (nextRow != height) {
            throw new IllegalStateException("The image has " + height + " rows, " + nextRow + " were decoded");
        }
        return new GrayImage(binnedWidth, binnedHeight, out, binning, width, height);
    }
}
