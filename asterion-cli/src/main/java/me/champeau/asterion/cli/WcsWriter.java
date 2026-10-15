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

import me.champeau.asterion.image.ImageLoader;
import me.champeau.asterion.solver.Solution;
import me.champeau.asterion.solver.SolutionFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Saves solutions next to images, see {@link SolutionFiles}. */
public final class WcsWriter {
    private WcsWriter() {
    }

    private static Path sibling(Path image, String extension) {
        var name = image.getFileName().toString();
        var dot = name.lastIndexOf('.');
        return image.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + extension);
    }

    /** Writes the stars which were matched with the catalog next to the image, as a CSV file. */
    static void writeMatches(Path image, Solution solution) throws IOException {
        Files.writeString(sibling(image, ".matches.csv"), SolutionFiles.matchesCsv(solution), StandardCharsets.US_ASCII);
    }

    /** Writes a FITS file without data next to the image, which header describes the solution. */
    static void writeWcsFile(Path image, Solution solution) throws IOException {
        Files.writeString(sibling(image, ".wcs"), SolutionFiles.wcsHeader(solution), StandardCharsets.US_ASCII);
    }

    /** Adds the solution to the header of the image, which must be an uncompressed FITS file. */
    public static void updateHeader(Path image, Solution solution) throws IOException {
        var name = image.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".fit") || name.endsWith(".fits") || name.endsWith(".fts"))) {
            throw new IOException("Only uncompressed FITS files can be updated, use --output or --wcs for " + image);
        }
        SolutionFiles.updateHeader(image, solution);
    }

    /**
     * Writes a copy of the image, with the solution in its header. Compressed images are written
     * uncompressed. The image itself is not modified.
     */
    static void writeCopy(Path image, Solution solution, Path target) throws IOException {
        if (Files.exists(target) && Files.isSameFile(image, target)) {
            throw new IOException("The output would replace " + image + ", use --update to modify it");
        }
        if (!ImageLoader.isFits(image)) {
            throw new IOException("Only FITS images can be written with the solution, use --wcs for " + image);
        }
        SolutionFiles.writeCopy(image, solution, target);
    }
}
