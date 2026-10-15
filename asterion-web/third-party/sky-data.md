# Sky data of the web interface

The charts of the web interface draw the constellations and the brightest stars from the file
`asterion-web/src/main/resources/web/sky.json`, and the interface names the constellation of a solution with
the table `asterion-web/src/main/resources/me/champeau/asterion/web/constellations.txt`. Both are generated
from the following sources by `asterion-web/src/sky/GenerateSkyData.java`.

## Constellation lines

The stick figures of the constellations are those of
[ConstellationLines](https://github.com/MarcvdSluys/ConstellationLines), by Marc van der Sluys:

> Copyright (c) 2005-2026, Marc van der Sluys, [hemel.waarnemen.com](https://hemel.waarnemen.com).
> DOI: [10.5281/zenodo.10397192](https://doi.org/10.5281/zenodo.10397192)

They are used under the terms of the [Creative Commons Attribution 4.0 International license (CC BY 4.0)](https://creativecommons.org/licenses/by/4.0/).

Changes: the stars of the figures, which the original file gives by their number in the Bright Star Catalogue,
are replaced by their J2000 positions in that catalog.

## Stars

The positions of the stars of the figures, and the stars of the charts down to magnitude 5.5, come from the
Bright Star Catalogue, 5th Revised Ed. (Preliminary Version), Hoffleit D., Warren Jr W.H., Astronomical Data
Center, NSSDC/ADC (1991), retrieved from the [CDS](https://cdsarc.cds.unistra.fr/viz-bin/cat/V/50), Strasbourg,
France.

## Constellation boundaries

The boundaries of the constellations come from the Catalogue of Constellation Boundary Data, Davenhall A.C.,
Leggett S.K., Royal Observatory Edinburgh (1989), retrieved from the
[CDS](https://cdsarc.cds.unistra.fr/viz-bin/cat/VI/49), Strasbourg, France. The boundaries of the equinox of
B1875 are cut in segments of 1° at most, and precessed to J2000.

The constellation of a position is found with the table and the method of Roman N.G., Identification of a
Constellation From Position, Publ. Astron. Soc. Pacific 99, 695 (1987), retrieved from the
[CDS](https://cdsarc.cds.unistra.fr/viz-bin/cat/VI/42), Strasbourg, France.

This research has made use of the VizieR catalogue access tool, CDS, Strasbourg, France.
