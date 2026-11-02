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

import me.champeau.asterion.catalog.Catalogs;
import me.champeau.asterion.catalog.DeepSkyObjects;
import me.champeau.asterion.image.DetectionOptions;
import me.champeau.asterion.image.ImageHints;
import me.champeau.asterion.image.RawPixels;
import me.champeau.asterion.index.StarIndex;
import me.champeau.asterion.progress.ProgressListener;
import me.champeau.asterion.progress.ProgressStep;
import me.champeau.asterion.progress.ProgressUpdate;
import me.champeau.asterion.solver.Parity;
import me.champeau.asterion.solver.PlateSolver;
import me.champeau.asterion.solver.Solution;
import me.champeau.asterion.solver.SolutionJson;
import me.champeau.asterion.solver.SolveResult;
import me.champeau.asterion.solver.SolverOptions;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.ObjectHandle;
import org.graalvm.nativeimage.ObjectHandles;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.function.CFunctionPointer;
import org.graalvm.nativeimage.c.function.InvokeCFunctionPointer;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CConst;
import org.graalvm.nativeimage.c.type.CDoublePointer;
import org.graalvm.nativeimage.c.type.CTypedef;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.nativeimage.c.type.VoidPointer;
import org.graalvm.nativeimage.c.type.WordPointer;
import org.graalvm.word.PointerBase;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;

import static me.champeau.asterion.lib.CStructs.CATALOG;
import static me.champeau.asterion.lib.CStructs.MATCH;
import static me.champeau.asterion.lib.CStructs.OBJECT;
import static me.champeau.asterion.lib.CStructs.OPTIONS;
import static me.champeau.asterion.lib.CStructs.PROGRESS;
import static me.champeau.asterion.lib.CStructs.SOLUTION;
import static me.champeau.asterion.lib.CStructs.getDouble;
import static me.champeau.asterion.lib.CStructs.getInt;
import static me.champeau.asterion.lib.CStructs.setAddress;
import static me.champeau.asterion.lib.CStructs.setDouble;
import static me.champeau.asterion.lib.CStructs.setInt;
import static me.champeau.asterion.lib.CStructs.setLong;
import static me.champeau.asterion.lib.CStructs.setString;

/** The functions of the shared library, which src/main/c/asterion.h documents. */
final class NativeLibrary {
    private NativeLibrary() {
    }

    /** What asterion_solve returns, and the other functions which can fail. */
    private static final int SOLVED = 1;
    private static final int NOT_SOLVED = 0;
    private static final int ERROR = -1;
    private static final int CANCELLED = -2;

    /** The events of asterion_progress, by their codes in the header: ASTERION_EVENT_STEP is 1. */
    private static final int STEP = 1;
    private static final int PROGRESS_MADE = 2;
    private static final int DETAIL = 3;
    private static final int DONE = 4;
    private static final int INFO = 5;

    /** The version of the library, which the build writes. */
    private static final String VERSION = readVersion();

    /** The index files which are being installed, so that a catalog is installed by one thread at a time. */
    private static final Set<Path> INSTALLING = ConcurrentHashMap.newKeySet();

    /** The types of samples, by their codes in the header: ASTERION_UINT8 is 1. */
    private static final RawPixels.SampleType[] SAMPLE_TYPES = RawPixels.SampleType.values();

    /** The parities, by their codes in the header: ASTERION_PARITY_BOTH is 0. */
    private static final List<Parity> PARITIES = List.of(Parity.BOTH, Parity.NORMAL, Parity.FLIPPED);

    /** The SIP polynomials, by the prefixes of their FITS keywords, with the largest order which their arrays hold. */
    private static final Map<String, Integer> POLYNOMIALS = Map.of(
            "A", CStructs.MAX_SIP_ORDER,
            "B", CStructs.MAX_SIP_ORDER,
            "AP", CStructs.MAX_SIP_INVERSE_ORDER,
            "BP", CStructs.MAX_SIP_INVERSE_ORDER);

    /** The message of the last error of each thread, see {@link #error}. */
    private static final ThreadLocal<String> LAST_ERROR = new ThreadLocal<>();

    /**
     * An open solver, and the arena of its catalogs: closing the session unmaps them at once, rather
     * than whenever the garbage collector would.
     */
    private record Session(
            PlateSolver solver,
            Arena arena) {
    }

    /**
     * The result of a solve which the program keeps: its deep sky objects are found the first time
     * they're asked for.
     */
    private static final class Result {
        final SolveResult result;
        private List<DeepSkyObjects.InField> objects;

        Result(SolveResult result) {
            this.result = result;
        }

        synchronized List<DeepSkyObjects.InField> objects() {
            if (objects == null) {
                objects = result.solution().map(s -> DeepSkyObjects.inField(s.wcs(), s.width(), s.height())).orElse(List.of());
            }
            return objects;
        }

        Solution solution() {
            return result.solution().orElseThrow(() -> new IllegalStateException("The image wasn't solved"));
        }
    }

    /** asterion_progress_callback: a function of the program which receives the progress of an installation. */
    interface ProgressCallback extends CFunctionPointer {
        /** @return non-zero to cancel the installation */
        @InvokeCFunctionPointer
        int invoke(VoidPointer progress, VoidPointer userData);
    }

    /** Hands the progress of an installation to the program, as asterion_progress, until it cancels. */
    private static final class Callback implements ProgressListener {
        private final ProgressCallback function;
        private final VoidPointer userData;
        private boolean cancelled;

        Callback(ProgressCallback function, VoidPointer userData) {
            this.function = function;
            this.userData = userData;
        }

        boolean cancelled() {
            return cancelled;
        }

        @Override
        public void started(ProgressStep step) {
            send(STEP, step, 0, -1, step.hasTotal() ? 0 : Double.NaN, null);
        }

        @Override
        public void advanced(ProgressUpdate update) {
            var detail = update.detail().isEmpty() ? null : update.detail();
            send(detail == null ? PROGRESS_MADE : DETAIL, update.step(), update.completed(), update.bytes(), update.fraction(), detail);
        }

        @Override
        public void finished(ProgressStep step) {
            send(DONE, step, 0, -1, Double.NaN, null);
        }

        @Override
        public void info(String message) {
            send(INFO, null, 0, -1, Double.NaN, message);
        }

        private void send(int event, ProgressStep step, long completed, long bytes, double fraction, String message) {
            if (function.isNull() || cancelled) {
                return;
            }
            // the strings are valid while the function runs
            try (var arena = Arena.ofConfined()) {
                var progress = arena.allocate(PROGRESS);
                setInt(PROGRESS, progress, "event", event);
                setLong(PROGRESS, progress, "completed", completed);
                setLong(PROGRESS, progress, "total", step == null ? -1 : step.total());
                setLong(PROGRESS, progress, "bytes", bytes);
                setDouble(PROGRESS, progress, "fraction", fraction);
                setAddress(PROGRESS, progress, "step", step == null ? MemorySegment.NULL : arena.allocateFrom(step.description()));
                setAddress(PROGRESS, progress, "unit", step == null ? MemorySegment.NULL : arena.allocateFrom(step.unit()));
                setAddress(PROGRESS, progress, "message", message == null ? MemorySegment.NULL : arena.allocateFrom(message));
                cancelled = function.invoke(WordFactory.pointer(progress.address()), userData) != 0;
            }
        }
    }

    /** Copies the version of the library, such as "0.4.0", null terminated: its length in bytes. */
    @CEntryPoint(name = "asterion_version")
    static int version(IsolateThread thread, CCharPointer buffer, UnsignedWord size) {
        try {
            return copyString(VERSION, buffer, size);
        } catch (Throwable e) {
            return failure(e);
        }
    }

    /**
     * Copies the directory of the catalogs which the functions use when they're given NULL, null
     * terminated: its length in bytes, or ASTERION_ERROR.
     */
    @CEntryPoint(name = "asterion_default_catalog_directory")
    static int defaultCatalogDirectory(IsolateThread thread, CCharPointer buffer, UnsignedWord size) {
        try {
            return copyString(Catalogs.defaultDirectory().toString(), buffer, size);
        } catch (Throwable e) {
            return failure(e);
        }
    }

    /**
     * Describes the catalogs which can be installed, then the other ones which are installed.
     *
     * @param catalogs where to write them, as many as fit
     * @param capacity the number of structures which fit
     * @return the number of catalogs, which may be larger than the capacity, or ASTERION_ERROR
     */
    @CEntryPoint(name = "asterion_catalogs")
    static int catalogs(IsolateThread thread, @CConst CCharPointer catalogDirectory, VoidPointer catalogs, int capacity) {
        try {
            if (capacity < 0) {
                throw new IllegalArgumentException("The capacity can't be negative");
            }
            var directory = directory(catalogDirectory);
            var installed = new LinkedHashMap<String, Path>();
            for (var file : Catalogs.installed(directory)) {
                installed.put(nameOf(file), file);
            }
            var names = new LinkedHashSet<String>();
            Catalogs.available().forEach(descriptor -> names.add(descriptor.name()));
            names.addAll(installed.keySet());
            var count = Math.min(names.size(), capacity);
            var out = count == 0 ? MemorySegment.NULL : memory(catalogs, CATALOG.byteSize() * count);
            var i = 0;
            for (var name : names) {
                if (i == count) {
                    break;
                }
                describe(name, installed.get(name), out.asSlice(CATALOG.byteSize() * i++, CATALOG.byteSize()));
            }
            return names.size();
        } catch (Throwable e) {
            return failure(e);
        }
    }

    /**
     * Installs a catalog, the way asterion --download-catalog does, and returns when it's over.
     *
     * @param callback receives the progress on the calling thread, and cancels by returning non-zero, or NULL
     * @return 0, ASTERION_CANCELLED or ASTERION_ERROR
     */
    @CEntryPoint(name = "asterion_install_catalog")
    static int installCatalog(IsolateThread thread, @CConst CCharPointer catalogDirectory, @CConst CCharPointer name,
                              @CTypedef(name = "asterion_progress_callback") ProgressCallback callback, VoidPointer userData) {
        try {
            var directory = directory(catalogDirectory);
            if (name.isNull()) {
                throw new IllegalArgumentException("The name of the catalog is required");
            }
            var catalog = CTypeConversion.utf8ToJavaString(name);
            var descriptor = Catalogs.find(catalog)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown catalog " + catalog + ": asterion_catalogs lists them"));
            var index = directory.resolve(descriptor.name() + Catalogs.EXTENSION).toAbsolutePath().normalize();
            if (!INSTALLING.add(index)) {
                throw new IllegalStateException(descriptor.name() + " is being installed in " + directory);
            }
            try {
                var listener = new Callback(callback, userData);
                Installation.run(progress -> Catalogs.install(descriptor, directory, progress), listener, listener::cancelled);
                return 0;
            } finally {
                INSTALLING.remove(index);
            }
        } catch (CancellationException e) {
            failure(e);
            return CANCELLED;
        } catch (Throwable e) {
            return failure(e);
        }
    }

    /**
     * Opens the catalogs of a directory: the solver keeps them mapped in memory until it's closed.
     *
     * @param catalogDirectory the directory, or NULL for the default one
     * @param solver where to write the solver
     * @return 0, or ASTERION_ERROR
     */
    @CEntryPoint(name = "asterion_open")
    static int open(IsolateThread thread, @CConst CCharPointer catalogDirectory, WordPointer solver) {
        var arena = Arena.ofShared();
        try {
            var directory = directory(catalogDirectory);
            var files = Catalogs.installed(directory);
            if (files.isEmpty()) {
                throw new IOException("No catalog is installed in " + directory + ": asterion_install_catalog installs one");
            }
            var session = new Session(PlateSolver.open(arena, files.toArray(Path[]::new)), arena);
            solver.write(ObjectHandles.getGlobal().create(session));
            return 0;
        } catch (Throwable e) {
            arena.close();
            return failure(e);
        }
    }

    /**
     * Closes a solver: its catalogs are unmapped at once, which releases their memory. The solver
     * can't be used anymore, nor closed again, and no solve may run with it meanwhile.
     *
     * @return 0, or ASTERION_ERROR
     */
    @CEntryPoint(name = "asterion_close")
    static int close(IsolateThread thread, ObjectHandle solver) {
        try {
            if (!(ObjectHandles.getGlobal().get(solver) instanceof Session session)) {
                throw new IllegalArgumentException("Not a solver");
            }
            // first: the solver stays open if its catalogs can't be closed, while a solve runs for example
            session.arena().close();
            ObjectHandles.getGlobal().destroy(solver);
            return 0;
        } catch (Throwable e) {
            return failure(e);
        }
    }

    /** Fills options with the defaults of the command line tool: 0, or ASTERION_ERROR. */
    @CEntryPoint(name = "asterion_default_options")
    static int defaultOptions(IsolateThread thread, VoidPointer pointer) {
        try {
            var out = memory(pointer, OPTIONS);
            var defaults = SolverOptions.defaults();
            out.fill((byte) 0);
            for (var field : List.of("ra", "dec", "scale_low", "scale_high", "fov", "pixel_size", "epoch")) {
                setDouble(OPTIONS, out, field, Double.NaN);
            }
            setDouble(OPTIONS, out, "radius", defaults.searchRadiusDeg());
            setDouble(OPTIONS, out, "timeout", defaults.timeout().toMillis() / 1000.0);
            setDouble(OPTIONS, out, "sigma", defaults.detection().thresholdSigma());
            setInt(OPTIONS, out, "parity", PARITIES.indexOf(defaults.parity()));
            setInt(OPTIONS, out, "sip_order", defaults.sipOrder());
            setInt(OPTIONS, out, "binning", defaults.binning());
            setInt(OPTIONS, out, "max_stars", defaults.detection().maxStars());
            setInt(OPTIONS, out, "quad_stars", defaults.maxQuadStars());
            return 0;
        } catch (Throwable e) {
            return failure(e);
        }
    }

    /**
     * Solves an image: its pixels are copied, converted and binned in a single pass, then solved.
     *
     * @param sampleType ASTERION_UINT8, ASTERION_UINT16 or ASTERION_FLOAT32
     * @param rowStride the distance between the starts of two rows, in bytes
     * @param bayer non-zero if the pixels are a raw frame of a color sensor
     * @param options the options of the search, or NULL for the defaults
     * @param solution where to write the solution, or NULL
     * @param result where to write the result, which the program frees with asterion_result_free, or NULL
     * @return ASTERION_SOLVED, ASTERION_NOT_SOLVED or ASTERION_ERROR
     */
    @CEntryPoint(name = "asterion_solve")
    static int solve(IsolateThread thread, ObjectHandle solver, @CConst VoidPointer pixels, int sampleType, int width, int height,
                     long rowStride, int bayer, @CConst VoidPointer options, VoidPointer solution, WordPointer result) {
        try {
            if (pixels.isNull()) {
                throw new IllegalArgumentException("The pixels are required");
            }
            if (sampleType < 1 || sampleType > SAMPLE_TYPES.length) {
                throw new IllegalArgumentException("Unknown type of samples " + sampleType);
            }
            PlateSolver plateSolver = ObjectHandles.getGlobal().<Session>get(solver).solver();
            var solverOptions = options(options);
            var type = SAMPLE_TYPES[sampleType - 1];
            var start = System.nanoTime();
            var size = Math.max(0, height - 1) * rowStride + (long) width * type.bytes();
            var image = RawPixels.read(memory(pixels, size), type, width, height, rowStride, bayer != 0, solverOptions.binning());
            var readMillis = (System.nanoTime() - start) / 1e6;
            var solved = plateSolver.solveInPlace(image, ImageHints.none(), solverOptions, readMillis);
            if (solution.isNonNull()) {
                write(solved, memory(solution, SOLUTION));
            }
            if (result.isNonNull()) {
                result.write(ObjectHandles.getGlobal().create(new Result(solved)));
            }
            return solved.solved() ? SOLVED : NOT_SOLVED;
        } catch (Throwable e) {
            return failure(e);
        }
    }

    /** Frees a result: NULL is ignored, and so is what isn't a result, which this function can't report. */
    @CEntryPoint(name = "asterion_result_free")
    static void freeResult(IsolateThread thread, ObjectHandle result) {
        try {
            if (result.notEqual(WordFactory.nullPointer()) && ObjectHandles.getGlobal().get(result) instanceof Result) {
                ObjectHandles.getGlobal().destroy(result);
            }
        } catch (Throwable e) {
            failure(e);
        }
    }

    /**
     * Copies the result as JSON, the output of asterion --json, null terminated.
     *
     * @return the length of the JSON in bytes, which may be larger than the buffer, or ASTERION_ERROR
     */
    @CEntryPoint(name = "asterion_result_json")
    static int json(IsolateThread thread, ObjectHandle result, CCharPointer buffer, UnsignedWord size) {
        try {
            return copyString(SolutionJson.of(null, result(result).result), buffer, size);
        } catch (Throwable e) {
            return failure(e);
        }
    }

    /** The number of deep sky objects of the image, or ASTERION_ERROR. */
    @CEntryPoint(name = "asterion_result_object_count")
    static int objectCount(IsolateThread thread, ObjectHandle result) {
        try {
            return result(result).objects().size();
        } catch (Throwable e) {
            return failure(e);
        }
    }

    /** Copies a deep sky object of the image, the most prominent first: 0, or ASTERION_ERROR. */
    @CEntryPoint(name = "asterion_result_object")
    static int object(IsolateThread thread, ObjectHandle result, int index, VoidPointer pointer) {
        try {
            var found = result(result).objects().get(index);
            var o = found.object();
            var out = memory(pointer, OBJECT);
            setDouble(OBJECT, out, "ra", o.raDeg());
            setDouble(OBJECT, out, "dec", o.decDeg());
            setDouble(OBJECT, out, "x", found.x());
            setDouble(OBJECT, out, "y", found.y());
            setDouble(OBJECT, out, "distance", found.distanceDeg());
            setDouble(OBJECT, out, "major_arcmin", o.majorArcmin());
            setDouble(OBJECT, out, "minor_arcmin", o.minorArcmin());
            setDouble(OBJECT, out, "angle", o.angleDeg());
            setDouble(OBJECT, out, "magnitude", o.magnitude());
            setInt(OBJECT, out, "messier", o.messier());
            setString(OBJECT, out, "name", o.name());
            setString(OBJECT, out, "designation", o.designation());
            setString(OBJECT, out, "common_name", o.commonName());
            setString(OBJECT, out, "aliases", o.aliases());
            setString(OBJECT, out, "type", o.type().description());
            return 0;
        } catch (Throwable e) {
            return failure(e);
        }
    }

    /** The number of stars of the image which were matched with the catalog, or ASTERION_ERROR. */
    @CEntryPoint(name = "asterion_result_match_count")
    static int matchCount(IsolateThread thread, ObjectHandle result) {
        try {
            return result(result).result.solution().map(s -> s.matches().size()).orElse(0);
        } catch (Throwable e) {
            return failure(e);
        }
    }

    /** Copies a star of the image which was matched with the catalog: 0, or ASTERION_ERROR. */
    @CEntryPoint(name = "asterion_result_match")
    static int match(IsolateThread thread, ObjectHandle result, int index, VoidPointer pointer) {
        try {
            var match = result(result).solution().matches().get(index);
            var out = memory(pointer, MATCH);
            setDouble(MATCH, out, "x", match.x());
            setDouble(MATCH, out, "y", match.y());
            setDouble(MATCH, out, "ra", match.raDeg());
            setDouble(MATCH, out, "dec", match.decDeg());
            setDouble(MATCH, out, "residual", match.residualArcsec());
            return 0;
        } catch (Throwable e) {
            return failure(e);
        }
    }

    /** The position on the sky of a pixel, in degrees, distortion included: 0, or ASTERION_ERROR. */
    @CEntryPoint(name = "asterion_result_pixel_to_sky")
    static int pixelToSky(IsolateThread thread, ObjectHandle result, double x, double y, CDoublePointer ra, CDoublePointer dec) {
        try {
            var sky = result(result).solution().wcs().pixelToSky(x, y);
            ra.write(sky[0]);
            dec.write(sky[1]);
            return 0;
        } catch (Throwable e) {
            return failure(e);
        }
    }

    /**
     * The pixel of a position on the sky, in degrees, distortion included: 0, or ASTERION_ERROR when
     * the position is too far from the image to be projected.
     */
    @CEntryPoint(name = "asterion_result_sky_to_pixel")
    static int skyToPixel(IsolateThread thread, ObjectHandle result, double ra, double dec, CDoublePointer x, CDoublePointer y) {
        try {
            var pixel = result(result).solution().wcs().skyToPixel(ra, dec);
            if (pixel == null) {
                throw new IllegalArgumentException("The position " + ra + ", " + dec + " can't be projected on the image");
            }
            x.write(pixel[0]);
            y.write(pixel[1]);
            return 0;
        } catch (Throwable e) {
            return failure(e);
        }
    }

    /**
     * Copies the message of the last error of the calling thread, null terminated.
     *
     * @return the length of the message in bytes, which may be larger than the buffer
     */
    @CEntryPoint(name = "asterion_error")
    static int error(IsolateThread thread, CCharPointer buffer, UnsignedWord size) {
        try {
            var message = LAST_ERROR.get();
            return copyString(message == null ? "" : message, buffer, size);
        } catch (Throwable e) {
            return ERROR;
        }
    }

    /** The size of a structure of the header, for asterion_check_header, or ASTERION_ERROR if there's none of this name. */
    @CEntryPoint(name = "asterion_sizeof")
    static int sizeOf(IsolateThread thread, @CConst CCharPointer name) {
        if (name.isNull()) {
            return ERROR;
        }
        var layout = CStructs.BY_NAME.get(CTypeConversion.utf8ToJavaString(name));
        return layout == null ? ERROR : (int) layout.byteSize();
    }

    /** The directory of catalogs which the program gives, or the default one for NULL. */
    private static Path directory(CCharPointer directory) {
        return directory.isNull() ? Catalogs.defaultDirectory() : Path.of(CTypeConversion.utf8ToJavaString(directory));
    }

    private static String nameOf(Path index) {
        var name = index.getFileName().toString();
        return name.substring(0, name.length() - Catalogs.EXTENSION.length());
    }

    /** Fills asterion_catalog, with what the index tells when it's installed. */
    private static void describe(String name, Path index, MemorySegment out) throws IOException {
        out.fill((byte) 0);
        var descriptor = Catalogs.find(name);
        setString(CATALOG, out, "name", name);
        setString(CATALOG, out, "description", descriptor.map(Catalogs.Descriptor::description).orElse(""));
        setInt(CATALOG, out, "available", descriptor.isPresent() ? 1 : 0);
        for (var field : List.of("magnitude_limit", "min_quad_arcmin", "max_quad_deg")) {
            setDouble(CATALOG, out, field, Double.NaN);
        }
        if (index == null) {
            return;
        }
        setInt(CATALOG, out, "installed", 1);
        setLong(CATALOG, out, "size", Files.size(index));
        // its own arena: the index is unmapped as soon as it's described
        try (var arena = Arena.ofConfined()) {
            var catalog = StarIndex.open(index, arena);
            setLong(CATALOG, out, "stars", catalog.starCount());
            setLong(CATALOG, out, "quads", catalog.quadCount());
            setDouble(CATALOG, out, "magnitude_limit", catalog.magnitudeLimit());
            setDouble(CATALOG, out, "min_quad_arcmin", Math.toDegrees(catalog.minQuadDiam()) * 60);
            setDouble(CATALOG, out, "max_quad_deg", Math.toDegrees(catalog.maxQuadDiam()));
        } catch (IOException | RuntimeException e) {
            setString(CATALOG, out, "error", e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private static String readVersion() {
        try (var in = NativeLibrary.class.getResourceAsStream("/asterion-version.txt")) {
            return in == null ? "(development)" : new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int failure(Throwable e) {
        LAST_ERROR.set(e.getMessage() == null ? e.toString() : e.getMessage());
        return ERROR;
    }

    private static Result result(ObjectHandle handle) {
        if (handle.equal(WordFactory.nullPointer())) {
            throw new IllegalArgumentException("No result");
        }
        return ObjectHandles.getGlobal().get(handle);
    }

    /** Copies a string into a buffer of the program, see {@link CStructs#copyString}: its length in bytes. */
    private static int copyString(String text, CCharPointer buffer, UnsignedWord size) {
        if (buffer.isNull() && size.notEqual(0)) {
            throw new IllegalArgumentException("The buffer is NULL");
        }
        return CStructs.copyString(text, buffer.isNull() ? MemorySegment.NULL : memory(buffer, size.rawValue()));
    }

    private static MemorySegment memory(PointerBase pointer, StructLayout layout) {
        if (pointer.isNull()) {
            throw new IllegalArgumentException("A pointer to a structure is null");
        }
        return memory(pointer, layout.byteSize());
    }

    /**
     * The memory of the program at an address. Giving a size to memory which comes from C is what
     * the restricted methods of FFM are for: the program is trusted with it, as with any C library.
     */
    @SuppressWarnings("restricted")
    private static MemorySegment memory(PointerBase pointer, long size) {
        return MemorySegment.ofAddress(pointer.rawValue()).reinterpret(size);
    }

    /** The options of a search, from asterion_options, the way the command line tool reads its own. */
    private static SolverOptions options(VoidPointer pointer) {
        if (pointer.isNull()) {
            return SolverOptions.defaults();
        }
        var in = memory(pointer, OPTIONS);
        var parity = getInt(OPTIONS, in, "parity");
        if (parity < 0 || parity >= PARITIES.size()) {
            throw new IllegalArgumentException("Unknown parity " + parity);
        }
        // the polynomials fill the arrays of asterion_solution
        var sipOrder = getInt(OPTIONS, in, "sip_order");
        if (sipOrder < -1 || sipOrder == 1 || sipOrder > CStructs.MAX_SIP_ORDER) {
            throw new IllegalArgumentException("The order of the SIP polynomials must be 0, 2 to " + CStructs.MAX_SIP_ORDER
                    + ", or -1, not " + sipOrder);
        }
        var timeout = requirePositive("timeout", getDouble(OPTIONS, in, "timeout"));
        var sigma = requirePositive("sigma", getDouble(OPTIONS, in, "sigma"));
        var maxStars = (int) requirePositive("max_stars", getInt(OPTIONS, in, "max_stars"));
        var quadStars = (int) requirePositive("quad_stars", getInt(OPTIONS, in, "quad_stars"));
        var binning = getInt(OPTIONS, in, "binning");
        if (binning < 0) {
            throw new IllegalArgumentException("The binning can't be negative: " + binning);
        }
        var builder = SolverOptions.builder()
                .parity(PARITIES.get(parity))
                .sipOrder(sipOrder)
                .timeout(Duration.ofMillis((long) (timeout * 1000)))
                .binning(binning)
                .maxQuadStars(quadStars)
                .detection(DetectionOptions.defaults()
                        .withThresholdSigma(sigma)
                        .withMaxStars(maxStars));
        var ra = getDouble(OPTIONS, in, "ra");
        var dec = getDouble(OPTIONS, in, "dec");
        if (Double.isNaN(ra) != Double.isNaN(dec)) {
            throw new IllegalArgumentException("ra and dec must be given together");
        }
        if (!Double.isNaN(ra)) {
            builder.position(ra, dec, requirePositive("radius", getDouble(OPTIONS, in, "radius")));
        }
        var low = getDouble(OPTIONS, in, "scale_low");
        var high = getDouble(OPTIONS, in, "scale_high");
        if (!Double.isNaN(low) || !Double.isNaN(high)) {
            builder.scale(Double.isNaN(low) ? 0 : low, Double.isNaN(high) ? 0 : high);
        }
        var fov = getDouble(OPTIONS, in, "fov");
        if (fov > 0) {
            builder.fieldOfView(fov * 0.7, fov * 1.3);
        }
        var pixelSize = getDouble(OPTIONS, in, "pixel_size");
        if (pixelSize > 0) {
            builder.pixelSize(pixelSize);
        }
        var epoch = getDouble(OPTIONS, in, "epoch");
        if (!Double.isNaN(epoch)) {
            builder.epoch(epoch);
        }
        return builder.build();
    }

    /**
     * An option which must be positive: options filled with zeros, rather than by asterion_default_options,
     * are an error rather than a search which can't succeed.
     */
    private static double requirePositive(String field, double value) {
        if (!(value > 0)) {
            throw new IllegalArgumentException("The option " + field + " must be positive, not " + value
                    + ": asterion_default_options fills the options with their defaults");
        }
        return value;
    }

    /** Fills asterion_solution: without a solution, its fields are NAN or 0. */
    private static void write(SolveResult result, MemorySegment out) {
        out.fill((byte) 0);
        for (var field : List.of("ra", "dec", "scale", "rotation", "field_width", "field_height", "rms", "log_odds", "focal_length",
                "pixel_size", "fwhm_arcsec")) {
            setDouble(SOLUTION, out, field, Double.NaN);
        }
        var stats = result.stats();
        setDouble(SOLUTION, out, "read_ms", stats.loadMillis());
        setDouble(SOLUTION, out, "detect_ms", stats.detectMillis());
        setDouble(SOLUTION, out, "solve_ms", stats.solveMillis());
        setInt(SOLUTION, out, "stars", result.stars().size());
        setDouble(SOLUTION, out, "fwhm_pixels", result.stars().medianFwhm());
        var s = result.solution().orElse(null);
        if (s == null) {
            return;
        }
        setDouble(SOLUTION, out, "ra", s.raDeg());
        setDouble(SOLUTION, out, "dec", s.decDeg());
        setDouble(SOLUTION, out, "scale", s.pixelScale());
        setDouble(SOLUTION, out, "rotation", s.rotationDeg());
        setDouble(SOLUTION, out, "field_width", s.fieldWidthDeg());
        setDouble(SOLUTION, out, "field_height", s.fieldHeightDeg());
        setDouble(SOLUTION, out, "rms", s.rmsArcsec());
        setDouble(SOLUTION, out, "log_odds", s.logOdds());
        setDouble(SOLUTION, out, "fwhm_arcsec", result.stars().medianFwhm() * s.pixelScale());
        result.focalLengthMm().ifPresent(focal -> {
            setDouble(SOLUTION, out, "focal_length", focal);
            setDouble(SOLUTION, out, "pixel_size", result.pixelSizeMicrons().orElseThrow());
        });
        setInt(SOLUTION, out, "flipped", s.flipped() ? 1 : 0);
        setInt(SOLUTION, out, "matched_stars", s.matchedStars());
        setString(SOLUTION, out, "index", s.indexName());
        // the WCS, as the keywords of the FITS headers which Asterion writes
        var keywords = s.wcs().toFitsKeywords();
        setDouble(SOLUTION, out, "crpix", 0, number(keywords, "CRPIX1"));
        setDouble(SOLUTION, out, "crpix", 1, number(keywords, "CRPIX2"));
        setDouble(SOLUTION, out, "crval", 0, number(keywords, "CRVAL1"));
        setDouble(SOLUTION, out, "crval", 1, number(keywords, "CRVAL2"));
        setDouble(SOLUTION, out, "cd", 0, number(keywords, "CD1_1"));
        setDouble(SOLUTION, out, "cd", 1, number(keywords, "CD1_2"));
        setDouble(SOLUTION, out, "cd", 2, number(keywords, "CD2_1"));
        setDouble(SOLUTION, out, "cd", 3, number(keywords, "CD2_2"));
        setInt(SOLUTION, out, "sip_order", keywords.containsKey("A_ORDER") ? (int) number(keywords, "A_ORDER") : 0);
        setInt(SOLUTION, out, "sip_inverse_order", keywords.containsKey("AP_ORDER") ? (int) number(keywords, "AP_ORDER") : 0);
        // A_p_q is sip_a[p][q]: the matrices are row after row
        POLYNOMIALS.forEach((polynomial, maxOrder) -> {
            var field = "sip_" + polynomial.toLowerCase(Locale.ROOT);
            for (var p = 0; p <= maxOrder; p++) {
                for (var q = 0; p + q <= maxOrder; q++) {
                    if (keywords.get(polynomial + "_" + p + "_" + q) instanceof Number n) {
                        setDouble(SOLUTION, out, field, p * (maxOrder + 1) + q, n.doubleValue());
                    }
                }
            }
        });
    }

    private static double number(Map<String, Object> keywords, String key) {
        return ((Number) keywords.get(key)).doubleValue();
    }
}
