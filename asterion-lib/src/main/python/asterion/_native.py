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
The native library: the structures and the functions of asterion.h, and the GraalVM isolate in which the
library runs. The isolate is created the first time the library is used, and each Python thread which
calls the library is attached to it.
"""
import ctypes as c
import math
import os
import sys
import threading
from pathlib import Path


class AsterionError(Exception):
    """An error which the library reports, with its message."""


AsterionError.__module__ = "asterion"


# The structures of asterion.h, field by field: ASTERION_MAX_SIP_ORDER and ASTERION_MAX_SIP_INVERSE_ORDER
_SIP = c.c_double * 6 * 6
_SIP_INVERSE = c.c_double * 8 * 8


class Options(c.Structure):
    _fields_ = [(name, c.c_double) for name in
                ("ra", "dec", "radius", "scale_low", "scale_high", "fov", "pixel_size", "timeout", "sigma", "epoch")] + \
               [(name, c.c_int32) for name in ("parity", "sip_order", "binning", "max_stars", "quad_stars", "padding")]


class Solution(c.Structure):
    _fields_ = [(name, c.c_double) for name in
                ("ra", "dec", "scale", "rotation", "field_width", "field_height", "rms", "log_odds", "focal_length",
                 "pixel_size", "fwhm_pixels", "fwhm_arcsec")] + \
               [("crpix", c.c_double * 2), ("crval", c.c_double * 2), ("cd", c.c_double * 4),
                ("sip_a", _SIP), ("sip_b", _SIP), ("sip_ap", _SIP_INVERSE), ("sip_bp", _SIP_INVERSE),
                ("read_ms", c.c_double), ("detect_ms", c.c_double), ("solve_ms", c.c_double)] + \
               [(name, c.c_int32) for name in ("sip_order", "sip_inverse_order", "flipped", "matched_stars", "stars")] + \
               [("index", c.c_char * 64), ("padding", c.c_int32)]


class Object(c.Structure):
    _fields_ = [(name, c.c_double) for name in
                ("ra", "dec", "x", "y", "distance", "major_arcmin", "minor_arcmin", "angle", "magnitude")] + \
               [("messier", c.c_int32), ("name", c.c_char * 32), ("designation", c.c_char * 32),
                ("common_name", c.c_char * 128), ("aliases", c.c_char * 256), ("type", c.c_char * 32)]


class Match(c.Structure):
    _fields_ = [(name, c.c_double) for name in ("x", "y", "ra", "dec", "residual")]


class Catalog(c.Structure):
    _fields_ = [("name", c.c_char * 64), ("description", c.c_char * 256), ("installed", c.c_int32), ("available", c.c_int32),
                ("size", c.c_int64), ("stars", c.c_int64), ("quads", c.c_int64), ("magnitude_limit", c.c_double),
                ("min_quad_arcmin", c.c_double), ("max_quad_deg", c.c_double), ("error", c.c_char * 256)]


class Progress(c.Structure):
    _fields_ = [("event", c.c_int32), ("padding", c.c_int32), ("completed", c.c_int64), ("total", c.c_int64),
                ("bytes", c.c_int64), ("fraction", c.c_double), ("step", c.c_char_p), ("unit", c.c_char_p),
                ("message", c.c_char_p)]


ProgressCallback = c.CFUNCTYPE(c.c_int, c.POINTER(Progress), c.c_void_p)

UINT8, UINT16, FLOAT32 = 1, 2, 3
SOLVED, NOT_SOLVED, ERROR, CANCELLED = 1, 0, -1, -2
PARITIES = {"both": 0, "normal": 1, "flipped": 2}
EVENTS = {1: "step", 2: "progress", 3: "detail", 4: "done", 5: "info"}

_STRUCTURES = {"asterion_options": Options, "asterion_solution": Solution, "asterion_object": Object,
               "asterion_match": Match, "asterion_catalog": Catalog, "asterion_progress": Progress}

_p = c.c_void_p
_SIGNATURES = {
    "graal_create_isolate": (c.c_int, [_p, c.POINTER(_p), c.POINTER(_p)]),
    "graal_attach_thread": (c.c_int, [_p, c.POINTER(_p)]),
    "graal_detach_thread": (c.c_int, [_p]),
    "asterion_version": (c.c_int, [_p, c.c_char_p, c.c_size_t]),
    "asterion_default_catalog_directory": (c.c_int, [_p, c.c_char_p, c.c_size_t]),
    "asterion_catalogs": (c.c_int, [_p, c.c_char_p, c.POINTER(Catalog), c.c_int]),
    "asterion_install_catalog": (c.c_int, [_p, c.c_char_p, c.c_char_p, ProgressCallback, _p]),
    "asterion_open": (c.c_int, [_p, c.c_char_p, c.POINTER(_p)]),
    "asterion_close": (c.c_int, [_p, _p]),
    "asterion_default_options": (c.c_int, [_p, c.POINTER(Options)]),
    "asterion_solve": (c.c_int, [_p, _p, _p, c.c_int, c.c_int, c.c_int, c.c_longlong, c.c_int, c.POINTER(Options),
                                 c.POINTER(Solution), c.POINTER(_p)]),
    "asterion_solve_cancellable": (c.c_int, [_p, _p, _p, c.c_int, c.c_int, c.c_int, c.c_longlong, c.c_int, c.POINTER(Options),
                                             c.POINTER(Solution), c.POINTER(_p), c.POINTER(c.c_int32)]),
    "asterion_result_free": (None, [_p, _p]),
    "asterion_result_json": (c.c_int, [_p, _p, c.c_char_p, c.c_size_t]),
    "asterion_result_object_count": (c.c_int, [_p, _p]),
    "asterion_result_object": (c.c_int, [_p, _p, c.c_int, c.POINTER(Object)]),
    "asterion_result_match_count": (c.c_int, [_p, _p]),
    "asterion_result_match": (c.c_int, [_p, _p, c.c_int, c.POINTER(Match)]),
    "asterion_result_pixel_to_sky": (c.c_int, [_p, _p, c.c_double, c.c_double, c.POINTER(c.c_double), c.POINTER(c.c_double)]),
    "asterion_result_sky_to_pixel": (c.c_int, [_p, _p, c.c_double, c.c_double, c.POINTER(c.c_double), c.POINTER(c.c_double)]),
    "asterion_error": (c.c_int, [_p, c.c_char_p, c.c_size_t]),
    "asterion_sizeof": (c.c_int, [_p, c.c_char_p]),
}


def _library_file():
    """The library next to this module, or the one which ASTERION_LIBRARY names, to use another build."""
    configured = os.environ.get("ASTERION_LIBRARY")
    if configured:
        return configured
    name = {"win32": "libasterion.dll", "darwin": "libasterion.dylib"}.get(sys.platform, "libasterion.so")
    return str(Path(__file__).with_name(name))


class _Attachment:
    """The thread of the isolate of a Python thread, which is detached when the Python thread ends."""

    def __init__(self, library, thread):
        self.library = library
        self.thread = thread
        self.ident = threading.get_ident()
        self.pid = os.getpid()

    def __del__(self):
        # Python releases the thread-local data of a thread when it ends, on that thread: a thread can only detach
        # itself. A forked process has a copy of the isolate, without its threads, which it must not touch. At exit,
        # the library may be gone already
        if threading.get_ident() == self.ident and os.getpid() == self.pid:
            try:
                self.library.graal_detach_thread(self.thread)
            except Exception:
                pass


class _Isolate:
    def __init__(self):
        self._lock = threading.Lock()
        self._library = None
        self._isolate = None
        self._forked = False
        self._local = threading.local()
        if hasattr(os, "register_at_fork"):
            os.register_at_fork(after_in_child=self._after_fork)

    def _after_fork(self):
        # the isolate of the parent can't be used, and the lock may have been held by one of its threads
        self._lock = threading.Lock()
        self._forked = self._library is not None

    def usable(self):
        """Whether the library can be called: it's loaded, and this process isn't a fork of the one which loaded it."""
        return self._library is not None and not self._forked

    def thread(self):
        """The library, and the thread of the isolate of the calling thread, attached the first time."""
        if self._forked:
            raise AsterionError("The library can't be used in a process forked after it was started: "
                                "start the processes with multiprocessing.set_start_method('spawn')")
        attachment = getattr(self._local, "attachment", None)
        if attachment is not None:
            return self._library, attachment.thread
        with self._lock:
            if self._library is None:
                self._create()
                return self._library, self._local.attachment.thread
        thread = c.c_void_p()
        if self._library.graal_attach_thread(self._isolate, c.byref(thread)) != 0:
            raise AsterionError("Unable to attach the thread to the isolate of the library")
        # a finalizer which ran meanwhile, on this thread, may have attached it already: to the same isolate thread
        attachment = getattr(self._local, "attachment", None)
        if attachment is None:
            attachment = self._local.attachment = _Attachment(self._library, thread)
        return self._library, attachment.thread

    def _create(self):
        library = c.CDLL(_library_file())
        for name, (result, arguments) in _SIGNATURES.items():
            function = getattr(library, name)
            function.restype, function.argtypes = result, arguments
        isolate, thread = c.c_void_p(), c.c_void_p()
        if library.graal_create_isolate(None, c.byref(isolate), c.byref(thread)) != 0:
            raise AsterionError("Unable to create the isolate of the library")
        for name, structure in _STRUCTURES.items():
            if library.asterion_sizeof(thread, name.encode()) != c.sizeof(structure):
                raise AsterionError(f"{name} doesn't match the library {_library_file()}")
        self._library, self._isolate = library, isolate
        self._local.attachment = _Attachment(library, thread)


_ISOLATE = _Isolate()


def call(name, *arguments):
    """Calls a function of the library on the thread of the caller, which it receives first."""
    library, thread = _ISOLATE.thread()
    return getattr(library, name)(thread, *arguments)


def release(name, handle):
    """Releases a handle when its object is garbage collected, which may happen at exit, or in a forked process:
    nothing is reported, as nobody can handle it."""
    if _ISOLATE.usable():
        try:
            call(name, handle)
        except Exception:
            pass


def error():
    """The message of the last error of the calling thread."""
    return string("asterion_error")


def check(status):
    """The status of a call, or AsterionError with its message if it failed."""
    if status == ERROR:
        raise AsterionError(error())
    return status


def string(name, *arguments):
    """A string which a function copies to a buffer: the function tells the size it needs, if the first one is
    too small."""
    buffer = c.create_string_buffer(4096)
    length = call(name, *arguments, buffer, len(buffer))
    if length < 0 and name != "asterion_error":
        raise AsterionError(error())
    if length >= len(buffer):
        buffer = c.create_string_buffer(length + 1)
        call(name, *arguments, buffer, len(buffer))
    return buffer.value.decode("utf-8", errors="replace")


def path(directory):
    """A directory for the library, UTF-8 encoded, or None for the default one."""
    if directory is None:
        return None
    directory = os.fspath(directory)
    return directory if isinstance(directory, bytes) else directory.encode("utf-8")


def text(value):
    """A string of a structure, or of a progress event."""
    return value.decode("utf-8", errors="replace") if value is not None else None


def optional(value):
    """A number of a structure, where NAN means unknown: None."""
    return None if math.isnan(value) else value
