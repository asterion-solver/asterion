# ASTAP command-line interface

This document specifies the command-line interface of the [ASTAP](https://www.hnsky.org/astap.htm)
plate solver: what it reads, what it writes, and how imaging programs use it. It is the contract that
Asterion's ASTAP compatibility mode must implement, so that programs which know how to call ASTAP can
use Asterion instead.

## Sources

Every statement below comes from one of these sources, and says which one when they disagree:

- **Manual**: the "Command line usage ASTAP" section and appendix 4 of the
  [ASTAP manual](https://www.hnsky.org/astap.htm), as of 2026-10-03.
- **Observed**: the behavior of `astap_cli` version 2026.09.01 for Linux x86-64, with the D05 and D50
  star databases, on FITS and JPEG images.
- **N.I.N.A.**: `NINA.Platesolving/Solvers/ASTAPSolver.cs` and `CLISolver.cs` in
  [isbeorn/nina](https://github.com/isbeorn/nina) (master, 2025-11).
- **CCDciel**: `src/cu_astrometry_engine.pas` in [pchev/ccdciel](https://github.com/pchev/ccdciel)
  (master, 2026-10).
- **SharpCap**: closed source, its invocation of ASTAP is **not verified** (see
  [open questions](#open-questions)).

## Executables

ASTAP is distributed as two programs which share this interface:

| Program | Platforms | Notes |
|---|---|---|
| `astap` / `astap.exe` | Windows, Linux, macOS | GUI program, solves without showing its window when it gets a `-f` option |
| `astap_cli` / `astap_cli.exe` | Windows, Linux, macOS, Android | command-line only; does not support the GUI-only options (`-annotate`, `-debug`, `-tofits`, `-sqm`, `-focusN`, `-stack`, `-p`) |

The manual says that `astap_cli.exe` "can be renamed to astap.exe". Clients refer to either name:

| Client | Executable |
|---|---|
| N.I.N.A. | any path, configured by the user |
| CCDciel | `<folder>/astap` on Linux and macOS, `<folder>\astap.exe` on Windows: the name is fixed, the folder is configured |
| SharpCap | not verified |

## Input

### Command line

Options are separated from their value by a space. Values containing spaces are quoted. The order of
options doesn't matter. Command-line values take precedence over the values found in the FITS header.

| Option | Value | Unit | Meaning (manual) | Default |
|---|---|---|---|---|
| `-f` | file | | Image to solve: FITS, TIFF, PNG, JPEG, uncompressed XISF. **Required** for solving | |
| `-r` | radius | degrees | Radius of the square search pattern around the start position. `180` searches the whole sky | program setting |
| `-fov` | height | degrees | Height of the field of view of the image (not the width, not the diagonal). `0` means auto: ASTAP tries heights from 10° down to 0.3°, and remembers the one which solves ("learn mode") | FITS header, else the last height which solved |
| `-ra` | right ascension | **hours** | Start position of the search | FITS header, else the last solution |
| `-spd` | south pole distance | degrees | Declination + 90, so always positive | FITS header, else the last solution |
| `-z` | factor | 0, 1, 2, … | Binning applied before solving. `0` is automatic. Clients should default to 0 | program setting |
| `-s` | count | | Maximum number of stars used for the solution, typically 500 | 500 |
| `-t` | tolerance | | Tolerance when comparing quads, typically 0.007 | 0.007 |
| `-m` | size | arcseconds | Minimum star size, to reject hot pixels | 1.5 |
| `-check` | `y` / `n` | | Apply a check pattern filter: for raw color frames, binned 1x1 | program setting |
| `-d` | path | | Directory of the star database | installation directory |
| `-D` | abbreviation | | Star database to use: `d80`, `d50`, `d20`, `d05`, `v50`, `w08`, … | automatic |
| `-o` | base path | | Base path and file name of the output files, without extension | input path without extension |
| `-sip` | (`y` / `n` in the manual) | | Add SIP distortion coefficients. The manual says it is "only required to deactivate SIP"; **observed**: `astap_cli` adds SIP only when `-sip` is given, and takes no value | no SIP (observed) |
| `-speed` | `slow` / `auto` | | `slow` forces more overlap of the search areas | `auto` |
| `-wcs` | | | Write the `.wcs` file as a standard FITS header, see [.wcs file](#wcs-file) | text `.wcs` |
| `-update` | | | Add the solution to the header of the input FITS or TIFF file. For JPEG and PNG, a new FITS file is created | |
| `-log` | | | Write a solver log to `<base>.log` | |
| `-progress` | | | Log all progress steps | |
| `-analyse` | SNR | | Only measure the stars: median HFD and number of stars, no solving | |
| `-extract` | SNR | | Write all the detected stars to a `.csv` file, no solving | |
| `-extract2` | SNR | | Solve, and write all the detected stars with their α, δ to a `.csv` file | |
| `-h`, `-help` | | | Print the usage | |

`-analyse` reports through the exit code on Windows (median HFD × 100M + number of stars) and on stdout
on Linux and macOS (`HFD_MEDIAN=3.3` and `STARS=666` lines).

### FITS header

When a value isn't given on the command line, ASTAP reads it from these FITS header keywords (manual,
appendix 4). The manual lists them without saying which one wins when several are present:

| Purpose | Keywords | Unit |
|---|---|---|
| Start position | `RA` and `DEC`; `OBJCTRA` and `OBJCTDEC`; `CRVAL1` and `CRVAL2` (from a previous solution) | degrees or sexagesimal (`hh:mm:ss`, `dd:mm:ss`) |
| Scale | `SECPIX1` / `SECPIX2`; `SCALE`; `PIXSCALE`; `CDELT1` / `CDELT2` and the `CD` matrix (from a previous solution) | arcseconds per pixel, or degrees per pixel for `CDELT` and `CD` |
| Scale, from the optics | `FOCALLEN` with `XPIXSZ` / `YPIXSZ` (binning included) | `FOCALLEN`: millimeters; `XPIXSZ`: micrometers |

`FOCALLEN` is in millimeters, although the manual says "meter". **Observed**: the same frame (7.8 µm
pixels, 3.08″/px) gives an image height of 1.73° and solves with `FOCALLEN = 522`, while
`FOCALLEN = 0.522` gives an image height of 172.4° and no solution.

**Observed**: with a FITS file without position or scale, and no `-fov`, ASTAP behaves as with
`-fov 0`, trying heights of 9.5°, 6.3°, 4.2°, 2.8° and 1.9°. When the scale it finds differs from the
expected one, it writes a warning such as `Warning scale was inaccurate! Set FOV=1.72d, scale=3.1", FL=523mm`.

## Output

ASTAP writes up to four files, in the directory of the input image, named after it without its
extension: for `/data/img_001.fits`, the base is `/data/img_001`. With `-o <base>`, that base is used
instead.

| File | When |
|---|---|
| `<base>.ini` | always, solved or not |
| `<base>.wcs` | only when solved |
| `<base>.log` | with `-log` |
| input image | modified in place with `-update` (FITS, TIFF), or a new `.fits` file for JPEG and PNG |

Clients delete these files before calling ASTAP, and detect the outcome from the files which exist.

### .ini file

One `KEY=value` per line, no spaces around `=`. **Observed**: lines end with LF on Linux; the manual
doesn't say which line ending Windows uses. Numbers are formatted with 17 significant digits and a
three-digit exponent, with a leading space for positive values: `CRPIX1= 1.5205000000000000E+003`,
`CDELT1=-8.5470206556581920E-004`. The decimal separator is always a dot.

After a successful solve, the keys are, in this order:

| Key | Value |
|---|---|
| `PLTSOLVD` | `T` |
| `CRPIX1`, `CRPIX2` | Reference pixel, 1-based. **Always the center of the image**: `(width + 1) / 2`, `(height + 1) / 2`, e.g. 1520.5 and 1008.5 for 3040 × 2016 |
| `CRVAL1`, `CRVAL2` | Right ascension and declination (J2000) of the reference pixel, in degrees |
| `CDELT1`, `CDELT2` | Pixel size along X and Y, in degrees |
| `CROTA1`, `CROTA2` | Rotation of the X and Y axes, in degrees |
| `CD1_1`, `CD1_2`, `CD2_1`, `CD2_2` | CD matrix, in degrees per pixel |
| `CMDLINE` | The command line, starting with the path of the executable |
| `WARNING` | Optional, a warning for the user |

`CDELT` and `CROTA` are redundant with the CD matrix. **Observed**: they are computed from it as
follows, `det` being `CD1_1 · CD2_2 − CD1_2 · CD2_1` (verified to 10⁻¹³ on 7 solutions):

```
CDELT1 = sign(det) · hypot(CD1_1, CD1_2)
CDELT2 = hypot(CD2_1, CD2_2)
CROTA1 = atan2(−CD1_2, sign(det) · CD1_1)        in degrees
CROTA2 = atan2(sign(det) · CD2_1, CD2_2)         in degrees
```

After a failure:

| Key | Value |
|---|---|
| `PLTSOLVD` | `F` |
| `CMDLINE` | The command line |
| `ERROR` | Optional: what went wrong, absent when no solution was found |
| `WARNING` | Optional |

Observed `ERROR` values: `Error reading image file.`, `Not enough stars.`, `No star database found.`.
Observed `WARNING` values: `Warning scale was inaccurate! Set FOV=…d, scale=…", FL=…mm`,
`Warning, small image dimensions!! `.

### .wcs file

The original header of the image, without its data, with the solution added. Two formats:

- **Text, the default.** One card per line, ended by LF (**observed** on Linux; the manual says
  "carriage return and line feed", probably on Windows). Most lines are padded to 80 characters, but
  `COMMENT` and `WARNING` lines aren't. Not a valid FITS file.
- **FITS, with `-wcs`.** 80-byte cards with no line separators, padded with spaces to a multiple of
  2880 bytes, like astrometry.net.

Content, **observed**:

1. `SIMPLE = T`, `BITPIX = 8`, `NAXIS = 0`: there is no data, and the `NAXIS1` / `NAXIS2` cards of the
   image are removed.
2. The other cards of the original header, unchanged.
3. The solution, in this order:
   ```
   CTYPE1  = 'RA---TAN'           / first parameter RA,    projection TANgential
   CTYPE2  = 'DEC--TAN'           / second parameter DEC,  projection TANgential
   CUNIT1  = 'deg     '           / Unit of coordinates
   EQUINOX =               2000.0 / Equinox of coordinates
   CRPIX1  =  1.520500000000E+003 / X of reference pixel
   CRPIX2  =  1.008500000000E+003 / Y of reference pixel
   CRVAL1  =  1.057598382192E+001 / RA of reference pixel (deg)
   CRVAL2  =  4.127711959883E+001 / DEC of reference pixel (deg)
   CDELT1  = -8.547020655658E-004 / X pixel size (deg)
   CDELT2  =  8.547891116290E-004 / Y pixel size (deg)
   CROTA1  = -1.355327302145E+002 / Image twist of X axis        (deg)
   CROTA2  = -1.355373531333E+002 / Image twist of Y axis        (deg)
   CD1_1   =  6.099587506127E-004 / CD matrix to convert (x,y) to (Ra, Dec)
   CD1_2   =  5.987202547379E-004 / CD matrix to convert (x,y) to (Ra, Dec)
   CD2_1   =  5.987320090771E-004 / CD matrix to convert (x,y) to (Ra, Dec)
   CD2_2   = -6.100691818687E-004 / CD matrix to convert (x,y) to (Ra, Dec)
   PLTSOLVD=                    T / Astrometric solved by ASTAP_CLI v2026.09.01.
   COMMENT 7 Solved in 3.4 sec. Offset was 42.4d.
   WARNING = 'Warning scale was inaccurate! Set FOV=1.72d, scale=3.1", FL=523mm'
   COMMENT cmdline:<the command line, cut in pieces of 72 characters>
   END
   ```
   There is no `CUNIT2`. Numbers have 13 significant digits and a three-digit exponent, right-aligned
   in 20 characters. The `WARNING` card is only present when there is a warning.
4. With `-sip`, the `CTYPE` values are `RA---TAN-SIP` and `DEC--TAN-SIP`, and the cards `A_ORDER`,
   `A_p_q`, `B_ORDER`, `B_p_q`, `AP_ORDER`, `AP_p_q`, `BP_ORDER`, `BP_p_q` are added for all the terms of
   order 0 to 3, including those of order 0 and 1.

### stdout

Progress messages for people, which clients show as status but don't parse. On success, the last lines
are:

```
Solution found: 00: 42  18.2 +41d 16  38
Solved in 3.4 sec. Δ was 42.4d.  Used stars down to magnitude: 12.7
```

On failure: `No solution found!  :(`, possibly preceded by the reason (`Only 0 stars found in image. Abort`).

### Exit code

| Code | Manual | Observed in `astap_cli` 2026.09.01 |
|---|---|---|
| 0 | No errors | solved |
| 1 | No solution | no solution, and **also** every error below |
| 2 | Not enough stars detected | not observed: 1, with `ERROR=Not enough stars.` |
| 16 | Error reading image file | not observed: 1, with `ERROR=Error reading image file.` |
| 32 | No star database found | not observed: 1, with `ERROR=No star database found.` |
| 33 | Error reading star database | not tested |
| 34 | Error updating input file | not tested |

The GUI program `astap` may return the documented codes; only `astap_cli` was tested.

## Clients

### N.I.N.A.

Verified from its source.

- Saves the image as a FITS file with a random name in its working directory.
- Runs, with stdout redirected and no console:
  ```
  <executable> -f "<image>.fits" -fov <height> -z <downsample> -s <max stars> -r <radius> -ra <hours> -spd <dec + 90>
  ```
  `-ra` and `-spd` are only given when a position is known and the search radius isn't 0; otherwise
  `-r 180`. Numbers are formatted with a dot, rounded to 6 decimals.
- Waits for the process to exit, for 10 minutes at most. **The exit code is ignored.**
- Reads `<image>.ini`. Each non-blank line is split at the first `=` into a dictionary. Solved if
  `PLTSOLVD` is `T`. Then requires `CRVAL1`, `CRVAL2`, `CRPIX1`, `CRPIX2`, `CD1_1`, `CD1_2`, `CD2_1`,
  `CD2_2`, parsed as invariant-culture numbers. Shows `WARNING` and `ERROR` to the user.
- Deletes the image, `.ini` and `.wcs` files afterwards, or moves them to a "failed" folder.
- **On Windows, reads the file version of the executable** (`FileVersionInfo`). Without a file version,
  it refuses a downsample factor of 0, saying that the ASTAP version is too old.

### CCDciel

Verified from its source.

- Runs `<folder>/astap` (`astap.exe` on Windows) with:
  ```
  -log -z <downsample> -r <radius> -f <image>
  ```
  `-r` is the configured radius when a position is known, `180` otherwise. **No `-ra`, `-spd` or
  `-fov`**: the position and the scale come from the FITS header written by CCDciel.
- Deletes `<image>.log` and `<image>.wcs` before solving.
- Waits for the process, up to a configured timeout. **Uses the exit code**: success is 0, and other
  codes are reported with the manual's meanings (1 no solution, 2 not enough stars, 16 error reading the
  image, 32 no star database, 33 error reading the star database).
- When the exit code is 0 and `<image>.wcs` exists, reads it **line by line** (text format), pads each
  line to 80 characters, collects the `WARNING =` cards, and merges the WCS into its own FITS file.
- Copies `<image>.log` to its own log when it exists. Never reads the `.ini`.

### SharpCap

Not verified: its source isn't available. To be determined by logging the command lines it uses (see
[open questions](#open-questions)).

## Requirements for Asterion

What a drop-in replacement needs, from the above:

1. **Names**: work when installed as `astap`, `astap.exe`, `astap_cli` or `astap_cli.exe`.
2. **Options**: accept every option of the [command line](#command-line) table without failing. Implement
   `-f`, `-r`, `-fov`, `-ra`, `-spd`, `-z`, `-o`, `-sip`, `-wcs`, `-update`, `-log`. Accept and ignore the
   options which only tune ASTAP's own algorithm or database: `-s`, `-t`, `-m`, `-check`, `-d`, `-D`,
   `-speed`, `-progress`. Reject `-analyse`, `-extract` and `-extract2` with exit code 1 and an `ERROR` in
   the `.ini`, until they are implemented.
3. **Hints**: read the start position and scale from the FITS header when the command line doesn't give
   them, with the keywords of [FITS header](#fits-header). `-fov 0` and a missing scale mean "unknown
   scale": a blind solve on the scale, not a search over a list of heights. There is no learn mode.
4. **Files**: write `<base>.ini` in all cases, `<base>.wcs` only when solved, `<base>.log` with `-log`,
   with the exact formats above. The reference pixel must be the center of the image, as clients may
   assume it. `CDELT` and `CROTA` follow the formulas above.
5. **Exit codes**: follow the manual (0, 1, 2, 16, 32, 33, 34) rather than `astap_cli`'s observed
   behavior, since CCDciel interprets them and N.I.N.A. ignores them. Write the matching `ERROR` in the
   `.ini` too.
6. **Output**: plain text on stdout, never interactive (clients redirect it), ending with the
   `Solution found:` or `No solution found!` lines.
7. **Windows**: the executable needs a file version resource, or N.I.N.A. refuses automatic
   downsampling.
8. **Diagnostics**: log the command lines received, to find out what clients such as SharpCap pass.

## Open questions

- **SharpCap**: which executable name and options it uses, and whether it reads the `.ini`, the `.wcs`,
  or both.
- **Windows line endings**: whether `.ini` and text `.wcs` files use CRLF on Windows, as the manual
  suggests for the `.wcs`.
- **GUI exit codes**: whether the GUI program `astap`, which CCDciel calls, returns the documented exit
  codes where `astap_cli` returns 1.
