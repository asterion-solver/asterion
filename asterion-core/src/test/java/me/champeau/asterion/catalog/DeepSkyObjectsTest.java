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

import me.champeau.asterion.SyntheticSky;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSkyObjectsTest {
    @Test
    void readsTheTable() {
        var all = DeepSkyObjects.all();
        assertTrue(all.size() > 12000, "only " + all.size() + " objects");
        var m31 = all.stream().filter(o -> o.messier() == 31).findFirst().orElseThrow();
        assertEquals("NGC 224", m31.designation());
        assertEquals("M 31", m31.name());
        assertEquals("M 31 (Andromeda Galaxy)", m31.label());
        assertEquals(DeepSkyObjects.Type.GALAXY, m31.type());
        assertEquals(10.6847, m31.raDeg(), 1e-3);
        assertEquals(41.2689, m31.decDeg(), 1e-3);
        assertEquals(177.83, m31.majorArcmin(), 1e-2);
        assertEquals(3.44, m31.magnitude(), 1e-2);
        // M 102 is a duplicate of M 101 for OpenNGC
        assertEquals(109, all.stream().filter(o -> o.messier() > 0).count());
        var pleiades = all.stream().filter(o -> o.messier() == 45).findFirst().orElseThrow();
        assertEquals("Melotte 22", pleiades.designation());
        assertEquals("M 45 (Pleiades)", pleiades.label());
        assertTrue(all.stream().anyMatch(o -> o.designation().equals("IC 1396")));
        assertTrue(all.stream().anyMatch(o -> o.designation().equals("Barnard 33")));
    }

    @Test
    void findsTheObjectsOfAField() {
        // 2.6° x 1.7° around M31, at 3"/px, like a QHY8L frame
        var width = 3000;
        var height = 2000;
        var wcs = SyntheticSky.wcs(width, height, 10.6, 41.3, 3.1, 25, false);
        var found = DeepSkyObjects.inField(wcs, width, height);
        var names = found.stream().map(f -> f.object().name()).toList();
        // Messier objects first, the largest first: M 110 is larger than M 32
        assertEquals(List.of("M 31", "M 110", "M 32"), names.subList(0, 3), names.toString());
        var m31 = found.getFirst();
        assertTrue(m31.distanceDeg() < 0.1, "M 31 at " + m31.distanceDeg() + "° from the center");
        assertEquals(10.6847, wcs.pixelToSky(m31.x(), m31.y())[0], 1e-3);
        assertTrue(names.contains("NGC 206"), names.toString());

        // a large nebula reaches into an image which doesn't contain its center
        var small = SyntheticSky.wcs(1000, 800, 315.4, 44.0, 2, 0, false);
        var nebulae = DeepSkyObjects.inField(small, 1000, 800).stream().map(f -> f.object().name()).toList();
        assertTrue(nebulae.contains("NGC 7000"), nebulae.toString());

        // an empty field: the south pole, a tiny one
        var pole = SyntheticSky.wcs(200, 200, 0, -89.9, 1, 0, false);
        assertTrue(DeepSkyObjects.inField(pole, 200, 200).isEmpty());
    }
}
