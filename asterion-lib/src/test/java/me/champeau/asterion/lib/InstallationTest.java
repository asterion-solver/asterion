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
import me.champeau.asterion.progress.ProgressTracker;
import me.champeau.asterion.progress.ProgressUpdate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallationTest {
    private static final Path INSTALLED = Path.of("catalogs", "test.astx");

    /** Records the events, and the threads which deliver them. */
    private static final class Recorder implements ProgressListener {
        final List<String> events = new ArrayList<>();
        final List<Thread> threads = new ArrayList<>();

        @Override
        public void started(ProgressStep step) {
            record("started " + step.description());
        }

        @Override
        public void advanced(ProgressUpdate update) {
            record("advanced " + update.completed());
        }

        @Override
        public void finished(ProgressStep step) {
            record("finished " + step.description());
        }

        @Override
        public void info(String message) {
            record("info " + message);
        }

        private void record(String event) {
            events.add(event);
            threads.add(Thread.currentThread());
        }
    }

    @Test
    void deliversTheProgressOfSeveralThreadsOnTheCallingThreadInOrder() throws IOException {
        var recorder = new Recorder();
        var installed = Installation.run(progress -> {
            progress.info("starting");
            try (var tracker = ProgressTracker.start(progress, "Downloading", 1000, "files")) {
                IntStream.range(0, 1000).parallel().forEach(i -> tracker.advance(1));
            }
            return INSTALLED;
        }, recorder, () -> false);

        assertSame(INSTALLED, installed);
        assertTrue(recorder.threads.stream().allMatch(thread -> thread == Thread.currentThread()), recorder.threads.toString());
        assertEquals("info starting", recorder.events.getFirst());
        assertEquals("started Downloading", recorder.events.get(1));
        assertEquals("finished Downloading", recorder.events.getLast());
        // the first unit of work, every 1% of the step, in order, up to its end
        var completed = recorder.events.subList(2, recorder.events.size() - 1).stream()
                .map(event -> Long.parseLong(event.substring("advanced ".length())))
                .toList();
        assertEquals(101, completed.size(), completed.toString());
        assertEquals(completed.stream().sorted().toList(), completed);
        assertEquals(1000, completed.getLast());
    }

    @Test
    void cancellingInterruptsTheInstallationAndWaitsForIt() {
        var recorder = new Recorder();
        var interrupted = new AtomicBoolean();
        var cancelled = assertThrows(CancellationException.class, () -> Installation.run(progress -> {
            progress.info("downloading");
            try {
                // a download, which an interruption stops
                new CountDownLatch(1).await();
                return INSTALLED;
            } catch (InterruptedException e) {
                interrupted.set(true);
                progress.info("interrupted");
                throw new IOException("The download was interrupted", e);
            }
        }, recorder, () -> !recorder.events.isEmpty()));

        assertEquals("The installation was cancelled", cancelled.getMessage());
        assertTrue(interrupted.get());
        // nothing is delivered once the program cancels
        assertEquals(List.of("info downloading"), recorder.events);
    }

    @Test
    void aListenerWhichFailsStopsTheInstallationAndWaitsForIt() {
        var interrupted = new AtomicBoolean();
        var failure = new IllegalStateException("The listener failed");
        var thrown = assertThrows(IllegalStateException.class, () -> Installation.run(progress -> {
            progress.info("downloading");
            try {
                new CountDownLatch(1).await();
                return INSTALLED;
            } catch (InterruptedException e) {
                interrupted.set(true);
                throw new IOException("The download was interrupted", e);
            }
        }, new ProgressListener() {
            @Override
            public void info(String message) {
                throw failure;
            }
        }, () -> false));

        assertSame(failure, thrown);
        // the installation was stopped and waited for
        assertTrue(interrupted.get());
    }

    @Test
    @Timeout(10)
    void theProgramCancelsWhileThereIsNoProgress() {
        var cancel = new AtomicBoolean();
        var interrupted = new AtomicBoolean();
        assertThrows(CancellationException.class, () -> Installation.run(progress -> {
            try {
                // a query which a server takes minutes to answer
                cancel.set(true);
                new CountDownLatch(1).await();
                return INSTALLED;
            } catch (InterruptedException e) {
                interrupted.set(true);
                throw new IOException("The download was interrupted", e);
            }
        }, new Recorder(), cancel::get));

        assertTrue(interrupted.get());
    }

    @Test
    @Timeout(10)
    void threadsWhichIgnoreInterruptionsStopWhenTheyReportProgress() {
        var recorder = new Recorder();
        assertThrows(CancellationException.class, () -> Installation.run(progress -> {
            // an index which a pool of threads builds: neither they nor what waits for them are interrupted
            CompletableFuture.runAsync(() -> {
                while (true) {
                    progress.info("indexing");
                }
            }).join();
            return INSTALLED;
        }, recorder, () -> !recorder.events.isEmpty()));

        // the program cancelled after the first event, and got nothing more
        assertEquals(List.of("info indexing"), recorder.events);
    }

    @Test
    void anInstallationWhichIsCancelledAlreadyDoesNotStart() {
        var started = new AtomicBoolean();
        assertThrows(CancellationException.class, () -> Installation.run(progress -> {
            started.set(true);
            return INSTALLED;
        }, new Recorder(), () -> true));

        assertFalse(started.get());
    }

    @Test
    void anInstallationWhichIsOverBeforeItCanStopSucceeds() throws IOException {
        var recorder = new Recorder();
        var installed = Installation.run(progress -> {
            progress.info("installed");
            return INSTALLED;
        }, recorder, () -> !recorder.events.isEmpty());

        assertSame(INSTALLED, installed);
    }

    @Test
    void failuresAreThoseOfTheInstallation() {
        var failure = new IOException("HTTP status 503");
        var thrown = assertThrows(IOException.class, () -> Installation.run(progress -> {
            throw failure;
        }, new Recorder(), () -> false));

        assertSame(failure, thrown);
    }
}
