# Copyright 2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""
The tests of the Python package, which NativeLibraryTest runs against its wheel, installed in an environment of
their own, with a synthetic catalog and an image of it, given by environment variables.
"""
import array
import ctypes
import os
import random
import sys
import tempfile
import threading
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest import mock

import asterion
from asterion import _native

CATALOGS = os.environ["ASTERION_TEST_CATALOGS"]
WIDTH = int(os.environ["ASTERION_TEST_WIDTH"])
HEIGHT = int(os.environ["ASTERION_TEST_HEIGHT"])
RA = float(os.environ["ASTERION_TEST_RA"])
DEC = float(os.environ["ASTERION_TEST_DEC"])
ARCSEC = 1 / 3600
# the image, as uint16 samples in the native byte order
PIXELS = Path(os.environ["ASTERION_TEST_PIXELS"]).read_bytes()


NATIVE_UINT16 = ("<" if sys.byteorder == "little" else ">") + "u2"


class ArrayInterface:
    """An array which describes itself like NumPy arrays do, without NumPy: with the address of its data, or with
    an object which exports it, as Pillow does."""

    def __init__(self, data, typestr=NATIVE_UINT16, row_stride=None, address=True):
        self.data = data
        self.__array_interface__ = {
            "shape": (HEIGHT, WIDTH), "typestr": typestr, "version": 3,
            "data": (ctypes.addressof((ctypes.c_char * len(data)).from_buffer(data)), False) if address else data,
            "strides": (row_stride, 2) if row_stride else None,
        }


class PackageTest(unittest.TestCase):
    def test_comes_from_the_wheel(self):
        # installed in the environment which runs the tests, with the library
        self.assertTrue(Path(asterion.__file__).resolve().is_relative_to(Path(sys.prefix).resolve()), asterion.__file__)

    def test_version(self):
        self.assertRegex(asterion.version(), r"^\d+\.\d+\.\d+")
        self.assertRegex(asterion.__version__, r"^\d+\.\d+\.\d+")

    def test_default_catalog_directory(self):
        self.assertIsInstance(asterion.default_catalog_directory(), Path)


class CatalogsTest(unittest.TestCase):
    def test_lists_the_available_then_the_installed_catalogs(self):
        listed = asterion.catalogs(CATALOGS)
        tycho2 = next(c for c in listed if c.name == "tycho2")
        self.assertTrue(tycho2.available)
        self.assertFalse(tycho2.installed)
        self.assertIsNone(tycho2.magnitude_limit)
        self.assertIn("Tycho-2", tycho2.description)
        synthetic = listed[-1]
        self.assertEqual("synthetic", synthetic.name)
        self.assertTrue(synthetic.installed)
        self.assertFalse(synthetic.available)
        self.assertGreater(synthetic.stars, 100_000)
        self.assertGreater(synthetic.size, 0)
        self.assertIsNotNone(synthetic.magnitude_limit)
        self.assertIsNone(synthetic.error)

    def test_unknown_catalogs_are_not_installed(self):
        with self.assertRaisesRegex(asterion.AsterionError, "Unknown catalog no-such-catalog"):
            asterion.install_catalog("no-such-catalog", CATALOGS)

    def test_a_cancelled_installation_downloads_nothing(self):
        cancellation = asterion.Cancellation()
        cancellation.cancel()
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(asterion.InstallationCancelled):
                asterion.install_catalog("tycho2", directory, cancellation=cancellation)
            self.assertEqual([], os.listdir(directory))


class InstallationTest(unittest.TestCase):
    """The progress of an installation, which the library reports to the callback: replaced here by events, to
    install nothing."""

    def install(self, progress, cancelled_status=_native.CANCELLED):
        def call(name, *arguments):
            if name != "asterion_install_catalog_cancellable":
                return original(name, *arguments)
            callback = arguments[2]
            for event in (_native.Progress(event=1, total=10, step=b"Downloading", unit=b"MB", fraction=0.0, bytes=-1),
                          _native.Progress(event=2, completed=5, total=10, step=b"Downloading", unit=b"MB", fraction=0.5,
                                           bytes=5_000_000),
                          _native.Progress(event=5, total=-1, fraction=float("nan"), bytes=-1, message=b"Installed")):
                if callback(ctypes.pointer(event), None):
                    return cancelled_status
            return 0

        original = _native.call
        with mock.patch.object(_native, "call", call):
            asterion.install_catalog("tycho2", CATALOGS, progress)

    def test_reports_the_progress(self):
        events = []
        self.install(events.append)
        self.assertEqual(["step", "progress", "info"], [e.event for e in events])
        self.assertEqual(("Downloading", "MB", 10, 0.5, 5_000_000), (events[1].step, events[1].unit, events[1].total,
                                                                     events[1].fraction, events[1].bytes))
        self.assertEqual((None, None, None, "Installed"), (events[2].total, events[2].fraction, events[2].bytes,
                                                           events[2].message))

    def test_the_callback_cancels(self):
        with self.assertRaises(asterion.InstallationCancelled):
            self.install(lambda event: event.event == "progress")

    def test_the_errors_of_the_callback_cancel_and_are_raised(self):
        for error in (ValueError("callback"), KeyboardInterrupt()):
            def progress(event):
                raise error

            with self.assertRaises(type(error)):
                self.install(progress)

    def test_a_directory_without_catalogs_has_no_solver(self):
        with self.assertRaisesRegex(asterion.AsterionError, "No catalog is installed"):
            asterion.Solver(Path(CATALOGS) / "missing")


class SolverTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.solver = asterion.Solver(CATALOGS)

    @classmethod
    def tearDownClass(cls):
        cls.solver.close()

    def assertSolved(self, result):
        self.assertTrue(result.solved, result)
        self.assertAlmostEqual(RA, result.ra, delta=ARCSEC)
        self.assertAlmostEqual(DEC, result.dec, delta=ARCSEC)
        self.assertEqual("synthetic", result.catalog)

    def test_solves_bytes(self):
        self.assertSolved(self.solver.solve(PIXELS, WIDTH, HEIGHT, sample_type="uint16"))

    def test_solves_an_array_of_samples(self):
        self.assertSolved(self.solver.solve(array.array("H", PIXELS), WIDTH, HEIGHT))

    def test_solves_floats(self):
        floats = array.array("f", array.array("H", PIXELS))
        self.assertSolved(self.solver.solve(floats, WIDTH, HEIGHT))

    def test_solves_a_two_dimensional_memoryview(self):
        self.assertSolved(self.solver.solve(memoryview(bytearray(PIXELS)).cast("H", (HEIGHT, WIDTH))))

    def test_solves_rows_which_are_apart(self):
        padding = 16
        padded = bytearray()
        for y in range(HEIGHT):
            padded += PIXELS[y * WIDTH * 2:(y + 1) * WIDTH * 2] + bytes(padding)
        stride = WIDTH * 2 + padding
        self.assertSolved(self.solver.solve(padded, WIDTH, HEIGHT, sample_type="uint16", row_stride=stride))
        self.assertSolved(self.solver.solve(ArrayInterface(padded, "<u2" if sys.byteorder == "little" else ">u2", stride)))

    def test_solves_bytes_of_an_array(self):
        # Pillow describes its images with the bytes of their pixels, rather than with their address
        self.assertSolved(self.solver.solve(ArrayInterface(PIXELS, address=False)))

    def test_solves_bytes_of_8_bits(self):
        samples = array.array("H", PIXELS)
        brightest = max(samples)
        bytes8 = bytes(sample * 255 // brightest for sample in samples)
        self.assertSolved(self.solver.solve(bytes8, WIDTH, HEIGHT, sample_type="uint8"))

    def test_refuses_samples_in_another_byte_order(self):
        other = (">" if sys.byteorder == "little" else "<") + "u2"
        with self.assertRaisesRegex(ValueError, "native byte order"):
            self.solver.solve(ArrayInterface(bytearray(PIXELS), other))

    def test_refuses_buffers_which_are_too_small(self):
        with self.assertRaisesRegex(ValueError, f"{len(PIXELS) - 2} bytes"):
            self.solver.solve(PIXELS[:-2], WIDTH, HEIGHT, sample_type="uint16")

    def test_refuses_pixels_which_dont_say_what_they_are(self):
        refused = {
            "sample type of a buffer of bytes is required": lambda: self.solver.solve(PIXELS, WIDTH, HEIGHT),
            "are uint16, not float32": lambda: self.solver.solve(array.array("H", PIXELS), WIDTH, HEIGHT,
                                                                 sample_type="float32"),
            "are its own": lambda: self.solver.solve(ArrayInterface(bytearray(PIXELS)), WIDTH, HEIGHT),
            "can't be -2 bytes apart": lambda: self.solver.solve(PIXELS, WIDTH, HEIGHT, sample_type="uint16",
                                                                 row_stride=-2),
            "one or two dimensions": lambda: self.solver.solve(memoryview(bytearray(PIXELS)).cast("B", (2, HEIGHT, WIDTH))),
            "must be contiguous": lambda: self.solver.solve(memoryview(bytearray(PIXELS))[::2], WIDTH, HEIGHT // 2,
                                                            sample_type="uint8"),
            "Unknown parity": lambda: self.solver.solve(PIXELS, WIDTH, HEIGHT, sample_type="uint16", parity="sideways"),
        }
        for message, solve in refused.items():
            with self.subTest(message), self.assertRaisesRegex(ValueError, message):
                solve()

    def test_cancels_a_solve_from_another_thread(self):
        # random stars over noise, which are searched until the budget of the search ends: each row of noise is a
        # slice of a longer one
        stars = random.Random(8)
        noise = array.array("H", (1000 + int(stars.gauss(0, 8)) for _ in range(WIDTH + 997)))
        samples = array.array("H")
        for y in range(HEIGHT):
            samples.extend(noise[y * 389 % 997:y * 389 % 997 + WIDTH])
        for _ in range(600):
            cx, cy = stars.uniform(5, WIDTH - 5), stars.uniform(5, HEIGHT - 5)
            for y in range(int(cy) - 3, int(cy) + 4):
                for x in range(int(cx) - 3, int(cx) + 4):
                    samples[y * WIDTH + x] += int(5000 * 2.718 ** (-((x - cx) ** 2 + (y - cy) ** 2) / 4))
        options = {"timeout": 60, "max_stars": 600, "quad_stars": 500}
        # how long the search lasts, then the same one, cancelled by another thread
        start = time.monotonic()
        self.assertFalse(self.solver.solve(samples, WIDTH, HEIGHT, cancellation=asterion.Cancellation(), **options).solved)
        uncancelled = time.monotonic() - start
        cancellation = asterion.Cancellation()
        self.assertFalse(cancellation.cancelled)
        threading.Timer(0.05, cancellation.cancel).start()
        start = time.monotonic()
        with self.assertRaises(asterion.SolveCancelled):
            self.solver.solve(samples, WIDTH, HEIGHT, cancellation=cancellation, **options)
        self.assertLess(time.monotonic() - start, uncancelled / 2)
        self.assertTrue(cancellation.cancelled)
        # a cancellation which is cancelled already cancels at once, one which isn't changes nothing
        with self.assertRaises(asterion.SolveCancelled):
            self.solver.solve(samples, WIDTH, HEIGHT, cancellation=cancellation, **options)
        self.assertSolved(self.solver.solve(PIXELS, WIDTH, HEIGHT, sample_type="uint16", cancellation=asterion.Cancellation()))

    def test_details_of_a_result(self):
        result = self.solver.solve(PIXELS, WIDTH, HEIGHT, sample_type="uint16")
        self.assertEqual("M 42", result.objects[0].name)
        self.assertGreaterEqual(len(result.matches), 10)
        ra, dec = result.pixel_to_sky((WIDTH - 1) / 2, (HEIGHT - 1) / 2)
        self.assertAlmostEqual(RA, ra, delta=ARCSEC)
        x, y = result.sky_to_pixel(ra, dec)
        self.assertAlmostEqual((WIDTH - 1) / 2, x, delta=1e-3)
        self.assertAlmostEqual((HEIGHT - 1) / 2, y, delta=1e-3)
        self.assertAlmostEqual(RA, result.wcs["CRVAL1"], delta=1)
        self.assertTrue(result.json.startswith('{"solved":true'))
        with self.assertRaisesRegex(asterion.AsterionError, "projected"):
            result.sky_to_pixel((RA + 180) % 360, -DEC)

    def test_options_limit_the_search(self):
        self.assertSolved(self.solver.solve(PIXELS, WIDTH, HEIGHT, sample_type="uint16", ra=RA, dec=DEC, radius=2))
        elsewhere = self.solver.solve(PIXELS, WIDTH, HEIGHT, sample_type="uint16", ra=(RA + 90) % 360, dec=DEC, radius=2,
                                      timeout=5)
        self.assertFalse(elsewhere.solved)
        self.assertIsNone(elsewhere.ra)
        self.assertIsNone(elsewhere.wcs)
        self.assertGreater(elsewhere.stars, 0)
        self.assertEqual([], elsewhere.objects)
        self.assertEqual([], elsewhere.matches)
        self.assertTrue(elsewhere.json.startswith('{"solved":false'))
        with self.assertRaisesRegex(asterion.AsterionError, "solved"):
            elsewhere.pixel_to_sky(0, 0)
        with self.assertRaisesRegex(asterion.AsterionError, "SIP"):
            self.solver.solve(PIXELS, WIDTH, HEIGHT, sample_type="uint16", sip_order=9)

    def test_several_threads_solve_at_once(self):
        with ThreadPoolExecutor(max_workers=4) as pool:
            results = list(pool.map(lambda _: self.solver.solve(PIXELS, WIDTH, HEIGHT, sample_type="uint16"), range(8)))
        for result in results:
            self.assertSolved(result)

    def test_a_closed_solver_doesnt_solve(self):
        solver = asterion.Solver(CATALOGS)
        result = solver.solve(PIXELS, WIDTH, HEIGHT, sample_type="uint16")
        solver.close()
        # closing twice does nothing, and the results stay valid
        solver.close()
        self.assertSolved(result)
        self.assertEqual("M 42", result.objects[0].name)
        with self.assertRaisesRegex(asterion.AsterionError, "closed"):
            solver.solve(PIXELS, WIDTH, HEIGHT, sample_type="uint16")

    def test_a_solver_isnt_closed_while_it_solves(self):
        solver = asterion.Solver(CATALOGS)
        # what solve holds while the library solves
        with solver._solve():
            with self.assertRaisesRegex(asterion.AsterionError, "while it solves"):
                solver.close()
        solver.close()

    @unittest.skipUnless(hasattr(os, "fork"), "fork() is a POSIX function")
    def test_a_forked_process_is_told_it_cant_use_the_library(self):
        pid = os.fork()
        if pid == 0:
            try:
                asterion.version()
                os._exit(1)
            except asterion.AsterionError:
                os._exit(0)
        self.assertEqual(0, os.waitstatus_to_exitcode(os.waitpid(pid, 0)[1]))


if __name__ == "__main__":
    unittest.main()
