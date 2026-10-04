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

import org.graalvm.nativeimage.ProcessProperties;

import java.nio.file.Path;
import java.util.Locale;

/**
 * The name under which the program was started, without directory nor extension: the same
 * executable behaves differently when it is started as {@code astap}.
 */
public final class ProgramName {
    private ProgramName() {
    }

    /** The name of the program, as it was invoked: a path, a file name, with or without extension. */
    public static String invoked() {
        // the name of the program is only known in a native image: java is the program otherwise
        return "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))
                ? NativeImage.argumentZero()
                : "asterion";
    }

    /** The name of the program, lower case, without directory nor extension. */
    public static String current() {
        var name = invoked();
        var file = Path.of(name).getFileName();
        var simple = file == null ? name : file.toString();
        var dot = simple.lastIndexOf('.');
        return (dot > 0 ? simple.substring(0, dot) : simple).toLowerCase(Locale.ROOT);
    }

    /** Only loaded in native images, where the GraalVM API is available. */
    private static final class NativeImage {
        static String argumentZero() {
            // argv[0], unlike the path of the executable, is the name of a symbolic link
            return ProcessProperties.getArgumentVectorProgramName();
        }
    }
}
