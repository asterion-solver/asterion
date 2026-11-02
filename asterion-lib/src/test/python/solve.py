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
Solves pixels which are in memory with the Asterion library, from Python, with ctypes only:

    python3 solve.py LIBRARY CATALOGS PIXELS WIDTH HEIGHT [CATALOG_TO_INSTALL]

LIBRARY is the file of the library (libasterion.so, libasterion.dylib or libasterion.dll), PIXELS a file
of uint16 samples in the native byte order, row after row. CATALOG_TO_INSTALL, such as tycho2, is
installed in CATALOGS first.
"""
import ctypes as c
import sys

# The structures of asterion.h, field by field: ASTERION_MAX_SIP_ORDER and ASTERION_MAX_SIP_INVERSE_ORDER
SIP = c.c_double * 6 * 6
SIP_INVERSE = c.c_double * 8 * 8


class Options(c.Structure):
    _fields_ = [(name, c.c_double) for name in
                ("ra", "dec", "radius", "scale_low", "scale_high", "fov", "pixel_size", "timeout", "sigma", "epoch")] + \
               [(name, c.c_int32) for name in ("parity", "sip_order", "binning", "max_stars", "quad_stars", "padding")]


class Solution(c.Structure):
    _fields_ = [(name, c.c_double) for name in
                ("ra", "dec", "scale", "rotation", "field_width", "field_height", "rms", "log_odds", "focal_length",
                 "pixel_size", "fwhm_pixels", "fwhm_arcsec")] + \
               [("crpix", c.c_double * 2), ("crval", c.c_double * 2), ("cd", c.c_double * 4),
                ("sip_a", SIP), ("sip_b", SIP), ("sip_ap", SIP_INVERSE), ("sip_bp", SIP_INVERSE),
                ("read_ms", c.c_double), ("detect_ms", c.c_double), ("solve_ms", c.c_double)] + \
               [(name, c.c_int32) for name in ("sip_order", "sip_inverse_order", "flipped", "matched_stars", "stars")] + \
               [("index", c.c_char * 64), ("padding", c.c_int32)]


class Catalog(c.Structure):
    _fields_ = [("name", c.c_char * 64), ("description", c.c_char * 256), ("installed", c.c_int32), ("available", c.c_int32),
                ("size", c.c_int64), ("stars", c.c_int64), ("quads", c.c_int64), ("magnitude_limit", c.c_double),
                ("min_quad_arcmin", c.c_double), ("max_quad_deg", c.c_double), ("error", c.c_char * 256)]


class Progress(c.Structure):
    _fields_ = [("event", c.c_int32), ("padding", c.c_int32), ("completed", c.c_int64), ("total", c.c_int64),
                ("bytes", c.c_int64), ("fraction", c.c_double), ("step", c.c_char_p), ("unit", c.c_char_p),
                ("message", c.c_char_p)]


ProgressCallback = c.CFUNCTYPE(c.c_int, c.POINTER(Progress), c.c_void_p)

ASTERION_UINT16 = 2
ASTERION_SOLVED = 1
ASTERION_CANCELLED = -2
ASTERION_EVENT_STEP, ASTERION_EVENT_INFO = 1, 5


def load(path):
    """The library, with the types of its functions: ctypes would pass 64-bit values as ints otherwise."""
    lib = c.CDLL(path)
    p, t = c.c_void_p, c.c_void_p
    signatures = {
        "graal_create_isolate": (c.c_int, [p, c.POINTER(p), c.POINTER(t)]),
        "graal_tear_down_isolate": (c.c_int, [t]),
        "asterion_sizeof": (c.c_int, [t, c.c_char_p]),
        "asterion_error": (c.c_int, [t, c.c_char_p, c.c_size_t]),
        "asterion_catalogs": (c.c_int, [t, c.c_char_p, c.POINTER(Catalog), c.c_int]),
        "asterion_install_catalog": (c.c_int, [t, c.c_char_p, c.c_char_p, ProgressCallback, p]),
        "asterion_open": (c.c_int, [t, c.c_char_p, c.POINTER(p)]),
        "asterion_close": (c.c_int, [t, p]),
        "asterion_default_options": (c.c_int, [t, c.POINTER(Options)]),
        "asterion_solve": (c.c_int, [t, p, p, c.c_int, c.c_int, c.c_int, c.c_longlong, c.c_int, c.POINTER(Options),
                                     c.POINTER(Solution), c.POINTER(p)]),
    }
    for name, (result, arguments) in signatures.items():
        function = getattr(lib, name)
        function.restype, function.argtypes = result, arguments
    return lib


def main():
    library, catalogs, pixels, width, height = sys.argv[1], sys.argv[2].encode(), sys.argv[3], int(sys.argv[4]), int(sys.argv[5])
    lib = load(library)
    isolate, thread = c.c_void_p(), c.c_void_p()
    if lib.graal_create_isolate(None, c.byref(isolate), c.byref(thread)) != 0:
        sys.exit("can't create the isolate")
    # the structures above must have the sizes which the library reads and writes
    for name, structure in (("asterion_options", Options), ("asterion_solution", Solution), ("asterion_catalog", Catalog),
                            ("asterion_progress", Progress)):
        if lib.asterion_sizeof(thread, name.encode()) != c.sizeof(structure):
            sys.exit(f"{name} doesn't match the library")

    def error():
        message = c.create_string_buffer(1024)
        lib.asterion_error(thread, message, len(message))
        return message.value.decode()

    if len(sys.argv) > 6:
        def progress(event, user_data):
            # Ctrl-C cancels the installation: the callback is the Python code which runs meanwhile
            try:
                event = event.contents
                if event.event == ASTERION_EVENT_STEP:
                    print("step:", event.step.decode())
                elif event.event == ASTERION_EVENT_INFO:
                    print("info:", event.message.decode())
                return 0
            except KeyboardInterrupt:
                return 1  # non-zero cancels the installation

        # the callback must stay referenced while the library may call it
        callback = ProgressCallback(progress)
        status = lib.asterion_install_catalog(thread, catalogs, sys.argv[6].encode(), callback, None)
        if status == ASTERION_CANCELLED:
            sys.exit("the installation was cancelled: installing again resumes it")
        if status != 0:
            sys.exit("can't install " + sys.argv[6] + ": " + error())

    count = lib.asterion_catalogs(thread, catalogs, None, 0)
    if count < 0:
        sys.exit("can't list the catalogs: " + error())
    listed = (Catalog * count)()
    lib.asterion_catalogs(thread, catalogs, listed, count)
    print("installed:", ", ".join(catalog.name.decode() for catalog in listed if catalog.installed))

    solver = c.c_void_p()
    if lib.asterion_open(thread, catalogs, c.byref(solver)) != 0:
        sys.exit("can't open the catalogs: " + error())
    options = Options()
    lib.asterion_default_options(thread, c.byref(options))
    # any buffer will do: a bytearray here. The library trusts the dimensions, which must match the buffer
    with open(pixels, "rb") as f:
        samples = bytearray(f.read())
    if len(samples) != width * height * 2:
        sys.exit(f"{pixels} has {len(samples)} bytes, not the {width * height * 2} of {width}x{height} uint16 samples")
    buffer = (c.c_char * len(samples)).from_buffer(samples)
    solution = Solution()
    status = lib.asterion_solve(thread, solver, c.addressof(buffer), ASTERION_UINT16, width, height, width * 2, 0, c.byref(options),
                                c.byref(solution), None)
    if status == ASTERION_SOLVED:
        print(f"solved: ra={solution.ra:.6f} dec={solution.dec:.6f} scale={solution.scale:.4f} rotation={solution.rotation:.2f}"
              f" index={solution.index.decode()}")
    elif status == 0:
        print("not solved")
    else:
        sys.exit("can't solve: " + error())
    # releases the memory of the catalogs at once
    if lib.asterion_close(thread, solver) != 0:
        sys.exit("can't close the solver: " + error())
    lib.graal_tear_down_isolate(thread)


if __name__ == "__main__":
    main()
