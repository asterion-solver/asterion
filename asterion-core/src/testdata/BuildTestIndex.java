/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import me.champeau.asterion.catalog.StarData;
import me.champeau.asterion.index.IndexBuilder;
import me.champeau.asterion.index.IndexOptions;
import me.champeau.asterion.index.StarIndex;
import me.champeau.asterion.math.Sphere;
import me.champeau.asterion.progress.ProgressListener;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

/**
 * Builds the small index of the tests which solve real images, from the stars of an installed
 * catalog around the fields of these images, see README.md.
 *
 * <pre>java -cp &lt;asterion-core and its dependencies&gt; asterion-core/src/testdata/BuildTestIndex.java \
 *     ~/.asterion/catalogs/gaia-500.astx asterion-core/src/test/resources/images/sharpcap-index.astx.gz \
 *     270.305,66.730 339.255,34.418 328.080,12.676</pre>
 */
class BuildTestIndex {
    /** The radius around each field, in degrees: enough for the quads of the index to reach into the images. */
    static final double RADIUS_DEG = 1;
    /** The cells of the finest level, in degrees: the smallest quads of gaia-500 are 5.3' across. */
    static final double MIN_CELL_SIZE_DEG = 0.08;

    public static void main(String[] args) throws Exception {
        var index = StarIndex.open(Path.of(args[0]));
        var output = Path.of(args[1]);
        var stars = new StarData(index.epoch());
        var vector = new double[3];
        var later = new double[3];
        for (var i = 2; i < args.length; i++) {
            var center = args[i].split(",");
            var ra = Math.toRadians(Double.parseDouble(center[0]));
            var dec = Math.toRadians(Double.parseDouble(center[1]));
            var radius = Math.toRadians(RADIUS_DEG);
            var centerVector = new double[3];
            Sphere.toVector(ra, dec, centerVector);
            var minDot = Math.cos(radius);
            // the levels of the pyramid of the index sort stars by brightness: the level and the
            // position in it stand for the magnitude, which the index doesn't store
            for (var level = 0; level < index.levelCount(); level++) {
                var ranges = new int[index.grid(level).maxConeRangeInts(radius)];
                var count = index.coneStarRanges(level, ra, dec, radius, ranges);
                var first = level == 0 ? 0 : index.cumulativeStars(level - 1);
                var size = index.cumulativeStars(level) - first;
                for (var r = 0; r < count; r += 2) {
                    for (var star = ranges[r]; star < ranges[r + 1]; star++) {
                        index.starVector(star, 0, vector);
                        if (vector[0] * centerVector[0] + vector[1] * centerVector[1] + vector[2] * centerVector[2] < minDot) {
                            continue;
                        }
                        // the proper motion, from the position a century later
                        index.starVector(star, 100, later);
                        var starRa = Sphere.ra(vector[0], vector[1]);
                        var starDec = Sphere.dec(vector[2]);
                        var pmRa = (Sphere.ra(later[0], later[1]) - starRa) * Math.cos(starDec);
                        pmRa -= 2 * Math.PI * Math.round(pmRa / (2 * Math.PI));
                        var pmDec = Sphere.dec(later[2]) - starDec;
                        var masPerYear = Math.toDegrees(1) * 3600 * 1000 / 100;
                        var magnitude = level + (star - first) / (double) Math.max(1, size);
                        stars.add(starRa, starDec, (float) (pmRa * masPerYear), (float) (pmDec * masPerYear), (float) magnitude);
                    }
                }
            }
        }
        // the stars cover a few patches of the sky: the pyramid must not stop on the fill of its
        // levels, as it does for a sparse catalog, but go down to the cells of the smallest quads
        var options = new IndexOptions(IndexOptions.defaults().maxCellSizeDeg(), MIN_CELL_SIZE_DEG, IndexOptions.defaults().starsPerCell(),
                IndexOptions.defaults().quadsPerCell(), 0, 0);
        // the index has tables for the cells of the whole sky, nearly all empty: it is committed
        // compressed, and the tests decompress it
        var name = output.getFileName().toString().replace(".astx.gz", "");
        var raw = output.resolveSibling(name + ".astx");
        IndexBuilder.build(stars, name, options, raw, ProgressListener.none());
        try (var in = Files.newInputStream(raw); var out = new GZIPOutputStream(Files.newOutputStream(output))) {
            in.transferTo(out);
        }
        Files.delete(raw);
        System.out.printf("%d stars from %s, index written to %s (%d bytes)%n", stars.size(), index.name(), output, Files.size(output));
    }
}
