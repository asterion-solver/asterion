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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Servers which publish the same data. Requests are spread over them at random, and the servers
 * which failed are only used when the others fail too.
 *
 * @param <T> the description of a server
 */
final class Mirrors<T> {
    private final List<T> servers;
    private final Function<T, String> name;
    private final ProgressListener progress;
    private final List<AtomicInteger> failures = new ArrayList<>();
    /** The servers whose failure was reported: it is reported once, even if the server works in between. */
    private final List<AtomicBoolean> reported = new ArrayList<>();

    /**
     * @param name the name of a server, for people
     * @param progress receives a message the first time each server fails
     */
    Mirrors(List<T> servers, Function<T, String> name, ProgressListener progress) {
        this.servers = List.copyOf(servers);
        this.name = name;
        this.progress = progress;
        servers.forEach(_ -> {
            failures.add(new AtomicInteger());
            reported.add(new AtomicBoolean());
        });
    }

    /** The servers, in the order they should be tried: at random, the ones which failed last. */
    List<T> ordered() {
        var indexes = new ArrayList<Integer>();
        for (var i = 0; i < servers.size(); i++) {
            indexes.add(i);
        }
        Collections.shuffle(indexes, ThreadLocalRandom.current());
        // the sort is stable: servers which failed as many times stay in random order
        indexes.sort(Comparator.comparingInt(i -> failures.get(i).get()));
        return indexes.stream().map(servers::get).toList();
    }

    void failed(T server, Exception failure) {
        var index = servers.indexOf(server);
        failures.get(index).incrementAndGet();
        if (reported.get(index).compareAndSet(false, true)) {
            progress.info("%s failed (%s): other servers are used".formatted(capitalize(name.apply(server)), failure.getMessage()));
        }
    }

    void succeeded(T server) {
        failures.get(servers.indexOf(server)).set(0);
    }

    private static String capitalize(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
