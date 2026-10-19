# OpenNGC

The table of deep sky objects of Asterion (`asterion-core/src/main/resources/me/champeau/asterion/catalog/deep-sky-objects.bin`),
which names the Messier, NGC and IC objects an image contains, is generated from
[OpenNGC](https://github.com/mattiaverga/OpenNGC), the database of NGC and IC objects by Mattia Verga,
release v20260501, by `asterion-core/src/dso/GenerateDeepSkyObjects.java`.

OpenNGC is distributed under the [Creative Commons Attribution-ShareAlike 4.0 International license
(CC BY-SA 4.0)](https://creativecommons.org/licenses/by-sa/4.0/), and so is the table derived from it. The
source code of Asterion remains under the Apache License 2.0.

Changes: of the fields of OpenNGC, the table keeps the designation, the position, the major and minor
axes, the position angle, the visual magnitude (or the blue one), the type, the Messier number and the
common names; duplicate and non-existent entries are left out, and designations are expanded
("NGC0224" becomes "NGC 224", "Mel022" becomes "Melotte 22").

The table also has the nebulae of the catalogs of Sharpless and Lynds, see [vizier-nebulae.md](vizier-nebulae.md):
the ones which are objects of OpenNGC are merged into them, with the identifications of OpenNGC.

OpenNGC itself merges data from the NASA/IPAC Extragalactic Database, HyperLEDA, SIMBAD, HEASARC and
Harold Corwin's NGC/IC catalog.
