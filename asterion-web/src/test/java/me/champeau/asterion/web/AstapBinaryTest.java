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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AstapBinaryTest {
    @TempDir
    Path directory;

    @Test
    void isUnavailableOnTheJvm() {
        var state = AstapBinary.detect();
        assertFalse(state.available());
        assertTrue(state.reason().contains("native"), state.reason());
        assertEquals(
                "{\"native\":false,\"available\":false,\"reason\":\"" + state.reason()
                        + "\",\"directory\":null,\"executable\":null,\"executableName\":null,"
                        + "\"target\":null,\"targetName\":null,\"installed\":false,\"link\":false}",
                state.describe().toString());
    }

    @Test
    void linksTheExecutableOnUnix() throws Exception {
        var web = directory.resolve("asterion-web");
        Files.write(web, new byte[]{1});
        var missing = AstapBinary.detect(web, "asterion", false);
        assertFalse(missing.available());
        assertTrue(missing.reason().contains("asterion"), missing.reason());

        var cli = Files.write(directory.resolve("asterion"), new byte[]{1, 2, 3});
        var state = AstapBinary.detect(web, "asterion", false);
        assertTrue(state.available());
        assertFalse(state.installed());
        assertEquals(directory.resolve("astap"), state.target());

        var installed = AstapBinary.install(state);
        assertTrue(installed.installed());
        assertTrue(installed.link());
        assertTrue(Files.isSymbolicLink(installed.target()));
        assertEquals(Path.of("asterion"), Files.readSymbolicLink(installed.target()));
        assertArrayEquals(Files.readAllBytes(cli), Files.readAllBytes(installed.target()));
        // detected afterwards, and replaced when installed again
        assertTrue(AstapBinary.detect(web, "asterion", false).installed());
        assertTrue(AstapBinary.install(installed).link());
    }

    @Test
    void copiesTheExecutableOnWindows() throws Exception {
        var web = Files.write(directory.resolve("asterion-web.exe"), new byte[]{1});
        Files.write(directory.resolve("asterion.exe"), new byte[]{4, 5, 6});
        var state = AstapBinary.detect(web, "asterion", true);
        assertTrue(state.available());
        assertEquals(directory.resolve("astap.exe"), state.target());
        var installed = AstapBinary.install(state);
        assertTrue(installed.installed());
        assertFalse(installed.link());
        assertFalse(Files.isSymbolicLink(installed.target()));
        assertArrayEquals(new byte[]{4, 5, 6}, Files.readAllBytes(installed.target()));
    }
}
