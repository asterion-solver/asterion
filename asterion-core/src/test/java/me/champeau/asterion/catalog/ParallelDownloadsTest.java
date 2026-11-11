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
package me.champeau.asterion.catalog;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelDownloadsTest {
    @Test
    void runsEveryDownloadWithAsManyConnectionsAtATime() throws IOException {
        var downloaded = ConcurrentHashMap.<Integer>newKeySet();
        var running = new AtomicInteger();
        var maxRunning = new AtomicInteger();
        ParallelDownloads.run(3, 50, index -> {
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            downloaded.add(index);
            running.decrementAndGet();
        });
        assertEquals(50, downloaded.size());
        assertTrue(maxRunning.get() <= 3, String.valueOf(maxRunning.get()));
    }

    @Test
    void aFailureStopsTheOtherDownloadsAndIsThrownOnceTheyStopped() {
        var failure = new IOException("HTTP status 503");
        var started = new CountDownLatch(2);
        var blocked = new AtomicInteger();
        var stopped = new AtomicInteger();
        var thrown = assertThrows(IOException.class, () -> ParallelDownloads.run(3, 100, index -> {
            if (index == 2) {
                try {
                    started.await();
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
                throw failure;
            }
            blocked.incrementAndGet();
            blockUntilInterrupted(started, stopped);
        }));
        assertSame(failure, thrown);
        // the downloads which started were interrupted, and the next ones never started
        assertEquals(blocked.get(), stopped.get());
        assertTrue(blocked.get() <= 3, String.valueOf(blocked.get()));
    }

    @Test
    void interruptingTheWaitingThreadStopsTheDownloadsAndWaitsForThem() throws InterruptedException {
        var started = new CountDownLatch(3);
        var stopped = new AtomicInteger();
        var waiting = Thread.currentThread();
        var interrupter = Thread.ofPlatform().start(() -> {
            try {
                started.await();
            } catch (InterruptedException e) {
                return;
            }
            waiting.interrupt();
        });
        try {
            assertThrows(InterruptedIOException.class,
                    () -> ParallelDownloads.run(3, 100, index -> blockUntilInterrupted(started, stopped)));
            assertEquals(3, stopped.get());
            // the thread stays interrupted, for what waits for it
            assertTrue(Thread.interrupted());
        } finally {
            interrupter.join();
        }
    }

    /** A download which only stops when it's interrupted, as an HTTP request does. */
    private static void blockUntilInterrupted(CountDownLatch started, AtomicInteger stopped) throws IOException {
        started.countDown();
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            stopped.incrementAndGet();
            throw new IOException("The download was interrupted", e);
        }
    }
}
