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

import me.champeau.asterion.index.StarIndex;
import me.champeau.asterion.math.TangentPlane;

/**
 * A candidate alignment of the image on the sky, deduced from the match of a quad: a similarity
 * which maps pixels to the standard coordinates of a tangent plane.
 */
final class Hypothesis {
    /** The index which contains the matched quad. */
    StarIndex index;
    /** The number of years between the epoch of the index and the date of the image. */
    double years;
    TangentPlane plane;
    double m00;
    double m01;
    double m10;
    double m11;
    double tx;
    double ty;
    /** Scale of the image, in radians per pixel. */
    double scale;
    /** Centroid of the quad in the image. */
    double quadX;
    double quadY;
    /** Sum of the squared distances of the stars of the quad to their centroid, in pixels. */
    double quadR2;
    final int[] stars = new int[4];
    double logOdds;

    /**
     * Projects a unit vector to the image.
     *
     * @return false if the direction is too far from the tangent point
     */
    boolean toPixel(double vx, double vy, double vz, double[] out) {
        if (plane.dot(vx, vy, vz) < 0.1) {
            return false;
        }
        var xi = plane.xi(vx, vy, vz) - tx;
        var eta = plane.eta(vx, vy, vz) - ty;
        var det = m00 * m11 - m01 * m10;
        out[0] = (m11 * xi - m01 * eta) / det;
        out[1] = (-m10 * xi + m00 * eta) / det;
        return true;
    }

    void toVector(double x, double y, double[] out) {
        plane.unproject(m00 * x + m01 * y + tx, m10 * x + m11 * y + ty, out);
    }

    /** The expected error of the positions of stars, in pixels. */
    static final double POSITION_ERROR = 1.5;

    /** The variance of the predicted position of a star, in pixels². */
    double variance(double x, double y) {
        var dx = x - quadX;
        var dy = y - quadY;
        var d2 = dx * dx + dy * dy;
        return POSITION_ERROR * POSITION_ERROR * (1.25 + d2 / quadR2) + 1e-6 * d2;
    }
}
