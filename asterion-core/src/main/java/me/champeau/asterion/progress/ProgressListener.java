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

import java.util.function.Consumer;

/**
 * Receives the progress of long operations, such as installing a catalog. Applications which
 * embed the library implement it to report progress their own way: all the methods do nothing by
 * default, so only the interesting events need to be handled.
 * <p>
 * Work is often done in parallel: implementations must be thread-safe. The updates of a given
 * step are delivered in order.
 */
public interface ProgressListener {
    /** A step starts. */
    default void started(ProgressStep step) {
    }

    /** A step made progress. */
    default void advanced(ProgressUpdate update) {
    }

    /** A step is over, whether it succeeded or not. */
    default void finished(ProgressStep step) {
    }

    /** Something happened which isn't part of a step. */
    default void info(String message) {
    }

    /** A listener which ignores everything. */
    static ProgressListener none() {
        return Silent.INSTANCE;
    }

    /**
     * A listener which describes progress as lines of text. To keep the output readable, the
     * progress of steps which have many units of work is reported every 2% only.
     *
     * @param output receives the lines
     */
    static ProgressListener text(Consumer<String> output) {
        return new TextProgressListener(output);
    }
}
