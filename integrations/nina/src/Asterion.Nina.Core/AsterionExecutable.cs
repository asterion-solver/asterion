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
using System.Text;

namespace Asterion.Nina.Core;

/// <summary>
/// The asterion executable, which the plugin runs for everything: solving images, and listing and
/// downloading catalogs. Its path may be wrong: running it tells why with an <see cref="AsterionException"/>.
/// </summary>
/// <param name="path">the path of the executable</param>
/// <param name="libraryDirectories">see <see cref="LibraryDirectories"/></param>
public sealed class AsterionExecutable(string path, IReadOnlyList<string>? libraryDirectories = null) {
    private static readonly string FileName = OperatingSystem.IsWindows() ? "asterion.exe" : "asterion";

    // the exit code of a Windows process which misses a DLL, such as the Visual C++ runtime
    private const int DllNotFound = unchecked((int) 0xC0000135);

    public string Path { get; } = path;

    /// <summary>
    /// The directories added to the PATH of asterion, where it also looks for the DLLs it needs. On
    /// Windows, it needs the Visual C++ runtime, which N.I.N.A. has in its own directory.
    /// </summary>
    public IReadOnlyList<string> LibraryDirectories { get; } = libraryDirectories ?? [];

    /// <summary>
    /// The executable to run: the one which is configured, or else the one included in the plugin,
    /// or else the one found on the PATH.
    /// </summary>
    /// <param name="configured">the path set by the user, or null or empty</param>
    /// <param name="pluginDirectory">the directory of the plugin, where its own asterion executable is, or null</param>
    /// <param name="libraryDirectories">see <see cref="LibraryDirectories"/></param>
    /// <exception cref="AsterionException">if there is no executable at all</exception>
    public static AsterionExecutable Resolve(string? configured, string? pluginDirectory, IReadOnlyList<string>? libraryDirectories = null) {
        if (!string.IsNullOrWhiteSpace(configured)) {
            var path = Environment.ExpandEnvironmentVariables(configured.Trim());
            if (File.Exists(path)) {
                return new AsterionExecutable(path, libraryDirectories);
            }
            throw new AsterionException(
                $"Asterion Solver wasn't found at {path}: correct its path in the advanced settings of the plugin, or clear it to use the one included in the plugin.");
        }
        var candidates = (Environment.GetEnvironmentVariable("PATH") ?? "").Split(System.IO.Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries)
            .Prepend(pluginDirectory)
            .OfType<string>()
            .Select(directory => System.IO.Path.Combine(directory, FileName));
        var found = candidates.FirstOrDefault(File.Exists)
            ?? throw new AsterionException("Asterion Solver wasn't found: reinstall the plugin, or set the path of asterion.exe in its advanced settings.");
        return new AsterionExecutable(found, libraryDirectories);
    }

    /// <summary>The arguments which select the directory of the catalogs, unless it is the default one.</summary>
    internal static IEnumerable<string> CatalogDirectoryArguments(string? catalogDirectory) =>
        string.IsNullOrWhiteSpace(catalogDirectory) ? [] : ["--catalog-dir", Environment.ExpandEnvironmentVariables(catalogDirectory.Trim())];

    /// <summary>The version of asterion, such as "Asterion Solver 0.2.0".</summary>
    /// <exception cref="AsterionException">if it doesn't run, or isn't Asterion Solver</exception>
    public async Task<string> VersionAsync(CancellationToken cancellation) {
        var (exitCode, output, errors) = await RunAsync(["--version"], cancellation, TimeSpan.FromSeconds(10)).ConfigureAwait(false);
        var version = output.Trim();
        if (exitCode != 0 || !version.StartsWith("Asterion Solver", StringComparison.Ordinal)) {
            var printed = (version.Length > 0 ? version : errors.Trim()).Split('\n')[0];
            throw new AsterionException($"{Path} isn't Asterion Solver: it printed \"{printed}\".");
        }
        return version;
    }

    /// <summary>Solves an image.</summary>
    /// <returns>the solution, which tells whether the image is solved</returns>
    /// <exception cref="AsterionException">if asterion fails, rather than finding no solution</exception>
    /// <exception cref="NoCatalogException">if no catalog is installed</exception>
    public async Task<AsterionSolution> SolveAsync(SolveRequest request, CancellationToken cancellation) {
        var (exitCode, output, errors) = await RunAsync(request.Arguments(), cancellation).ConfigureAwait(false);
        // 0: solved, 1: not solved, 2: invalid options or errors, such as no catalog installed
        if (exitCode == 2 || output.Trim().Length == 0) {
            var message = Failure("Asterion Solver failed", exitCode, errors);
            throw errors.Contains("No catalog is installed", StringComparison.Ordinal) ? new NoCatalogException(message) : new AsterionException(message);
        }
        return AsterionSolution.Parse(output);
    }

    /// <summary>The catalogs which are installed, and the ones which can be downloaded.</summary>
    /// <param name="catalogDirectory">the directory of the catalogs, or null for the default one</param>
    /// <exception cref="AsterionException">if asterion fails</exception>
    public async Task<CatalogList> ListCatalogsAsync(string? catalogDirectory, CancellationToken cancellation) {
        var (exitCode, output, errors) = await RunAsync(["--list-catalogs", "--json", .. CatalogDirectoryArguments(catalogDirectory)],
            cancellation, TimeSpan.FromSeconds(30)).ConfigureAwait(false);
        if (exitCode != 0) {
            throw new AsterionException(Failure("Asterion Solver couldn't list the catalogs", exitCode, errors));
        }
        return CatalogList.Parse(output);
    }

    /// <summary>
    /// Downloads and installs a catalog, which takes from seconds to many minutes. A download which
    /// is cancelled resumes where it stopped the next time.
    /// </summary>
    /// <param name="catalogDirectory">the directory of the catalogs, or null for the default one</param>
    /// <param name="progress">receives the progress of the download, or null</param>
    /// <exception cref="AsterionException">if asterion fails</exception>
    /// <exception cref="OperationCanceledException">if the download is cancelled, after asterion is stopped</exception>
    public async Task DownloadCatalogAsync(string name, string? catalogDirectory, IProgress<DownloadProgress>? progress,
                                           CancellationToken cancellation) {
        var (exitCode, _, errors) = await RunAsync(["--download-catalog", name, "--json", .. CatalogDirectoryArguments(catalogDirectory)],
            cancellation, outputLine: line => {
                if (DownloadProgress.Read(line) is { } read) {
                    progress?.Report(read);
                }
            }).ConfigureAwait(false);
        if (exitCode != 0) {
            throw new AsterionException(Failure($"The download of {name} failed", exitCode, errors));
        }
    }

    /// <summary>Runs asterion and waits for it.</summary>
    /// <param name="timeout">the time after which asterion is stopped, or null to wait for it as long as it runs</param>
    /// <param name="outputLine">receives each line of the standard output as soon as it is printed, or null</param>
    /// <returns>its exit code, and what it printed on its standard output and on its standard error</returns>
    /// <exception cref="AsterionException">if it can't be started, or doesn't finish in time</exception>
    /// <exception cref="OperationCanceledException">if it is cancelled, after asterion is stopped</exception>
    public async Task<(int ExitCode, string Output, string Errors)> RunAsync(IReadOnlyList<string> arguments, CancellationToken cancellation,
                                                                             TimeSpan? timeout = null, Action<string>? outputLine = null) {
        using var process = new Process { StartInfo = StartInfo(arguments) };
        try {
            process.Start();
        } catch (Exception e) when (e is System.ComponentModel.Win32Exception or InvalidOperationException) {
            throw new AsterionException($"Asterion Solver couldn't be started from {Path}: {e.Message}", e);
        }
        var output = ReadLinesAsync(process.StandardOutput, outputLine);
        var errors = ReadLinesAsync(process.StandardError, null);
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        if (timeout is { } delay) {
            deadline.CancelAfter(delay);
        }
        try {
            await process.WaitForExitAsync(deadline.Token).ConfigureAwait(false);
        } catch (OperationCanceledException) {
            try {
                process.Kill(entireProcessTree: true);
            } catch (InvalidOperationException) {
                // it has just exited
            }
            cancellation.ThrowIfCancellationRequested();
            throw new AsterionException($"Asterion Solver doesn't answer: {Path} was stopped after {timeout?.TotalSeconds} seconds.");
        }
        if (process.ExitCode == DllNotFound && OperatingSystem.IsWindows()) {
            throw new AsterionException(
                $"Asterion Solver couldn't be started from {Path}: a DLL is missing. Install the Visual C++ Redistributable (x64) from Microsoft.");
        }
        return (process.ExitCode, await output.ConfigureAwait(false), await errors.ConfigureAwait(false));
    }

    private ProcessStartInfo StartInfo(IReadOnlyList<string> arguments) {
        var startInfo = new ProcessStartInfo(Path) {
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
        };
        foreach (var argument in arguments) {
            startInfo.ArgumentList.Add(argument);
        }
        if (LibraryDirectories.Count > 0) {
            startInfo.Environment.TryGetValue("PATH", out var current);
            startInfo.Environment["PATH"] = string.Join(System.IO.Path.PathSeparator, LibraryDirectories.Prepend(current).OfType<string>()
                .Where(directory => directory.Length > 0));
        }
        return startInfo;
    }

    private static async Task<string> ReadLinesAsync(StreamReader reader, Action<string>? lineRead) {
        var text = new StringBuilder();
        while (await reader.ReadLineAsync().ConfigureAwait(false) is { } line) {
            text.Append(line).Append('\n');
            lineRead?.Invoke(line);
        }
        return text.ToString();
    }

    /// <summary>A message which tells why asterion failed: the last line it printed on its standard error, which tells why.</summary>
    private static string Failure(string what, int exitCode, string errors) {
        var lines = errors.Split('\n', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries);
        if (lines.Length == 0) {
            return $"{what}, with exit code {exitCode}.";
        }
        const string prefix = "Error: ";
        var last = lines[^1];
        return $"{what}: {(last.StartsWith(prefix, StringComparison.Ordinal) ? last[prefix.Length..] : last)}";
    }
}
