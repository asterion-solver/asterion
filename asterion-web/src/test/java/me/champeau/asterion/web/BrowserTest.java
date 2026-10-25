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

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BrowserTest {
    private static final URI ADDRESS = URI.create("http://127.0.0.1:7531/");

    @Test
    void usesTheOpenerOfTheSystem() {
        assertEquals(Optional.of(List.of("rundll32", "url.dll,FileProtocolHandler", "http://127.0.0.1:7531/")),
                Browser.command("Windows 11", Map.of(), ADDRESS));
        assertEquals(Optional.of(List.of("open", "http://127.0.0.1:7531/")), Browser.command("Mac OS X", Map.of(), ADDRESS));
        assertEquals(Optional.of(List.of("xdg-open", "http://127.0.0.1:7531/")),
                Browser.command("Linux", Map.of("DISPLAY", ":0"), ADDRESS));
        assertEquals(Optional.of(List.of("xdg-open", "http://127.0.0.1:7531/")),
                Browser.command("Linux", Map.of("WAYLAND_DISPLAY", "wayland-0"), ADDRESS));
    }

    @Test
    void opensNothingWithoutADisplay() {
        assertEquals(Optional.empty(), Browser.command("Linux", Map.of(), ADDRESS));
    }

    @Test
    void opensNothingOverSsh() {
        assertEquals(Optional.empty(), Browser.command("Linux", Map.of("DISPLAY", "localhost:10.0", "SSH_CONNECTION", "x"), ADDRESS));
        assertEquals(Optional.empty(), Browser.command("Mac OS X", Map.of("SSH_CLIENT", "x"), ADDRESS));
        assertEquals(Optional.empty(), Browser.command("Windows 11", Map.of("SSH_CONNECTION", "x"), ADDRESS));
    }
}
