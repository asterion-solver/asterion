# Test data

`src/test/resources/images/` holds real images which the tests solve, with the small index they are solved
against, `sharpcap-index.astx.gz`: the stars of `gaia-500` within 1° of the center of each image, with the
levels of its pyramid standing for magnitudes, indexed down to the 5.3' quads of `gaia-500`. Index files have
tables for the cells of the whole sky, nearly all empty here, so the index is committed compressed (600 KB
for 61 MB), and the tests decompress it. `BuildTestIndex.java` builds it from an installed catalog:

```
./gradlew :asterion-cli:installDist
java -cp "asterion-cli/build/install/asterion/lib/*" asterion-core/src/testdata/BuildTestIndex.java \
    ~/.asterion/catalogs/gaia-500.astx asterion-core/src/test/resources/images/sharpcap-index.astx.gz \
    270.305,66.730 339.255,34.418 328.080,12.676
```

The centers are those of the images, in degrees. The index must be built again when the format of index
files changes (`StarIndex.VERSION`), or when an image is added.

- `sharpcap-draco.png`, `sharpcap-ngc7331.png`, `sharpcap-pegasus.png`: 16-bit captures of SharpCap, 1944x1096
  pixels, 1 s exposures at a high gain, fields of 19'x11' at 0.58"/pixel: small fields with few catalog stars,
  which solve with 14 to 20 of them.
