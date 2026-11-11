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

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;

/**
 * Downloads several files at a time, as many as servers accept. Unlike a parallel stream, the downloads
 * stop as soon as one fails or the thread which waits for them is interrupted, such as when an installation
 * is cancelled: the other ones are interrupted, which stops their transfers, and waited for.
 */
final class ParallelDownloads {
    private ParallelDownloads() {
    }

    /** A download, of the file of an index. */
    @FunctionalInterface
    interface Download {
        void run(int index) throws IOException;
    }

    /**
     * Runs downloads, in their order, and waits for them.
     *
     * @param connections how many downloads run at a time
     * @param count the number of downloads, which get the indexes from 0 to count - 1
     * @throws InterruptedIOException if the calling thread is interrupted, which it stays
     */
    static void run(int connections, int count, Download download) throws IOException {
        var threads = Thread.ofPlatform().name("asterion-download-", 0).daemon().factory();
        try (var executor = Executors.newFixedThreadPool(connections, threads)) {
            var downloads = new ExecutorCompletionService<Void>(executor);
            for (var i = 0; i < count; i++) {
                var index = i;
                downloads.submit(() -> {
                    download.run(index);
                    return null;
                });
            }
            try {
                for (var i = 0; i < count; i++) {
                    downloads.take().get();
                }
            } catch (InterruptedException | ExecutionException e) {
                // the executor waits for the downloads which run when it's closed: they must stop first
                executor.shutdownNow();
                throw e;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("The downloads were interrupted");
        } catch (ExecutionException e) {
            switch (e.getCause()) {
                case IOException cause -> throw cause;
                case RuntimeException cause -> throw cause;
                case Error cause -> throw cause;
                case Throwable cause -> throw new IOException(cause);
            }
        }
    }
}
