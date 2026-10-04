# Asterion Solver: developer guide

How to embed the solver in other programs, build it, and release it. For using the command line tool,
see the [README](README.md).

## Using the library

The library is the `asterion-core` module. Until it is published, install it in your local Maven
repository with `./gradlew publishToMavenLocal`, then:

```kotlin
dependencies {
    implementation("me.champeau.asterion:asterion-core:0.1.0-SNAPSHOT")
}
```

```java
PlateSolver solver = PlateSolver.open(Path.of("tycho2.astx"), Path.of("gaia-500.astx"));

// from a FITS file
SolveResult result = solver.solve(Path.of("image.fits"), SolverOptions.defaults());
result.solution().ifPresent(solution -> {
    System.out.printf("%.5f %.5f, %.3f\"/px%n", solution.raDeg(), solution.decDeg(), solution.pixelScale());
    double[] sky = solution.wcs().pixelToSky(1024, 768);
    Map<String, Object> keywords = solution.wcs().toFitsKeywords();
});

// from pixels
GrayImage image = new GrayImage(width, height, floats);
result = solver.solve(image, ImageHints.none(), SolverOptions.defaults());

// from stars detected by other means, sorted by decreasing brightness
result = solver.solve(StarList.of(x, y), width, height, ImageHints.none(),
        SolverOptions.builder().position(83.8, -5.4, 5).scale(0.9, 1.1).build());
```

Installing a catalog reports its progress to a `ProgressListener`, which applications implement to show
progress their own way. All its methods are optional:

```java
Catalogs.install(Catalogs.find("tycho2").orElseThrow(), Catalogs.defaultDirectory(), new ProgressListener() {
    @Override
    public void advanced(ProgressUpdate update) {
        // update.step() describes the step: "Downloading Tycho-2...", 21 "files"
        progressBar.setValue(update.fraction());
    }
});
```

`ProgressListener.text(System.out::println)` prints progress the way the command line tool does, and
`ProgressTracker` reports the progress of a step, from any number of threads, for those who write their own
catalog descriptors.

A `PlateSolver` is thread-safe. Opening an index is immediate, since index files are memory mapped.
Catalogs can be installed programmatically with `Catalogs.install(...)`, and custom indexes can be built
from any list of stars with `IndexBuilder`. The library requires Java 25.

## Project structure

- `asterion-core`: the library
- `asterion-cli`: the command line tool, including the [ASTAP compatibility mode](docs/astap-interface.md)
  in the `me.champeau.asterion.cli.astap` package
- `build-logic`: the build conventions, an included build
- `benchmarks`: the [comparison with other solvers](benchmarks/README.md)
- `docs`: specifications, such as the [interface of ASTAP](docs/astap-interface.md) which the compatibility mode follows

## Building

The build requires a Java 25 installation, which Gradle finds as a toolchain, and GraalVM 25 for the
native executable.

```
./gradlew build                 # compiles and tests
./gradlew nativeCompile         # builds the native executable, requires GraalVM 25
./gradlew nativeZip             # packages it for the current platform
./gradlew installDist           # JVM distribution, in asterion-cli/build/install
```

The native executable is `asterion-cli/build/native/nativeCompile/asterion`, a single file: images are
decoded by Asterion itself, not by AWT, which native images don't support on every platform. The
executable targets any x86-64 or ARM64 processor by default: `-PnativeMarch=native` optimizes it for the
build machine.

The native executable is built with profile-guided optimization, when the build runs with Oracle GraalVM:
the profile is `asterion-cli/src/pgo-profiles/main/default.iprof`. It halves the size of the
executable, and makes it 3% (typical solve) to 10% (index building, exhaustive searches) faster.
`./update-pgo.sh` regenerates the profile from a workload of your choice, which is worth doing when the
solver changes significantly.

## Image decoders

PNG, JPEG, TIFF and BMP images are decoded by Asterion, and must give exactly the pixels which ImageIO
gives: the brightness of a pixel is the sum of its color channels. JPEG images are decoded like the IJG
library which the JDK uses, see [third-party/ijg-libjpeg.md](third-party/ijg-libjpeg.md). Color profiles are
ignored, unlike ImageIO which applies some of them.

`ImageDecodersTest` checks every variant ImageIO writes, and damaged files. Two more tests take a list of
image files, one path per line, to check real images:

```
./gradlew :asterion-core:test --tests '*ImageCorpusTest' -PimageCorpus=images.txt          # same pixels as ImageIO
./gradlew :asterion-core:test --tests '*ImageDecodersBenchmark' -PimageBenchmark=images.txt # time compared with ImageIO
```

Their reports are written to `asterion-core/build/reports/image-corpus.txt` and `image-benchmark.txt`.

## Formatting

`./gradlew format` formats the Java sources and adds the license header to new files; `./gradlew check`
fails when a file isn't formatted. The rules are an Eclipse formatter profile,
[`config/formatting/eclipse-java.xml`](config/formatting/eclipse-java.xml), which IntelliJ IDEA can import
(Settings > Editor > Code Style > Java > Import Scheme > Eclipse XML Profile). Among them:

- 4 spaces of indentation, 8 for continuation lines, and lines of at most 140 characters;
- record components and enum constants one per line;
- line breaks made by hand are kept, and comments aren't reformatted;
- `// @formatter:off` and `// @formatter:on` exclude code from formatting.

The formatter wraps a call which is too long before its method name when it can't wrap between its
arguments: wrapping it by hand where it reads best is kept.

## Publishing catalogs

Users download catalogs already indexed, from the releases of
[asterion-solver/catalogs](https://github.com/asterion-solver/catalogs/releases). There is a release per
version of the format of index files (`StarIndex.VERSION`), named `catalogs-v<version>`: each version of
Asterion downloads the files it can read. A release contains the indexes compressed with gzip, split in
parts of less than 2 GB for GitHub, and a manifest, `catalogs.properties`, which lists their sizes and
SHA-256 checksums. Catalogs which aren't published, or can't be downloaded, are built from their sources.

`./publish-catalogs.sh` builds `tycho2`, `gaia-500` and `gaia-2000` from their sources, packs them and uploads
them with the GitHub CLI, which must be logged in with the right to create releases in the organization.
It needs about 30 GB of disk space and 8 GB of memory, and works in `build/publish-catalogs`: when it's
interrupted, running it again resumes the downloads. It must run again when the format of index files
changes, which creates a new release, or when the indexes change.

The script uses two hidden options of the command line tool:

- `--from-sources` builds indexes from the stars of the catalogs, instead of downloading them already indexed;
- `--pack-catalogs <directory>` writes the compressed parts of the installed catalogs, and their manifest.

## Testing a build before releasing

The `Build` workflow runs for each push to `main`, each pull request, and on demand. It builds and tests the
native executable on every platform, and uploads the archives as artifacts of the run, named
`native-<platform>` (`native-windows-x86_64`, `native-osx-aarch_64`…), which are kept 90 days. Nothing is
released. To test the Windows archive of the last build of `main`:

```
gh run download --repo asterion-solver/asterion -n native-windows-x86_64 \
  $(gh run list --repo asterion-solver/asterion --workflow Build --branch main --limit 1 --json databaseId --jq '.[0].databaseId')
```

To build a branch without merging it: `gh workflow run Build --repo asterion-solver/asterion --ref <branch>`,
then download its artifacts the same way. In a browser, the artifacts are at the bottom of the page of the
run, in the Actions tab: GitHub wraps them in another zip archive.

## Releasing

Releases are made with the `Release` GitHub workflow, started by hand with the version to release
(for example `1.0.0`). It builds and tests the native executable on Linux (x86-64 and ARM64), macOS
(Intel and Apple Silicon) and Windows, then [JReleaser](https://jreleaser.org) tags the current commit
and publishes a GitHub release with one zip per platform, their SHA-256 checksums and a changelog
built from the commit messages (the `conventional-commits` preset groups `feat:`, `fix:`... commits).

The version of `gradle.properties` is always a snapshot: the released version is given to the build with
`-Pversion`. Once the release is published, the workflow sets it to the next development version and
pushes that commit, as `github-actions[bot]`: `0.1.1-SNAPSHOT` after `0.1.0`, `1.0.0-SNAPSHOT` after
`1.0.0-rc.1`. A higher version which was set by hand, when the next release adds features for example,
is kept. The name of the executable and of the archives is the `executableName` property of
`gradle.properties`.

To check the release configuration locally, put archives named like the ones of the workflow in an
`artifacts` directory and run:

```
JRELEASER_GITHUB_TOKEN=dummy ./gradlew jreleaserFullRelease --dryrun -Pversion=1.0.0
```
