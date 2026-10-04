# Asterion Solver

A fast astrometric plate solver, written in Java.

Give it an image of the night sky, and it tells where the telescope was pointing, the scale of the image,
its orientation, and a precise WCS including optical distortion. It doesn't need to know anything about the
image: solving is *blind* by default, and takes a few milliseconds once stars are extracted.

- **Fast**: a 61 megapixel raw frame is read, analyzed and solved blindly in about 50 ms, process
  startup included. The search itself takes 2 to 10 ms.
- **Precise**: solutions are refined against Gaia DR3 or Tycho-2 with proper motions applied at the date of
  the image, and SIP distortion polynomials whose order is chosen automatically. Typical residuals are 0.1″.
- **Robust**: a bayesian verification step makes false positives extremely unlikely, and tolerates
  spurious detections, missing stars and distortion.
- **Embeddable**: the solver is a Java library with a single dependency, usable from any JVM application.
  The command line tool is a native executable for Linux, macOS and Windows, which needs no Java runtime.
- **Drop-in replacement for ASTAP**: imaging programs which call ASTAP can call Asterion instead, see
  [ASTAP compatibility mode](#astap-compatibility-mode).

## Installation

Download the archive of your platform from the [releases](https://github.com/asterion-solver/asterion/releases):
Linux (x86-64 or ARM64, such as a Raspberry Pi 4 or 5), macOS (Apple Silicon) or Windows. Unzip it,
and put the `asterion` executable in a directory of your `PATH`: it's the only file needed, and it needs no Java
runtime.

On macOS, executables downloaded with a browser are blocked until they are allowed:
`xattr -d com.apple.quarantine asterion`, in the directory of the executable.

## Quick start

Download a catalog, once:

```
asterion --download-catalog tycho2
```

Then solve images, which can be FITS files (including compressed ones), PNG, JPEG, TIFF or BMP. Color images are
solved from the sum of their channels, and color profiles are ignored:

```
$ asterion M33.fits
M33.fits: solved in 38 ms (read 17, detect 13, solve 8)
  Center:    01h 33m 52.08s, +30° 38' 38.4"  (23.46701°, +30.64400°)
  Scale:     0.9112 "/px
  Field:     2.424° x 1.617°
  Focal:     851 mm (3.76 µm pixels)
  Rotation:  -130.611° east of north, flipped
  Stars:     878 matched out of 1000, RMS 0.07" (0.08 px)
```

Useful options (see `asterion --help` for all of them):

| Option                            | Description                                                                 |
|-----------------------------------|-----------------------------------------------------------------------------|
| `--wcs`                           | writes the solution next to the image, as a FITS header in a `.wcs` file     |
| `-o`, `--output`                  | writes a copy of the image with the solution in its header; a directory when solving several images |
| `-u`, `--update`                  | writes the solution in the header of the image itself (uncompressed FITS only) |
| `--plain`                         | prints progress as plain lines instead of a live display (automatic when not in a terminal) |
| `--json`                          | prints results as JSON, including the WCS keywords                          |
| `--matches`                       | writes the matched stars (pixel and sky coordinates) as a CSV file           |
| `--ra`, `--dec`, `--radius`       | restricts the search to a region of the sky                                 |
| `--scale-low`, `--scale-high`, `--fov` | restricts the scale of the image                                       |
| `--no-hints`                      | ignores the position and scale found in the FITS header                     |
| `--pixel-size`                    | pixel size in µm, binning included, to report the focal length (read from `XPIXSZ` in FITS headers) |
| `--sip-order`                     | order of the distortion polynomials: 0 to disable, automatic by default      |

When the header of an image tells where the telescope was pointing (`RA`/`DEC`, `OBJCTRA`/`OBJCTDEC`, or a
previous solution) or its scale (`PIXSCALE`, `SCALE`, `SECPIX`, or `FOCALLEN` and `XPIXSZ`), these hints order
the search: first around that position, at any scale, then the whole sky at that scale, then at any scale.
Wrong hints can't prevent an image from being solved, they only make it slower. `--no-blind` keeps the search
around the position of the header, `--no-hints` ignores the header. `--ra`, `--dec` and `--radius` are limits
rather than hints: the region is searched from its center, and never beyond.

In an interactive terminal, long operations (installing catalogs, solving several images) show their
progress live: the steps which are running stay at the bottom of the terminal while results scroll above
them. Ctrl+C cancels. The output is plain text when it isn't a terminal, with `--json`, or with `--plain`.

The exit code is 0 when all images are solved, 1 when some can't be solved, 2 for invalid options or
errors (no catalog installed, for example), and 130 when cancelled.

## Catalogs

Catalogs are downloaded already indexed, from the [releases of the catalog
repository](https://github.com/asterion-solver/catalogs/releases). They are stored in `~/.asterion/catalogs`
unless `--catalog-dir` or the `ASTERION_CATALOGS` environment variable says otherwise.
`asterion --list-catalogs` shows what is installed and what is available.

### Which catalog?

Choose the catalog from the field of view of your images, along their shorter side. Each catalog also
solves fields which are wider than its minimum: one catalog is enough.

| Resolution | Catalog     | Content                                                   | Fields of view    | Download | Index  |
|------------|-------------|-----------------------------------------------------------|-------------------|----------|--------|
| Standard   | `tycho2`    | Tycho-2: 2.5 million stars, to magnitude 12               | 0.7° (42′) and up | 295 MB   | 378 MB |
| High       | `gaia-500`  | Gaia DR3, the 500 brightest stars of each square degree: 21 million stars | 0.15° (9′) and up | 1.5 GB | 1.9 GB |
| High+      | `gaia-1000` | Gaia DR3, 1000 stars per square degree: 41 million stars  | 0.15° (9′) and up, sometimes 0.12° (7′) | 3.1 GB | 3.7 GB |
| Very high  | `gaia-2000` | Gaia DR3, 2000 stars per square degree: 83 million stars  | 0.1° (6′) and up  | 6.1 GB   | 7.4 GB |

These limits were measured by solving fields of the Digitized Sky Survey blindly, at two places of the sky.
Images with fewer stars, such as very short exposures, may need a wider field.

The longest focal length each catalog handles, for some common sensors, reducers and Barlow lenses included:

| Sensor (shorter side)        | Standard (`tycho2`) | High (`gaia-500`) | Very high (`gaia-2000`) |
|------------------------------|---------------------|-------------------|-------------------------|
| Full frame, IMX455 (24 mm)   | 2,000 mm            | 9,000 mm          | 14,000 mm               |
| APS-C, IMX571 (15.7 mm)      | 1,300 mm            | 6,000 mm          | 9,000 mm                |
| IMX533 (11.3 mm)             | 900 mm              | 4,300 mm          | 6,500 mm                |
| IMX585 (6.3 mm)              | 500 mm              | 2,400 mm          | 3,600 mm                |
| IMX462 (3.1 mm)              | 250 mm              | 1,200 mm          | 1,800 mm                |

`gaia-1000` handles the focal lengths of `gaia-500`, and sometimes 25% more.

For other sensors, the field of view in degrees is 57.3 × the height of the sensor in mm / the focal
length in mm.

Installed catalogs are used together. Adding `tycho2` to a Gaia catalog doesn't hurt: it contains the
brightest stars, which Gaia measures poorly.

### Other catalogs

Gaia DR3 can be installed with any density, from 10 to 10,000 stars per square degree: `gaia-<N>`,
for example `gaia-1000`. These catalogs aren't published already indexed: they are built from the stars
of Gaia DR3, which are queried from the [ESA Gaia archive](https://gea.esac.esa.int/archive/) and its mirrors at
[ARI Heidelberg](https://gaia.ari.uni-heidelberg.de/), [GAVO](https://dc.g-vo.org/),
[NOIRLab](https://datalab.noirlab.edu/) and [IRSA](https://irsa.ipac.caltech.edu/): each region of the sky
is downloaded from one of them at random, and from another one when it fails. The same happens for the
other catalogs when the releases can't be reached; `tycho2` then comes from the
[CDS](https://cdsarc.cds.unistra.fr/viz-bin/cat/I/259).

Building an index needs about 80 bytes of memory per star: 2 GB for `gaia-500`, 7 GB for `gaia-2000`.
Beyond that, it uses the memory which is available, and works in several passes when there is little,
which is slower: it adapts to the memory left by the other programs which run on the machine. The
downloaded stars and a temporary file need about twice the size of the index on disk, besides the index.

Gaia selections have the same density all over the sky, instead of a limiting magnitude: this is
what solving needs, and keeps the Milky Way from making catalogs huge.

## ASTAP compatibility mode

Programs which know how to call [ASTAP](https://www.hnsky.org/astap.htm), such as N.I.N.A., CCDciel or
SharpCap, can use Asterion instead: started under the name `astap` (or `astap_cli`), the executable
accepts the command line of ASTAP and writes its files. Copy or link it under that name, and point the
program to it:

```bash
ln -s "$(command -v asterion)" ~/bin/astap      # Windows: copy asterion.exe to astap.exe
asterion astap -f image.fits -r 30 -fov 1.7     # the same mode, without renaming
```

This is a **best effort** emulation, following the [specification](docs/astap-interface.md) which was
written from the manual, the outputs of `astap_cli` and the source of N.I.N.A. and CCDciel:

- `-f`, `-r`, `-fov` (height in degrees), `-ra` (hours), `-spd` (declination + 90), `-z`, `-o`, `-sip`,
  `-wcs`, `-update` (FITS files only) and `-log` are supported. When the command line gives no position
  or scale, they are read from the FITS header.
- The options which tune ASTAP's own algorithm or star database (`-s`, `-t`, `-m`, `-check`, `-d`,
  `-D`, `-speed`, `-progress`) are accepted and ignored: the catalogs of Asterion are used. `-analyse`
  and `-extract` are not supported.
- `<image>.ini` is always written, `<image>.wcs` (text, or a FITS header with `-wcs`) when the image is
  solved, with the formats of ASTAP. The exit codes are the ones of the ASTAP manual: 0 solved, 1 no
  solution, 2 not enough stars, 16 unreadable image, 32 no catalog installed.
- Each command line received is logged in `~/.asterion/astap-calls.log`, to help with programs which
  don't behave as expected.

## How it works

1. **Star extraction**. The background of the image and its noise are estimated, then stars are detected
   and measured precisely. Hot pixels are rejected, and raw frames of color sensors are binned 2x2.
2. **Geometric hashing**. Groups of four stars are described by a code which doesn't depend on the position,
   scale and orientation of the image, as in [astrometry.net](https://arxiv.org/abs/0910.2233). An index
   contains millions of such groups, built from the brightest stars of each region of the sky at all
   scales, and those of the image are looked up in it, brightest stars first. Neighbouring stars which
   must be in the image too reject most false matches immediately.
3. **Verification**. Each match is a hypothesis about where the image is. Catalog stars are compared with
   the stars of the image until the odds that the match is right reach a billion to one, or get too low.
4. **Refinement**. All the stars of the image are matched with the deepest catalog, with proper motions
   applied at the date of the image, and the simplest model which describes the image well is kept: a
   TAN projection, with SIP distortion polynomials when the optics need them.

## Performance

Measured on 63 raw frames of 6 to 61 megapixels (ZWO ASI6200MC and QHY8L cameras, fields of 2.5°), with a
Ryzen 9 9950X. Times are the wall clock time of the whole command, including process startup and reading
a 117 MB file. Accuracy is the RMS distance between the Gaia DR3 position of the stars of the image and
their position according to the solution.

| Solver                                          | Solved | Median time | Slowest | Median accuracy |
|-------------------------------------------------|--------|-------------|---------|-----------------|
| Asterion, blind, `tycho2` + `gaia-500`           | 63/63  | 0.05 s      | 0.06 s  | 0.11″           |
| Asterion, blind, `tycho2` only                   | 63/63  | 0.04 s      | 0.06 s  | 0.26″           |
| ASTAP 2026.09.01, D50, position and scale from header | 62/63  | 0.35 s      | 4.1 s   | 0.46″           |
| ASTAP 2026.09.01, D50, blind (scale from header) | 63/63  | 2.2 s       | 6.5 s   | 0.47″           |

The engine of astrometry.net was tried on 9 of these frames, through the `astrometry` Python package
with the 4100 series of indexes: it solves them blindly in 0.2 to 0.7 s *once stars are extracted*, a
step for which Asterion needs 2 to 10 ms, and its solutions have residuals of 0.3″ to 1.2″.

Other observations:

- All the 677 frames of 7 nights of imaging which contain stars are solved blindly, as well as 20 fields
  of the Digitized Sky Survey from 12′ to 6° wide.
- When an image can't be solved, the search of the whole sky ends in 1 to 5 seconds.

See [benchmarks](benchmarks/README.md) for the method.

## Using the library

The solver is also a Java library, which other programs can embed: see [DEVELOPERS.md](DEVELOPERS.md#using-the-library).

## License

Apache License, version 2.0.

This software is based in part on the work of the Independent JPEG Group: its JPEG decoder reproduces parts of
the IJG JPEG library, release 6b, see [third-party/ijg-libjpeg.md](third-party/ijg-libjpeg.md).

This software uses data from the Tycho-2 catalogue (Høg et al., 2000) and from the European Space Agency
mission Gaia, processed by the Gaia Data Processing and Analysis Consortium.
