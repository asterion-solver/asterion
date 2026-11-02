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

import me.champeau.asterion.catalog.Catalogs;
import me.champeau.asterion.catalog.DeepSkyObjects;
import me.champeau.asterion.index.StarIndex;
import me.champeau.asterion.solver.Solution;
import me.champeau.asterion.solver.SolutionJson;
import me.champeau.asterion.solver.SolveResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.StringJoiner;
import java.util.function.Consumer;

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
            var objects = objects(s);
            if (!objects.isEmpty()) {
                out.accept("  Objects:   " + summary(objects));
            }
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

    /** The deep sky objects of the image, the most prominent first. */
    private static List<DeepSkyObjects.InField> objects(Solution s) {
        return DeepSkyObjects.inField(s.wcs(), s.width(), s.height());
    }

    /** The first objects, and how many others there are: "M 31 (Andromeda Galaxy), M 110, M 32, and 4 more". */
    static String summary(List<DeepSkyObjects.InField> objects) {
        var shown = Math.min(objects.size(), MAX_OBJECTS_SHOWN);
        var names = new StringJoiner(", ");
        for (var i = 0; i < shown; i++) {
            names.add(objects.get(i).object().label());
        }
        var more = objects.size() - shown;
        return names + (more > 0 ? ", and " + more + " more" : "");
    }

    private static final int MAX_OBJECTS_SHOWN = 5;

    private static String field(double degrees) {
        return degrees >= 1 ? Main.format("%.3f°", degrees) : Main.format("%.2f'", degrees * 60);
    }

    /**
     * The catalogs, as JSON: the directory, the version of the program, the installed catalogs
     * with their size and content, and the ones which can be downloaded.
     */
    static String catalogsJson(Path directory, List<Path> installed, List<Catalogs.Descriptor> available,
                               String version) throws IOException {
        var sb = new StringBuilder("{");
        field(sb, "directory", directory.toString());
        field(sb, "version", version);
        sb.append("\"installed\":[");
        var names = new HashSet<String>();
        for (var file : installed) {
            sb.append('{');
            var name = file.getFileName().toString();
            name = name.substring(0, name.length() - Catalogs.EXTENSION.length());
            names.add(name);
            field(sb, "name", name);
            field(sb, "size", Files.size(file));
            try {
                var index = StarIndex.open(file);
                field(sb, "stars", index.starCount());
                field(sb, "magnitudeLimit", index.magnitudeLimit());
                field(sb, "quads", index.quadCount());
                field(sb, "minQuadArcmin", Math.toDegrees(index.minQuadDiam()) * 60);
                field(sb, "maxQuadDeg", Math.toDegrees(index.maxQuadDiam()));
            } catch (IOException e) {
                field(sb, "error", String.valueOf(e.getMessage()));
            }
            sb.setLength(sb.length() - 1);
            sb.append("},");
        }
        if (!installed.isEmpty()) {
            sb.setLength(sb.length() - 1);
        }
        sb.append("],\"available\":[");
        for (var descriptor : available) {
            sb.append('{');
            field(sb, "name", descriptor.name());
            field(sb, "description", descriptor.description());
            field(sb, "installed", names.contains(descriptor.name()));
            sb.setLength(sb.length() - 1);
            sb.append("},");
        }
        if (!available.isEmpty()) {
            sb.setLength(sb.length() - 1);
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void field(StringBuilder sb, String name, Object value) {
        SolutionJson.field(sb, name, value);
    }
}
