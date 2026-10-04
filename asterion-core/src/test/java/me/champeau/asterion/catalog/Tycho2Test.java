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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Tycho2Test {
    private static final String MAIN = """
            0001 00008 1| |  2.31750494|  2.23184345|  -16.3|   -9.0| 68| 73| 1.7| 1.8|1958.89|1951.94| 4|1.0|1.0|0.9|1.0|12.146|0.158|12.146|0.223|999| |         |  2.31754222|  2.23186444|1.67|1.54| 88.0|100.8| |-0.2
            0001 00013 1| |  1.12558209|  2.26739400|   27.7|   -0.5|  9| 12| 1.2| 1.2|1990.76|1989.25| 8|1.0|0.8|1.0|0.7|10.488|0.038| 8.670|0.015|999|T|         |  1.12551889|  2.26739556|1.81|1.52|  9.3| 12.7| |-0.2
            0001 00033 1|X|            |            |       |       |   |   |    |    |       |       |  |   |   |   |   |12.512|0.228|      |     |  5|T|         |  1.58432611|  0.94286667|1.69|1.58|115.5|125.1| | 0.0
            """;
    private static final String SUPPLEMENT = """
            0002 00580 1|T|003.03708871|+01.57351477|       |       | 42.9| 39.5|     |     | |11.688|0.122|11.171|0.121|999|T|      \s
            0002 01127 2|H|004.36837051|+00.31948829|  -32.1|   -9.1| 13.6|  8.3|  8.2|  5.0|H|      |     |10.279|0.041| 75| |  1397B
            """;

    @Test
    void readsCatalogue(@TempDir Path directory) throws IOException {
        for (var name : Tycho2.files()) {
            var content = name.equals("tyc2.dat.00.gz") ? MAIN : name.startsWith("suppl") ? SUPPLEMENT : "";
            try (var out = new GZIPOutputStream(Files.newOutputStream(directory.resolve(name)))) {
                out.write(content.getBytes(StandardCharsets.ISO_8859_1));
            }
        }
        var stars = Tycho2.read(directory);
        assertEquals(5, stars.size());
        assertEquals(2000.0, stars.epoch());
        assertEquals(2.31750494, Math.toDegrees(stars.ra(0)), 1e-9);
        assertEquals(2.23184345, Math.toDegrees(stars.dec(0)), 1e-9);
        assertEquals(-16.3f, stars.pmRa(0));
        assertEquals(-9.0f, stars.pmDec(0));
        assertEquals(12.146f, stars.mag(0), 1e-3);
        // V = VT - 0.09 (BT - VT)
        assertEquals(8.670 - 0.09 * (10.488 - 8.670), stars.mag(1), 1e-3);
        // no mean position: the observed one is used
        assertEquals(1.58432611, Math.toDegrees(stars.ra(2)), 1e-9);
        assertEquals(0f, stars.pmRa(2));
        assertEquals(12.512f, stars.mag(2), 1e-3);
        // the supplement
        assertEquals(3.03708871, Math.toDegrees(stars.ra(3)), 1e-9);
        // positions are at epoch 1991.25, and are moved to 2000
        assertEquals(0.31948829 - 9.1e-3 * 8.75 / 3600, Math.toDegrees(stars.dec(4)), 1e-9);
        assertEquals(10.279f, stars.mag(4), 1e-3);
    }
}
