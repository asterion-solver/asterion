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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import me.champeau.asterion.catalog.Catalogs;
import me.champeau.asterion.image.ImageLoader;
import me.champeau.asterion.solver.SolutionFiles;
import me.champeau.asterion.solver.SolveResult;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A web interface to the solver, with the HTTP server of the JDK: it lists and installs catalogs,
 * and solves the images which are uploaded.
 */
public final class WebServer implements AutoCloseable {
    /** The largest image which can be uploaded. */
    private static final long MAX_UPLOAD_BYTES = 2L << 30;
    /** The port the server listens on, unless another one is given on the command line or saved from the interface. */
    private static final int DEFAULT_PORT = 7531;
    /** The width of the thumbnails which are made here, see {@link #thumbnail}: the ones made by browsers have the same. */
    private static final int THUMBNAIL_WIDTH = 800;
    /** The largest radius of charts: they show the stars of a catalog around a solution. */
    private static final double MAX_CHART_RADIUS_DEG = 30;
    private static final Map<String, String> STATIC_FILES = Map.of(
            "/", "index.html",
            "/index.html", "index.html",
            "/app.js", "app.js",
            "/charts.js", "charts.js",
            "/style.css", "style.css",
            "/sky.json", "sky.json",
            "/api/openapi.yaml", "openapi.yaml");

    /** Solved images are kept for downloads: the last ones, for an hour, within a limit of disk space. */
    private static final int KEPT_IMAGES = 10;
    private static final long KEPT_BYTES = 4L << 30;
    private static final Duration KEPT_FOR = Duration.ofHours(1);

    private final HttpServer server;
    private final CatalogService catalogs;
    private final SolvedImages solved;
    private final Preferences preferences;
    /** True when the port was given on the command line, which wins over the one of the preferences. */
    private final boolean portFromCommandLine;

    private WebServer(HttpServer server, CatalogService catalogs, SolvedImages solved, Preferences preferences,
                      boolean portFromCommandLine) {
        this.server = server;
        this.catalogs = catalogs;
        this.solved = solved;
        this.preferences = preferences;
        this.portFromCommandLine = portFromCommandLine;
    }

    /**
     * Starts a server.
     *
     * @param port the port, or 0 for any free port
     */
    public static WebServer start(String host, int port, Path catalogDirectory) throws IOException {
        return start(host, port, catalogDirectory, Preferences.ofUser(), true);
    }

    /**
     * Starts a server.
     *
     * @param port the port, or 0 for any free port
     * @param portFromCommandLine true if the port was given on the command line, rather than read from the preferences
     */
    static WebServer start(String host, int port, Path catalogDirectory, Preferences preferences,
                           boolean portFromCommandLine) throws IOException {
        var server = HttpServer.create(new InetSocketAddress(host, port), 0);
        var web = new WebServer(server, new CatalogService(catalogDirectory, preferences),
                new SolvedImages(KEPT_IMAGES, KEPT_BYTES, KEPT_FOR, Clock.systemUTC()), preferences, portFromCommandLine);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        web.routes().forEach((path, route) -> server.createContext(path, web.handler(route)));
        server.createContext("/", web.handler(WebServer::staticFile));
        server.start();
        return web;
    }

    /**
     * The operations of the API, by their paths, which its definition describes: see
     * {@code web/openapi.yaml}, which is served at {@code /api/openapi.yaml}.
     */
    Map<String, Route> routes() {
        var routes = new LinkedHashMap<String, Route>();
        routes.put("/api/catalogs", this::catalogs);
        routes.put("/api/install", this::install);
        routes.put("/api/enable", this::enable);
        routes.put("/api/solve", this::solve);
        routes.put("/api/stars", this::stars);
        routes.put("/api/download", this::download);
        routes.put("/api/thumbnail", this::thumbnail);
        routes.put("/api/astap", WebServer::astap);
        routes.put("/api/settings", this::settings);
        return routes;
    }

    /** The address of the interface, which uses "localhost" when the server listens on all addresses. */
    public URI uri() {
        var address = server.getAddress();
        var host = address.getAddress().isAnyLocalAddress() ? "localhost" : address.getHostString();
        return URI.create("http://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + address.getPort() + "/");
    }

    @Override
    public void close() {
        server.stop(0);
        try {
            solved.close();
        } catch (IOException e) {
            // temporary files: the system cleans them eventually
        }
    }

    @FunctionalInterface
    interface Route {
        void handle(HttpExchange exchange) throws IOException;
    }

    /** Turns failures into JSON errors: invalid requests are 400, conflicts 409, and the rest 500. */
    private HttpHandler handler(Route route) {
        return exchange -> {
            try (exchange) {
                try {
                    route.handle(exchange);
                } catch (IllegalArgumentException e) {
                    error(exchange, 400, e.getMessage());
                } catch (NoSuchElementException e) {
                    error(exchange, 404, e.getMessage());
                } catch (IllegalStateException e) {
                    error(exchange, 409, e.getMessage());
                } catch (IOException | RuntimeException e) {
                    error(exchange, 500, e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
        };
    }

    private static void expect(HttpExchange exchange, String method) {
        if (!exchange.getRequestMethod().equals(method)) {
            throw new IllegalArgumentException("Use " + method + " for " + exchange.getRequestURI().getPath());
        }
    }

    private void catalogs(HttpExchange exchange) throws IOException {
        expect(exchange, "GET");
        send(exchange, 200, catalogs.describe().field("version", version()));
    }

    /**
     * The settings of the server: GET describes them, POST with a {@code port} saves the port the
     * server listens on the next time it starts, unless a port is given on the command line.
     */
    private void settings(HttpExchange exchange) throws IOException {
        if (exchange.getRequestMethod().equals("POST")) {
            var value = SolveRequest.parse(exchange.getRequestURI().getRawQuery()).parameter("port");
            int port;
            try {
                port = Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid port " + value + ": use a number from 1 to 65535");
            }
            preferences.savePort(port);
        } else {
            expect(exchange, "GET");
        }
        var current = server.getAddress().getPort();
        var saved = preferences.port().orElse(DEFAULT_PORT);
        send(exchange, 200, Json.object()
                .field("port", saved)
                .field("currentPort", current)
                .field("portFromCommandLine", portFromCommandLine)
                // the port which the server will listen on when it starts again
                .field("restartNeeded", !portFromCommandLine && saved != current));
    }

    private void install(HttpExchange exchange) throws IOException {
        expect(exchange, "POST");
        var name = SolveRequest.parse(exchange.getRequestURI().getRawQuery()).fileName();
        catalogs.install(name);
        send(exchange, 202, catalogs.describe().field("version", version()));
    }

    private void enable(HttpExchange exchange) throws IOException {
        expect(exchange, "POST");
        var request = SolveRequest.parse(exchange.getRequestURI().getRawQuery());
        var enabled = request.parameter("enabled");
        if (!"true".equals(enabled) && !"false".equals(enabled)) {
            throw new IllegalArgumentException("enabled must be true or false");
        }
        catalogs.setEnabled(request.fileName(), Boolean.parseBoolean(enabled));
        send(exchange, 200, catalogs.describe().field("version", version()));
    }

    private void solve(HttpExchange exchange) throws IOException {
        expect(exchange, "POST");
        var request = SolveRequest.parse(exchange.getRequestURI().getRawQuery());
        var options = request.options();
        var solver = catalogs.requireSolver();
        // images are read from files: the name keeps the extension, which tells compressed FITS files
        var file = Files.createTempFile("asterion-", "-" + request.fileName());
        try {
            copy(exchange.getRequestBody(), file);
            SolveResult result;
            try {
                result = solver.solve(file, options);
            } catch (IOException e) {
                // an image which can't be read: the message names the upload, not the temporary file
                throw new IllegalArgumentException(String.valueOf(e.getMessage()).replace(file.toString(), request.fileName()), e);
            }
            var json = SolveRequest.describe(request.fileName(), result);
            if (result.solution().isPresent()) {
                // kept for downloads
                var fits = ImageLoader.isFits(file);
                var id = solved.keep(file, request.fileName(), result.solution().orElseThrow());
                json.field("download", Json.object().field("id", id).field("fits", fits));
            }
            send(exchange, 200, json);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * The ASTAP compatible executable: GET tells if it can be installed next to this one and
     * whether it is, POST installs it.
     */
    private static void astap(HttpExchange exchange) throws IOException {
        var state = AstapBinary.detect();
        if (exchange.getRequestMethod().equals("POST")) {
            if (!state.available()) {
                throw new IllegalStateException(state.reason());
            }
            state = AstapBinary.install(state);
        } else {
            expect(exchange, "GET");
        }
        send(exchange, 200, state.describe());
    }

    /** The brightest stars of a catalog around a position, for charts. */
    private void stars(HttpExchange exchange) throws IOException {
        expect(exchange, "GET");
        var request = SolveRequest.parse(exchange.getRequestURI().getRawQuery());
        var ra = request.number("ra", Double.NaN);
        var dec = request.number("dec", Double.NaN);
        var radius = request.number("radius", Double.NaN);
        if (Double.isNaN(ra) || Double.isNaN(dec) || Math.abs(dec) > 90 || !(radius > 0 && radius <= MAX_CHART_RADIUS_DEG)) {
            throw new IllegalArgumentException("ra, dec and radius (up to " + MAX_CHART_RADIUS_DEG + "°) are required");
        }
        var count = (int) Math.clamp(request.number("count", 2000), 1, 20000);
        send(exchange, 200, catalogs.stars(request.parameter("catalog"), ra, dec, radius, count));
    }

    /**
     * Downloads a solved image: the image with its solution in its header (FITS images only), the
     * solution as a FITS header (a .wcs file), or the matched stars as CSV.
     */
    private void download(HttpExchange exchange) throws IOException {
        expect(exchange, "GET");
        var request = SolveRequest.parse(exchange.getRequestURI().getRawQuery());
        var image = solved.get(request.parameter("id"));
        // image.fits.gz gives image.solved.fits, image.wcs and image.matches.csv
        var base = image.name().replaceAll("(?i)\\.(gz|fz|z|bz2)$", "").replaceAll("\\.[^.]*$", "");
        var format = String.valueOf(request.parameter("format"));
        switch (format) {
            case "fits" -> {
                var copy = solved.createTempFile(".fits");
                try {
                    try {
                        SolutionFiles.writeCopy(image.file(), image.solution(), copy);
                    } catch (IOException e) {
                        throw new IllegalArgumentException(String.valueOf(e.getMessage()).replace(image.file().toString(), image.name()),
                                e);
                    }
                    attachment(exchange, base + ".solved.fits", "application/fits", Files.size(copy));
                    Files.copy(copy, exchange.getResponseBody());
                } finally {
                    Files.deleteIfExists(copy);
                }
            }
            case "wcs" -> {
                var content = SolutionFiles.wcsHeader(image.solution()).getBytes(StandardCharsets.US_ASCII);
                attachment(exchange, base + ".wcs", "application/fits", content.length);
                exchange.getResponseBody().write(content);
            }
            case "csv" -> {
                var content = SolutionFiles.matchesCsv(image.solution()).getBytes(StandardCharsets.US_ASCII);
                attachment(exchange, base + ".matches.csv", "text/csv; charset=us-ascii", content.length);
                exchange.getResponseBody().write(content);
            }
            default -> throw new IllegalArgumentException("Unknown format " + format + ": use fits, wcs or csv");
        }
    }

    /**
     * The thumbnail of a solved image, for the images which browsers can't display: it is made the
     * first time it's asked for, after the image was solved, and is never part of a solution.
     */
    private void thumbnail(HttpExchange exchange) throws IOException {
        expect(exchange, "GET");
        var request = SolveRequest.parse(exchange.getRequestURI().getRawQuery());
        var image = solved.get(request.parameter("id"));
        var file = image.thumbnail();
        var bottomUp = Thumbnail.bottomUp(image.file(), image.name());
        if (!Files.exists(file)) {
            byte[] png;
            try {
                png = Thumbnail.png(ImageLoader.load(image.file(), 0).image(), THUMBNAIL_WIDTH, bottomUp);
            } catch (IOException e) {
                throw new IllegalArgumentException(String.valueOf(e.getMessage()).replace(image.file().toString(), image.name()), e);
            }
            // written aside, then moved: a request at the same time never reads half a file
            var temp = solved.createTempFile(".png");
            Files.write(temp, png);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        exchange.getResponseHeaders().set("Content-Type", "image/png");
        exchange.getResponseHeaders().set("Cache-Control", "private, max-age=3600");
        // the thumbnail is displayed as it is: this tells where the first row of the image is
        exchange.getResponseHeaders().set("Asterion-Row-Order", bottomUp ? "bottom-up" : "top-down");
        exchange.sendResponseHeaders(200, Files.size(file));
        Files.copy(file, exchange.getResponseBody());
    }

    private static void attachment(HttpExchange exchange, String name, String type, long length) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        // names of uploads only have letters, digits, dots, dashes and underscores
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + name + "\"");
        exchange.sendResponseHeaders(200, length);
    }

    private static void copy(InputStream in, Path file) throws IOException {
        try (var limited = new LimitedInputStream(in, MAX_UPLOAD_BYTES)) {
            Files.copy(limited, file, StandardCopyOption.REPLACE_EXISTING);
        }
        if (Files.size(file) == 0) {
            throw new IllegalArgumentException("No image was sent");
        }
    }

    private static void staticFile(HttpExchange exchange) throws IOException {
        var file = STATIC_FILES.get(exchange.getRequestURI().getPath());
        if (file == null) {
            error(exchange, 404, "Not found: " + exchange.getRequestURI().getPath());
            return;
        }
        expect(exchange, "GET");
        byte[] content;
        try (var in = WebServer.class.getResourceAsStream("/web/" + file)) {
            if (in == null) {
                throw new IOException("Missing resource " + file);
            }
            content = in.readAllBytes();
        }
        if (file.endsWith(".yaml")) {
            // the definition of the API has the version of the server
            content = new String(content, StandardCharsets.UTF_8).replace("@VERSION@", version()).getBytes(StandardCharsets.UTF_8);
        }
        var type = file.endsWith(".html") ? "text/html"
                : file.endsWith(".js") ? "text/javascript"
                        : file.endsWith(".json") ? "application/json"
                                : file.endsWith(".yaml") ? "application/yaml" : "text/css";
        exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, content.length);
        exchange.getResponseBody().write(content);
    }

    private static void error(HttpExchange exchange, int status, String message) throws IOException {
        // the rest of an upload is read: a client which is still sending wouldn't get the answer
        try (var body = new LimitedInputStream(exchange.getRequestBody(), MAX_UPLOAD_BYTES)) {
            body.transferTo(OutputStream.nullOutputStream());
        } catch (IOException | IllegalArgumentException _) {
            // the connection is closed anyway
        }
        send(exchange, status, Json.object().field("error", message));
    }

    private static void send(HttpExchange exchange, int status, Json json) throws IOException {
        var content = json.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, content.length);
        exchange.getResponseBody().write(content);
    }

    /** Fails when more bytes than allowed are read, so that uploads can't fill the disk. */
    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;

        LimitedInputStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            var b = super.read();
            if (b >= 0 && --remaining < 0) {
                throw new IllegalArgumentException("The image is larger than " + (MAX_UPLOAD_BYTES >> 20) + " MB");
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            var n = super.read(buffer, offset, length);
            if (n > 0 && (remaining -= n) < 0) {
                throw new IllegalArgumentException("The image is larger than " + (MAX_UPLOAD_BYTES >> 20) + " MB");
            }
            return n;
        }
    }

    public static void main(String[] args) throws IOException {
        // nom-tam-fits logs the oddities of FITS files, which are reported as errors when they matter
        Logger.getLogger("nom.tam").setLevel(Level.OFF);
        var host = "127.0.0.1";
        Integer port = null;
        var directory = Catalogs.defaultDirectory();
        for (var i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = value(args, ++i);
                case "--port" -> port = Integer.parseInt(value(args, ++i));
                case "--catalog-dir" -> directory = Path.of(value(args, ++i));
                case "-V", "--version" -> {
                    System.out.println("Asterion Solver " + version() + ", web interface");
                    return;
                }
                case "-h", "--help" -> {
                    usage();
                    return;
                }
                default -> {
                    System.err.println("Unknown option " + args[i]);
                    usage();
                    System.exit(2);
                }
            }
        }
        // the port of the command line, or else the one saved from the interface, or else the default
        var preferences = Preferences.ofUser();
        var saved = preferences.port();
        var listen = port != null ? port : saved.orElse(DEFAULT_PORT);
        WebServer web;
        try {
            web = start(host, listen, directory, preferences, port != null);
        } catch (BindException e) {
            System.err.println("Port " + listen + " is already in use, by another program or another web interface. "
                    + (port == null && saved.isPresent()
                            ? "It was saved from the interface, in " + preferences.file() + ": choose another one with --port."
                            : "Choose another one with --port."));
            System.exit(1);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(web::close));
        System.out.println("Asterion Solver " + version() + ", web interface: " + web.uri());
        System.out.println("Catalogs: " + directory.toAbsolutePath());
        if (!host.equals("127.0.0.1") && !host.equals("localhost") && !host.equals("::1")) {
            System.out.println("Warning: the interface is reachable from other machines, and has no access control");
        }
        System.out.println("Press Ctrl+C to stop");
    }

    private static String value(String[] args, int index) {
        if (index >= args.length) {
            System.err.println("Missing value for " + args[index - 1]);
            System.exit(2);
        }
        return args[index];
    }

    private static void usage() {
        System.out.println("""
                Usage: asterion-web [--host <address>] [--port <port>] [--catalog-dir <directory>]
                  --host <address>         the address to listen on (default: 127.0.0.1, this machine only)
                  --port <port>            the port to listen on (default: the one saved from the interface, or 7531; 0 for any free port)
                  --catalog-dir <dir>      the directory of the catalogs (default: $ASTERION_CATALOGS, or ~/.asterion/catalogs)
                  -V, --version            prints the version
                  -h, --help               prints this help""");
    }

    private static String version() {
        try (var in = WebServer.class.getResourceAsStream("/asterion-version.txt")) {
            return in == null ? "(development)" : new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "(development)";
        }
    }
}
