# Catalogs of nebulae of Sharpless, van den Bergh and Lynds

The table of deep sky objects of Asterion (`asterion-core/src/main/resources/me/champeau/asterion/catalog/deep-sky-objects.bin`)
includes the objects of four catalogs of nebulae, retrieved from [VizieR](https://vizier.cds.unistra.fr/), the
service of catalogs of the CDS, Strasbourg, France (Ochsenbein, Bauer & Marcout, 2000, A&AS 143, 23), by
`asterion-core/src/dso/GenerateDeepSkyObjects.java`:

- Sharpless, S. 1959, "A Catalogue of H II Regions", ApJS 4, 257: VizieR catalog VII/20
- van den Bergh, S. 1966, "A Study of Reflection Nebulae", AJ 71, 990: VizieR catalog VII/21
- Lynds, B. T. 1962, "Catalogue of Dark Nebulae", ApJS 7, 1: VizieR catalog VII/7A
- Lynds, B. T. 1965, "Catalogue of Bright Nebulae", ApJS 12, 163: VizieR catalog VII/9

Changes: of the fields of these catalogs, the table keeps the number, the position for J2000 as computed by VizieR
from the ones of the catalogs (for 1900 and 1950), and the size: the diameter of the HII regions of Sharpless, twice
the largest extent of the reflection nebulae of van den Bergh around their stars, whose positions are theirs, the
dimensions of the bright nebulae of Lynds, and the diameter of a circle of the area of the dark nebulae of Lynds.
The objects which OpenNGC, or a more famous catalog, already lists are merged into them, as aliases: by the
identifications of OpenNGC and of the catalog of bright nebulae, or else by their positions and sizes, and only by
their positions for the nebulae of van den Bergh. Common
names are added to a few objects. The four dark nebulae which the revision of the catalog of Lynds added without
a number of the original catalog are left out.
