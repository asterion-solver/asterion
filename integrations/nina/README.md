# Asterion Solver for N.I.N.A.

A plugin which makes Asterion Solver a plate solver of [N.I.N.A.](https://nighttime-imaging.eu/): a very
fast, modern plate solver, which can generally solve any image within milliseconds even in blind mode. It
can be the plate solver, the blind solver, or both, wherever N.I.N.A. solves: centering, plate solve and
sync, the framing assistant, the sequencer.

## Requirements

The plugin uses the plate solver API of N.I.N.A., which appeared in N.I.N.A. 3.3.0.1069, a nightly build:
it needs that version or a later one. It runs on Windows 64-bit, like N.I.N.A.

## Installation

Install the plugin from the plugin manager of N.I.N.A. The plugin includes Asterion Solver itself: there
is nothing else to install.

Then, in Options > Plate Solving:

1. choose Asterion as the plate solver, the blind solver, or both;
2. download a star catalog from the settings of Asterion, below: gaia-500 or gaia-1000 is usually a good
   start. See [which catalog](../../README.md#which-catalog) suits the field of view of your images.

A download can be cancelled: downloading the catalog again resumes where it stopped. The catalogs are
shared with the other tools of Asterion, such as its command line and its web interface.

## Settings

The settings of the plugin are in Options > Plate Solving, when Asterion is selected. They are kept in the
profile of N.I.N.A. The advanced settings are:

| Setting           | Description                                                                    |
|-------------------|--------------------------------------------------------------------------------|
| Catalog directory | where the catalogs are installed, instead of the default directory of Asterion |
| Timeout           | the time after which a solve is given up                                       |

## How it works

The plugin includes the native library of Asterion, `libasterion.dll`, which runs in the process of N.I.N.A.
N.I.N.A. gives the image to solve in memory, and the plugin gives its pixels to the library as they are: no
file is written. The catalogs stay open between solves, which keeps them fast, and are closed when one is
installed or removed. The primary solver searches the region of the sky which N.I.N.A. gives, the blind
solver searches the whole sky, and cancelling a solve in N.I.N.A. stops its search at once. The orientation
and the flip of the solution are computed the way N.I.N.A. computes them for ASTAP, so they mean the same
for every solver.

As the library runs in the process of N.I.N.A., a fault in it would stop N.I.N.A., where it only stopped the
solve of an external program before; its memory stays in N.I.N.A. between solves. The plugin checks that the
library is the one it was built for before calling it. Like the other plugins, an update of the plugin takes
effect once N.I.N.A. restarts: the library in use is only replaced then.

## Building

The plugin is built with the .NET 10 SDK, against the packages of the N.I.N.A. version of
`Directory.Build.props`. It builds on Linux and macOS too, but runs on Windows only.

```bash
dotnet build src/Asterion.Nina -c Release
```

There are two test projects:

- `tests/Asterion.Nina.Core.Tests` tests the calls to the library, on any platform. Its tests call the
  library when `ASTERION_LIBRARY` gives its path, such as the one which `./gradlew :asterion-lib:nativeLibrary`
  builds in `asterion-lib/build/native-library`, and are skipped otherwise.
- `tests/Asterion.Nina.Tests` checks that the results of solutions are the ones N.I.N.A. computes for ASTAP.
  It needs N.I.N.A., so it builds on any platform but runs on Windows only.

```bash
ASTERION_LIBRARY=/path/to/libasterion.so dotnet test tests/Asterion.Nina.Core.Tests
```

With `-p:AsterionLibrary=<the directory of the Windows library of Asterion, libasterion.dll>`, the build also
packages the plugin, with the library, the license of Asterion and the notices of what it includes, as
`src/Asterion.Nina/bin/Release/asterion-nina-<version>.zip`.
The release workflow of the repository builds it this way, and publishes it with the other archives of a
release. `New-NinaManifest.ps1` writes the manifest of a release for the plugin repository of N.I.N.A.: see
"The N.I.N.A. plugin" in [DEVELOPERS.md](../../DEVELOPERS.md).

## AI-assisted development

This plugin was developed with the help of an AI coding assistant, Claude by Anthropic. Its maintainer
reviews, tests and maintains its code, and is responsible for it.
