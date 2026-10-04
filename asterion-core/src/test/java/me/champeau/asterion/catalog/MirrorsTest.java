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

import me.champeau.asterion.progress.ProgressListener;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MirrorsTest {
    @Test
    void reportsTheFailureOfEachServerOnce() {
        var messages = new ArrayList<String>();
        var mirrors = new Mirrors<>(List.of("a", "b"), Function.identity(), ProgressListener.text(messages::add));
        // a server which fails, works, then fails again, like an overloaded one
        mirrors.failed("a", new IOException("HTTP status 500"));
        mirrors.succeeded("a");
        mirrors.failed("a", new IOException("HTTP status 500"));
        mirrors.failed("b", new IOException("timeout"));
        mirrors.failed("a", new IOException("HTTP status 502"));
        assertEquals(List.of("A failed (HTTP status 500): other servers are used", "B failed (timeout): other servers are used"), messages);
    }

    @Test
    void triesTheServersWhichFailedLast() {
        var mirrors = new Mirrors<>(List.of("a", "b", "c"), Function.identity(), ProgressListener.none());
        mirrors.failed("b", new IOException("down"));
        for (var i = 0; i < 20; i++) {
            assertEquals("b", mirrors.ordered().getLast());
        }
        mirrors.succeeded("b");
        var last = new ArrayList<String>();
        for (var i = 0; i < 100; i++) {
            last.add(mirrors.ordered().getLast());
        }
        // once it works again, it's picked at random like the others
        assertEquals(3, last.stream().distinct().count());
    }
}
