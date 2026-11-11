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

namespace Asterion.Nina.Core;

/// <summary>
/// The solver which the solves of the plugin share: it's opened by the first solve, with the catalogs of the directory
/// it's given, and stays open, the catalogs mapped, which keeps the next solves fast. It's opened again when the
/// directory changes, and once catalogs are installed or removed: Windows can't delete the file of a catalog which is
/// mapped.
/// </summary>
public sealed class SharedSolver(AsterionLibrary library) : IDisposable {
    private readonly Lock sync = new();
    private AsterionSolver? solver;
    private string? directory;

    public AsterionLibrary Library => library;

    /// <summary>Solves an image with the catalogs of a directory, see <see cref="AsterionSolver.Solve"/>.</summary>
    /// <param name="catalogDirectory">the directory of the catalogs, or null for the default one</param>
    /// <exception cref="NoCatalogException">if no catalog is installed</exception>
    public AsterionSolution Solve(string? catalogDirectory, ReadOnlySpan<ushort> pixels, int width, int height, bool bayer,
                                  SolveOptions options, CancellationToken cancellation) {
        try {
            return Solver(catalogDirectory).Solve(pixels, width, height, bayer, options, cancellation);
        } catch (ObjectDisposedException) {
            // closed by another thread meanwhile, such as one which installs a catalog
            return Solver(catalogDirectory).Solve(pixels, width, height, bayer, options, cancellation);
        }
    }

    /// <summary>The solver of a directory, opened if it isn't.</summary>
    private AsterionSolver Solver(string? catalogDirectory) {
        lock (sync) {
            if (solver == null || directory != catalogDirectory) {
                solver?.Dispose();
                solver = null;
                solver = library.Open(catalogDirectory);
                directory = catalogDirectory;
            }
            return solver;
        }
    }

    /// <summary>
    /// Installs a catalog, see <see cref="AsterionLibrary.InstallCatalog"/>, once the solver which may map it is closed,
    /// as the library requires: the next solve opens the catalogs again, with the new one.
    /// </summary>
    public void InstallCatalog(string name, string? catalogDirectory, IProgress<DownloadProgress>? progress, CancellationToken cancellation) {
        Close();
        try {
            library.InstallCatalog(name, catalogDirectory, progress, cancellation);
        } finally {
            // a solve may have opened the catalogs meanwhile, without the new one
            Close();
        }
    }

    /// <summary>Removes the file of a catalog, once the solver which maps it is closed.</summary>
    /// <exception cref="IOException">if the file can't be deleted</exception>
    /// <exception cref="UnauthorizedAccessException">if the file can't be deleted</exception>
    public void RemoveCatalog(string file) {
        lock (sync) {
            CloseSolver();
            File.Delete(file);
        }
    }

    /// <summary>Closes the solver, once the solves which run are over: the next solve opens it again.</summary>
    public void Close() {
        lock (sync) {
            CloseSolver();
        }
    }

    private void CloseSolver() {
        solver?.Dispose();
        solver = null;
    }

    public void Dispose() => Close();
}
