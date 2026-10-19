# Asterion Solver for PixInsight

A script which solves the active image blindly with Asterion Solver, in a fraction of a second, and writes
the astrometric solution in the image: PixInsight's own tools then find it, such as Image > Astrometric
Solution, AnnotateImage or MosaicByCoordinates.

## Installation

1. Install Asterion Solver and download a catalog, see the [README](../../README.md): the `asterion`
   executable must be in the `PATH`, or its path given in the dialog of the script.
2. In PixInsight, Script > Feature Scripts..., Add, and select this directory (`integrations/pixinsight`).
   The script appears in Script > Asterion > Asterion Solver.

## Usage

Run Script > Asterion > Asterion Solver, select the image to solve, and click Solve. The console shows the
solution, and Image > Astrometric Solution describes it. The script works on any image, linear or
stretched, mono or color, with or without a position in its keywords: the search is blind, and the
position, when there is one, only makes it a little faster.

The solution is stored the way ImageSolver stores its own, so PixInsight treats it the same: a TAN
projection, which replaces a previous solution. Asterion also fits SIP distortion polynomials, which
PixInsight has no use for; for the distortion of wide fields, run ImageSolver with distortion correction
afterwards, which starts from the solution and only needs a few seconds.

The dialog also lists the catalogs: the installed ones, and the ones which can be downloaded with its
Download button, which takes minutes and shows its progress in the console. When the path of the
executable is wrong, the dialog says so and stays open to fix it.

The settings of the dialog are remembered between runs.

## Headless use

The script can be used by other scripts, without its dialog:

```javascript
#define ASTERION_SOLVER_LIBRARY
#include "AsterionSolver.js"

let params = new AsterionParameters;
params.executable = "/usr/local/bin/asterion";
let window = ImageWindow.open( "/path/to/image.fits" )[0];
asterionSolve( window, params );
console.writeln( window.astrometricSolutionSummary() );
```
