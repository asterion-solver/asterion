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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreferencesTest {
    @TempDir
    Path directory;

    @Test
    void savesThePort() throws Exception {
        var file = directory.resolve("asterion").resolve("web.properties");
        var preferences = new Preferences(file);
        assertEquals(OptionalInt.empty(), preferences.port());
        preferences.savePort(9090);
        assertEquals(OptionalInt.of(9090), preferences.port());
        assertEquals(OptionalInt.of(9090), new Preferences(file).port());
        assertTrue(Files.readString(file).contains("port=9090"));
    }

    @Test
    void rejectsInvalidPorts() {
        var preferences = new Preferences(directory.resolve("web.properties"));
        assertThrows(IllegalArgumentException.class, () -> preferences.savePort(0));
        assertThrows(IllegalArgumentException.class, () -> preferences.savePort(65536));
        assertEquals(OptionalInt.empty(), preferences.port());
    }

    @Test
    void ignoresInvalidFiles() throws Exception {
        var file = directory.resolve("web.properties");
        Files.writeString(file, "port=not a port\n");
        assertEquals(OptionalInt.empty(), new Preferences(file).port());
        Files.writeString(file, "port=70000\n");
        assertEquals(OptionalInt.empty(), new Preferences(file).port());
    }
}
