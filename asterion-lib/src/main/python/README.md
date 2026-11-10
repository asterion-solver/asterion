# Asterion Solver for Python

[Asterion](https://github.com/asterion-solver/asterion) is a fast blind astrometric plate solver: it finds the
celestial coordinates, scale and orientation of astronomical images, without any hint. This package solves the
images which a program has in memory, such as the frames of a camera: the solver stays in memory between images,
with its catalogs open, and needs no other dependency.

```
pip install asterion-solver
```

Wheels are published for Python 3.10 and later, on:

- Linux, x86-64 and ARM64, with glibc 2.34 or later: Ubuntu 22.04, Debian 12, Fedora 35, RHEL 9 and later;
- macOS 11 and later, on Apple Silicon;
- Windows, x86-64.

## Catalogs

Asterion needs a catalog of stars, which is installed once on the machine, in the directory of the command line
tool: `$ASTERION_CATALOGS`, or `~/.asterion/catalogs`. Each function takes another directory if needed.

```python
import asterion

for catalog in asterion.catalogs():
    print(catalog.name, "installed" if catalog.installed else "", catalog.description)

asterion.install_catalog("tycho2", progress=lambda p: print(p.step, p.fraction))
```

`tycho2` suits wide fields of view, and the Gaia catalogs, denser, smaller ones: their descriptions tell which
fields each one is for. An installation can be cancelled, by its progress callback or with Ctrl-C, which takes
effect at its next event, and the next installation resumes its download. A solver which uses a catalog is closed
before the catalog is installed again.

## Solving

```python
with asterion.Solver() as solver:
    result = solver.solve(frame)
    if result.solved:
        print(result.ra, result.dec, result.scale, result.rotation)
```

A solver keeps its catalogs open, which keeps the next solves fast, until it's closed: closing it releases their
memory at once. It can be used by several threads at once.

The pixels are gray levels, one per pixel: unsigned 8 or 16-bit integers, or 32-bit floats, in the native byte
order. `solve` takes:

- a two-dimensional array, of the shape `(height, width)`, such as a NumPy array, whose rows may be apart while
  the pixels of a row are contiguous, or a contiguous `memoryview`;
- or a one-dimensional buffer, such as `bytes` or `array.array`, with the width and the height, and the sample type
  for buffers of bytes: `solver.solve(data, 1920, 1080, sample_type="uint16")`.

The pixels are copied, converted and binned in a single pass, so they can be changed as soon as `solve` returns. A
raw frame of a color sensor is binned to remove the pattern of its filters with `bayer=True`. A color image is
reduced to gray levels first: the sum of its channels, as the command line tool does.

The options limit the search, which makes it faster: the region of the sky (`ra`, `dec` and `radius`, in degrees),
the scale (`fov` in degrees, or `scale_low` and `scale_high` in arcseconds per pixel), the handedness (`parity`). The
others are the ones of the command line tool: `timeout`, `sip_order`, `binning`, `sigma`, `max_stars`...

## With NumPy

The package doesn't depend on NumPy, but solves NumPy arrays without copying them first: they describe their memory
to it. A camera frame, or a part of it, whose rows are then apart:

```python
import numpy as np

frame = np.frombuffer(buffer, dtype=np.uint16).reshape(height, width)
result = solver.solve(frame)
result = solver.solve(frame[:, 100:-100])
```

A FITS file, with astropy: FITS files store big-endian samples, which `astype` converts to the native byte order. A
color image, from the sum of its channels:

```python
from astropy.io import fits

result = solver.solve(fits.getdata("light.fits").astype(np.float32))
result = solver.solve(rgb.sum(axis=2, dtype=np.float32))
```

The WCS of a result makes the one of astropy:

```python
from astropy.wcs import WCS

wcs = WCS(result.wcs)
```

## Results

A result tells whether the image was solved, and gives the center of the image, its scale and orientation, the field
of view, and the focal length when `pixel_size` is given. It also gives:

- `wcs`: the WCS of the solution, as the keywords of a FITS header, with its SIP polynomials: `astropy.wcs.WCS(result.wcs)`
  makes the WCS of astropy;
- `pixel_to_sky(x, y)` and `sky_to_pixel(ra, dec)`, distortion included;
- `objects`: the deep sky objects of the image, the most prominent first;
- `matches`: the stars of the image which were matched with the catalog;
- `json`: the result as the JSON of `asterion --json`.

Pixel positions are counted from 0: the center of the first pixel of the image is `(0, 0)`. The WCS follows the
conventions of FITS instead, where it's `(1, 1)`.

## API

`help(asterion)`, or the documentation of an IDE, tells the details of each function and class.

| | |
|---|---|
| `Solver(catalog_directory=None)` | opens the catalogs of a directory: a context manager |
| `Solver.solve(pixels, width=None, height=None, **options)` | solves an image: a `Result` |
| `Solver.close()` | releases the memory of the catalogs at once |
| `Result` | `solved`, `ra`, `dec`, `scale`, `rotation`, `field_width`, `field_height`, `flipped`, `catalog`, `matched_stars`, `rms`, `log_odds`, `focal_length`, `pixel_size`, `stars`, `fwhm_pixels`, `fwhm_arcsec`, `read_ms`, `detect_ms`, `solve_ms`, `wcs`, `objects`, `matches`, `json`, `pixel_to_sky(x, y)`, `sky_to_pixel(ra, dec)` |
| `DeepSkyObject` | `name`, `designation`, `type`, `common_name`, `aliases`, `messier`, `ra`, `dec`, `x`, `y`, `distance`, `major_arcmin`, `minor_arcmin`, `angle`, `magnitude` |
| `Match` | `x`, `y`, `ra`, `dec`, `residual` |
| `catalogs(directory=None)` | the catalogs which can be installed, then the other installed ones: a list of `Catalog` |
| `install_catalog(name, directory=None, progress=None)` | installs a catalog, reporting its `Progress` |
| `default_catalog_directory()` | the directory of the catalogs by default |
| `version()` | the version of the solver |
| `Cancellation()` | cancels solves from another thread, with `cancel()` |
| `AsterionError` | the errors of the solver; `InstallationCancelled` when an installation is cancelled, `SolveCancelled` when a solve is. Pixels which can't be read raise `ValueError` |

The options of `solve`, all optional:

| Option | |
|---|---|
| `sample_type`, `row_stride` | `"uint8"`, `"uint16"` or `"float32"`, and the distance between two rows in bytes, for one-dimensional buffers |
| `bayer` | `True` for a raw frame of a color sensor |
| `ra`, `dec`, `radius` | the region of the sky to search, in degrees |
| `fov` | the approximate width of the field of view, in degrees |
| `scale_low`, `scale_high` | the range of the scale, in arcseconds per pixel |
| `pixel_size` | the size of the pixels, in micrometers, which gives the focal length |
| `epoch` | the date of the image, as a Julian year, for the proper motions of the stars |
| `parity` | `"both"`, `"normal"` or `"flipped"` |
| `timeout` | how long to search, in seconds |
| `sip_order` | the order of the polynomials of the distortion: 0 to disable them, 2 to 5, or -1 to choose |
| `binning`, `sigma`, `max_stars`, `quad_stars` | the detection of stars, as with the command line tool |
| `cancellation` | a `Cancellation`, which stops the solve once it's cancelled, from another thread |

A solve can be cancelled from another thread, such as the one of a user interface, while the solve runs on its own:

```python
cancellation = asterion.Cancellation()
# the user interface calls cancellation.cancel(), on its own thread, when the user cancels
try:
    result = solver.solve(frame, cancellation=cancellation)
except asterion.SolveCancelled:
    ...  # cancelled before the image was solved
```

## Processes

The solver runs in the process, in a GraalVM isolate which is created the first time it's used. A process which is
forked afterwards can't use it: `multiprocessing` must start its processes with the `spawn` method then.

The errors of the solver raise `asterion.AsterionError`, with its message, and pixels which can't be read raise
`ValueError`.

## License

Asterion is published under the Apache License 2.0. The wheels contain the native library of Asterion, built with
GraalVM, whose table of deep sky objects comes from OpenNGC, under the CC BY-SA 4.0 license, and whose JPEG decoder
follows the one of the Independent JPEG Group: their notices are installed with the package.
