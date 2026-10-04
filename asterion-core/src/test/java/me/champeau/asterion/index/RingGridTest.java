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
package me.champeau.asterion.index;

import me.champeau.asterion.math.Sphere;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RingGridTest {
    @Test
    void cellsHaveSimilarAreas() {
        var grid = new RingGrid(Math.toRadians(2));
        var count = new int[grid.cellCount()];
        var random = new Random(1);
        var samples = 2_000_000;
        for (var i = 0; i < samples; i++) {
            count[grid.cellOf(random.nextDouble() * Sphere.TWO_PI, Math.asin(2 * random.nextDouble() - 1))]++;
        }
        var expected = (double) samples / grid.cellCount();
        for (var c : count) {
            assertTrue(c > 0.5 * expected && c < 1.6 * expected, "cell with " + c + " samples instead of " + expected);
        }
    }

    @Test
    void cellCenterIsInCell() {
        var grid = new RingGrid(Math.toRadians(5));
        var raDec = new double[2];
        for (var cell = 0; cell < grid.cellCount(); cell++) {
            grid.cellCenter(cell, raDec);
            assertEquals(cell, grid.cellOf(raDec[0], raDec[1]));
        }
    }

    @Test
    void coneRangesCoverTheCone() {
        var random = new Random(2);
        var center = new double[3];
        var v = new double[3];
        for (var size : new double[]{0.3, 1, 7}) {
            var grid = new RingGrid(Math.toRadians(size));
            for (var trial = 0; trial < 300; trial++) {
                var ra = random.nextDouble() * Sphere.TWO_PI;
                // insist on the poles and on the origin of right ascensions
                var dec = trial % 3 == 0 ? Math.copySign(Sphere.HALF_PI - random.nextDouble() * 0.05, random.nextDouble() - 0.5)
                        : Math.asin(2 * random.nextDouble() - 1);
                if (trial % 5 == 0) {
                    ra = random.nextDouble() * 0.01;
                }
                var radius = Math.toRadians(0.1 + random.nextDouble() * 10);
                var ranges = new int[grid.maxConeRangeInts(radius)];
                var n = grid.coneRanges(ra, dec, radius, ranges);
                Sphere.toVector(ra, dec, center);
                for (var k = 0; k < 200; k++) {
                    // a random point of the cone
                    var r = radius * Math.sqrt(random.nextDouble());
                    var angle = random.nextDouble() * Sphere.TWO_PI;
                    var plane = me.champeau.asterion.math.TangentPlane.at(center[0], center[1], center[2]);
                    plane.unproject(Math.tan(r) * Math.cos(angle), Math.tan(r) * Math.sin(angle), v);
                    var cell = grid.cellOf(Sphere.ra(v[0], v[1]), Sphere.dec(v[2]));
                    var covered = false;
                    for (var i = 0; i < n; i += 2) {
                        covered |= cell >= ranges[i] && cell < ranges[i + 1];
                    }
                    assertTrue(covered, "cell not covered for a cone at dec " + Math.toDegrees(dec));
                }
            }
        }
    }
}
