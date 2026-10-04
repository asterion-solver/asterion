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

/**
 * Tracks the progress of a step, and reports it to a listener. A tracker may be updated from
 * several threads. It is meant to be used with a try-with-resources statement, so that the end
 * of the step is always reported:
 * <pre>{@code
 * try (var tracker = ProgressTracker.start(listener, "Downloading files", files.size(), "files")) {
 *     for (var file : files) {
 *         tracker.advance(1, download(file));
 *     }
 * }
 * }</pre>
 */
public final class ProgressTracker implements AutoCloseable {
    private final ProgressListener listener;
    private final ProgressStep step;
    private long completed;
    private long bytes = -1;

    private ProgressTracker(ProgressListener listener, ProgressStep step) {
        this.listener = listener;
        this.step = step;
    }

    /**
     * Starts a step.
     *
     * @param total the amount of work of the step, or -1 if it isn't known
     * @param unit the unit of the work, in plural form, like "files"
     */
    public static ProgressTracker start(ProgressListener listener, String description, long total, String unit) {
        var tracker = new ProgressTracker(listener, new ProgressStep(description, total, unit));
        listener.started(tracker.step);
        return tracker;
    }

    /** Reports that some units of work are done. */
    public void advance(long units) {
        update(units, 0, "");
    }

    /** Reports that some units of work are done, which transferred data. */
    public void advance(long units, long transferredBytes) {
        update(units, transferredBytes, "");
    }

    /** Reports that some units of work are done, with a description of what was done. */
    public void advance(long units, String detail) {
        update(units, 0, detail);
    }

    // synchronized, so that updates are delivered in order
    private synchronized void update(long units, long transferredBytes, String detail) {
        completed += units;
        if (transferredBytes > 0) {
            bytes = Math.max(bytes, 0) + transferredBytes;
        }
        listener.advanced(new ProgressUpdate(step, completed, bytes, detail));
    }

    @Override
    public void close() {
        listener.finished(step);
    }
}
