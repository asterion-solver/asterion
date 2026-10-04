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
package me.champeau.asterion.catalog;

import java.util.Arrays;

/**
 * An in-memory list of catalog stars, stored as parallel arrays. This is the input of the index
 * builder: any star catalog can be used to build an index as long as it can be expressed this way.
 */
public final class StarData {
    private final double epoch;
    private double[] ra;
    private double[] dec;
    private float[] pmRa;
    private float[] pmDec;
    private float[] mag;
    private int size;

    /**
     * @param epoch the epoch of the star positions, as a Julian year (e.g. 2000.0)
     */
    public StarData(double epoch) {
        this(epoch, 1024);
    }

    public StarData(double epoch, int initialCapacity) {
        this.epoch = epoch;
        this.ra = new double[initialCapacity];
        this.dec = new double[initialCapacity];
        this.pmRa = new float[initialCapacity];
        this.pmDec = new float[initialCapacity];
        this.mag = new float[initialCapacity];
    }

    /**
     * Adds a star.
     *
     * @param raRad right ascension, in radians
     * @param decRad declination, in radians
     * @param pmRaMasYr proper motion in right ascension, multiplied by cos(dec), in mas/year
     * @param pmDecMasYr proper motion in declination, in mas/year
     * @param magnitude the magnitude of the star, used to rank stars by brightness
     */
    public void add(double raRad, double decRad, float pmRaMasYr, float pmDecMasYr, float magnitude) {
        if (size == ra.length) {
            var capacity = size + (size >> 1) + 16;
            ra = Arrays.copyOf(ra, capacity);
            dec = Arrays.copyOf(dec, capacity);
            pmRa = Arrays.copyOf(pmRa, capacity);
            pmDec = Arrays.copyOf(pmDec, capacity);
            mag = Arrays.copyOf(mag, capacity);
        }
        ra[size] = raRad;
        dec[size] = decRad;
        pmRa[size] = pmRaMasYr;
        pmDec[size] = pmDecMasYr;
        mag[size] = magnitude;
        size++;
    }

    /** Appends all the stars of another list, which must share the same epoch. */
    public void addAll(StarData other) {
        if (other.epoch != epoch) {
            throw new IllegalArgumentException("Cannot merge star lists of different epochs");
        }
        for (var i = 0; i < other.size; i++) {
            add(other.ra[i], other.dec[i], other.pmRa[i], other.pmDec[i], other.mag[i]);
        }
    }

    public double epoch() {
        return epoch;
    }

    public int size() {
        return size;
    }

    public double ra(int i) {
        return ra[i];
    }

    public double dec(int i) {
        return dec[i];
    }

    public float pmRa(int i) {
        return pmRa[i];
    }

    public float pmDec(int i) {
        return pmDec[i];
    }

    public float mag(int i) {
        return mag[i];
    }
}
