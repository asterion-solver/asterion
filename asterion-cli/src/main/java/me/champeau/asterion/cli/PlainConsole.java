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
package me.champeau.asterion.cli;

import me.champeau.asterion.progress.ProgressListener;

import java.io.PrintStream;
import java.util.concurrent.Callable;

/** Reports everything as lines of text, for logs, pipes and terminals without rich output. */
final class PlainConsole implements Console {
    private final PrintStream out;
    private final ProgressListener progress;

    PlainConsole(PrintStream out) {
        this.out = out;
        this.progress = ProgressListener.text(out::println);
    }

    @Override
    public void println(String line) {
        out.println(line);
    }

    @Override
    public void error(String line) {
        System.err.println(line);
    }

    @Override
    public ProgressListener progress() {
        return progress;
    }

    @Override
    public <T> T run(Callable<T> work) throws Exception {
        return work.call();
    }
}
