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

| Setting             | Description                                                                          |
|---------------------|--------------------------------------------------------------------------------------|
| Asterion executable | another `asterion.exe` than the one included in the plugin, such as a newer version  |
| Catalog directory   | where the catalogs are installed, instead of the default directory of Asterion       |
| Timeout             | the time after which a solve is given up                                             |

## How it works

N.I.N.A. gives the image to solve in memory: the plugin writes it to a temporary FITS file, runs
`asterion --json` on it, and reads the solution from its output. The primary solver searches the region of
the sky which N.I.N.A. gives, the blind solver searches the whole sky. The orientation and the flip of the
solution are computed the way N.I.N.A. computes them for ASTAP, so they mean the same for every solver.

## Building

The plugin is built with the .NET 10 SDK, against the packages of the N.I.N.A. version of
`Directory.Build.props`. It builds on Linux and macOS too, but runs on Windows only.

```bash
dotnet build src/Asterion.Nina -c Release
```

There are two test projects:

- `tests/Asterion.Nina.Core.Tests` tests running asterion and reading its output, on any platform. Its tests
  run `asterion` when `ASTERION_EXECUTABLE` gives its path, and are skipped otherwise.
- `tests/Asterion.Nina.Tests` checks that the results of solutions are the ones N.I.N.A. computes for ASTAP.
  It needs N.I.N.A., so it builds on any platform but runs on Windows only.

```bash
ASTERION_EXECUTABLE=/path/to/asterion dotnet test tests/Asterion.Nina.Core.Tests
```

With `-p:AsterionDistribution=<the unzipped Windows distribution of Asterion>`, the build also packages
the plugin, with `asterion.exe` and its licenses, as `src/Asterion.Nina/bin/Release/asterion-nina-<version>.zip`.
The release workflow of the repository builds it this way, and publishes it with the other archives of a
release. `New-NinaManifest.ps1` writes the manifest of a release for the plugin repository of N.I.N.A.: see
"The N.I.N.A. plugin" in [DEVELOPERS.md](../../DEVELOPERS.md).

## AI-assisted development

This plugin was developed with the help of an AI coding assistant, Claude by Anthropic. Its maintainer
reviews, tests and maintains its code, and is responsible for it.
