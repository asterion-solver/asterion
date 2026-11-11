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
package me.champeau.asterion.lib;

import me.champeau.asterion.progress.ProgressListener;
import me.champeau.asterion.progress.ProgressStep;
import me.champeau.asterion.progress.ProgressUpdate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Runs the installation of a catalog in a thread of its own, and hands its progress to the thread
 * which waits for it: a program gets its callbacks on its own thread, one at a time, while the
 * installation downloads and indexes with several threads. Meanwhile, that thread asks the program
 * whether it cancels, whether there is progress or not.
 */
final class Installation {
    private Installation() {
    }

    /** What is installed. */
    @FunctionalInterface
    interface Task {
        Path install(ProgressListener progress) throws IOException;
    }

    /** The end of the events of an installation. */
    private static final Consumer<ProgressListener> END = listener -> {
    };

    /** How often the program is asked whether it cancels, when there's no progress to deliver. */
    private static final long CANCEL_POLL_MILLIS = 100;

    /**
     * Installs, and waits for the installation to be over.
     *
     * @param listener receives the progress, on the calling thread
     * @param cancelled tells, before it starts, after each event and every 100 ms, if the program wants the
     *                  installation to stop: it stops as soon as it can, and the listener receives nothing more
     * @return what the task returns, when it's over before it could stop
     * @throws CancellationException if the installation stopped
     */
    static Path run(Task task, ProgressListener listener, BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("The installation was cancelled");
        }
        var events = new LinkedBlockingQueue<Consumer<ProgressListener>>();
        var outcome = new CompletableFuture<Path>();
        var forwarder = new Forwarder(events);
        // every 1% of the steps which have many units of work, like the JSON of the command line tool
        var progress = ProgressListener.throttled(forwarder, 100);
        var worker = Thread.ofPlatform().name("asterion-install").start(() -> {
            try {
                outcome.complete(task.install(progress));
            } catch (Throwable e) {
                outcome.completeExceptionally(e);
            } finally {
                events.add(END);
            }
        });
        var stopping = false;
        var interrupted = false;
        Throwable listenerFailure = null;
        while (true) {
            Consumer<ProgressListener> event;
            try {
                event = events.poll(CANCEL_POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                // the installation stops, and is waited for: its files aren't left half written
                interrupted = true;
                stopping = true;
                stop(forwarder, worker);
                continue;
            }
            if (event == END) {
                break;
            }
            if (!stopping) {
                if (event != null) {
                    try {
                        event.accept(listener);
                    } catch (RuntimeException | Error e) {
                        // thrown once the installation is over, like the other ways to stop it
                        listenerFailure = e;
                    }
                }
                if (listenerFailure != null || cancelled.getAsBoolean()) {
                    stopping = true;
                    stop(forwarder, worker);
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        switch (listenerFailure) {
            case null -> {
            }
            case RuntimeException e -> throw e;
            case Error e -> throw e;
            default -> throw new IllegalStateException(listenerFailure);
        }
        // the end of the events comes after the outcome
        if (outcome.state() == Future.State.SUCCESS) {
            return outcome.resultNow();
        }
        if (stopping) {
            throw new CancellationException("The installation was cancelled");
        }
        switch (outcome.exceptionNow()) {
            case IOException e -> throw e;
            case RuntimeException e -> throw e;
            case Error e -> throw e;
            case Throwable e -> throw new IOException(e);
        }
    }

    /**
     * Stops an installation: the thread which runs it is interrupted, which stops what waits, such as
     * downloads, and the threads which report progress, such as the ones which index, fail when they do.
     */
    private static void stop(Forwarder forwarder, Thread worker) {
        forwarder.stopped = true;
        worker.interrupt();
    }

    /** Queues the progress of the installation, for the waiting thread, until it stops. */
    private static final class Forwarder implements ProgressListener {
        private final LinkedBlockingQueue<Consumer<ProgressListener>> events;
        private volatile boolean stopped;

        Forwarder(LinkedBlockingQueue<Consumer<ProgressListener>> events) {
            this.events = events;
        }

        @Override
        public void started(ProgressStep step) {
            forward(listener -> listener.started(step));
        }

        @Override
        public void advanced(ProgressUpdate update) {
            forward(listener -> listener.advanced(update));
        }

        @Override
        public void finished(ProgressStep step) {
            forward(listener -> listener.finished(step));
        }

        @Override
        public void info(String message) {
            forward(listener -> listener.info(message));
        }

        private void forward(Consumer<ProgressListener> event) {
            if (stopped) {
                throw new CancellationException("The installation was cancelled");
            }
            events.add(event);
        }
    }
}
