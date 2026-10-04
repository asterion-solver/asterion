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

/**
 * Where the command line tool reports what it does: either plain lines of text, or a live display
 * of the progress of long operations.
 */
sealed interface Console permits PlainConsole, RichConsole {
    /** Prints a line of normal output. */
    void println(String line);

    /** Reports a problem. */
    void error(String line);

    /** The listener which displays the progress of long operations. */
    ProgressListener progress();

    /**
     * Runs work, displaying its progress while it runs.
     *
     * @throws CancelledException if the user cancelled the work
     */
    <T> T run(Callable<T> work) throws Exception;

    /**
     * Creates a console.
     *
     * @param rich true to use a live display, when the output is an interactive terminal
     * @param out where normal output goes
     */
    static Console create(boolean rich, PrintStream out) {
        if (rich && isInteractive()) {
            return new RichConsole();
        }
        return new PlainConsole(out);
    }

    private static boolean isInteractive() {
        var console = System.console();
        var term = System.getenv("TERM");
        return console != null && console.isTerminal() && !"dumb".equals(term);
    }

    /** Thrown when the user cancels the work, with Ctrl+C or q. */
    final class CancelledException extends Exception {
        CancelledException() {
            super("Cancelled");
        }
    }
}
