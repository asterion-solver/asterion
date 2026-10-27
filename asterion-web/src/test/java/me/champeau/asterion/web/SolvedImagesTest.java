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
package me.champeau.asterion.web;

import me.champeau.asterion.solver.Solution;
import me.champeau.asterion.wcs.Wcs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SolvedImagesTest {
    /** A solution, whatever the image: the tests of kept images don't look at it. */
    static final Solution SOLUTION = new Solution(new Wcs(10, 10, 1, 0.5, new double[]{1e-5, 0, 0, 1e-5}),
            57.3, 28.6, 2, 0, false, 0.01, 0.01, 10, 0.5, 40, "test", List.of());

    @TempDir
    Path uploads;

    /** A clock which only moves when told to. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-15T20:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private Path upload(int size) throws Exception {
        return Files.write(Files.createTempFile(uploads, "upload-", ".fits"), new byte[size]);
    }

    @Test
    void keepsTheLastImages() throws Exception {
        var clock = new TestClock();
        try (var images = new SolvedImages(2, 1000, Duration.ofHours(1), clock)) {
            var upload = upload(100);
            var first = images.keep(upload, "first.fits", SOLUTION);
            assertFalse(Files.exists(upload));
            var image = images.get(first);
            assertEquals("first.fits", image.name());
            assertTrue(image.file().getFileName().toString().endsWith("-first.fits"));
            assertEquals(SOLUTION, image.solution());
            var second = images.keep(upload(100), "second.fits", SOLUTION);
            assertNotEquals(first, second);

            Files.write(image.thumbnail(), new byte[10]);

            // too many images: the oldest is deleted, with its thumbnail
            var third = images.keep(upload(100), "third.fits", SOLUTION);
            assertThrows(NoSuchElementException.class, () -> images.get(first));
            assertFalse(Files.exists(image.file()));
            assertFalse(Files.exists(image.thumbnail()));
            images.get(second);

            // too large
            images.keep(upload(950), "large.fits", SOLUTION);
            assertThrows(NoSuchElementException.class, () -> images.get(second));
            assertThrows(NoSuchElementException.class, () -> images.get(third));

            // too old
            var last = images.keep(upload(10), "last.fits", SOLUTION);
            clock.now = clock.now.plus(Duration.ofMinutes(61));
            assertThrows(NoSuchElementException.class, () -> images.get(last));
            assertThrows(NoSuchElementException.class, () -> images.get(null));
            assertThrows(NoSuchElementException.class, () -> images.get("../../etc/passwd"));
        }
    }

    @Test
    void deletesItsFilesWhenClosed() throws Exception {
        Path file;
        try (var images = new SolvedImages(2, 1000, Duration.ofHours(1), Clock.systemUTC())) {
            file = images.get(images.keep(upload(10), "image.fits", SOLUTION)).file();
            assertTrue(Files.exists(file));
        }
        assertFalse(Files.exists(file));
        assertFalse(Files.exists(file.getParent()));
    }
}
