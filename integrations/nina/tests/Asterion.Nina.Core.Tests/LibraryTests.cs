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

using System.IO.Compression;
using StbImageSharp;
using Xunit;

namespace Asterion.Nina.Core.Tests;

/// <summary>
/// The native library of Asterion, from ASTERION_LIBRARY, the path of libasterion: the tests are skipped without it.
/// They solve a test image of asterion-core with its small index.
/// </summary>
public class LibraryTests {
    private static readonly string? TestedLibrary = Environment.GetEnvironmentVariable("ASTERION_LIBRARY") is { Length: > 0 } path
                                                    && File.Exists(path) ? path : null;
    private static readonly Lazy<Image> Draco = new(() => Image.Read(Path.Combine(TestImages(), "sharpcap-draco.png")));

    /// <summary>A temporary directory, deleted when disposed.</summary>
    private sealed class TemporaryDirectory : IDisposable {
        public string Path { get; } = Directory.CreateTempSubdirectory("asterion-nina-").FullName;

        public void Dispose() => Directory.Delete(Path, true);
    }

    /// <summary>The pixels of an image, as Asterion reads a color image: the sum of its channels.</summary>
    private sealed record Image(ushort[] Pixels, int Width, int Height) {
        public static Image Read(string file) {
            using var stream = File.OpenRead(file);
            var decoded = ImageResult.FromStream(stream, ColorComponents.RedGreenBlue);
            var pixels = new ushort[decoded.Width * decoded.Height];
            for (var i = 0; i < pixels.Length; i++) {
                pixels[i] = (ushort) (decoded.Data[3 * i] + decoded.Data[3 * i + 1] + decoded.Data[3 * i + 2]);
            }
            return new Image(pixels, decoded.Width, decoded.Height);
        }
    }

    /// <summary>The directory of the test images of asterion-core, with their small index.</summary>
    private static string TestImages() {
        var directory = new DirectoryInfo(AppContext.BaseDirectory);
        while (directory != null && !Directory.Exists(Path.Combine(directory.FullName, "asterion-core"))) {
            directory = directory.Parent;
        }
        Assert.NotNull(directory);
        return Path.Combine(directory.FullName, "asterion-core", "src", "test", "resources", "images");
    }

    /// <summary>A directory of catalogs with the small index of the test images, compressed in the repository.</summary>
    private static async Task<TemporaryDirectory> Catalogs() {
        var catalogs = new TemporaryDirectory();
        await using var compressed = File.OpenRead(Path.Combine(TestImages(), "sharpcap-index.astx.gz"));
        await using var gzip = new GZipStream(compressed, CompressionMode.Decompress);
        await using var index = File.Create(Path.Combine(catalogs.Path, "sharpcap.astx"));
        await gzip.CopyToAsync(index);
        return catalogs;
    }

    private static readonly SolveOptions Blind = new(null, null, 0, 60);

    [Fact]
    public void TellsWhenTheLibraryIsntThere() {
        var e = Assert.Throws<AsterionException>(() => AsterionLibrary.Load("/nowhere/libasterion.dll"));
        Assert.Contains("wasn't found", e.Message);
    }

    [Fact]
    public void TellsWhenTheFileIsntALibrary() {
        using var directory = new TemporaryDirectory();
        var file = Path.Combine(directory.Path, "libasterion.dll");
        File.WriteAllText(file, "not a library");
        var e = Assert.Throws<AsterionException>(() => AsterionLibrary.Load(file));
        Assert.Contains("couldn't be loaded", e.Message);
    }

    [Fact]
    public void LoadsTheLibraryOnce() {
        if (TestedLibrary == null) {
            return;
        }
        var library = AsterionLibrary.Load(TestedLibrary);
        Assert.Same(library, AsterionLibrary.Load(TestedLibrary));
        Assert.Matches(@"^\d+\.\d+\.\d+", library.Version);
        Assert.True(Path.IsPathRooted(library.DefaultCatalogDirectory));
    }

    [Fact]
    public void ListsTheCatalogs() {
        if (TestedLibrary == null) {
            return;
        }
        using var catalogs = new TemporaryDirectory();
        var list = AsterionLibrary.Load(TestedLibrary).Catalogs(catalogs.Path);
        Assert.Equal(catalogs.Path, list.Directory);
        Assert.Contains(list.Catalogs, c => c is { Name: "gaia-500", Installed: false, Size: 0, Published: true } && c.Description.Length > 0);
    }

    [Fact]
    public async Task ListsTheCatalogsWhichAreInstalled() {
        if (TestedLibrary == null) {
            return;
        }
        using var catalogs = await Catalogs();
        var list = AsterionLibrary.Load(TestedLibrary).Catalogs(catalogs.Path);
        // a catalog which was built by hand, after the ones which can be installed, and isn't published
        Assert.Equal(new Catalog("sharpcap", "", true, new FileInfo(list.IndexFile("sharpcap")).Length, Published: false), list.Catalogs[^1]);
    }

    [Fact]
    public void TellsWhyAnInstallationFails() {
        if (TestedLibrary == null) {
            return;
        }
        using var catalogs = new TemporaryDirectory();
        var e = Assert.Throws<AsterionException>(() =>
            AsterionLibrary.Load(TestedLibrary).InstallCatalog("nothing", catalogs.Path, null, CancellationToken.None));
        Assert.StartsWith("The download of nothing failed: Unknown catalog nothing", e.Message);
    }

    [Fact]
    public void AnInstallationWhichIsCancelledDownloadsNothing() {
        if (TestedLibrary == null) {
            return;
        }
        using var catalogs = new TemporaryDirectory();
        Assert.ThrowsAny<OperationCanceledException>(() =>
            AsterionLibrary.Load(TestedLibrary).InstallCatalog("gaia-1500", catalogs.Path, null, new CancellationToken(true)));
        Assert.Empty(Directory.EnumerateFileSystemEntries(catalogs.Path));
    }

    [Fact]
    public void TellsWhenNoCatalogIsInstalled() {
        if (TestedLibrary == null) {
            return;
        }
        using var catalogs = new TemporaryDirectory();
        Assert.Throws<NoCatalogException>(() => AsterionLibrary.Load(TestedLibrary).Open(catalogs.Path));
    }

    [Fact]
    public async Task SolvesTheImagesOfMemory() {
        if (TestedLibrary == null) {
            return;
        }
        using var catalogs = await Catalogs();
        using var solver = AsterionLibrary.Load(TestedLibrary).Open(catalogs.Path);
        var image = Draco.Value;
        var blind = solver.Solve(image.Pixels, image.Width, image.Height, false, Blind, CancellationToken.None);
        Assert.True(blind.Solved);
        Assert.Equal(270.305, blind.Wcs!.Crval1, 0.01);
        Assert.Equal(66.730, blind.Wcs.Crval2, 0.01);
        // the scale of the image, 0.58"/px
        Assert.Equal(0.58 / 3600, Math.Sqrt(blind.Wcs.Cd12 * blind.Wcs.Cd12 + blind.Wcs.Cd22 * blind.Wcs.Cd22), 0.01 / 3600);
        Assert.True(blind.MatchedStars >= 10);
        Assert.True(blind.TotalMillis > 0);

        // the region of the primary solver: the one of the image solves, another one doesn't
        Assert.True(solver.Solve(image.Pixels, image.Width, image.Height, false, new SolveOptions(270.3, 66.7, 2, 60), CancellationToken.None).Solved);
        Assert.False(solver.Solve(image.Pixels, image.Width, image.Height, false, new SolveOptions(90, -30, 5, 60), CancellationToken.None).Solved);
    }

    [Fact]
    public async Task StopsWhenCancelled() {
        if (TestedLibrary == null) {
            return;
        }
        using var catalogs = await Catalogs();
        using var solver = AsterionLibrary.Load(TestedLibrary).Open(catalogs.Path);
        var image = Draco.Value;
        Assert.ThrowsAny<OperationCanceledException>(() =>
            solver.Solve(image.Pixels, image.Width, image.Height, false, Blind, new CancellationToken(true)));
    }

    [Fact]
    public async Task RefusesWhatIsntAnImageOrASolver() {
        if (TestedLibrary == null) {
            return;
        }
        using var catalogs = await Catalogs();
        var solver = AsterionLibrary.Load(TestedLibrary).Open(catalogs.Path);
        Assert.Throws<ArgumentException>(() => solver.Solve(new ushort[10], 4, 4, false, Blind, CancellationToken.None));
        solver.Dispose();
        solver.Dispose();
        var image = Draco.Value;
        Assert.Throws<ObjectDisposedException>(() => solver.Solve(image.Pixels, image.Width, image.Height, false, Blind, CancellationToken.None));
    }

    [Fact]
    public async Task SharesASolverWhichFollowsTheCatalogs() {
        if (TestedLibrary == null) {
            return;
        }
        using var catalogs = await Catalogs();
        using var empty = new TemporaryDirectory();
        using var shared = new SharedSolver(AsterionLibrary.Load(TestedLibrary));
        var image = Draco.Value;
        AsterionSolution Solve(string directory) =>
            shared.Solve(directory, image.Pixels, image.Width, image.Height, false, Blind, CancellationToken.None);

        // several threads at once, with the solver of the directory, opened again for another directory
        var solutions = await Task.WhenAll(Enumerable.Range(0, 4).Select(_ => Task.Run(() => Solve(catalogs.Path))));
        Assert.All(solutions, s => Assert.True(s.Solved));
        Assert.Throws<NoCatalogException>(() => Solve(empty.Path));
        Assert.True(Solve(catalogs.Path).Solved);

        // a catalog is removed while the solver maps it: the solver is closed first
        var index = Path.Combine(catalogs.Path, "sharpcap.astx");
        shared.RemoveCatalog(index);
        Assert.False(File.Exists(index));
        Assert.Throws<NoCatalogException>(() => Solve(catalogs.Path));
    }
}
