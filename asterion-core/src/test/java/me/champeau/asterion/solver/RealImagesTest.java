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
package me.champeau.asterion.solver;

import me.champeau.asterion.math.Sphere;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Solves real images, blindly, against the small index of their fields, see
 * {@code asterion-core/src/testdata/README.md}.
 */
class RealImagesTest {
    @TempDir
    static Path directory;
    private static PlateSolver solver;

    private static Path resource(String name) throws Exception {
        return Path.of(RealImagesTest.class.getResource("/images/" + name).toURI());
    }

    /** The index is committed compressed: it is mostly empty tables of the cells of the whole sky. */
    @BeforeAll
    static void openIndex() throws Exception {
        var index = directory.resolve("sharpcap-index.astx");
        try (var in = new GZIPInputStream(Files.newInputStream(resource("sharpcap-index.astx.gz")))) {
            Files.copy(in, index);
        }
        solver = PlateSolver.open(index);
    }

    @ParameterizedTest
    @CsvSource({
            // SharpCap captures: small fields of 19' x 11', with few catalog stars
            "sharpcap-draco.png, 270.30507, 66.73039, 0.5753, 14",
            "sharpcap-ngc7331.png, 339.25489, 34.41761, 0.5782, 12",
            "sharpcap-pegasus.png, 328.07988, 12.67584, 0.5756, 16"
    })
    void solvesRealImagesBlindly(String image, double raDeg, double decDeg, double scale, int minMatched) throws Exception {
        var result = solver.solve(resource(image), SolverOptions.builder().useImageHints(false).build());
        assertTrue(result.solved(), image + " didn't solve: " + result.stats());
        var solution = result.solution().orElseThrow();
        var expected = new double[3];
        Sphere.toVector(Math.toRadians(raDeg), Math.toRadians(decDeg), expected);
        var found = new double[3];
        Sphere.toVector(Math.toRadians(solution.raDeg()), Math.toRadians(solution.decDeg()), found);
        var separation = Math.toDegrees(Math.acos(Math.min(1, expected[0] * found[0] + expected[1] * found[1] + expected[2] * found[2])))
                * 3600;
        assertTrue(separation < 5, image + " solved " + separation + "\" from the expected center");
        assertEquals(scale, solution.pixelScale(), 0.002, image + " scale");
        assertTrue(solution.matchedStars() >= minMatched, image + " matched " + solution.matchedStars() + " stars only");
        assertTrue(solution.rmsArcsec() / solution.pixelScale() < 3.5,
                image + " RMS " + solution.rmsArcsec() / solution.pixelScale() + " px");
    }
}
