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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StarListTest {
    private static final StarList STARS = new StarList(
            new double[]{1, 2, 3},
            new double[]{4, 5, 6},
            new float[]{300, 200, 100},
            new float[]{3.5f, 2.5f, 1.5f},
            new float[]{1, 0.5f, 1},
            new float[]{80, 40, 20},
            new boolean[]{true, false, false});

    @Test
    void keepsAllThatIsKnownOfTheStarsOfASubset() {
        var subset = STARS.subset(new int[]{2, 0});
        assertArrayEquals(new double[]{3, 1}, subset.x());
        assertArrayEquals(new double[]{6, 4}, subset.y());
        assertArrayEquals(new float[]{100, 300}, subset.flux());
        assertArrayEquals(new float[]{1.5f, 3.5f}, subset.fwhm());
        assertArrayEquals(new float[]{1, 1}, subset.concentration());
        assertArrayEquals(new float[]{20, 80}, subset.snr());
        assertArrayEquals(new boolean[]{false, true}, subset.saturated());
    }

    @Test
    void keepsAllThatIsKnownOfTheStarsAtOtherPositions() {
        var moved = STARS.withPositions(new double[]{7, 8, 9}, new double[]{10, 11, 12});
        assertArrayEquals(new double[]{7, 8, 9}, moved.x());
        assertArrayEquals(new double[]{10, 11, 12}, moved.y());
        assertArrayEquals(STARS.fwhm(), moved.fwhm());
        assertArrayEquals(STARS.snr(), moved.snr());
        assertArrayEquals(STARS.saturated(), moved.saturated());
    }

    @Test
    void starsOfListsWithoutMeasuresAreUnknown() {
        var stars = new StarList(new double[]{1, 2}, new double[]{3, 4}, new float[]{2, 1}, new float[]{3, 3});
        for (var i = 0; i < stars.size(); i++) {
            assertTrue(Float.isNaN(stars.snr()[i]));
            assertFalse(stars.saturated()[i]);
        }
    }

    @Test
    void requiresAMeasureOfEachStar() {
        assertThrows(IllegalArgumentException.class, () -> new StarList(new double[]{1, 2}, new double[]{3, 4},
                new float[]{2, 1}, new float[]{3, 3}, new float[]{1, 1}, new float[]{10}, new boolean[2]));
    }
}
