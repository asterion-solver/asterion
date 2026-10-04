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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GaiaDr3Test {
    @Test
    void readsQueryResults(@TempDir Path directory) throws IOException {
        var file = directory.resolve("gaia-000.csv");
        Files.writeString(file, """
                ra,dec,pmra,pmdec,phot_g_mean_mag
                89.53490035481087,45.11224243752479,6.094033318125215,-10.879393669337649,11.817675
                222.7191482591402,-16.042081011481653,,,2.7223556
                """);
        var stars = new StarData(2016.0);
        GaiaDr3.read(file, stars);
        assertEquals(2, stars.size());
        assertEquals(89.53490035481087, Math.toDegrees(stars.ra(0)), 1e-12);
        assertEquals(45.11224243752479, Math.toDegrees(stars.dec(0)), 1e-12);
        assertEquals(6.094f, stars.pmRa(0), 1e-3);
        assertEquals(-10.879f, stars.pmDec(0), 1e-3);
        assertEquals(11.817675f, stars.mag(0), 1e-5);
        // bright stars have no proper motion
        assertEquals(0f, stars.pmRa(1));
        assertEquals(0f, stars.pmDec(1));
        assertEquals(2.7223556f, stars.mag(1), 1e-5);
    }

    @Test
    void knownCatalogs() {
        assertTrue(Catalogs.find("tycho2").isPresent());
        assertEquals("gaia-500", Catalogs.find("GAIA-500").orElseThrow().name());
        assertEquals("gaia-1234", Catalogs.find("gaia-1234").orElseThrow().name());
        assertTrue(Catalogs.find("gaia-5").isEmpty());
        assertTrue(Catalogs.find("hipparcos").isEmpty());
    }
}
