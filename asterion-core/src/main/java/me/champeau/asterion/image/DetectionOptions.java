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
 * Parameters of the star detector.
 *
 * @param thresholdSigma the detection threshold, in units of background noise
 * @param maxStars the maximum number of stars to return, the brightest ones being kept
 * @param tileSize the size of the tiles used to estimate the background, in pixels
 * @param peakRanking true to keep the stars with the highest peaks rather than the ones with the
 * largest flux: in images with a foreground, such as landscapes, the brightest detections are
 * lit windows and the sky seen through foliage, which outnumber the stars, faint but sharp points
 */
public record DetectionOptions(
        double thresholdSigma,
        int maxStars,
        int tileSize,
        boolean peakRanking) {
    public DetectionOptions(double thresholdSigma, int maxStars, int tileSize) {
        this(thresholdSigma, maxStars, tileSize, false);
    }

    public static DetectionOptions defaults() {
        return new DetectionOptions(5, 1000, 64);
    }

    public DetectionOptions withThresholdSigma(double value) {
        return new DetectionOptions(value, maxStars, tileSize, peakRanking);
    }

    public DetectionOptions withMaxStars(int value) {
        return new DetectionOptions(thresholdSigma, value, tileSize, peakRanking);
    }

    public DetectionOptions withPeakRanking(boolean value) {
        return new DetectionOptions(thresholdSigma, maxStars, tileSize, value);
    }
}
