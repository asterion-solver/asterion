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
 * A single channel image, stored as floats in row order. The image may be a binned version of a
 * larger source image, in which case {@link #binning()} is greater than 1.
 *
 * @param width the width of the image, in pixels
 * @param height the height of the image, in pixels
 * @param data the pixels, in row order, which are not copied
 * @param binning the binning factor of the image, compared to the source image
 * @param sourceWidth the width of the image before binning
 * @param sourceHeight the height of the image before binning
 */
@SuppressWarnings("ArrayRecordComponent") // the pixels are shared on purpose: images are large
public record GrayImage(
        int width,
        int height,
        float[] data,
        int binning,
        int sourceWidth,
        int sourceHeight) {
    public GrayImage {
        if (data.length != width * height) {
            throw new IllegalArgumentException("Data doesn't match the image dimensions");
        }
    }

    public GrayImage(int width, int height, float[] data) {
        this(width, height, data, 1, width, height);
    }
}
