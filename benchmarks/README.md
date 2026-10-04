# Benchmarks

`compare.py` runs Asterion Solver and [ASTAP](https://www.hnsky.org/astap.htm) on the same images, and reports
for each of them:

- how many images are solved
- the wall clock time of the whole command, from process startup to exit
- the accuracy of solutions

## Method

Both solvers are run twice: with the hints found in the FITS header (position and scale), then blindly.
For ASTAP, "blindly" means that the search starts from the north pole with a radius of 180°: ASTAP still
uses the scale of the header when there's one, since it can't search without a field of view.

Accuracy is measured the same way for both solvers. The stars that Asterion matched with Gaia DR3 are
written with `--matches`: their position in the image, and their catalog position at the date of the image.
The WCS written by each solver, including SIP polynomials, converts these pixel positions to sky
coordinates with astropy, and the RMS distance to the catalog positions is the accuracy of the solution.
This favors neither solver for the reference positions, which are Gaia's, but star centroids are the
ones measured by Asterion.

## Running

```
python3 -m venv venv
venv/bin/pip install numpy astropy

asterion --download-catalog tycho2,gaia-500

venv/bin/python compare.py \
    --asterion ../asterion-cli/build/native/nativeCompile/asterion \
    --astap /path/to/astap_cli --astap-db /path/to/astap/database \
    '/path/to/images/*.fits'
```

ASTAP's command line version and its star databases (D50 was used for the published results) can be
downloaded from [SourceForge](https://sourceforge.net/projects/astap-program/files/).

## Results

63 raw frames of deep sky objects and comets, 61 megapixels for most of them, exposures from 5 to 300
seconds, on a Ryzen 9 9950X with files in the page cache:

```
Asterion, with header hints       solved 63/63, time median 0.050 s, max 0.063 s, RMS median 0.11", max 0.94"
Asterion, blind                   solved 63/63, time median 0.050 s, max 0.059 s, RMS median 0.11", max 0.94"
ASTAP, with header hints          solved 62/63, time median 0.346 s, max 4.147 s, RMS median 0.46", max 23.98"
ASTAP, blind                      solved 63/63, time median 2.184 s, max 6.489 s, RMS median 0.47", max 23.98"
```

ASTAP was less accurate than Asterion on each of the frames it solved.
