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
package me.champeau.asterion.progress;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressTrackerTest {
    /** Records the events it receives. */
    private static final class Recorder implements ProgressListener {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final List<ProgressUpdate> updates = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void started(ProgressStep step) {
            events.add("started " + step.description());
        }

        @Override
        public void advanced(ProgressUpdate update) {
            updates.add(update);
            events.add("advanced " + update.completed() + " " + update.bytes() + " " + update.detail());
        }

        @Override
        public void finished(ProgressStep step) {
            events.add("finished " + step.description());
        }
    }

    @Test
    void reportsCumulativeProgress() {
        var recorder = new Recorder();
        try (var tracker = ProgressTracker.start(recorder, "Downloading", 3, "files")) {
            tracker.advance(1, 100);
            tracker.advance(1, 50);
            tracker.advance(1, "the last one");
        }
        assertEquals(
                List.of("started Downloading", "advanced 1 100 ", "advanced 2 150 ", "advanced 3 150 the last one", "finished Downloading"),
                recorder.events);
        assertEquals(1.0, recorder.updates.getLast().fraction());
    }

    @Test
    void reportsNoBytesForStepsWhichTransferNothing() {
        var recorder = new Recorder();
        try (var tracker = ProgressTracker.start(recorder, "Building", -1, "levels")) {
            tracker.advance(2);
        }
        var update = recorder.updates.getFirst();
        assertEquals(-1, update.bytes());
        assertTrue(Double.isNaN(update.fraction()));
    }

    @Test
    void reportsTheEndOfFailedSteps() {
        var recorder = new Recorder();
        assertThrows(IllegalStateException.class, () -> {
            try (var tracker = ProgressTracker.start(recorder, "Failing", 2, "files")) {
                tracker.advance(1);
                throw new IllegalStateException("network error");
            }
        });
        assertEquals("finished Failing", recorder.events.getLast());
    }

    @Test
    void deliversConcurrentUpdatesInOrder() {
        var recorder = new Recorder();
        try (var tracker = ProgressTracker.start(recorder, "Parallel", 10_000, "items")) {
            IntStream.range(0, 10_000).parallel().forEach(_ -> tracker.advance(1, 1));
        }
        assertEquals(10_000, recorder.updates.size());
        for (var i = 0; i < recorder.updates.size(); i++) {
            assertEquals(i + 1, recorder.updates.get(i).completed());
            assertEquals(i + 1, recorder.updates.get(i).bytes());
        }
    }

    @Test
    void describesProgressAsText() {
        var lines = new ArrayList<String>();
        var listener = ProgressListener.text(lines::add);
        listener.info("Installing");
        try (var tracker = ProgressTracker.start(listener, "Downloading regions", 768, "regions")) {
            for (var i = 0; i < 768; i++) {
                tracker.advance(1, 2 * 1024 * 1024);
            }
        }
        try (var tracker = ProgressTracker.start(listener, "Building quads", 2, "levels")) {
            tracker.advance(1, "Level 1/2: 10 quads");
            tracker.advance(1, "Level 2/2: 20 quads");
        }
        assertEquals("Installing", lines.getFirst());
        assertEquals("Downloading regions", lines.get(1));
        // every 2% at most, and always the last update
        var regions = lines.stream().filter(l -> l.endsWith(" MB)")).toList();
        assertTrue(regions.size() <= 51, regions.size() + " lines");
        assertEquals("  768/768 regions (1536 MB)", regions.getLast());
        assertEquals(List.of("Building quads", "  Level 1/2: 10 quads", "  Level 2/2: 20 quads"),
                lines.subList(lines.size() - 3, lines.size()));
    }

    @Test
    void silentListenerIgnoresEverything() {
        try (var tracker = ProgressTracker.start(ProgressListener.none(), "Nothing", 1, "items")) {
            tracker.advance(1);
        }
        ProgressListener.none().info("ignored");
    }
}
