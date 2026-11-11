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

using System.Runtime.InteropServices;

namespace Asterion.Nina.Core;

/// <summary>
/// The native library of Asterion, libasterion, loaded in the process: it lists and installs catalogs, and opens the
/// solvers which solve the images N.I.N.A. has in memory. The library runs in a GraalVM isolate, created once for the
/// process; each call attaches the calling thread to it, unless it is already attached.
/// </summary>
public sealed unsafe class AsterionLibrary {
    private static readonly Lock LoadLock = new();
    private static readonly Dictionary<string, AsterionLibrary> Loaded = new(StringComparer.OrdinalIgnoreCase);
    // the libraries which can't be used, once loaded: loading them again would only create another isolate
    private static readonly Dictionary<string, AsterionException> Unusable = new(StringComparer.OrdinalIgnoreCase);

    private readonly NativeFunctions functions;
    private readonly nint isolate;

    private AsterionLibrary(string file, NativeFunctions functions, nint isolate) {
        File = file;
        this.functions = functions;
        this.isolate = isolate;
    }

    /// <summary>The file of the library.</summary>
    public string File { get; }

    /// <summary>The version of the library, such as "0.5.0".</summary>
    public string Version => Call(thread => NativeFunctions.Copy((buffer, size) => functions.Version(thread, (byte*) buffer, size))
                                            ?? throw Failure(thread, "The version of the library is unknown"));

    /// <summary>The directory of the catalogs by default, which the other tools of Asterion share.</summary>
    public string DefaultCatalogDirectory => Call(thread =>
        NativeFunctions.Copy((buffer, size) => functions.DefaultCatalogDirectory(thread, (byte*) buffer, size))
        ?? throw Failure(thread, "The directory of the catalogs is unknown"));

    /// <summary>
    /// Loads the library, the first time it's asked for in the process, and creates its isolate: the next times, the
    /// same library is given.
    /// </summary>
    /// <exception cref="AsterionException">if the library can't be loaded, or isn't the one the plugin was built for</exception>
    public static AsterionLibrary Load(string file) {
        file = Path.GetFullPath(file);
        lock (LoadLock) {
            if (Loaded.TryGetValue(file, out var loaded)) {
                return loaded;
            }
            if (Unusable.TryGetValue(file, out var unusable)) {
                throw unusable;
            }
            if (!System.IO.File.Exists(file)) {
                throw new AsterionException($"Asterion Solver wasn't found at {file}: reinstall the plugin.");
            }
            nint handle;
            try {
                handle = NativeLibrary.Load(file);
            } catch (DllNotFoundException e) {
                throw new AsterionException(OperatingSystem.IsWindows()
                    ? $"Asterion Solver couldn't be loaded from {file}: a DLL is missing. Install the Visual C++ Redistributable (x64) from Microsoft."
                    : $"Asterion Solver couldn't be loaded from {file}: {e.Message}", e);
            } catch (BadImageFormatException e) {
                throw new AsterionException($"Asterion Solver couldn't be loaded from {file}: it isn't a library for this platform.", e);
            }
            NativeFunctions functions;
            try {
                functions = new NativeFunctions(handle);
            } catch (EntryPointNotFoundException e) {
                throw new AsterionException($"{file} isn't the library of Asterion Solver which the plugin needs: {e.Message}", e);
            }
            nint isolate, thread;
            if (functions.CreateIsolate(0, &isolate, &thread) != 0) {
                throw new AsterionException($"Asterion Solver couldn't start, from {file}.");
            }
            var library = new AsterionLibrary(file, functions, isolate);
            try {
                library.CheckStructures(thread);
            } catch (AsterionException e) {
                Unusable[file] = e;
                throw;
            } finally {
                functions.DetachThread(thread);
            }
            Loaded[file] = library;
            return library;
        }
    }

    /// <summary>The structures of the plugin must be the ones of the library, which reads and writes them.</summary>
    private void CheckStructures(nint thread) {
        var sizes = new (string Name, int Size)[] {
            ("asterion_options", sizeof(NativeOptions)), ("asterion_solution", sizeof(NativeSolution)),
            ("asterion_catalog", sizeof(NativeCatalog)), ("asterion_progress", sizeof(NativeProgress))
        };
        foreach (var (name, size) in sizes) {
            fixed (byte* text = NativeFunctions.Utf8(name)) {
                if (functions.SizeOf(thread, text) != size) {
                    throw new AsterionException($"{File} isn't the library of Asterion Solver which the plugin needs: {name} differs.");
                }
            }
        }
    }

    /// <summary>
    /// The catalogs which can be installed, then the other ones which are installed in a directory.
    /// </summary>
    /// <param name="directory">the directory of the catalogs, or null for the default one</param>
    /// <exception cref="AsterionException">if the catalogs can't be listed</exception>
    public CatalogList Catalogs(string? directory) => Call(thread => {
        fixed (byte* path = NativeFunctions.Utf8(directory)) {
            var count = functions.Catalogs(thread, path, null, 0);
            if (count < 0) {
                throw Failure(thread, "The catalogs can't be listed");
            }
            var native = new NativeCatalog[count];
            fixed (NativeCatalog* first = native) {
                count = Math.Min(count, functions.Catalogs(thread, path, first, native.Length));
            }
            if (count < 0) {
                throw Failure(thread, "The catalogs can't be listed");
            }
            var catalogs = new List<Catalog>(count);
            for (var i = 0; i < count; i++) {
                fixed (NativeCatalog* c = &native[i]) {
                    catalogs.Add(new Catalog(NativeFunctions.Text(c->Name, 64), NativeFunctions.Text(c->Description, 256), c->Installed != 0,
                        c->Size, NativeFunctions.Text(c->Error, 256), c->Available != 0));
                }
            }
            return new CatalogList(directory ?? DefaultCatalogDirectory, catalogs);
        }
    });

    /// <summary>
    /// Installs a catalog, which takes from seconds to many minutes, on the calling thread: a download which is
    /// cancelled resumes where it stopped the next time.
    /// </summary>
    /// <param name="directory">the directory of the catalogs, or null for the default one</param>
    /// <param name="progress">receives the progress of the download, on the calling thread, or null</param>
    /// <exception cref="AsterionException">if the catalog can't be installed</exception>
    /// <exception cref="OperationCanceledException">if the installation is cancelled</exception>
    public void InstallCatalog(string name, string? directory, IProgress<DownloadProgress>? progress, CancellationToken cancellation) {
        using var attachment = Attach();
        var installation = new Installation(progress);
        var handle = GCHandle.Alloc(installation);
        // the flag which cancels the installation, which the token sets on the thread which cancels: the library reads
        // it all along, while the progress, which can cancel too, may not come for minutes
        var cancel = (int*) NativeMemory.AllocZeroed(sizeof(int));
        try {
            int status;
            // disposing the registration waits for its callback, if it runs: the flag is freed afterwards
            using (cancellation.Register(() => Interlocked.Exchange(ref *cancel, 1))) {
                fixed (byte* path = NativeFunctions.Utf8(directory), catalog = NativeFunctions.Utf8(name)) {
                    status = functions.InstallCatalogCancellable(attachment.Thread, path, catalog, &Installation.Report,
                        GCHandle.ToIntPtr(handle), cancel);
                }
            }
            if (status == NativeFunctions.Cancelled) {
                if (installation.Failure is { } failure) {
                    throw failure;
                }
                cancellation.ThrowIfCancellationRequested();
                throw new OperationCanceledException();
            }
            if (status != 0) {
                throw Failure(attachment.Thread, $"The download of {name} failed");
            }
        } finally {
            handle.Free();
            NativeMemory.Free(cancel);
        }
    }

    /// <summary>What an installation reports to, which the callback of the library finds by its handle.</summary>
    private sealed class Installation(IProgress<DownloadProgress>? progress) {
        private IProgress<DownloadProgress>? Progress { get; } = progress;

        /// <summary>An exception of the progress, which cancelled the installation, and is thrown once it stopped.</summary>
        public Exception? Failure { get; private set; }

        [UnmanagedCallersOnly]
        public static int Report(NativeProgress* progress, nint userData) {
            var installation = (Installation) GCHandle.FromIntPtr(userData).Target!;
            try {
                if (DownloadProgress.Of(progress) is { } reported) {
                    installation.Progress?.Report(reported);
                }
                return 0;
            } catch (Exception e) {
                // an exception can't cross the library: it cancels, and is thrown afterwards
                installation.Failure = e;
                return 1;
            }
        }
    }

    /// <summary>Opens a solver, with the catalogs of a directory, which stay open until it's disposed.</summary>
    /// <param name="directory">the directory of the catalogs, or null for the default one</param>
    /// <exception cref="NoCatalogException">if no catalog is installed in the directory</exception>
    /// <exception cref="AsterionException">if the catalogs can't be opened</exception>
    public AsterionSolver Open(string? directory) => Call(thread => {
        nint solver;
        fixed (byte* path = NativeFunctions.Utf8(directory)) {
            if (functions.Open(thread, path, &solver) != 0) {
                var message = Message(thread);
                throw message.StartsWith("No catalog is installed", StringComparison.Ordinal)
                    ? new NoCatalogException(message)
                    : new AsterionException(message);
            }
        }
        return new AsterionSolver(this, functions, solver);
    });

    /// <summary>
    /// Attaches the calling thread to the isolate, unless it is already, as during the progress of an installation:
    /// disposing the attachment detaches it then.
    /// </summary>
    internal Attachment Attach() {
        var thread = functions.CurrentThread(isolate);
        if (thread != 0) {
            return new Attachment(functions, thread, false);
        }
        if (functions.AttachThread(isolate, &thread) != 0) {
            throw new AsterionException("Asterion Solver couldn't be called from this thread.");
        }
        return new Attachment(functions, thread, true);
    }

    /// <summary>The thread of the isolate of the calling thread, while it calls the library.</summary>
    internal readonly struct Attachment(NativeFunctions functions, nint thread, bool attached) : IDisposable {
        public nint Thread => thread;

        public void Dispose() {
            if (attached) {
                functions.DetachThread(thread);
            }
        }
    }

    /// <summary>Calls the library on the calling thread, attached to the isolate for the call.</summary>
    private T Call<T>(Func<nint, T> call) {
        using var attachment = Attach();
        return call(attachment.Thread);
    }

    /// <summary>The message of the last error of a thread.</summary>
    internal string Message(nint thread) =>
        NativeFunctions.Copy((buffer, size) => functions.LastError(thread, (byte*) buffer, size)) is { Length: > 0 } message
            ? message
            : "Asterion Solver failed.";

    /// <summary>The last error of a thread, after what failed.</summary>
    internal AsterionException Failure(nint thread, string what) => new($"{what}: {Message(thread)}");
}
