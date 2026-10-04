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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.function.LongConsumer;

/** Downloads files over HTTP. */
final class Downloader implements AutoCloseable {
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    HttpClient client() {
        return client;
    }

    @Override
    public void close() {
        client.close();
    }

    /** Downloads a small text file. */
    String text(String url) throws IOException {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(1)).GET().build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException("HTTP status " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download of " + url + " was interrupted", e);
        }
    }

    /**
     * Downloads a file, or the rest of it if a previous download was interrupted.
     *
     * @param transferred receives the number of bytes which are written to the file, including the ones of the previous download
     */
    void resume(String url, Path target, LongConsumer transferred) throws IOException {
        var existing = Files.exists(target) ? Files.size(target) : 0;
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2)).GET();
        if (existing > 0) {
            builder.header("Range", "bytes=" + existing + "-");
        }
        try {
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                switch (response.statusCode()) {
                    // the server ignores ranges: the file is downloaded again
                    case 200 -> existing = 0;
                    case 206 -> {
                    }
                    // the file was complete
                    case 416 -> {
                        transferred.accept(existing);
                        return;
                    }
                    default -> throw new IOException("HTTP status " + response.statusCode());
                }
                transferred.accept(existing);
                var options = existing > 0
                        ? new OpenOption[]{StandardOpenOption.APPEND}
                        : new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE};
                try (var out = Files.newOutputStream(target, options)) {
                    var buffer = new byte[1 << 16];
                    int n;
                    while ((n = body.read(buffer)) >= 0) {
                        out.write(buffer, 0, n);
                        transferred.accept(n);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download of " + url + " was interrupted", e);
        }
    }

    /**
     * Downloads a file, unless it's already there.
     *
     * @return the size of the file
     */
    long download(String url, Path target) throws IOException {
        if (Files.exists(target)) {
            return Files.size(target);
        }
        var part = target.resolveSibling(target.getFileName() + ".part");
        IOException failure = null;
        for (var attempt = 0; attempt < 3; attempt++) {
            try {
                var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (var body = response.body()) {
                    if (response.statusCode() != 200) {
                        throw new IOException("Unable to download " + url + ": HTTP status " + response.statusCode());
                    }
                    Files.copy(body, part, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                return Files.size(target);
            } catch (IOException e) {
                failure = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Download of " + url + " was interrupted", e);
            }
        }
        throw failure;
    }
}
