# Asterion Solver for Siril

A Python script for [Siril](https://siril.org) 1.4 and later, which solves the loaded image blindly with
Asterion Solver, in a fraction of a second, and writes the solution in the image: a TAN projection with
SIP distortion polynomials, which Siril's own tools then use (annotations, photometry, mosaics...). It also
lists the catalogs of Asterion, and downloads them.

## Installation

1. Install Asterion Solver, see the [README](../../README.md). A catalog can be downloaded from the script.
2. In Siril, Preferences > Scripts, add the `integrations/siril/Asterion` directory to the script storage
   directories. The script appears in the Scripts menu, under Asterion: Siril names the submenu after the
   directory of the script, as for the scripts of its repository.

Siril installs PyQt6 in its Python environment the first time the dialog opens.

## Usage

Load an image, run Asterion Solver from the Scripts menu, and click Solve. The console shows the solution,
and the deep sky objects of the field. The dialog remembers the path of the `asterion` executable: when it's
on the `PATH`, or in `~/.local/bin` or `/usr/local/bin`, there's nothing to set. When the path is wrong, the
dialog says why, and stays open to fix it.

The Catalogs section lists the installed catalogs and the ones which can be downloaded: Download installs the
selected one, which takes minutes and shows its progress in the console.

The search is blind: the position and the scale of the header, when there are some, only make it a little
faster, and can be ignored with the option of the dialog.

## Command line

The script can be run from Siril scripts (`.ssf`) and its command line, with the `pyscript` command:

```
pyscript AsterionSolver.py --solve
pyscript AsterionSolver.py --solve --no-hints
pyscript AsterionSolver.py --list-catalogs
pyscript AsterionSolver.py --download-catalog tycho2
pyscript AsterionSolver.py --executable /path/to/asterion --solve
```

`--executable` is remembered for the next runs, of the command line and of the dialog.

## Testing without the dialog

The solving can be checked headless, with `siril-cli` and a script such as:

```
requires 1.4.0
load image
pyscript /path/to/integrations/siril/Asterion/AsterionSolver.py --solve
platesolve -force
```

which solves the image with Asterion, then with Siril's own solver: their solutions agree within a pixel at
the corners of the image. With the Flatpak of Siril:
`flatpak run --command=siril-cli org.siril.Siril -d <directory> -s <script>.ssf`.

## Row order

Siril keeps solutions in bottom-up pixel coordinates, also for the images whose rows are top-down
(`ROWORDER = 'TOP-DOWN'`, as written by N.I.N.A. for example), which Asterion solves as they are stored: the
script flips the solution of such images, its SIP polynomials included.

## Publishing

Siril's script repository is [siril-scripts](https://gitlab.com/free-astro/siril-scripts): a merge request
adding the `Asterion` directory makes the script appear in Scripts > Asterion for every Siril user who syncs
the repository.
