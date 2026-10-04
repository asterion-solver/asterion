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
 * The .ini file, which N.I.N.A. reads: {@code KEY=value} lines, the solution first.
 */
final class AstapIni {
    private AstapIni() {
    }

    static String format(AstapOutcome outcome, String commandLine) {
        var sb = new StringBuilder();
        var eol = System.lineSeparator();
        sb.append("PLTSOLVD=").append(outcome.solved() ? 'T' : 'F').append(eol);
        if (outcome.solved()) {
            var plate = AstapPlate.of(outcome.solution().wcs());
            line(sb, "CRPIX1", plate.crpix1());
            line(sb, "CRPIX2", plate.crpix2());
            line(sb, "CRVAL1", plate.crval1());
            line(sb, "CRVAL2", plate.crval2());
            line(sb, "CDELT1", plate.cdelt1());
            line(sb, "CDELT2", plate.cdelt2());
            line(sb, "CROTA1", plate.crota1());
            line(sb, "CROTA2", plate.crota2());
            line(sb, "CD1_1", plate.cd11());
            line(sb, "CD1_2", plate.cd12());
            line(sb, "CD2_1", plate.cd21());
            line(sb, "CD2_2", plate.cd22());
        }
        sb.append("CMDLINE=").append(commandLine).append(eol);
        if (outcome.error() != null) {
            sb.append("ERROR=").append(outcome.error()).append(eol);
        }
        if (!outcome.warnings().isEmpty()) {
            // a single line is read by clients
            sb.append("WARNING=").append(String.join(" ", outcome.warnings())).append(eol);
        }
        return sb.toString();
    }

    private static void line(StringBuilder sb, String key, double value) {
        sb.append(key).append('=').append(AstapNumbers.ini(value)).append(System.lineSeparator());
    }
}
