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

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.PrintStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AstapOutputTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({
            // the image, without its extension
            "'-f frames/m31.fits', frames/m31",
            "'-f frames/m31', frames/m31",
            // -o, which ASTAP takes with or without extension: KStars gives the .ini file
            "'-f frames/m31.fits -o out/solution', out/solution",
            "'-f frames/m31.fits -o out/solution.ini', out/solution",
            "'-f frames/m31.fits -o out/m31.v2.ini', out/m31.v2",
    })
    void namesTheOutputFilesLikeAstap(String options, String base) {
        var arguments = AstapArguments.parse(Arrays.asList(options.split(" ")));
        var command = new AstapCommand(arguments, options, directory, new PrintStream(OutputStream.nullOutputStream()));
        assertEquals(Path.of(base).toAbsolutePath(), command.outputBase());
    }
}
