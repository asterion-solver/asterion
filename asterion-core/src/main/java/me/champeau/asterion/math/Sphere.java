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
package me.champeau.asterion.math;

/** Spherical geometry helpers. Angles are in radians unless stated otherwise. */
public final class Sphere {
    public static final double TWO_PI = 2 * Math.PI;
    public static final double HALF_PI = Math.PI / 2;
    public static final double ARCSEC = Math.PI / (180 * 3600);
    public static final double SKY_AREA = 4 * Math.PI;

    private Sphere() {
    }

    public static void toVector(double ra, double dec, double[] out) {
        var cd = Math.cos(dec);
        out[0] = cd * Math.cos(ra);
        out[1] = cd * Math.sin(ra);
        out[2] = Math.sin(dec);
    }

    public static double ra(double x, double y) {
        var ra = Math.atan2(y, x);
        return ra < 0 ? ra + TWO_PI : ra;
    }

    public static double dec(double z) {
        return Math.asin(Math.clamp(z, -1, 1));
    }

    /** Angular separation of two directions, accurate for small and large angles. */
    public static double separation(double ra1, double dec1, double ra2, double dec2) {
        var sd = Math.sin((dec2 - dec1) / 2);
        var sr = Math.sin((ra2 - ra1) / 2);
        var a = sd * sd + Math.cos(dec1) * Math.cos(dec2) * sr * sr;
        return 2 * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** Converts a squared chord length between two unit vectors into the angle they subtend. */
    public static double chord2ToAngle(double chord2) {
        return 2 * Math.asin(Math.min(1, Math.sqrt(chord2) / 2));
    }

    /** Squared chord length for a given angle. */
    public static double angleToChord2(double angle) {
        var c = 2 * Math.sin(angle / 2);
        return c * c;
    }

    public static double normalizeRa(double ra) {
        ra %= TWO_PI;
        return ra < 0 ? ra + TWO_PI : ra;
    }
}
