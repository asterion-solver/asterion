# Deep sky objects

`GenerateDeepSkyObjects.java` generates the table of deep sky objects which Asterion names in images
(`src/main/resources/me/champeau/asterion/catalog/deep-sky-objects.bin`): the Messier, NGC and IC objects, and
the few others OpenNGC adds, with their position, size, type and names. It downloads a release of
[OpenNGC](https://github.com/mattiaverga/OpenNGC), see [the credits](../../../third-party/openngc.md):

```
java asterion-core/src/dso/GenerateDeepSkyObjects.java asterion-core/src/main/resources
```

The generated file is committed: the build doesn't download it. `DeepSkyObjects` reads it.
