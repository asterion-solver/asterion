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

import me.champeau.asterion.SyntheticSky;
import me.champeau.asterion.catalog.Catalogs;
import me.champeau.asterion.image.GrayImage;
import me.champeau.asterion.index.IndexBuilder;
import me.champeau.asterion.index.IndexOptions;
import me.champeau.asterion.progress.ProgressListener;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Solves an image with the shared library, from a program in C which calls it like an acquisition
 * program would, see src/test/c/solve.c: the pixels are in its memory, and never in an image file.
 */
class NativeLibraryTest {
    private static final int WIDTH = 1600;
    private static final int HEIGHT = 1200;
    private static final double RA = 83.8;
    private static final double DEC = -5.4;
    private static final double SCALE = 20;
    private static final int REPEAT = 3;
    private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

    @TempDir
    private static Path directory;
    private static Path catalogs;
    private static GrayImage image;

    @BeforeAll
    static void buildCatalogAndImage() throws IOException {
        var sky = new SyntheticSky(300_000, 42);
        catalogs = Files.createDirectories(directory.resolve("catalogs"));
        IndexBuilder.build(sky.stars(), "synthetic", IndexOptions.defaults(), catalogs.resolve("synthetic.astx"), ProgressListener.none());
        image = sky.render(SyntheticSky.wcs(WIDTH, HEIGHT, RA, DEC, SCALE, 30, false), WIDTH, HEIGHT, 7);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void solvesPixelsInTheMemoryOfAProgramInC(int sampleType) throws Exception {
        var pixels = directory.resolve("pixels-" + sampleType + ".raw");
        Files.write(pixels, samples(sampleType));

        var output = run(program("solve"), catalogs.toString(), pixels.toString(), String.valueOf(sampleType), String.valueOf(WIDTH),
                String.valueOf(HEIGHT), String.valueOf(REPEAT));

        var lines = output.lines().toList();
        // the path, as the library writes it on this system
        assertEquals("error=No catalog is installed in " + Path.of("/no/catalogs/here") + ": asterion_install_catalog installs one",
                lines.getFirst(), output);
        var solves = lines.stream().filter(l -> l.startsWith("solved=")).map(NativeLibraryTest::fields).toList();
        assertEquals(REPEAT, solves.size(), output);
        for (var solve : solves) {
            assertEquals("1", solve.get("solved"), output);
            assertEquals(RA, Double.parseDouble(solve.get("ra")), 1 / 3600.0, output);
            assertEquals(DEC, Double.parseDouble(solve.get("dec")), 1 / 3600.0, output);
            assertEquals(SCALE, Double.parseDouble(solve.get("scale")), SCALE * 1e-3, output);
            assertEquals("0", solve.get("flipped"), output);
            assertTrue(Integer.parseInt(solve.get("matched")) >= 10, output);
            assertEquals("synthetic", solve.get("index"), output);
        }

        // the details of a result: the objects of the field, the Orion nebula first, the matched stars,
        // the conversions between pixels and the sky, the JSON of the command line tool
        var details = fields(line(lines, "details: "));
        assertTrue(Integer.parseInt(details.get("objects")) > 10, output);
        assertEquals("M 42", details.get("object"), output);
        assertTrue(Integer.parseInt(details.get("matches")) >= 10, output);
        assertTrue(Double.parseDouble(details.get("match_residual")) < SCALE, output);
        assertEquals(RA, Double.parseDouble(details.get("center_ra")), 1 / 3600.0, output);
        assertEquals(DEC, Double.parseDouble(details.get("center_dec")), 1 / 3600.0, output);
        assertEquals((WIDTH - 1) / 2.0, Double.parseDouble(details.get("back_x")), 1e-3, output);
        assertEquals((HEIGHT - 1) / 2.0, Double.parseDouble(details.get("back_y")), 1e-3, output);
        assertEquals("{\"solved\":true,\"", details.get("json_start"), output);
        // the options limit the search
        var regions = fields(line(lines, "regions: "));
        assertEquals("1", regions.get("right"), output);
        assertEquals("0", regions.get("wrong"), output);
        // a solve which may be cancelled: a solve without a flag or when it's 0, and when it's set, a search of a
        // minute which stops at once, without a result
        var cancel = fields(line(lines, "cancel: "));
        assertEquals("1", cancel.get("null"), output);
        assertEquals("1", cancel.get("not_cancelled"), output);
        assertEquals("-2", cancel.get("cancelled"), output);
        assertTrue(Double.parseDouble(cancel.get("cancelled_ms")) < 5000, output);
        assertEquals("0", cancel.get("result"), output);
        var zeros = fields(line(lines, "zero_options: "));
        assertEquals("-1", zeros.get("status"), output);
        assertTrue(zeros.get("error").startsWith("The option timeout must be positive"), output);
        // closing the solver unmaps its catalogs, where the system tells what is mapped
        var catalogs = fields(line(lines, "catalogs: "));
        if (!catalogs.get("mapped_before_close").equals("-1")) {
            assertTrue(Integer.parseInt(catalogs.get("mapped_before_close")) > 0, output);
            assertEquals("0", catalogs.get("mapped_after_close"), output);
        }
    }

    @Test
    void listsAndInstallsCatalogsFromAProgramInC() throws Exception {
        var output = run(program("catalogs"), catalogs.toString(), "no-such-catalog");
        var lines = output.lines().toList();
        assertTrue(lines.contains("default_directory=" + Catalogs.defaultDirectory()), output);
        assertTrue(line(lines, "version=").matches("\\d+\\.\\d+\\.\\d+.*|\\(development\\)"), output);

        var version = line(lines, "version=");
        var truncated = fields(line(lines, "truncated: "));
        assertEquals(version.substring(0, 3), truncated.get("version"), output);
        assertEquals(String.valueOf(version.length()), truncated.get("length"), output);
        assertEquals("-1", truncated.get("sizeof_null"), output);

        // the catalogs which can be installed, in their order, then the other installed ones
        var listed = lines.stream().filter(l -> l.startsWith("catalog: ")).map(l -> fields(l.substring("catalog: ".length()))).toList();
        var available = Catalogs.available();
        assertEquals(2 * (available.size() + 1), listed.size(), output);
        for (var i = 0; i < available.size(); i++) {
            var catalog = listed.get(i);
            assertEquals(available.get(i).name(), catalog.get("name"), output);
            assertEquals(available.get(i).description(), catalog.get("description"), output);
            assertEquals("0", catalog.get("installed"), output);
            assertEquals("1", catalog.get("available"), output);
            // NAN, which C libraries print their own way: nan, -nan, -nan(ind)...
            assertTrue(catalog.get("magnitude_limit").toLowerCase(Locale.ROOT).replace("-", "").startsWith("nan"), output);
        }
        var synthetic = listed.get(available.size());
        assertEquals("synthetic", synthetic.get("name"), output);
        assertEquals("1", synthetic.get("installed"), output);
        assertEquals("0", synthetic.get("available"), output);
        assertEquals(String.valueOf(Files.size(catalogs.resolve("synthetic.astx"))), synthetic.get("size"), output);
        assertTrue(Long.parseLong(synthetic.get("stars")) > 100_000, output);
        assertTrue(Long.parseLong(synthetic.get("quads")) > 0, output);
        assertEquals("", synthetic.get("error"), output);

        // installing what Asterion doesn't know fails at once, and says why
        assertEquals("status=-1 error=\"Unknown catalog no-such-catalog: asterion_catalogs lists them\"", line(lines, "installed: "),
                output);
    }

    @Test
    void thePythonPackageWorksOnceItsWheelIsInstalled() throws Exception {
        var python = WINDOWS ? "python" : "python3";
        // CI tests the package on every platform: elsewhere, where Python can create environments, which some
        // distributions install apart
        var environments = available(python, "-c", "import ensurepip, venv");
        if (System.getenv("CI") != null) {
            assertTrue(environments, "Python, with venv and ensurepip, isn't installed");
        }
        assumeTrue(environments, "Python, with venv and ensurepip, isn't installed");
        // an environment of its own, where the wheel is installed like pip installs it from PyPI, without the network
        var environment = directory.resolve("python-environment");
        run(Map.of(), python, "-m", "venv", environment.toString());
        var environmentPython = environment.resolve(WINDOWS ? "Scripts/python.exe" : "bin/python").toString();
        try (var files = Files.list(Path.of(System.getProperty("asterion.wheels")))) {
            var wheel = files.filter(f -> f.toString().endsWith(".whl")).findFirst().orElseThrow();
            run(Map.of(), environmentPython, "-m", "pip", "install", "--no-index", "--no-deps", "--disable-pip-version-check",
                    wheel.toString());
        }
        var pixels = directory.resolve("pixels-python.raw");
        Files.write(pixels, samples(2));

        var output = run(Map.of(
                "ASTERION_TEST_CATALOGS", catalogs.toString(),
                "ASTERION_TEST_PIXELS", pixels.toString(),
                "ASTERION_TEST_WIDTH", String.valueOf(WIDTH),
                "ASTERION_TEST_HEIGHT", String.valueOf(HEIGHT),
                "ASTERION_TEST_RA", String.valueOf(RA),
                "ASTERION_TEST_DEC", String.valueOf(DEC)),
                environmentPython, "-m", "unittest", "discover", "--start-directory", System.getProperty("asterion.pythonTests"),
                "--verbose");
        // the tests of the package, in the report of this one
        System.out.println(output);
        assertTrue(output.contains("\nOK"), output);
    }

    @Test
    void theLibraryIsFoundInTheDirectoriesWhichProgramsRecordOnMacOs() throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Mac"), "macOS only");
        var library = Path.of(System.getProperty("asterion.library"), "libasterion.dylib");
        var output = run("otool", "-D", library.toString());

        assertEquals("@rpath/libasterion.dylib", output.lines().toList().getLast(), output);
    }

    /** The path of a test program in C. */
    private static String program(String name) {
        return Path.of(System.getProperty("asterion.cTests"), WINDOWS ? name + ".exe" : name).toString();
    }

    /** Runs a program which uses the library, which must succeed: its output. */
    private static String run(String... command) throws IOException, InterruptedException {
        return run(Map.of(), command);
    }

    /** Runs a program which uses the library, with environment variables, which must succeed: its output. */
    private static String run(Map<String, String> variables, String... command) throws IOException, InterruptedException {
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(variables);
        // the Python package is the installed one, with its library
        builder.environment().remove("PYTHONPATH");
        builder.environment().remove("ASTERION_LIBRARY");
        if (System.getProperty("os.name").equals("Linux")) {
            // a locale which every Linux has, and isn't the one programs start with: the program fails
            // if creating the isolate sets it
            builder.environment().put("LC_ALL", "C.UTF-8");
        }
        if (WINDOWS) {
            // Windows finds the DLLs of a program in the directories of the PATH, whose name has any case: Path in
            // PowerShell, PATH in Git Bash. A second variable, of another case, would be ignored
            var environment = builder.environment();
            var path = environment.keySet().stream().filter("PATH"::equalsIgnoreCase).findFirst().orElse("PATH");
            environment.merge(path, System.getProperty("asterion.library"), (directories, library) -> library + ";" + directories);
        }
        var process = builder.start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output;
    }

    /** Whether a command can run, and succeeds. */
    private static boolean available(String... command) {
        try {
            return new ProcessBuilder(command).redirectErrorStream(true).start().waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The line of the program which starts with a prefix, without it. */
    private static String line(List<String> lines, String prefix) {
        return lines.stream().filter(l -> l.startsWith(prefix)).findFirst().orElseThrow().substring(prefix.length());
    }

    /** The samples of the image, in the native byte order: uint16 (2) or float32 (3). */
    private static byte[] samples(int sampleType) {
        var data = image.data();
        var buffer = ByteBuffer.allocate(data.length * (sampleType == 2 ? 2 : 4)).order(ByteOrder.nativeOrder());
        for (var value : data) {
            if (sampleType == 2) {
                buffer.putShort((short) Math.clamp(Math.round(value), 0, 65535));
            } else {
                buffer.putFloat(value);
            }
        }
        return buffer.array();
    }

    /** The fields of a line of the program: "solved=1 ra=83.800000 ... object=\"M 42\"". */
    private static Map<String, String> fields(String line) {
        var fields = new HashMap<String, String>();
        var matcher = FIELD.matcher(line);
        while (matcher.find()) {
            fields.put(matcher.group(1), matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
        }
        return fields;
    }

    private static final Pattern FIELD = Pattern.compile("(\\w+)=(?:\"([^\"]*)\"|(\\S+))");
}
