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
package me.champeau.asterion.web;

import me.champeau.asterion.catalog.Catalogs;
import me.champeau.asterion.catalog.StarData;
import me.champeau.asterion.index.IndexBuilder;
import me.champeau.asterion.index.IndexOptions;
import me.champeau.asterion.progress.ProgressListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebServerTest {
    @TempDir
    private Path catalogs;
    private WebServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws IOException {
        server = WebServer.start("127.0.0.1", 0, catalogs);
    }

    @AfterEach
    void stop() {
        server.close();
        client.close();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(server.uri().resolve(path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, byte[] body) throws Exception {
        var request = HttpRequest.newBuilder(server.uri().resolve(path)).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void listensOnTheLoopbackAddress() {
        assertEquals("127.0.0.1", server.uri().getHost());
        assertTrue(server.uri().getPort() > 0);
        assertEquals(URI.create("http://127.0.0.1:" + server.uri().getPort() + "/"), server.uri());
    }

    @Test
    void servesTheInterface() throws Exception {
        var page = get("/");
        assertEquals(200, page.statusCode());
        assertTrue(page.headers().firstValue("Content-Type").orElseThrow().startsWith("text/html"));
        assertTrue(page.body().contains("<title>Asterion Solver</title>"));
        assertEquals(200, get("/app.js").statusCode());
        assertEquals(200, get("/style.css").statusCode());
        var sky = get("/sky.json");
        assertEquals(200, sky.statusCode());
        assertTrue(sky.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
        assertTrue(sky.body().contains("\"Ori\":\"Orion\""));
        assertEquals(404, get("/secret.txt").statusCode());
        assertEquals(404, get("/web/../../etc/passwd").statusCode());
    }

    @Test
    void listsCatalogs() throws Exception {
        var response = get("/api/catalogs");
        assertEquals(200, response.statusCode());
        var body = response.body();
        assertTrue(body.contains("\"installed\":[]"), body);
        assertTrue(body.contains("\"name\":\"gaia-500\""), body);
        assertTrue(body.contains("\"installations\":[]"), body);
        assertTrue(body.contains("\"directory\":"), body);
    }

    @Test
    void reportsErrorsAsJson() throws Exception {
        var noCatalog = post("/api/solve?name=image.fits", new byte[]{1, 2, 3});
        assertEquals(409, noCatalog.statusCode());
        assertTrue(noCatalog.body().startsWith("{\"error\":\"No catalog is installed"), noCatalog.body());

        var invalid = post("/api/solve?ra=abc&dec=1", new byte[1000]);
        assertEquals(400, invalid.statusCode());
        assertEquals("{\"error\":\"Invalid value for ra: abc\"}", invalid.body());

        var wrongMethod = get("/api/solve");
        assertEquals(400, wrongMethod.statusCode());

        var expired = get("/api/download?id=0123456789abcdef&format=fits");
        assertEquals(404, expired.statusCode());
        assertEquals("{\"error\":\"This image isn't kept anymore: solve it again\"}", expired.body());
        assertEquals(404, get("/api/download?format=wcs").statusCode());

        // the ASTAP compatible executable can't be installed by a JVM
        var astap = get("/api/astap");
        assertEquals(200, astap.statusCode());
        assertTrue(astap.body().startsWith("{\"native\":false,\"available\":false,"), astap.body());
        assertEquals(409, post("/api/astap", new byte[0]).statusCode());

        var unknown = post("/api/install?name=hipparcos", new byte[0]);
        assertEquals(400, unknown.statusCode());
        assertEquals("{\"error\":\"Unknown catalog hipparcos\"}", unknown.body());
    }

    @Test
    void chartsTheStarsOfACatalog() throws Exception {
        assertEquals(409, get("/api/stars?ra=10&dec=20&radius=5").statusCode());
        var random = new SplittableRandom(42);
        var sky = new StarData(2016.0, 20000);
        for (var i = 0; i < 20000; i++) {
            sky.add(random.nextDouble() * 2 * Math.PI, Math.asin(2 * random.nextDouble() - 1), 0, 0, (float) (6 + 8 * random.nextDouble()));
        }
        IndexBuilder.build(sky, "test", IndexOptions.defaults(), catalogs.resolve("test" + Catalogs.EXTENSION), ProgressListener.none());

        var response = get("/api/stars?ra=10&dec=20&radius=8&count=50");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().startsWith("{\"catalog\":\"test\",\"levels\":"), response.body());
        var values = response.body().replaceAll(".*\"stars\":\\[(.*)]}", "$1").split(",");
        assertEquals(0, values.length % 3);
        // the brightest levels, until there are enough stars, in the cone
        assertTrue(values.length / 3 >= 50, response.body());
        var center = vector(10, 20);
        for (var i = 0; i < values.length; i += 3) {
            var star = vector(Double.parseDouble(values[i]), Double.parseDouble(values[i + 1]));
            var distance = Math.toDegrees(Math.acos(Math.min(1, center[0] * star[0] + center[1] * star[1] + center[2] * star[2])));
            assertTrue(distance <= 8.001, "Star at " + distance + "°");
        }

        assertEquals(400, get("/api/stars?ra=10&dec=20").statusCode());
        assertEquals(400, get("/api/stars?ra=10&dec=20&radius=45").statusCode());
        assertEquals(400, get("/api/stars?ra=10&dec=20&radius=5&catalog=gaia-500").statusCode());
    }

    private static double[] vector(double raDeg, double decDeg) {
        var ra = Math.toRadians(raDeg);
        var dec = Math.toRadians(decDeg);
        return new double[]{Math.cos(dec) * Math.cos(ra), Math.cos(dec) * Math.sin(ra), Math.sin(dec)};
    }

    @Test
    void escapesJson() {
        var json = Json.object().field("text", "a \"quoted\" \\ line\n").field("nan", Double.NaN).field("list",
                Json.array().add(1).add(true));
        assertEquals("{\"text\":\"a \\\"quoted\\\" \\\\ line\\n\",\"nan\":null,\"list\":[1,true]}", json.toString());
    }
}
