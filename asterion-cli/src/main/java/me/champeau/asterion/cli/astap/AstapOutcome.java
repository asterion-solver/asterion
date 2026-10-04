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

import me.champeau.asterion.solver.Solution;

import java.util.List;

/**
 * What a run produces, which output files describe.
 *
 * @param status the outcome
 * @param error the ERROR value of the .ini file, or null
 * @param solution the solution, or null if the image wasn't solved
 * @param warnings the warnings for the user
 * @param seconds the duration of the solve
 * @param offsetDeg the distance between the start position and the solution, or NaN if there was no start position
 */
record AstapOutcome(
        AstapStatus status,
        String error,
        Solution solution,
        List<String> warnings,
        double seconds,
        double offsetDeg) {
    static AstapOutcome failure(AstapStatus status, List<String> warnings) {
        return failure(status, status.error(), warnings);
    }

    static AstapOutcome failure(AstapStatus status, String error, List<String> warnings) {
        return new AstapOutcome(status, error, null, List.copyOf(warnings), 0, Double.NaN);
    }

    boolean solved() {
        return solution != null;
    }

    AstapOutcome withError(AstapStatus newStatus, String newError) {
        return new AstapOutcome(newStatus, newError, solution, warnings, seconds, offsetDeg);
    }
}
