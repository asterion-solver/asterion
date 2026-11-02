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

/**
 * Pixels which are in memory already, such as the frames which the driver of a camera gives to an
 * acquisition program: they are copied into a {@link GrayImage}, converted and binned in a single
 * pass, the way image files are decoded, and the caller can reuse its memory right away.
 */
public final class RawPixels {
    private RawPixels() {
    }

    /** The type of the samples, one per pixel, in the native byte order. */
    public enum SampleType {
        UINT8(1),
        UINT16(2),
        FLOAT32(4);

        private final int bytes;

        SampleType(int bytes) {
            this.bytes = bytes;
        }

        /** The size of a sample, in bytes. */
        public int bytes() {
            return bytes;
        }
    }

    /**
     * Copies pixels into an image.
     *
     * @param pixels the pixels, row after row, from the first one: they are read by several threads,
     * so the segment can't be confined to one
     * @param rowStride the distance between the starts of two rows, in bytes: at least the width
     * times the size of a sample, more when rows are padded
     * @param bayer true if the pixels are a raw frame of a color sensor, which is binned 2x2 to
     * remove the pattern of its filters
     * @param binning the binning, or 0 to choose it like for image files: 2x2 for raw frames of
     * color sensors, more for very large images
     */
    public static GrayImage read(MemorySegment pixels, SampleType type, int width, int height, long rowStride, boolean bayer,
                                 int binning) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid image dimensions " + width + "x" + height);
        }
        if (rowStride < (long) width * type.bytes()) {
            throw new IllegalArgumentException("The rows of " + width + " pixels can't be " + rowStride + " bytes apart");
        }
        // the last row doesn't need its padding
        var size = (height - 1) * rowStride + (long) width * type.bytes();
        if (pixels.byteSize() < size) {
            throw new IllegalArgumentException("The pixels are " + pixels.byteSize() + " bytes, " + size + " were expected");
        }
        var b = GrayBinner.binningFor(width, height, binning, bayer);
        if ((long) (width / b) * (height / b) > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("Image too large: " + width + "x" + height);
        }
        // decoded like the uncompressed images of FITS files, in parallel bands of rows
        var data = RowDecoder.decode(pixels, () -> RowDecoder.raw(type, width), width, height, 1, 0, rowStride, b, 1, 0);
        return new GrayImage(width / b, height / b, data, b, width, height);
    }
}
