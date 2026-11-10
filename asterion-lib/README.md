# The Asterion library for C, C++, Python...

Asterion is also a native shared library, `libasterion.so` (`libasterion.dylib` on macOS, `libasterion.dll`
on Windows), which programs in any language able to call C functions use to solve the images they have in
memory, such as the frames of a camera. The solver stays in memory between images, with its catalogs open:
a program which solves frame after frame pays neither the start of a process nor the reading of an image
file, only the copy of its pixels. The library does everything the command line tool does, the installation
of catalogs included: a program needs nothing else.

`asterion.h` documents each function and structure. This page tells how they fit together.

## Getting the library

Each [release](https://github.com/asterion-solver/asterion/releases) has an archive of the library,
`asterion-library-<version>.zip`, with a directory per platform: `linux-x86_64`, `linux-aarch_64`,
`osx-aarch_64` and `windows-x86_64`. Each one holds:

- the library: `libasterion.so`, `libasterion.dylib`, or `libasterion.dll` and its import library `libasterion.lib`;
- `asterion.h`, the header which programs include, which includes the headers which GraalVM generates for the
  library: `libasterion.h` and `graal_isolate.h`.

A program ships the library with the other files of its directory, if there are any besides the headers:
they're the ones it needs.

On macOS, files downloaded with a browser are blocked until they are allowed:
`xattr -d com.apple.quarantine libasterion.dylib`.

The library of the current platform is also built from the sources, into `asterion-lib/build/native-library`:

```
./gradlew :asterion-lib:nativeLibrary
```

A program in C is compiled with the directory of the platform in the path of its headers, and linked with the
library:

```
cc -I DIR program.c -L DIR -lasterion -Wl,-rpath,DIR -o program          # Linux, macOS
cl /I DIR program.c /link /LIBPATH:DIR libasterion.lib                    # Windows, Visual C++
```

The system must find the library when the program starts. On Linux and macOS, it looks in the directories
which the program records, with `-Wl,-rpath`: `-Wl,-rpath,'$ORIGIN'` on Linux and `-Wl,-rpath,@executable_path`
on macOS for the directory of the program, then in the directories of the system. On Windows, it looks in the
directory of the program, then in the directories of the `PATH`.

## Starting

The library runs in a GraalVM isolate, which the program creates once, and passes to each function with the
thread which calls it. The program checks once that the header it was compiled with matches the library:

```c
#include "asterion.h"

graal_isolate_t *isolate;
graal_isolatethread_t *thread;
graal_create_isolate(NULL, &isolate, &thread);
if (!asterion_check_header(thread)) { /* this header doesn't match the library */ }
```

Functions which fail return `ASTERION_ERROR`, and `asterion_error` gives the message of the last error of the
calling thread. Strings are UTF-8 and null terminated; the functions which copy one into a buffer return its
length, which may be larger than the buffer: the string is then truncated. `asterion_version` gives the
version of the library. The library leaves the locale of the program alone.

Other threads of the program attach to the isolate with `graal_attach_thread`, and use the thread which it
gives them. A solver can be used by several threads at once.

## Catalogs

Asterion needs a catalog of stars, which is installed once on the machine. The functions which take the
directory of the catalogs use the default one when they're given `NULL`: the directory of the `ASTERION_CATALOGS`
environment variable, or `~/.asterion/catalogs`, the one of the command line tool, which
`asterion_default_catalog_directory` gives.

`asterion_catalogs` describes the catalogs which can be installed, then the other ones which are installed
in a directory: their names, what they're for, whether they're installed, and the size, the stars and the
quads of the installed ones. A first call with no room counts them:

```c
int count = asterion_catalogs(thread, NULL, NULL, 0);
asterion_catalog *catalogs = calloc(count, sizeof(asterion_catalog));
asterion_catalogs(thread, NULL, catalogs, count);
for (int i = 0; i < count; i++) {
    printf("%s%s: %s\n", catalogs[i].name, catalogs[i].installed ? " (installed)" : "", catalogs[i].description);
}
```

`asterion_install_catalog` installs one, the way `asterion --download-catalog` does, and returns when it's
over: `tycho2`, or a Gaia catalog, denser, for smaller fields of view, as their descriptions tell. It reports its progress to a function of the program, which cancels the installation by returning non-zero:

```c
static int progress(const asterion_progress *p, void *user_data) {
    if (p->event == ASTERION_EVENT_PROGRESS && !isnan(p->fraction)) {
        printf("%s: %.0f%%\n", p->step, p->fraction * 100);
    }
    return cancel_requested;
}

if (asterion_install_catalog(thread, NULL, "tycho2", progress, NULL) != 0) { /* ASTERION_CANCELLED, or ASTERION_ERROR */ }
```

The installation downloads and indexes with several threads, but the function of the program is always called
on the thread which installs, one event at a time, for every 1% of each step at most. Once it cancels, the
installation stops as soon as it can, and `asterion_install_catalog` returns `ASTERION_CANCELLED` when it has.
The part which was downloaded is kept: installing the catalog again resumes the download.

A solver uses the catalogs which were installed when it was opened: to use a new one, the program opens
another solver. A catalog which an open solver uses is installed again only once that solver is closed.

## Solving

A solver opens the catalogs of a directory, and keeps them open until it's closed:

```c
void *solver;
if (asterion_open(thread, NULL, &solver) != 0) { /* no catalog is installed... */ }
```

A program sends the pixels of an image as they are in its memory: a pointer to the first pixel, the type of
the samples, one per pixel in the native byte order (`ASTERION_UINT8`, `ASTERION_UINT16` or `ASTERION_FLOAT32`),
the width and the height of the image, the distance in bytes between the starts of two rows, and whether the
image is a raw frame of a color sensor, which is binned 2x2 to remove the pattern of its filters. A color
image is sent as one gray level per pixel, such as the sum of its channels. Asterion copies, converts and
bins the pixels in a single pass: the program can reuse its buffer as soon as the call returns.

```c
asterion_options options;
asterion_default_options(thread, &options);           // the defaults of the command line tool
options.ra = 83.8; options.dec = -5.4; options.radius = 5;   // optional limits of the search

asterion_solution solution;
void *result = NULL;                                  // written unless asterion_solve fails
if (asterion_solve(thread, solver, pixels, ASTERION_UINT16, width, height, row_stride, 0, &options, &solution,
        &result) == ASTERION_SOLVED) {
    printf("%f %f, %f\"/px, SIP order %d\n", solution.ra, solution.dec, solution.scale, solution.sip_order);
}
asterion_result_free(thread, result);
```

The options are the ones of the command line tool: the region of the sky and the scale to search, the
handedness of the image, the order of the distortion polynomials, the binning, the detection of stars... The
solution comes back in a structure: the center, the scale and the orientation of the image, its field of
view, the focal length when the size of the pixels is known, and the full WCS, with the conventions of FITS,
its SIP polynomials included.

A result, which the program asks for and frees with `asterion_result_free`, also gives:

- the deep sky objects of the image, with their positions in it: `asterion_result_object_count`, `asterion_result_object`;
- the stars which were matched with the catalog: `asterion_result_match_count`, `asterion_result_match`;
- the conversions between pixels and sky coordinates, distortion included: `asterion_result_pixel_to_sky`,
  `asterion_result_sky_to_pixel`;
- the JSON of `asterion --json`: `asterion_result_json`.

Pixel positions are counted from 0, in the image which was given: the center of its first pixel is (0, 0),
whatever the binning which Asterion applies to it. The WCS of `asterion_solution` follows the conventions of
FITS instead, where the center of the first pixel is (1, 1).

A solve can be cancelled from another thread, such as the one of a user interface, while it runs on its own:
`asterion_solve_cancellable` takes the same arguments as `asterion_solve`, and a flag which the program sets
to cancel it. The search then stops as soon as it can, and the function returns `ASTERION_CANCELLED`, unless the
image was solved before. The thread which cancels doesn't need to be attached to the isolate:

```c
volatile int32_t cancel = 0;                          // stays valid until the solve returns
// on another thread: cancel = 1;
int status = asterion_solve_cancellable(thread, solver, pixels, ASTERION_UINT16, width, height, row_stride, 0,
                                        &options, &solution, &result, &cancel);
```

## Closing

The catalogs of a solver are memory mapped: what its searches read of them stays in the memory of the
program, up to several gigabytes with the largest catalogs, and keeps the next searches fast. Closing the
solver with `asterion_close` unmaps them at once, which releases that memory: a program closes its solver
when it stops solving for a while, such as at the end of a night, and opens another one when it needs it
again. A program closes a solver once no thread solves with it anymore, and doesn't use it afterwards; the
results it keeps stay valid until `asterion_result_free`. It closes its solvers before
`graal_tear_down_isolate`, which releases the rest of the memory of the library:

```c
asterion_close(thread, solver);
graal_tear_down_isolate(thread);
```

## From Python

The Python package, `asterion-solver` on PyPI, wraps the library in a Python API, with the library of each platform:

```
pip install asterion-solver
```

See [its documentation](https://github.com/asterion-solver/asterion/tree/main/asterion-lib/src/main/python). It
calls the library with `ctypes`, which comes with Python: [`_native.py`](https://github.com/asterion-solver/asterion/blob/main/asterion-lib/src/main/python/asterion/_native.py)
declares the structures and the functions of `asterion.h`, and attaches the threads of the program to the isolate,
which makes it an example for the other languages which call C functions too.

## More examples

The tests of the library are programs which use it: [`solve.c`](https://github.com/asterion-solver/asterion/blob/main/asterion-lib/src/test/c/solve.c) solves pixels and uses every
function of the results, and [`catalogs.c`](https://github.com/asterion-solver/asterion/blob/main/asterion-lib/src/test/c/catalogs.c) lists and installs catalogs, with a progress
callback which cancels.
