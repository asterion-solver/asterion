# Deep sky objects

`GenerateDeepSkyObjects.java` generates the table of deep sky objects which Asterion names in images
(`src/main/resources/me/champeau/asterion/catalog/deep-sky-objects.bin`): the Messier, NGC and IC objects, and
the few others OpenNGC adds, the HII regions of Sharpless, and the bright and dark nebulae of Lynds, with their
position, size, type and names. It downloads a release of [OpenNGC](https://github.com/mattiaverga/OpenNGC), see
[the credits](../../../third-party/openngc.md), and the catalogs of Sharpless and Lynds from VizieR, see
[their credits](../../../third-party/vizier-nebulae.md):

```
java asterion-core/src/dso/GenerateDeepSkyObjects.java asterion-core/src/main/resources
```

The generated file is committed: the build doesn't download it. `DeepSkyObjects` reads it.

An object which several catalogs list appears once, with the designation of the most famous catalog: Messier,
NGC, IC, the others of OpenNGC, Sharpless, LBN, then LDN; the other designations are its aliases. The
identifications of OpenNGC (`LBN 373` for NGC 7000) and of the catalog of bright nebulae (`S 17`, `NGC 6514`) are
used first, then the positions and sizes: the positions of Sharpless and Lynds are rough, so an object is the same
as one of a more famous catalog at the same center (5', or 10% of its size, up to 15'), or within half of the larger
size when their sizes are similar. The objects of a catalog are never merged with each other: the catalogs list
the parts of complexes on purpose. The extents of Sharpless replace the sizes of OpenNGC for clusters with nebulae,
whose sizes are the ones of their clusters. A few common names which these catalogs don't have, such as the
Flying Bat nebula for Sh2-129, are in `COMMON_NAMES`.
