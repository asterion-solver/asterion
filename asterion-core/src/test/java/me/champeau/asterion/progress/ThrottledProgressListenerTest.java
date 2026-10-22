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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThrottledProgressListenerTest {
    /** Records what it receives, as text. */
    private static final class Recorder implements ProgressListener {
        final List<String> events = new ArrayList<>();

        @Override
        public void started(ProgressStep step) {
            events.add("started " + step.description());
        }

        @Override
        public void advanced(ProgressUpdate update) {
            events.add(update.detail().isEmpty() ? "advanced " + update.completed() : "detail " + update.detail());
        }

        @Override
        public void finished(ProgressStep step) {
            events.add("finished " + step.description());
        }

        @Override
        public void info(String message) {
            events.add("info " + message);
        }
    }

    @Test
    void forwardsTheProgressOfLongStepsAGivenNumberOfTimes() {
        var recorder = new Recorder();
        var listener = ProgressListener.throttled(recorder, 10);
        var step = new ProgressStep("Indexing", 1000, "stars");
        listener.started(step);
        for (var completed = 0; completed <= 1000; completed++) {
            listener.advanced(new ProgressUpdate(step, completed, -1, ""));
        }
        listener.finished(step);

        // every tenth, and the end
        assertEquals(List.of("started Indexing", "advanced 0", "advanced 100", "advanced 200", "advanced 300", "advanced 400",
                "advanced 500", "advanced 600", "advanced 700", "advanced 800", "advanced 900", "advanced 1000", "finished Indexing"),
                recorder.events);
    }

    @Test
    void forwardsEverythingElseAsItComes() {
        var recorder = new Recorder();
        var listener = ProgressListener.throttled(recorder, 10);
        var small = new ProgressStep("Reading", 5, "files");
        var unknown = new ProgressStep("Downloading", -1, "MB");
        var large = new ProgressStep("Indexing", 1000, "stars");
        for (var completed = 1; completed <= 3; completed++) {
            listener.advanced(new ProgressUpdate(small, completed, -1, ""));
            listener.advanced(new ProgressUpdate(unknown, completed, -1, ""));
            listener.advanced(new ProgressUpdate(large, completed, -1, "mirror " + completed));
        }
        listener.info("Done");

        assertEquals(List.of("advanced 1", "advanced 1", "detail mirror 1", "advanced 2", "advanced 2", "detail mirror 2", "advanced 3",
                "advanced 3", "detail mirror 3", "info Done"), recorder.events);
    }

    @Test
    void startsAgainWhenAStepIsRepeated() {
        var recorder = new Recorder();
        var listener = ProgressListener.throttled(recorder, 10);
        var step = new ProgressStep("Downloading", 1000, "kB");
        listener.advanced(new ProgressUpdate(step, 500, -1, ""));
        listener.finished(step);
        listener.advanced(new ProgressUpdate(step, 0, -1, ""));

        assertEquals(List.of("advanced 500", "finished Downloading", "advanced 0"), recorder.events);
    }
}
