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

/**
 * Forwards the progress of steps which have many units of work a given number of times per step
 * only, such as every 2%, and everything else as it comes.
 */
final class ThrottledProgressListener implements ProgressListener {
    private final ProgressListener delegate;
    private final int reportsPerStep;
    /** The last part of each step which was forwarded, out of reportsPerStep. */
    private final Map<ProgressStep, Long> reported = new ConcurrentHashMap<>();

    ThrottledProgressListener(ProgressListener delegate, int reportsPerStep) {
        this.delegate = delegate;
        this.reportsPerStep = reportsPerStep;
    }

    @Override
    public void started(ProgressStep step) {
        delegate.started(step);
    }

    @Override
    public void advanced(ProgressUpdate update) {
        var step = update.step();
        // details, and the end of a step, are always forwarded
        if (update.detail().isEmpty() && step.hasTotal() && step.total() > reportsPerStep && update.completed() < step.total()) {
            var part = update.completed() * reportsPerStep / step.total();
            if (part <= reported.getOrDefault(step, -1L)) {
                return;
            }
            reported.put(step, part);
        }
        delegate.advanced(update);
    }

    @Override
    public void finished(ProgressStep step) {
        reported.remove(step);
        delegate.finished(step);
    }

    @Override
    public void info(String message) {
        delegate.info(message);
    }
}
