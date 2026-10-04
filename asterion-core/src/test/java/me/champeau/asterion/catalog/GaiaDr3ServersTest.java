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

import com.sun.net.httpserver.HttpServer;
import me.champeau.asterion.progress.ProgressListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GaiaDr3ServersTest {
    private static final Pattern TOP = Pattern.compile("TOP (\\d+)");
    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void stopServers() {
        servers.forEach(server -> server.stop(0));
    }

    /**
     * Starts a fake TAP service.
     *
     * @param status the HTTP status of answers
     * @param maxRows the number of rows the server returns at most, whatever it's asked for
     */
    private GaiaDr3.Server server(String name, int status, int maxRows, AtomicInteger queries) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tap/sync", exchange -> {
            queries.incrementAndGet();
            var form = URLDecoder.decode(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8);
            var matcher = TOP.matcher(form);
            var rows = matcher.find() ? Math.min(Integer.parseInt(matcher.group(1)), maxRows) : 0;
            var csv = new StringBuilder("ra,dec,pmra,pmdec,phot_g_mean_mag\n");
            for (var i = 0; i < rows; i++) {
                // some servers write NaN when proper motions are missing
                csv.append(i % 2 == 0 ? "10.5,20.5,1.5,-2.5," : "10.5,20.5,NaN,NaN,").append(5 + i * 1e-4).append('\n');
            }
            var body = csv.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        servers.add(server);
        return new GaiaDr3.Server(name, "http://127.0.0.1:" + server.getAddress().getPort() + "/tap/sync", "gaiadr3.gaia_source");
    }

    @Test
    void usesTheServersWhichWork(@TempDir Path work) throws IOException {
        var failingQueries = new AtomicInteger();
        var limitedQueries = new AtomicInteger();
        var workingQueries = new AtomicInteger();
        var failing = server("failing", 500, Integer.MAX_VALUE, failingQueries);
        var limited = server("limited", 200, 100, limitedQueries);
        var working = server("working", 200, Integer.MAX_VALUE, workingQueries);
        var messages = new ConcurrentLinkedQueue<String>();
        var stars = GaiaDr3.fetch(work, 10, List.of(failing, limited, working), ProgressListener.text(messages::add));
        var perChunk = (int) Math.ceil(10 * 41252.96 / GaiaDr3.CHUNKS);
        assertEquals(perChunk * GaiaDr3.CHUNKS, stars.size());
        // every region comes from the server which works, which is asked once per region
        assertEquals(GaiaDr3.CHUNKS, workingQueries.get());
        assertTrue(failingQueries.get() > 0);
        assertTrue(limitedQueries.get() > 0);
        // the failures are reported once per server, and the others are used first afterwards
        assertEquals(1, messages.stream().filter(m -> m.startsWith("Failing failed (HTTP status 500)")).count(), messages::toString);
        assertEquals(1, messages.stream().filter(m -> m.startsWith("Limited failed (results are limited to 100 rows)")).count(),
                messages::toString);
        assertTrue(failingQueries.get() < GaiaDr3.CHUNKS / 2, () -> failingQueries + " queries");
        assertEquals(1.5f, stars.pmRa(0));
        assertEquals(0f, stars.pmRa(1));
    }
}
