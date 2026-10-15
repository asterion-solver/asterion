# Sky data

`GenerateSkyData.java` generates the sky data of the web interface: the constellation lines, the constellation
boundaries and the bright stars of the charts (`src/main/resources/web/sky.json`), and the table which names
the constellation of a position (`src/main/resources/me/champeau/asterion/web/constellations.txt`). It
downloads its sources from the CDS and GitHub, see [the credits](../../third-party/sky-data.md):

```
java asterion-web/src/sky/GenerateSkyData.java asterion-web/src/main/resources
```

The generated files are committed: the build doesn't download them.
