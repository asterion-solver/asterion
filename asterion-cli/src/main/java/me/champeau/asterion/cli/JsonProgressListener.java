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
package me.champeau.asterion.cli;

import me.champeau.asterion.progress.ProgressListener;
import me.champeau.asterion.progress.ProgressStep;
import me.champeau.asterion.progress.ProgressUpdate;

import java.util.function.Consumer;

/**
 * Describes progress as JSON objects, one per line, for the programs which run asterion: each
 * object has an {@code event}, which is one of
 * <ul>
 *     <li>{@code step}: a step starts, with its {@code step} description, and its {@code total} and {@code unit} of work
 *     when they are known</li>
 *     <li>{@code progress}: work is done in a step, with its {@code step}, {@code completed}, {@code total}, {@code unit},
 *     {@code fraction} from 0 to 1 when the total is known, and {@code bytes} when bytes are counted</li>
 *     <li>{@code detail}: something happens in a step, with its {@code step} and a {@code message}</li>
 *     <li>{@code done}: a step is finished, with its {@code step}</li>
 *     <li>{@code info}: something happens outside of steps, with a {@code message}</li>
 * </ul>
 * {@link #create} throttles the progress of steps which have many units of work to every 1%.
 */
final class JsonProgressListener implements ProgressListener {
    private final Consumer<String> output;

    JsonProgressListener(Consumer<String> output) {
        this.output = output;
    }

    /** A listener which prints the progress as JSON lines, every 1% for the steps which have many units of work. */
    static ProgressListener create(Consumer<String> output) {
        return ProgressListener.throttled(new JsonProgressListener(output), 100);
    }

    @Override
    public void started(ProgressStep step) {
        var event = Json.object().field("event", "step").field("step", step.description());
        if (step.hasTotal()) {
            event.field("total", step.total());
        }
        print(event.field("unit", step.unit()));
    }

    @Override
    public void advanced(ProgressUpdate update) {
        var step = update.step();
        if (!update.detail().isEmpty()) {
            print(Json.object().field("event", "detail").field("step", step.description()).field("message", update.detail()));
            return;
        }
        var event = Json.object().field("event", "progress").field("step", step.description()).field("completed", update.completed());
        if (step.hasTotal()) {
            event.field("total", step.total());
        }
        event.field("unit", step.unit());
        var fraction = update.fraction();
        if (Double.isFinite(fraction)) {
            event.field("fraction", fraction);
        }
        if (update.bytes() >= 0) {
            event.field("bytes", update.bytes());
        }
        print(event);
    }

    @Override
    public void finished(ProgressStep step) {
        print(Json.object().field("event", "done").field("step", step.description()));
    }

    @Override
    public void info(String message) {
        print(Json.object().field("event", "info").field("message", message));
    }

    private synchronized void print(Json event) {
        output.accept(event.toString());
    }
}
