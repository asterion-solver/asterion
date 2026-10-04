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

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.app.InlineToolkitRunner;
import dev.tamboui.toolkit.element.Element;
import me.champeau.asterion.progress.ProgressListener;
import me.champeau.asterion.progress.ProgressStep;
import me.champeau.asterion.progress.ProgressUpdate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.lineGauge;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.spinner;
import static dev.tamboui.toolkit.Toolkit.text;

/**
 * Displays the progress of long operations live, with TamboUI: the steps which are running are
 * shown at the bottom of the terminal, while the output scrolls above them. The work runs on a
 * worker thread, while the main thread renders the display. All the state of the display is only
 * read and written on the render thread.
 */
final class RichConsole implements Console {
    /** The steps which are running, and their last update. */
    private final LinkedHashMap<ProgressStep, ProgressUpdate> steps = new LinkedHashMap<>();
    private final ProgressListener progress = new Listener();
    /** The display, while work runs. */
    private volatile InlineToolkitRunner runner;
    /** What happens when no step runs. */
    private String status = "";

    @Override
    public void println(String line) {
        var current = runner;
        if (current == null) {
            System.out.println(line);
        } else {
            current.runOnRenderThread(() -> current.println(line));
        }
    }

    @Override
    public void error(String line) {
        var current = runner;
        if (current == null) {
            System.err.println(line);
        } else {
            current.runOnRenderThread(() -> current.println(text(line).red()));
        }
    }

    @Override
    public ProgressListener progress() {
        return progress;
    }

    @Override
    public <T> T run(Callable<T> work) throws Exception {
        try (var display = InlineToolkitRunner.builder(1)
                .tickRate(Duration.ofMillis(80))
                .clearOnClose(true)
                .build()) {
            runner = display;
            var result = new CompletableFuture<T>();
            var worker = Thread.ofPlatform().daemon().name("asterion-worker").start(() -> {
                try {
                    result.complete(work.call());
                } catch (Throwable e) {
                    result.completeExceptionally(e);
                } finally {
                    display.runOnRenderThread(display::quit);
                }
            });
            display.run(this::render);
            if (!result.isDone()) {
                // the user quit while the work was running: it is abandoned
                worker.interrupt();
                throw new CancelledException();
            }
            try {
                return result.get();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof Exception cause) {
                    throw cause;
                }
                throw e;
            }
        } finally {
            runner = null;
        }
    }

    /** Updates the display, if it is running: progress which is reported outside of {@link #run} isn't displayed. */
    private void onRenderThread(Runnable action) {
        var current = runner;
        if (current != null) {
            current.runOnRenderThread(action);
        }
    }

    private Element render() {
        if (steps.isEmpty()) {
            return status.isEmpty() ? text("") : spinner(status).cyan();
        }
        var lines = new ArrayList<Element>();
        for (var update : steps.values()) {
            var step = update.step();
            lines.add(row(spinner().cyan().length(2), text(step.description()).bold().fill()));
            if (step.hasTotal()) {
                lines.add(row(text("").length(2), lineGauge(update.fraction()).filledColor(Color.CYAN).label(amount(update) + " ").fill()));
            } else if (update.completed() > 0) {
                lines.add(row(text("").length(2), text(amount(update)).dim().fill()));
            }
        }
        return column(lines.toArray(Element[]::new));
    }

    /** Describes how much work is done: "3/21 files · 23 MB". */
    private static String amount(ProgressUpdate update) {
        var step = update.step();
        var amount = new StringBuilder().append(update.completed());
        if (step.hasTotal()) {
            amount.append('/').append(step.total());
        }
        amount.append(' ').append(step.unit());
        if (update.bytes() >= 0) {
            amount.append(" · ").append(update.bytes() / (1024 * 1024)).append(" MB");
        }
        return amount.toString();
    }

    private final class Listener implements ProgressListener {
        @Override
        public void started(ProgressStep step) {
            onRenderThread(() -> steps.put(step, new ProgressUpdate(step, 0, -1, "")));
        }

        @Override
        public void advanced(ProgressUpdate update) {
            onRenderThread(() -> {
                steps.replace(update.step(), update);
                if (!update.detail().isEmpty()) {
                    runner.println(text("  " + update.detail()).dim());
                }
            });
        }

        @Override
        public void finished(ProgressStep step) {
            onRenderThread(() -> {
                var last = steps.remove(step);
                var summary = last == null || last.completed() == 0 ? "" : " (" + amount(last) + ")";
                runner.println(text("✓ " + step.description() + summary).green());
            });
        }

        @Override
        public void info(String message) {
            onRenderThread(() -> {
                status = message;
                runner.println(message);
            });
        }
    }
}
