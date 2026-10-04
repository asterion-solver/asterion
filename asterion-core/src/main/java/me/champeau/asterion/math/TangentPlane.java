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

/**
 * A gnomonic (TAN) projection plane, tangent to the celestial sphere at a given direction.
 * Standard coordinates are (xi, eta), in radians, with xi growing to the east and eta to the north.
 * Planes are created with {@link #at(double, double)} or {@link #at(double, double, double)}, which
 * compute the vectors of the plane from its tangent point.
 *
 * @param cx the x coordinate of the tangent point, a unit vector
 * @param cy the y coordinate of the tangent point
 * @param cz the z coordinate of the tangent point
 * @param ex the x coordinate of the unit vector pointing east at the tangent point
 * @param ey the y coordinate of the east vector
 * @param ez the z coordinate of the east vector
 * @param nx the x coordinate of the unit vector pointing north at the tangent point
 * @param ny the y coordinate of the north vector
 * @param nz the z coordinate of the north vector
 */
public record TangentPlane(
        double cx,
        double cy,
        double cz,
        double ex,
        double ey,
        double ez,
        double nx,
        double ny,
        double nz) {
    private static TangentPlane fromUnitVector(double cx, double cy, double cz) {
        var h = Math.hypot(cx, cy);
        double ex;
        double ey;
        if (h < 1e-12) {
            // at the poles east is arbitrary, pick the one of RA = 0
            ex = 0;
            ey = 1;
        } else {
            ex = -cy / h;
            ey = cx / h;
        }
        var ez = 0.0;
        return new TangentPlane(cx, cy, cz, ex, ey, ez, cy * ez - cz * ey, cz * ex - cx * ez, cx * ey - cy * ex);
    }

    /** Creates the plane tangent at the direction of the given vector, which doesn't need to be normalized. */
    public static TangentPlane at(double x, double y, double z) {
        var n = Math.sqrt(x * x + y * y + z * z);
        return fromUnitVector(x / n, y / n, z / n);
    }

    public static TangentPlane at(double ra, double dec) {
        var cd = Math.cos(dec);
        return fromUnitVector(cd * Math.cos(ra), cd * Math.sin(ra), Math.sin(dec));
    }

    /** Cosine of the angle between the tangent point and the given unit vector. */
    public double dot(double x, double y, double z) {
        return cx * x + cy * y + cz * z;
    }

    public double xi(double x, double y, double z) {
        return (ex * x + ey * y + ez * z) / dot(x, y, z);
    }

    public double eta(double x, double y, double z) {
        return (nx * x + ny * y + nz * z) / dot(x, y, z);
    }

    /** Deprojects standard coordinates to a unit vector. */
    public void unproject(double xi, double eta, double[] out) {
        var x = cx + xi * ex + eta * nx;
        var y = cy + xi * ey + eta * ny;
        var z = cz + xi * ez + eta * nz;
        var n = Math.sqrt(x * x + y * y + z * z);
        out[0] = x / n;
        out[1] = y / n;
        out[2] = z / n;
    }

    public double ra() {
        return Sphere.ra(cx, cy);
    }

    public double dec() {
        return Sphere.dec(cz);
    }
}
