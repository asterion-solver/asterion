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

import me.champeau.asterion.solver.SolveResult;

import java.util.function.Consumer;
import java.nio.file.Path;

/** Prints the result of a solve. */
final class Report {
    private Report() {
    }

    static void print(Consumer<String> out, Path image, SolveResult result, boolean verbose) {
        var stats = result.stats();
        var timing = Main.format("%.0f ms (read %.0f, detect %.0f, solve %.0f)",
                stats.totalMillis(), stats.loadMillis(), stats.detectMillis(), stats.solveMillis());
        if (result.solution().isEmpty()) {
            out.accept(image + ": no solution found in " + timing + ", with " + result.stars().size() + " stars");
        } else {
            var s = result.solution().orElseThrow();
            out.accept(image + ": solved in " + timing);
            out.accept(Main.format("  Center:    %s, %s  (%.5f°, %+.5f°)", Angles.formatRa(s.raDeg()), Angles.formatDec(s.decDeg()),
                    s.raDeg(), s.decDeg()));
            out.accept(Main.format("  Scale:     %.4f \"/px", s.pixelScale()));
            out.accept(Main.format("  Field:     %s x %s", field(s.fieldWidthDeg()), field(s.fieldHeightDeg())));
            result.focalLengthMm().ifPresent(focal -> out.accept(
                    Main.format("  Focal:     %.0f mm (%.2f µm pixels)", focal, result.pixelSizeMicrons().orElseThrow())));
            out.accept(Main.format("  Rotation:  %.3f° east of north%s", s.rotationDeg(), s.flipped() ? ", flipped" : ""));
            var fwhm = result.stars().medianFwhm();
            out.accept(Main.format("  Stars:     %d matched out of %d, RMS %.2f\" (%.2f px), FWHM %.1f\" (%.1f px)",
                    s.matchedStars(), result.stars().size(), s.rmsArcsec(), s.rmsArcsec() / s.pixelScale(), fwhm * s.pixelScale(), fwhm));
        }
        if (verbose) {
            var confidence = result.solution()
                    .map(s -> Main.format(", log-odds %.0f, index %s, SIP order %d%s", s.logOdds(), s.indexName(), s.wcs().sipOrder(),
                            stats.usedHints() ? ", with header hints" : ", blind"))
                    .orElse("");
            out.accept(Main.format("  Search:    %,d quads, %,d candidates, %,d verified%s", stats.quads(), stats.candidates(),
                    stats.verifications(), confidence));
        }
    }

    private static String field(double degrees) {
        return degrees >= 1 ? Main.format("%.3f°", degrees) : Main.format("%.2f'", degrees * 60);
    }

    static String json(Path image, SolveResult result) {
        var sb = new StringBuilder("{");
        var stats = result.stats();
        field(sb, "image", image.toString());
        field(sb, "solved", result.solved());
        field(sb, "stars", result.stars().size());
        field(sb, "fwhmPixels", result.stars().medianFwhm());
        result.solution().ifPresent(s -> {
            field(sb, "ra", s.raDeg());
            field(sb, "dec", s.decDeg());
            field(sb, "scale", s.pixelScale());
            result.focalLengthMm().ifPresent(focal -> {
                field(sb, "focalLength", focal);
                field(sb, "pixelSize", result.pixelSizeMicrons().orElseThrow());
            });
            field(sb, "rotation", s.rotationDeg());
            field(sb, "flipped", s.flipped());
            field(sb, "fieldWidth", s.fieldWidthDeg());
            field(sb, "fieldHeight", s.fieldHeightDeg());
            field(sb, "fwhmArcsec", result.stars().medianFwhm() * s.pixelScale());
            field(sb, "matchedStars", s.matchedStars());
            field(sb, "rmsArcsec", s.rmsArcsec());
            field(sb, "logOdds", s.logOdds());
            field(sb, "index", s.indexName());
            sb.append("\"wcs\":{");
            for (var card : s.wcs().toFitsKeywords().entrySet()) {
                field(sb, card.getKey(), card.getValue());
            }
            sb.setLength(sb.length() - 1);
            sb.append("},");
        });
        sb.append("\"timing\":{");
        field(sb, "readMillis", stats.loadMillis());
        field(sb, "detectMillis", stats.detectMillis());
        field(sb, "solveMillis", stats.solveMillis());
        field(sb, "totalMillis", stats.totalMillis());
        sb.setLength(sb.length() - 1);
        sb.append("}}");
        return sb.toString();
    }

    private static void field(StringBuilder sb, String name, Object value) {
        sb.append('"').append(name).append("\":");
        if (value instanceof String s) {
            sb.append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        } else {
            sb.append(value);
        }
        sb.append(',');
    }
}
