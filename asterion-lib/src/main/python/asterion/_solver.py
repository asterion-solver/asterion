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
"""Solving images: the solver, which keeps the catalogs open, and the results of its solves."""
import ctypes as c
import json
import sys
import threading
import weakref
from contextlib import contextmanager
from dataclasses import dataclass
from functools import cached_property
from typing import Any, Optional

from . import _native
from ._catalogs import PathLike
from ._native import AsterionError, Cancellation

# The sample types by their names, then by their kinds, as __array_interface__ names them: code, size
_SAMPLE_TYPES = {"uint8": (_native.UINT8, 1), "uint16": (_native.UINT16, 2), "float32": (_native.FLOAT32, 4)}
_KINDS = {"u1": "uint8", "u2": "uint16", "f4": "float32"}
# the formats of the buffer protocol, and their byte orders
_FORMATS = {"B": "u1", "H": "u2", "f": "f4"}
_NATIVE = "<" if sys.byteorder == "little" else ">"
_ORDERS = {"@": _NATIVE, "=": _NATIVE, "|": _NATIVE, "<": "<", ">": ">", "!": ">"}


@dataclass(frozen=True)
class DeepSkyObject:
    """
    A deep sky object of an image: a galaxy, a nebula, a cluster...

    Attributes:
        name: the name it's best known by: "M 31", "NGC 7000"
        designation: its designation in its catalog: "NGC 224"
        type: its kind, in words: "galaxy", "HII region"...
        common_name: its common names, separated by commas, or ""
        aliases: its designations in other catalogs, separated by commas, or ""
        messier: its number in the Messier catalog, or None
        ra: the right ascension of its center, J2000, in degrees
        dec: the declination of its center, in degrees
        x: its position in the image, in pixels, which may be outside of it
        y: its position in the image, in pixels
        distance: the distance of its center to the one of the image, in degrees
        major_arcmin: its major axis, in arcminutes, or None
        minor_arcmin: its minor axis, in arcminutes, or None
        angle: the position angle of its major axis, in degrees east of north, or None
        magnitude: its visual magnitude, or None
    """

    name: str
    designation: str
    type: str
    common_name: str
    aliases: str
    messier: Optional[int]
    ra: float
    dec: float
    x: float
    y: float
    distance: float
    major_arcmin: Optional[float]
    minor_arcmin: Optional[float]
    angle: Optional[float]
    magnitude: Optional[float]


@dataclass(frozen=True)
class Match:
    """
    A star of an image which was matched with the catalog.

    Attributes:
        x: its position in the image, in pixels
        y: its position in the image, in pixels
        ra: the right ascension of the catalog star at the date of the image, in degrees
        dec: the declination of the catalog star at the date of the image, in degrees
        residual: the distance between the two according to the solution, in arcseconds
    """

    x: float
    y: float
    ra: float
    dec: float
    residual: float


class Result:
    """
    The result of a solve. Without a solution, solved is False and the attributes of the solution are None: the
    stars which were detected, their width and the timings are known still.

    Pixel positions are counted from 0: the center of the first pixel of the image is (0, 0), whatever the binning
    which Asterion applied to it. The WCS follows the conventions of FITS instead, where it's (1, 1).

    Results come from Solver.solve, and stay valid once the solver is closed.

    Attributes:
        solved: whether the image was solved
        ra: the right ascension of the center of the image, J2000, in degrees
        dec: the declination of the center of the image, J2000, in degrees
        scale: the scale, in arcseconds per pixel
        rotation: the position angle of the Y axis of the image, in degrees east of north
        field_width: the width of the field of view, in degrees
        field_height: the height of the field of view, in degrees
        flipped: whether the image is mirrored
        rms: the residuals of the matched stars, in arcseconds
        log_odds: the confidence in the solution
        matched_stars: the number of stars matched with the catalog
        catalog: the name of the catalog which solved the image, such as "gaia-500"
        focal_length: the focal length, in millimeters, when pixel_size was given
        pixel_size: the size of the pixels used for the focal length, in micrometers
        fwhm_arcsec: fwhm_pixels, in arcseconds
        stars: the number of stars detected in the image
        fwhm_pixels: the median width of the stars, in pixels, or 0 without stars: once solved, of gaussians fitted
            to the matched stars which are neither faint nor saturated, if there are enough
        read_ms: how long copying the pixels took, in milliseconds
        detect_ms: how long detecting the stars took, in milliseconds
        solve_ms: how long solving took, in milliseconds
    """

    solved: bool
    ra: Optional[float]
    dec: Optional[float]
    scale: Optional[float]
    rotation: Optional[float]
    field_width: Optional[float]
    field_height: Optional[float]
    flipped: Optional[bool]
    rms: Optional[float]
    log_odds: Optional[float]
    matched_stars: Optional[int]
    catalog: Optional[str]
    focal_length: Optional[float]
    pixel_size: Optional[float]
    fwhm_arcsec: Optional[float]
    stars: int
    fwhm_pixels: Optional[float]
    read_ms: float
    detect_ms: float
    solve_ms: float

    def __init__(self, solved, solution, handle):
        """Results come from Solver.solve."""
        self.solved = solved

        def known(value):
            return _native.optional(value) if solved else None

        self.ra, self.dec = known(solution.ra), known(solution.dec)
        self.scale, self.rotation = known(solution.scale), known(solution.rotation)
        self.field_width, self.field_height = known(solution.field_width), known(solution.field_height)
        self.flipped = bool(solution.flipped) if solved else None
        self.rms, self.log_odds = known(solution.rms), known(solution.log_odds)
        self.matched_stars = solution.matched_stars if solved else None
        self.catalog = _native.text(solution.index) if solved else None
        self.focal_length, self.pixel_size = known(solution.focal_length), known(solution.pixel_size)
        self.fwhm_arcsec = known(solution.fwhm_arcsec)
        self.stars = solution.stars
        self.fwhm_pixels = _native.optional(solution.fwhm_pixels)
        self.read_ms, self.detect_ms, self.solve_ms = solution.read_ms, solution.detect_ms, solution.solve_ms
        self._handle = handle
        # freed when the result is garbage collected
        weakref.finalize(self, _native.release, "asterion_result_free", handle)

    def __repr__(self):
        if not self.solved:
            return f"Result(solved=False, stars={self.stars})"
        return (f"Result(solved=True, ra={self.ra:.6f}, dec={self.dec:.6f}, scale={self.scale:.4f}, "
                f"rotation={self.rotation:.2f}, catalog={self.catalog!r})")

    @cached_property
    def json(self) -> str:
        """The result as JSON, the output of asterion --json."""
        return _native.string("asterion_result_json", self._handle)

    @cached_property
    def wcs(self) -> Optional[dict[str, Any]]:
        """The WCS of the solution, as the keywords of a FITS header: CTYPE1, CRPIX1, CD1_1, A_ORDER, A_2_0...
        astropy.wcs.WCS(result.wcs) makes the WCS of astropy. None without a solution."""
        return json.loads(self.json).get("wcs") if self.solved else None

    @cached_property
    def objects(self) -> list[DeepSkyObject]:
        """The deep sky objects of the image, the most prominent first."""
        found = _native.Object()
        objects = []
        for i in range(self._count("asterion_result_object_count")):
            _native.check(_native.call("asterion_result_object", self._handle, i, c.byref(found)))
            objects.append(DeepSkyObject(
                _native.text(found.name), _native.text(found.designation), _native.text(found.type),
                _native.text(found.common_name), _native.text(found.aliases), found.messier or None,
                found.ra, found.dec, found.x, found.y, found.distance, _native.optional(found.major_arcmin),
                _native.optional(found.minor_arcmin), _native.optional(found.angle), _native.optional(found.magnitude)))
        return objects

    @cached_property
    def matches(self) -> list[Match]:
        """The stars of the image which were matched with the catalog."""
        found = _native.Match()
        matches = []
        for i in range(self._count("asterion_result_match_count")):
            _native.check(_native.call("asterion_result_match", self._handle, i, c.byref(found)))
            matches.append(Match(found.x, found.y, found.ra, found.dec, found.residual))
        return matches

    def pixel_to_sky(self, x: float, y: float) -> tuple[float, float]:
        """The position on the sky of a pixel, in degrees, distortion included.

        Raises:
            AsterionError: if the image wasn't solved
        """
        return self._convert("asterion_result_pixel_to_sky", x, y)

    def sky_to_pixel(self, ra: float, dec: float) -> tuple[float, float]:
        """The pixel of a position on the sky, in degrees, distortion included.

        Raises:
            AsterionError: if the image wasn't solved, or the position is too far from the image to be projected
        """
        return self._convert("asterion_result_sky_to_pixel", ra, dec)

    def _count(self, function):
        return _native.check(_native.call(function, self._handle)) if self.solved else 0

    def _convert(self, function, a, b):
        first, second = c.c_double(), c.c_double()
        _native.check(_native.call(function, self._handle, a, b, c.byref(first), c.byref(second)))
        return first.value, second.value


class SolveCancelled(AsterionError):
    """A solve was cancelled, see Cancellation."""


SolveCancelled.__module__ = "asterion"


class Solver:
    """
    A solver, with the catalogs which are installed in a directory: they stay open between solves, which keeps
    them fast, until the solver is closed. A solver can be used by several threads at once.

        with asterion.Solver() as solver:
            result = solver.solve(frame)
    """

    def __init__(self, catalog_directory: Optional[PathLike] = None):
        """
        Opens the catalogs of a directory.

        Args:
            catalog_directory: the directory of the catalogs, by default the one of the command line tool

        Raises:
            AsterionError: if no catalog is installed
        """
        handle = c.c_void_p()
        _native.check(_native.call("asterion_open", _native.path(catalog_directory), c.byref(handle)))
        self._handle = handle
        self._lock = threading.Lock()
        self._solving = 0
        # closed when the solver is garbage collected, if it wasn't
        self._finalizer = weakref.finalize(self, _native.release, "asterion_close", handle)

    def __enter__(self):
        return self

    def __exit__(self, *exception):
        self.close()

    def close(self) -> None:
        """Closes the solver, if it isn't: its catalogs are unmapped at once, which releases their memory. The results
        stay valid.

        Raises:
            AsterionError: if a solve runs meanwhile
        """
        with self._lock:
            if self._solving:
                raise AsterionError("The solver can't be closed while it solves")
            if self._handle is not None:
                _native.check(_native.call("asterion_close", self._handle))
                self._handle = None
                self._finalizer.detach()

    def solve(self, pixels: Any, width: Optional[int] = None, height: Optional[int] = None, *,
              sample_type: Optional[str] = None, row_stride: Optional[int] = None, bayer: bool = False,
              ra: Optional[float] = None, dec: Optional[float] = None, radius: Optional[float] = None,
              fov: Optional[float] = None, scale_low: Optional[float] = None, scale_high: Optional[float] = None,
              pixel_size: Optional[float] = None, epoch: Optional[float] = None, parity: str = "both",
              timeout: Optional[float] = None, sip_order: Optional[int] = None, binning: Optional[int] = None,
              sigma: Optional[float] = None, max_stars: Optional[int] = None, quad_stars: Optional[int] = None,
              cancellation: Optional[Cancellation] = None) -> Result:
        """
        Solves an image: its pixels are copied, converted and binned in a single pass, then searched for in the
        catalogs, so that they can be changed as soon as solve returns.

        Args:
            pixels: the gray levels of the image, one per pixel: unsigned 8 or 16-bit integers, or 32-bit floats, in
                the native byte order. An array of the shape (height, width), which describes itself with
                __array_interface__, such as a NumPy array, whose rows may be apart; a contiguous two-dimensional
                buffer, such as a memoryview; or a one-dimensional buffer, such as bytes or array.array, with the
                width and the height. The pixels of a row must be contiguous.
            width: the width of the image, for one-dimensional buffers
            height: the height of the image, for one-dimensional buffers
            sample_type: "uint8", "uint16" or "float32", for buffers of bytes
            row_stride: the distance between the starts of two rows, in bytes, for one-dimensional buffers whose rows
                are apart
            bayer: whether the image is a raw frame of a color sensor, which is binned 2x2 at least to remove its
                pattern, unless the binning is given
            ra: the right ascension of the center of the region to search, in degrees, with dec and radius: the whole
                sky by default
            dec: the declination of the center of the region to search, in degrees
            radius: the radius of the region to search, in degrees
            fov: the approximate width of the field of view, in degrees: 30% is tolerated
            scale_low: the smallest scale of the image, in arcseconds per pixel
            scale_high: the largest scale of the image, in arcseconds per pixel
            pixel_size: the size of the pixels, in micrometers, binning included, which gives the focal length
            epoch: the date of the image, as a Julian year, for the proper motions of the stars
            parity: "both", "normal" or "flipped", for mirrored images
            timeout: how long to search before giving up, in seconds
            sip_order: the order of the SIP polynomials of the distortion: 0 to disable them, 2 to 5, or -1 to choose
            binning: the binning applied before detecting stars, or 0 to choose
            sigma: the threshold of the detection of stars, in units of background noise
            max_stars: the largest number of stars to detect
            quad_stars: the number of stars, the brightest first, used to search for a match
            cancellation: cancels the solve from another thread, see Cancellation

        Returns:
            the result, solved or not

        Raises:
            ValueError: if the pixels can't be read: their type, their byte order, their layout, their size
            SolveCancelled: if the cancellation was cancelled before the image was solved
            AsterionError: if the solver is closed, or the options are out of range
        """
        options = _options(parity, ra=ra, dec=dec, radius=radius, fov=fov, scale_low=scale_low, scale_high=scale_high,
                           pixel_size=pixel_size, epoch=epoch, timeout=timeout, sip_order=sip_order, binning=binning,
                           sigma=sigma, max_stars=max_stars, quad_stars=quad_stars)
        image = _Image.of(pixels, width, height, sample_type, row_stride)
        solution = _native.Solution()
        result = c.c_void_p()
        with self._solve() as handle, image.address() as address:
            status = _native.call("asterion_solve_cancellable", handle, address, image.code, image.width, image.height,
                                  image.row_stride, int(bayer), c.byref(options), c.byref(solution), c.byref(result),
                                  _native.flag(cancellation))
        if status == _native.CANCELLED:
            raise SolveCancelled("The solve was cancelled")
        _native.check(status)
        return Result(status == _native.SOLVED, solution, result)

    @contextmanager
    def _solve(self):
        """The handle of the solver, which can't be closed meanwhile."""
        with self._lock:
            if self._handle is None:
                raise AsterionError("The solver is closed")
            self._solving += 1
        try:
            yield self._handle
        finally:
            with self._lock:
                self._solving -= 1


def _options(parity, **given):
    """The options of a solve: the defaults of the library, and the ones which are given."""
    if parity not in _native.PARITIES:
        raise ValueError(f"Unknown parity {parity!r}: expected one of {', '.join(_native.PARITIES)}")
    options = _native.Options()
    _native.check(_native.call("asterion_default_options", c.byref(options)))
    options.parity = _native.PARITIES[parity]
    for name, value in given.items():
        if value is not None:
            setattr(options, name, value)
    return options


@dataclass(frozen=True)
class _Image:
    """The pixels of an image, as the library reads them: their memory, the type and the size of their samples, and
    the layout of their rows. The memory is an address, or an object which exports it with the buffer protocol."""

    memory: Any
    offset: int
    code: int
    width: int
    height: int
    row_stride: int

    @staticmethod
    def of(pixels, width, height, sample_type, row_stride):
        interface = getattr(pixels, "__array_interface__", None)
        if interface is not None:
            if width is not None or height is not None or row_stride is not None:
                raise ValueError("The width, the height and the row stride of an array are its own")
            return _Image._array(pixels, interface, sample_type)
        try:
            view = memoryview(pixels)
        except TypeError:
            raise ValueError(f"Expected an array or a buffer of pixels, not {type(pixels).__name__}") from None
        if view.ndim == 2:
            if width is not None or height is not None or row_stride is not None:
                raise ValueError("The width, the height and the row stride of a two-dimensional buffer are its own")
            if not view.c_contiguous:
                raise ValueError("A two-dimensional buffer must be contiguous: pass the array, or a one-dimensional "
                                 "buffer with its row stride")
            name = _sample_type(*_format(view.format), sample_type)
            height, width = view.shape
            return _Image._of(view, 0, name, width, height, view.strides[0])
        if view.ndim != 1:
            raise ValueError(f"Expected pixels in one or two dimensions, not {view.ndim}")
        if width is None or height is None:
            raise ValueError("The width and the height are required with a one-dimensional buffer")
        if not view.c_contiguous:
            raise ValueError("A one-dimensional buffer must be contiguous")
        # a buffer of bytes needs the type of its samples; a typed one, such as array.array, has its own
        kind = _format(view.format) if view.format not in ("B", "c") else None
        name = _sample_type(*kind, sample_type) if kind else sample_type
        if name is None:
            raise ValueError("The sample type of a buffer of bytes is required: uint8, uint16 or float32")
        return _Image._of(view, 0, name, width, height, row_stride)

    @staticmethod
    def _array(pixels, interface, sample_type):
        """An array which describes itself with __array_interface__: its data is an address, or an object which
        exports its memory, or the array itself when it's None."""
        shape = interface["shape"]
        if len(shape) != 2:
            raise ValueError(f"Expected an array of the shape (height, width), not {tuple(shape)}")
        height, width = shape
        typestr = interface["typestr"]
        name = _sample_type(typestr[0], typestr[1:], sample_type)
        strides = interface.get("strides")
        row_stride = None
        if strides is not None:
            if strides[1] != _SAMPLE_TYPES[name][1]:
                raise ValueError("The pixels of a row must be contiguous")
            row_stride = strides[0]
        data = interface.get("data")
        if isinstance(data, tuple):
            return _Image._of(data[0], 0, name, width, height, row_stride)
        return _Image._of(memoryview(pixels if data is None else data), interface.get("offset", 0), name, width, height,
                          row_stride)

    @staticmethod
    def _of(memory, offset, name, width, height, row_stride):
        code, size = _SAMPLE_TYPES[name]
        if row_stride is None:
            row_stride = width * size
        if row_stride < width * size:
            raise ValueError(f"The rows of {width} {name} samples can't be {row_stride} bytes apart: arrays whose rows "
                             f"are reversed or overlap can be copied with numpy.ascontiguousarray")
        if isinstance(memory, memoryview):
            needed = offset + (height - 1) * row_stride + width * size if height > 0 else 0
            if memory.nbytes < needed:
                raise ValueError(f"The pixels are {memory.nbytes} bytes, {width}x{height} {name} samples need {needed}")
        return _Image(memory, offset, code, width, height, row_stride)

    @contextmanager
    def address(self):
        """The address of the pixels, which stay where they are meanwhile."""
        if not isinstance(self.memory, memoryview):
            yield self.memory
            return
        with _pinned(self.memory.cast("B") if self.memory.c_contiguous else self.memory) as address:
            yield address + self.offset


def _format(code):
    """The byte order and the kind of the samples of a format of the buffer protocol: "<", "u2" for "<H"."""
    order, code = (code[0], code[1:]) if code[:1] in "@=<>!" else ("@", code)
    if code not in _FORMATS:
        raise ValueError(f"Unsupported samples {code!r}: expected unsigned 8 or 16-bit integers, or 32-bit floats")
    return order, _FORMATS[code]


def _sample_type(order, kind, given):
    """The sample type of pixels which tell their byte order and kind, which must be the given one, if any."""
    if kind not in _KINDS:
        raise ValueError(f"Unsupported samples {kind!r}: expected unsigned 8 or 16-bit integers, or 32-bit floats")
    if _ORDERS.get(order) != _NATIVE and kind != "u1":
        raise ValueError("The samples must be in the native byte order: with NumPy, astype(dtype.newbyteorder('='))")
    name = _KINDS[kind]
    if given is not None and given != name:
        raise ValueError(f"The samples are {name}, not {given}")
    return name


class _Buffer(c.Structure):
    """Py_buffer, which the buffer protocol fills: the memory of an object, which stays where it is until released."""

    _fields_ = [("buf", c.c_void_p), ("obj", c.py_object), ("len", c.c_ssize_t), ("itemsize", c.c_ssize_t),
                ("readonly", c.c_int), ("ndim", c.c_int), ("format", c.c_char_p), ("shape", c.c_void_p),
                ("strides", c.c_void_p), ("suboffsets", c.c_void_p), ("internal", c.c_void_p)]


_GET_BUFFER = c.pythonapi.PyObject_GetBuffer
_GET_BUFFER.argtypes = [c.py_object, c.POINTER(_Buffer), c.c_int]
_GET_BUFFER.restype = c.c_int
_RELEASE_BUFFER = c.pythonapi.PyBuffer_Release
_RELEASE_BUFFER.argtypes = [c.POINTER(_Buffer)]
_RELEASE_BUFFER.restype = None


@contextmanager
def _pinned(view):
    """The address of the memory of a contiguous buffer, read-only ones included, which ctypes doesn't give."""
    buffer = _Buffer()
    # PyBUF_SIMPLE: the memory as contiguous bytes. A failure raises the error of Python
    _GET_BUFFER(view, c.byref(buffer), 0)
    try:
        yield buffer.buf
    finally:
        _RELEASE_BUFFER(c.byref(buffer))
