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
package me.champeau.asterion.cli.astap;

/**
 * The outcomes of a solve, with the exit codes of the ASTAP manual, and the error which is written
 * in the .ini file. {@code astap_cli} returns 1 for every failure, but CCDciel reports the codes of
 * the manual, and N.I.N.A. ignores them.
 */
public enum AstapStatus {
    SOLVED(0, null),
    NO_SOLUTION(1, null),
    UNSUPPORTED(1, "Option not supported by Asterion."),
    NOT_ENOUGH_STARS(2, "Not enough stars."),
    IMAGE_ERROR(16, "Error reading image file."),
    NO_DATABASE(32, "No star database found."),
    DATABASE_ERROR(33, "Error reading star database."),
    UPDATE_ERROR(34, "Error updating input file.");

    private final int exitCode;
    private final String error;

    AstapStatus(int exitCode, String error) {
        this.exitCode = exitCode;
        this.error = error;
    }

    public int exitCode() {
        return exitCode;
    }

    /** The ERROR value of the .ini file, or null if there's none. */
    public String error() {
        return error;
    }
}
