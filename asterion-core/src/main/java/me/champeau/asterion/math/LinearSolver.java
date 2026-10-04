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

/** Small dense linear algebra routines. */
public final class LinearSolver {
    private LinearSolver() {
    }

    /**
     * Solves the linear system A.x = b by gaussian elimination with partial pivoting.
     *
     * @param a the matrix, in row order, which is destroyed
     * @param b the right hand side, which is replaced by the solution
     * @return false if the matrix is singular
     */
    public static boolean solve(double[] a, double[] b, int n) {
        for (var col = 0; col < n; col++) {
            var pivot = col;
            var max = Math.abs(a[col * n + col]);
            for (var row = col + 1; row < n; row++) {
                var v = Math.abs(a[row * n + col]);
                if (v > max) {
                    max = v;
                    pivot = row;
                }
            }
            if (max < 1e-300) {
                return false;
            }
            if (pivot != col) {
                for (var k = 0; k < n; k++) {
                    var t = a[col * n + k];
                    a[col * n + k] = a[pivot * n + k];
                    a[pivot * n + k] = t;
                }
                var t = b[col];
                b[col] = b[pivot];
                b[pivot] = t;
            }
            var diag = a[col * n + col];
            for (var row = col + 1; row < n; row++) {
                var factor = a[row * n + col] / diag;
                if (factor != 0) {
                    for (var k = col; k < n; k++) {
                        a[row * n + k] -= factor * a[col * n + k];
                    }
                    b[row] -= factor * b[col];
                }
            }
        }
        for (var row = n - 1; row >= 0; row--) {
            var sum = b[row];
            for (var k = row + 1; k < n; k++) {
                sum -= a[row * n + k] * b[k];
            }
            b[row] = sum / a[row * n + row];
        }
        return true;
    }

    /**
     * Solves two least squares problems sharing the same design matrix, using normal equations.
     *
     * @param basis the design matrix, in row order: one row of {@code terms} values per observation
     * @param y1 the observations of the first problem
     * @param y2 the observations of the second problem
     * @param n the number of observations
     * @param terms the number of unknowns
     * @return the two solutions, or null if the problem is degenerate
     */
    public static double[][] leastSquares(double[] basis, double[] y1, double[] y2, int n, int terms) {
        var ata = new double[terms * terms];
        var atb1 = new double[terms];
        var atb2 = new double[terms];
        for (var i = 0; i < n; i++) {
            var row = i * terms;
            for (var p = 0; p < terms; p++) {
                var bp = basis[row + p];
                atb1[p] += bp * y1[i];
                atb2[p] += bp * y2[i];
                for (var q = p; q < terms; q++) {
                    ata[p * terms + q] += bp * basis[row + q];
                }
            }
        }
        for (var p = 0; p < terms; p++) {
            for (var q = 0; q < p; q++) {
                ata[p * terms + q] = ata[q * terms + p];
            }
        }
        var copy = ata.clone();
        if (!solve(copy, atb1, terms) || !solve(ata, atb2, terms)) {
            return null;
        }
        return new double[][]{atb1, atb2};
    }
}
