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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * The ASTAP compatible executable: the command line tool of Asterion behaves like ASTAP when it
 * is started under the name {@code astap}. The web interface, when it is a native executable
 * next to the command line one, can install it: a link on Unix, a copy on Windows.
 */
final class AstapBinary {
    /** The name of ASTAP's executable, which imaging programs look for. */
    static final String ASTAP = "astap";

    /**
     * What can be done.
     *
     * @param available true if the executable can be installed, by this process
     * @param reason why it can't, or null
     * @param directory the directory of the executables, or null
     * @param executable the command line executable, or null
     * @param target the ASTAP compatible executable, installed or not, or null
     * @param installed true if the target exists
     * @param link true if the target is a link to the executable, rather than a copy
     */
    record State(
            boolean available,
            String reason,
            Path directory,
            Path executable,
            Path target,
            boolean installed,
            boolean link) {
        static State unavailable(String reason) {
            return new State(false, reason, null, null, null, false, false);
        }

        Json describe() {
            return Json.object()
                    .field("native", nativeImage())
                    .field("available", available)
                    .field("reason", reason)
                    .field("directory", directory == null ? null : directory.toString())
                    .field("executable", executable == null ? null : executable.toString())
                    .field("executableName", executable == null ? null : executable.getFileName().toString())
                    .field("target", target == null ? null : target.toString())
                    .field("targetName", target == null ? null : target.getFileName().toString())
                    .field("installed", installed)
                    .field("link", link);
        }
    }

    private AstapBinary() {
    }

    /** Tells if this process is a native executable, the only kind which can install anything. */
    static boolean nativeImage() {
        return "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"));
    }

    /** The state, for this process: it must be a native executable, next to the command line one. */
    static State detect() {
        if (!nativeImage()) {
            return State.unavailable("The web interface isn't a native executable");
        }
        var running = ProcessHandle.current().info().command().map(Path::of).orElse(null);
        if (running == null) {
            return State.unavailable("The path of the web interface executable is unknown");
        }
        var windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return detect(running, executableName(), windows);
    }

    /**
     * The state, given the running executable.
     *
     * @param running the executable of this process
     * @param executableName the name of the command line executable, without extension
     * @param windows true on Windows, where executables have an extension, and are copied rather than linked
     */
    static State detect(Path running, String executableName, boolean windows) {
        var directory = running.toAbsolutePath().getParent();
        if (directory == null) {
            return State.unavailable("The web interface executable has no directory");
        }
        var extension = windows ? ".exe" : "";
        var executable = directory.resolve(executableName + extension);
        if (!Files.isRegularFile(executable)) {
            return State.unavailable("The command line executable, " + executable + ", isn't next to the web interface");
        }
        var target = directory.resolve(ASTAP + extension);
        var installed = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        return new State(true, null, directory, executable, target, installed, installed && Files.isSymbolicLink(target));
    }

    /**
     * Installs the ASTAP compatible executable: a link to the command line executable, or a copy
     * of it when links aren't possible, as on Windows or some file systems. An existing one is replaced.
     *
     * @return the state afterwards
     * @throws IOException if the directory can't be written, for example
     */
    static State install(State state) throws IOException {
        if (!state.available()) {
            throw new IllegalStateException(state.reason());
        }
        var target = state.target();
        Files.deleteIfExists(target);
        var windows = target.getFileName().toString().endsWith(".exe");
        var linked = false;
        if (!windows) {
            try {
                Files.createSymbolicLink(target, state.executable().getFileName());
                linked = true;
            } catch (IOException | UnsupportedOperationException _) {
                // a copy then
            }
        }
        if (!linked) {
            Files.copy(state.executable(), target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }
        return new State(true, null, state.directory(), state.executable(), target, true, linked);
    }

    /** The name of the command line executable, written by the build. */
    private static String executableName() {
        try (var in = AstapBinary.class.getResourceAsStream("/asterion-executable.txt")) {
            return in == null ? "asterion" : new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "asterion";
        }
    }
}
