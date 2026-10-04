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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Describes progress as lines of text. */
final class TextProgressListener implements ProgressListener {
    /** Steps with more units of work than this are reported every 2% only. */
    private static final int REPORTS_PER_STEP = 50;

    private final Consumer<String> output;
    /** The last part of each step which was reported, out of REPORTS_PER_STEP. */
    private final Map<ProgressStep, Long> reported = new ConcurrentHashMap<>();

    TextProgressListener(Consumer<String> output) {
        this.output = output;
    }

    @Override
    public void started(ProgressStep step) {
        print(step.description());
    }

    @Override
    public void advanced(ProgressUpdate update) {
        var step = update.step();
        if (!update.detail().isEmpty()) {
            print("  " + update.detail());
            return;
        }
        if (step.hasTotal() && step.total() > REPORTS_PER_STEP && update.completed() < step.total()) {
            var part = update.completed() * REPORTS_PER_STEP / step.total();
            if (part <= reported.getOrDefault(step, -1L)) {
                return;
            }
            reported.put(step, part);
        }
        var line = new StringBuilder("  ").append(update.completed());
        if (step.hasTotal()) {
            line.append('/').append(step.total());
        }
        line.append(' ').append(step.unit());
        if (update.bytes() >= 0) {
            line.append(" (").append(update.bytes() / (1024 * 1024)).append(" MB)");
        }
        print(line.toString());
    }

    @Override
    public void finished(ProgressStep step) {
        reported.remove(step);
    }

    @Override
    public void info(String message) {
        print(message);
    }

    private synchronized void print(String line) {
        output.accept(line);
    }
}
