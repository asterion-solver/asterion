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
        assertTrue(all.size() > 16000, "only " + all.size() + " objects");
        var m31 = all.stream().filter(o -> o.messier() == 31).findFirst().orElseThrow();
        assertEquals("NGC 224", m31.designation());
        assertEquals(DeepSkyObjects.Catalog.NGC, m31.catalog());
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

    private static DeepSkyObjects.DeepSkyObject named(String designation) {
        return DeepSkyObjects.all().stream().filter(o -> o.designation().equals(designation)).findFirst().orElseThrow();
    }

    @Test
    void listsAnObjectOnceWithTheDesignationOfTheMostFamousCatalog() {
        var all = DeepSkyObjects.all();
        // the catalogs of Sharpless and Lynds, merged with OpenNGC
        assertTrue(all.stream().filter(o -> o.catalog() == DeepSkyObjects.Catalog.SHARPLESS).count() > 250);
        assertTrue(all.stream().filter(o -> o.catalog() == DeepSkyObjects.Catalog.LBN).count() > 700);
        assertTrue(all.stream().filter(o -> o.catalog() == DeepSkyObjects.Catalog.LDN).count() > 1700);
        // the North America nebula is NGC 7000, Sh2-117 and LBN 373: it's listed once, as NGC 7000
        var northAmerica = named("NGC 7000");
        assertEquals("NGC 7000 (North America Nebula)", northAmerica.label());
        assertTrue(northAmerica.aliases().contains("Sh2-117") && northAmerica.aliases().contains("LBN 373"), northAmerica.aliases());
        assertTrue(all.stream().noneMatch(o -> o.designation().equals("Sh2-117") || o.designation().equals("LBN 373")));
        // Messier objects first: M 42 is also Sh2-281, the Horsehead nebula is also LDN 1630
        var orion = all.stream().filter(o -> o.messier() == 42).findFirst().orElseThrow();
        assertTrue(orion.aliases().contains("Sh2-281"), orion.aliases());
        assertTrue(named("Barnard 33").aliases().contains("LDN 1630"), named("Barnard 33").aliases());
        // the extent of the HII region of a cluster with nebula, rather than the one of its cluster
        assertEquals(170, named("IC 1396").majorArcmin(), 1);
        // the objects of the other catalogs, with the names astrophotographers use
        assertEquals("Sh2-129 (Flying Bat Nebula)", named("Sh2-129").label());
        assertEquals(DeepSkyObjects.Catalog.SHARPLESS, named("Sh2-129").catalog());
        assertEquals("Sh2-276 (Barnard's Loop)", named("Sh2-276").label());
        assertEquals(1200, named("Sh2-276").majorArcmin(), 1);
        assertEquals("LDN 1622 (Boogeyman Nebula)", named("LDN 1622").label());
        assertEquals(DeepSkyObjects.Type.DARK_NEBULA, named("LDN 1622").type());
        assertEquals("IC 1805 (Heart Nebula)", named("IC 1805").label());
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

        // the North America and Pelican nebulae: the named objects first, then the others by
        // catalog, the most famous first; the dark nebulae of Lynds last
        var cygnus = SyntheticSky.wcs(2000, 1300, 313.8, 44.3, 7, 0, false);
        var inCygnus = DeepSkyObjects.inField(cygnus, 2000, 1300).stream().map(f -> f.object()).toList();
        assertEquals(List.of("NGC 7000", "IC 5070"), inCygnus.subList(0, 2).stream().map(DeepSkyObjects.DeepSkyObject::name).toList(),
                inCygnus.toString());
        var catalogs = inCygnus.stream().filter(o -> o.tier() == 2).map(DeepSkyObjects.DeepSkyObject::catalog).toList();
        assertEquals(catalogs.stream().sorted().toList(), catalogs);
        assertTrue(catalogs.contains(DeepSkyObjects.Catalog.LDN), catalogs.toString());

        // Barnard's Loop, 20° across, reaches into an image of Orion far from its center
        var orion = SyntheticSky.wcs(1000, 800, 88.0, 2.0, 10, 0, false);
        assertTrue(DeepSkyObjects.inField(orion, 1000, 800).stream().anyMatch(f -> f.object().designation().equals("Sh2-276")));

        // an empty field: the south pole, a tiny one
        var pole = SyntheticSky.wcs(200, 200, 0, -89.9, 1, 0, false);
        assertTrue(DeepSkyObjects.inField(pole, 200, 200).isEmpty());
    }
}
