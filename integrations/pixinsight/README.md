# Asterion Solver for PixInsight

A script which solves the active image blindly with Asterion Solver, in a fraction of a second, and writes
the astrometric solution in the image: PixInsight's own tools then find it, such as Image > Astrometric
Solution, AnnotateImage or MosaicByCoordinates.

## Installation

1. Install Asterion Solver, see the [README](../../README.md): the `asterion` executable must be in the
   `PATH`, or its path given in the dialog of the script. Catalogs can be downloaded from the dialog.
2. In PixInsight, Resources > Updates > Manage Repositories, add the repository of the script:

   ```
   https://asterion-solver.github.io/asterion/pixinsight/
   ```

   Then Resources > Updates > Check for Updates, select Asterion Solver, and restart PixInsight to
   install it. The script appears in Script > Astrometry > Asterion Solver, next to ImageSolver. Its new
   versions are offered the same way.

The repository isn't signed yet: PixInsight asks to confirm before downloading from it.

### From the sources

To run the script of a working copy, in PixInsight, Script > Feature Scripts..., Add, and select this
directory (`integrations/pixinsight`). The directory is remembered from one session to the next. After
changing the script, Script > Feature Scripts..., Regenerate, picks the changes up. Don't install the script
from the repository at the same time: the same script would be featured twice.

## Usage

Run Script > Astrometry > Asterion Solver, select the image to solve, and click Solve. The console shows the
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

## What the script does on your computer

- **Programs:** it runs the `asterion` executable, the one given in its dialog or found in the `PATH`, to
  check its version, list and download catalogs, and solve images. It runs no other program.
- **Network:** the script makes no connection itself. When you click Download, `asterion` downloads the
  catalog over HTTPS from the [catalog releases](https://github.com/asterion-solver/catalogs/releases), and
  checks it with its SHA-256 checksum before installing it. When these releases can't be reached, it builds
  the catalog from its sources instead, over HTTPS: Gaia DR3 from the ESA Gaia archive and its mirrors
  (gea.esac.esa.int, gaia.ari.uni-heidelberg.de, dc.g-vo.org, datalab.noirlab.edu, irsa.ipac.caltech.edu),
  and Tycho-2 from the CDS (cdsarc.cds.unistra.fr).
- **Files:** catalogs are stored outside the PixInsight installation, in `~/.asterion/catalogs`, or the
  directory of the `ASTERION_CATALOGS` environment variable. To solve an image, the script writes it to a
  temporary FITS file in the temporary directory of PixInsight, which is deleted once it is solved. The
  solution is written in the image, in memory: it is saved when you save the image. The settings of the
  dialog are kept in the settings of PixInsight.

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
