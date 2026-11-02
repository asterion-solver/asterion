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
package me.champeau.asterion.lib;

import me.champeau.asterion.image.ImageLoader;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.Arrays;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;
import static me.champeau.asterion.lib.CStructs.CATALOG;
import static me.champeau.asterion.lib.CStructs.MATCH;
import static me.champeau.asterion.lib.CStructs.OBJECT;
import static me.champeau.asterion.lib.CStructs.OPTIONS;
import static me.champeau.asterion.lib.CStructs.SOLUTION;
import static me.champeau.asterion.lib.CStructs.setDouble;

/**
 * The workload of the profile-guided optimization of the library, see update-library-pgo.sh: an
 * instrumented build of the library solves images the way programs do, and writes the profile to
 * the working directory when its isolate is torn down. The images are decoded by Asterion, then
 * handed to the library as samples of each type in turn, as programs have them in memory.
 *
 *     PgoWorkload LIBRARY CATALOGS IMAGE...
 */
// loading a native library and calling its functions is what the restricted methods of FFM are for
@SuppressWarnings("restricted")
final class PgoWorkload {
    private static final int SOLVED = 1;
    private static final int UINT8 = 1;
    private static final int UINT16 = 2;
    private static final int FLOAT32 = 3;

    private final MemorySegment thread;
    private final MethodHandle solve;
    private final MethodHandle resultFree;
    private final MethodHandle resultJson;
    private final MethodHandle objectCount;
    private final MethodHandle object;
    private final MethodHandle matchCount;
    private final MethodHandle match;
    private final MethodHandle pixelToSky;
    private final MethodHandle skyToPixel;

    private PgoWorkload(SymbolLookup library, MemorySegment thread) {
        this.thread = thread;
        solve = function(library, "asterion_solve", JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT,
                ADDRESS, ADDRESS, ADDRESS);
        resultFree = Linker.nativeLinker().downcallHandle(library.findOrThrow("asterion_result_free"),
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
        resultJson = function(library, "asterion_result_json", JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG);
        objectCount = function(library, "asterion_result_object_count", JAVA_INT, ADDRESS, ADDRESS);
        object = function(library, "asterion_result_object", JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS);
        matchCount = function(library, "asterion_result_match_count", JAVA_INT, ADDRESS, ADDRESS);
        match = function(library, "asterion_result_match", JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS);
        pixelToSky = function(library, "asterion_result_pixel_to_sky", JAVA_INT, ADDRESS, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, ADDRESS,
                ADDRESS);
        skyToPixel = function(library, "asterion_result_sky_to_pixel", JAVA_INT, ADDRESS, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, ADDRESS,
                ADDRESS);
    }

    public static void main(String[] args) throws Throwable {
        if (args.length < 3) {
            System.err.println("usage: PgoWorkload LIBRARY CATALOGS IMAGE...");
            System.exit(2);
        }
        try (var arena = Arena.ofConfined()) {
            var library = SymbolLookup.libraryLookup(Path.of(args[0]), arena);
            var catalogs = arena.allocateFrom(args[1]);
            var isolate = arena.allocate(ADDRESS);
            var threadPointer = arena.allocate(ADDRESS);
            check((int) function(library, "graal_create_isolate", JAVA_INT, ADDRESS, ADDRESS, ADDRESS)
                    .invokeExact(MemorySegment.NULL, isolate, threadPointer), "create the isolate");
            var thread = threadPointer.get(ADDRESS, 0);

            // what a program does once: describing the catalogs, opening them
            var describe = function(library, "asterion_catalogs", JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT);
            var count = (int) describe.invokeExact(thread, catalogs, MemorySegment.NULL, 0);
            check(count, "count the catalogs");
            check((int) describe.invokeExact(thread, catalogs, arena.allocate(CATALOG, Math.max(1, count)), count),
                    "describe the catalogs");
            var solverPointer = arena.allocate(ADDRESS);
            check((int) function(library, "asterion_open", JAVA_INT, ADDRESS, ADDRESS, ADDRESS)
                    .invokeExact(thread, catalogs, solverPointer), "open the catalogs");
            var solver = solverPointer.get(ADDRESS, 0);
            var options = arena.allocate(OPTIONS);
            check((int) function(library, "asterion_default_options", JAVA_INT, ADDRESS, ADDRESS).invokeExact(thread, options),
                    "get the default options");

            var workload = new PgoWorkload(library, thread);
            var images = Arrays.copyOfRange(args, 2, args.length);
            for (var i = 0; i < images.length; i++) {
                workload.solve(Path.of(images[i]), i, solver, options);
            }

            check((int) function(library, "asterion_close", JAVA_INT, ADDRESS, ADDRESS).invokeExact(thread, solver), "close the solver");
            // writes the profile
            check((int) function(library, "graal_tear_down_isolate", JAVA_INT, ADDRESS).invokeExact(thread), "tear down the isolate");
        }
    }

    /**
     * Solves an image like a program running all night, as samples of a type which depends on its
     * number: a few times with the solution only, then once with the details of the result.
     */
    private void solve(Path image, int number, MemorySegment solver, MemorySegment options) throws Throwable {
        var gray = ImageLoader.load(image, 1).image();
        var type = new int[]{UINT16, FLOAT32, UINT8}[number % 3];
        // every other image has padding at the end of its rows, as some buffers do
        var bytes = type == UINT8 ? 1 : type == UINT16 ? 2 : 4;
        var rowStride = (long) gray.width() * bytes + (number % 2 == 0 ? 0 : 64);
        try (var arena = Arena.ofConfined()) {
            var pixels = samples(gray.data(), gray.width(), gray.height(), type, rowStride, arena);
            var solution = arena.allocate(SOLUTION);
            var resultPointer = arena.allocate(ADDRESS);
            var status = 0;
            for (var i = 0; i < 3; i++) {
                status = (int) solve.invokeExact(thread, solver, pixels, type, gray.width(), gray.height(), rowStride, 0, options, solution,
                        MemorySegment.NULL);
            }
            System.out.println(image.getFileName() + ": " + (status == SOLVED ? "solved" : status == 0 ? "not solved" : "error"));
            status = (int) solve.invokeExact(thread, solver, pixels, type, gray.width(), gray.height(), rowStride, 0, options, solution,
                    resultPointer);
            if (status == SOLVED) {
                details(resultPointer.get(ADDRESS, 0), gray.width(), gray.height(), arena);
            }
            if (status >= 0) {
                resultFree.invokeExact(thread, resultPointer.get(ADDRESS, 0));
            }
            // the first image again, as the raw frame of a color sensor, which is binned
            if (number == 0) {
                var limited = arena.allocate(OPTIONS);
                MemorySegment.copy(options, 0, limited, 0, OPTIONS.byteSize());
                setDouble(OPTIONS, limited, "timeout", 5);
                status = (int) solve.invokeExact(thread, solver, pixels, type, gray.width(), gray.height(), rowStride, 1, limited, solution,
                        MemorySegment.NULL);
                System.out.println(image.getFileName() + " (Bayer): " + (status == SOLVED ? "solved" : "not solved"));
            }
        }
    }

    /** Reads what a result gives: the objects, the matched stars, conversions, the JSON. */
    private void details(MemorySegment result, int width, int height, Arena arena) throws Throwable {
        var objects = (int) objectCount.invokeExact(thread, result);
        var anObject = arena.allocate(OBJECT);
        for (var i = 0; i < objects; i++) {
            check((int) object.invokeExact(thread, result, i, anObject), "read an object");
        }
        var matches = (int) matchCount.invokeExact(thread, result);
        var aMatch = arena.allocate(MATCH);
        for (var i = 0; i < matches; i++) {
            check((int) match.invokeExact(thread, result, i, aMatch), "read a match");
        }
        var first = arena.allocate(JAVA_DOUBLE);
        var second = arena.allocate(JAVA_DOUBLE);
        for (var y = 0; y < height; y += Math.max(1, height / 8)) {
            for (var x = 0; x < width; x += Math.max(1, width / 8)) {
                check((int) pixelToSky.invokeExact(thread, result, (double) x, (double) y, first, second), "convert a pixel");
                check((int) skyToPixel.invokeExact(thread, result, first.get(JAVA_DOUBLE, 0), second.get(JAVA_DOUBLE, 0), first, second),
                        "convert a position");
            }
        }
        var length = (int) resultJson.invokeExact(thread, result, MemorySegment.NULL, 0L);
        check((int) resultJson.invokeExact(thread, result, arena.allocate(length + 1L), length + 1L), "copy the JSON");
    }

    /** The samples of an image, in the native byte order, scaled to fit integer types. */
    private static MemorySegment samples(float[] data, int width, int height, int type, long rowStride, Arena arena) {
        var max = 0f;
        for (var value : data) {
            max = Math.max(max, value);
        }
        var limit = type == UINT8 ? 255f : 65535f;
        var scale = type == FLOAT32 || max <= limit ? 1f : limit / max;
        var pixels = arena.allocate(rowStride * height, 8);
        for (var y = 0; y < height; y++) {
            var row = y * rowStride;
            for (var x = 0; x < width; x++) {
                var value = data[y * width + x] * scale;
                switch (type) {
                    case UINT8 -> pixels.set(JAVA_BYTE, row + x, (byte) Math.clamp(Math.round(value), 0, 255));
                    case UINT16 ->
                        pixels.set(JAVA_SHORT.withByteAlignment(1), row + 2L * x, (short) Math.clamp(Math.round(value), 0, 65535));
                    default -> pixels.set(JAVA_FLOAT.withByteAlignment(1), row + 4L * x, value);
                }
            }
        }
        return pixels;
    }

    private static MethodHandle function(SymbolLookup library, String name, MemoryLayout result, MemoryLayout... arguments) {
        return Linker.nativeLinker().downcallHandle(library.findOrThrow(name), FunctionDescriptor.of(result, arguments));
    }

    private static void check(int status, String what) {
        if (status < 0) {
            throw new IllegalStateException("Unable to " + what);
        }
    }
}
