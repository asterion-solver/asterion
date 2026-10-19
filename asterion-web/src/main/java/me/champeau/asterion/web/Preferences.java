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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.OptionalInt;
import java.util.Properties;

/**
 * The preferences of the web interface which are set from the interface itself, kept in a
 * properties file: the port the server listens on when it starts.
 */
final class Preferences {
    private static final String PORT = "port";

    private final Path file;

    Preferences(Path file) {
        this.file = file;
    }

    /** The file of the preferences of the user: {@code ~/.asterion/web.properties}. */
    static Preferences ofUser() {
        return new Preferences(Path.of(System.getProperty("user.home"), ".asterion", "web.properties"));
    }

    Path file() {
        return file;
    }

    /** The port which was saved, if any, and valid. */
    synchronized OptionalInt port() {
        var value = load().getProperty(PORT);
        if (value != null) {
            try {
                var port = Integer.parseInt(value.trim());
                if (validPort(port)) {
                    return OptionalInt.of(port);
                }
            } catch (NumberFormatException e) {
                // a file edited by hand: the default port
            }
        }
        return OptionalInt.empty();
    }

    /** Saves the port the server listens on the next time it starts. */
    synchronized void savePort(int port) throws IOException {
        if (!validPort(port)) {
            throw new IllegalArgumentException("Invalid port " + port + ": use a number from 1 to 65535");
        }
        var properties = load();
        properties.setProperty(PORT, Integer.toString(port));
        Files.createDirectories(file.getParent());
        // written aside, then moved: the file is never half written
        var temp = Files.createTempFile(file.getParent(), "web-", ".properties");
        try (var out = Files.newOutputStream(temp)) {
            properties.store(out, "Preferences of the web interface of Asterion Solver");
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static boolean validPort(int port) {
        return port >= 1 && port <= 65535;
    }

    private Properties load() {
        var properties = new Properties();
        if (Files.isRegularFile(file)) {
            try (var in = Files.newInputStream(file)) {
                properties.load(in);
            } catch (IOException e) {
                // unreadable preferences: the defaults
            }
        }
        return properties;
    }
}
