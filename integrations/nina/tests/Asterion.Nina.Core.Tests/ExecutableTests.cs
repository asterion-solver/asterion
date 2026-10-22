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

using System.Diagnostics;
using System.IO.Compression;
using System.Runtime.Versioning;
using Xunit;

namespace Asterion.Nina.Core.Tests;

/// <summary>
/// Running asterion. Some tests run small shell scripts, on Linux and macOS only. The others run
/// the asterion executable of ASTERION_EXECUTABLE, a native executable or the one of a
/// distribution, and are skipped without it.
/// </summary>
public class ExecutableTests {
    private static readonly string? TestedExecutable = Environment.GetEnvironmentVariable("ASTERION_EXECUTABLE") is { Length: > 0 } path
                                                       && File.Exists(path) ? path : null;

    /// <summary>A temporary directory, deleted when disposed.</summary>
    private sealed class TemporaryDirectory : IDisposable {
        public string Path { get; } = Directory.CreateTempSubdirectory("asterion-nina-").FullName;

        public void Dispose() => Directory.Delete(Path, true);
    }

    /// <summary>A shell script which plays the role of asterion.</summary>
    [UnsupportedOSPlatform("windows")]
    private static string Script(TemporaryDirectory directory, string commands) {
        var script = Path.Combine(directory.Path, "asterion");
        File.WriteAllText(script, "#!/bin/sh\n" + commands.Replace("\r", ""));
        File.SetUnixFileMode(script, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        return script;
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

    /// <summary>A progress which reports on the thread of the work, unlike <see cref="Progress{T}"/>.</summary>
    private sealed class SynchronousProgress(Action<DownloadProgress> report) : IProgress<DownloadProgress> {
        public void Report(DownloadProgress value) => report(value);
    }

    [Fact]
    public void TellsWhenTheConfiguredExecutableDoesntExist() {
        var e = Assert.Throws<AsterionException>(() => AsterionExecutable.Resolve("/nowhere/asterion.exe", null));
        Assert.Contains("/nowhere/asterion.exe", e.Message);
    }

    [Fact]
    public void UsesTheExecutableIncludedInThePlugin() {
        using var plugin = new TemporaryDirectory();
        var included = Path.Combine(plugin.Path, OperatingSystem.IsWindows() ? "asterion.exe" : "asterion");
        File.WriteAllText(included, "");
        Assert.Equal(included, AsterionExecutable.Resolve(null, plugin.Path).Path);
        Assert.Equal(included, AsterionExecutable.Resolve("  ", plugin.Path).Path);
        Assert.Equal(["/nina"], AsterionExecutable.Resolve(null, plugin.Path, ["/nina"]).LibraryDirectories);
    }

    [Fact]
    public async Task AddsTheLibraryDirectoriesToThePath() {
        if (OperatingSystem.IsWindows()) {
            return;
        }
        var (exitCode, output, _) = await new AsterionExecutable("/bin/sh", ["/nina"]).RunAsync(["-c", "echo $PATH"], CancellationToken.None);
        Assert.Equal(0, exitCode);
        Assert.EndsWith(":/nina", output.Trim());
    }

    [Fact]
    public async Task TellsWhenTheExecutableIsntAsterion() {
        if (OperatingSystem.IsWindows()) {
            return;
        }
        var e = await Assert.ThrowsAsync<AsterionException>(() => new AsterionExecutable("/bin/echo").VersionAsync(CancellationToken.None));
        Assert.Contains("isn't Asterion Solver", e.Message);
    }

    [Fact]
    public async Task StopsWhenCancelled() {
        if (OperatingSystem.IsWindows()) {
            return;
        }
        using var cancellation = new CancellationTokenSource(TimeSpan.FromMilliseconds(200));
        var started = Stopwatch.StartNew();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() =>
            new AsterionExecutable("/bin/sh").RunAsync(["-c", "sleep 30"], cancellation.Token));
        Assert.True(started.Elapsed < TimeSpan.FromSeconds(10));
    }

    [Fact]
    public async Task StopsWhenItDoesntAnswerInTime() {
        if (OperatingSystem.IsWindows()) {
            return;
        }
        var e = await Assert.ThrowsAsync<AsterionException>(() =>
            new AsterionExecutable("/bin/sh").RunAsync(["-c", "sleep 30"], CancellationToken.None, TimeSpan.FromMilliseconds(200)));
        Assert.Contains("doesn't answer", e.Message);
    }

    [Fact]
    public async Task ReportsTheProgressOfADownloadAsItGoes() {
        if (OperatingSystem.IsWindows()) {
            return;
        }
        using var directory = new TemporaryDirectory();
        var asterion = Script(directory, """
            echo '{"event":"step","step":"Downloading tycho2 (280 MB)","total":281,"unit":"MB"}'
            echo '{"event":"progress","step":"Downloading tycho2 (280 MB)","completed":281,"total":281,"unit":"MB","fraction":1.0}'
            echo '{"event":"installed","catalog":"tycho2","file":"tycho2.astx","size":378244396}'
            """);
        var reported = new List<DownloadProgress>();
        await new AsterionExecutable(asterion).DownloadCatalogAsync("tycho2", null, new SynchronousProgress(reported.Add), CancellationToken.None);
        Assert.Equal([
            new DownloadProgress("Downloading tycho2 (280 MB)", null),
            new DownloadProgress("Downloading tycho2 (280 MB): 281/281 MB", 1)
        ], reported);
    }

    [Fact]
    public async Task TellsWhySolvingFails() {
        if (OperatingSystem.IsWindows()) {
            return;
        }
        using var directory = new TemporaryDirectory();
        var asterion = Script(directory, """
            echo 'Reading image.fits' >&2
            echo 'Error: image.fits is not an image' >&2
            exit 2
            """);
        var e = await Assert.ThrowsAsync<AsterionException>(() =>
            new AsterionExecutable(asterion).SolveAsync(new SolveRequest("image.fits", null, null, 0, 30), CancellationToken.None));
        Assert.Equal("Asterion Solver failed: image.fits is not an image", e.Message);
    }

    [Fact]
    public async Task ListsTheCatalogs() {
        if (TestedExecutable == null) {
            return;
        }
        using var catalogs = new TemporaryDirectory();
        var list = await new AsterionExecutable(TestedExecutable).ListCatalogsAsync(catalogs.Path, CancellationToken.None);
        Assert.Equal(catalogs.Path, list.Directory);
        Assert.Contains(list.Catalogs, c => c is { Name: "gaia-500", Installed: false } && c.Description.Length > 0);
    }

    [Fact]
    public async Task TellsWhenNoCatalogIsInstalled() {
        if (TestedExecutable == null) {
            return;
        }
        using var catalogs = new TemporaryDirectory();
        var request = new SolveRequest(Path.Combine(TestImages(), "sharpcap-draco.png"), null, null, 0, 30, catalogs.Path);
        await Assert.ThrowsAsync<NoCatalogException>(() => new AsterionExecutable(TestedExecutable).SolveAsync(request, CancellationToken.None));
    }

    [Fact]
    public async Task TellsWhyADownloadFails() {
        if (TestedExecutable == null) {
            return;
        }
        var e = await Assert.ThrowsAsync<AsterionException>(() =>
            new AsterionExecutable(TestedExecutable).DownloadCatalogAsync("nothing", null, null, CancellationToken.None));
        Assert.StartsWith("The download of nothing failed: Unknown catalog 'nothing'", e.Message);
    }

    [Fact]
    public async Task SolvesARealImage() {
        if (TestedExecutable == null) {
            return;
        }
        var images = TestImages();
        using var catalogs = new TemporaryDirectory();
        // the small index of the test images, compressed in the repository
        await using (var compressed = File.OpenRead(Path.Combine(images, "sharpcap-index.astx.gz")))
        await using (var gzip = new GZipStream(compressed, CompressionMode.Decompress))
        await using (var index = File.Create(Path.Combine(catalogs.Path, "sharpcap.astx"))) {
            await gzip.CopyToAsync(index);
        }
        var asterion = new AsterionExecutable(TestedExecutable);
        Assert.StartsWith("Asterion Solver", await asterion.VersionAsync(CancellationToken.None));

        var image = Path.Combine(images, "sharpcap-draco.png");
        var blind = await asterion.SolveAsync(new SolveRequest(image, null, null, 0, 60, catalogs.Path), CancellationToken.None);
        Assert.True(blind.Solved);
        Assert.Equal(270.305, blind.Wcs!.Crval1, 0.01);
        Assert.Equal(66.730, blind.Wcs.Crval2, 0.01);
        // the scale of the image, 0.58"/px
        Assert.Equal(0.58 / 3600, Math.Sqrt(blind.Wcs.Cd12 * blind.Wcs.Cd12 + blind.Wcs.Cd22 * blind.Wcs.Cd22), 0.01 / 3600);

        // a region which doesn't contain the image: the primary solver gives up
        var elsewhere = await asterion.SolveAsync(new SolveRequest(image, 90, -30, 5, 60, catalogs.Path), CancellationToken.None);
        Assert.False(elsewhere.Solved);
    }
}
