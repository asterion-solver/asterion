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
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Opens the interface in the default browser, with the opener of the system rather than java.awt.Desktop, which
 * would bring AWT into the native executable.
 */
final class Browser {
    private Browser() {
    }

    /** Opens the address, and tells whether the opener could be started: the browser itself isn't waited for. */
    static boolean open(URI uri) {
        var command = command(System.getProperty("os.name", ""), System.getenv(), uri);
        if (command.isEmpty()) {
            return false;
        }
        try {
            new ProcessBuilder(command.get())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The command which opens the address, if there's a desktop to open it on: none over SSH, where the browser
     * would open on the remote machine, nor on Unix without a display.
     */
    static Optional<List<String>> command(String osName, Map<String, String> env, URI uri) {
        if (env.containsKey("SSH_CONNECTION") || env.containsKey("SSH_CLIENT")) {
            return Optional.empty();
        }
        var url = uri.toString();
        if (osName.startsWith("Windows")) {
            // unlike "cmd /c start", it doesn't interpret the characters of the address
            return Optional.of(List.of("rundll32", "url.dll,FileProtocolHandler", url));
        }
        if (osName.startsWith("Mac")) {
            return Optional.of(List.of("open", url));
        }
        if (env.containsKey("DISPLAY") || env.containsKey("WAYLAND_DISPLAY")) {
            return Optional.of(List.of("xdg-open", url));
        }
        return Optional.empty();
    }
}
