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
/// A solver, with the catalogs of a directory, which stay memory mapped until it's disposed: disposing it waits for the
/// solves which run, and releases the memory of its catalogs at once. Several threads can solve at once.
/// </summary>
public sealed unsafe class AsterionSolver : IDisposable {
    private readonly AsterionLibrary library;
    private readonly NativeFunctions functions;
    private readonly ReaderWriterLockSlim solving = new();
    private nint handle;

    internal AsterionSolver(AsterionLibrary library, NativeFunctions functions, nint handle) {
        this.library = library;
        this.functions = functions;
        this.handle = handle;
    }

    /// <summary>
    /// Solves an image on the calling thread, which waits for the solution: its pixels are copied as the solve starts,
    /// and can be changed as soon as it returns.
    /// </summary>
    /// <param name="pixels">the gray levels of the image, row after row</param>
    /// <param name="bayer">whether the image is the raw frame of a color sensor, whose pattern is binned away</param>
    /// <exception cref="OperationCanceledException">if the solve is cancelled before the image is solved</exception>
    /// <exception cref="ObjectDisposedException">if the solver is disposed</exception>
    /// <exception cref="AsterionException">if the solve fails, rather than finding no solution</exception>
    public AsterionSolution Solve(ReadOnlySpan<ushort> pixels, int width, int height, bool bayer, SolveOptions options,
                                  CancellationToken cancellation) {
        if (width <= 0 || height <= 0 || pixels.Length < (long) width * height) {
            throw new ArgumentException($"{pixels.Length} pixels aren't an image of {width} x {height}", nameof(pixels));
        }
        // the flag which cancels the solve, which the token sets on the thread which cancels
        var cancel = (int*) NativeMemory.AllocZeroed(sizeof(int));
        solving.EnterReadLock();
        try {
            ObjectDisposedException.ThrowIf(handle == 0, this);
            using var attachment = library.Attach();
            var native = Options(attachment.Thread, options);
            var solution = default(NativeSolution);
            int status;
            // disposing the registration waits for its callback, if it runs: the flag is freed afterwards
            using (cancellation.Register(() => Interlocked.Exchange(ref *cancel, 1))) {
                fixed (ushort* first = pixels) {
                    status = functions.SolveCancellable(attachment.Thread, handle, first, NativeFunctions.Uint16, width, height, width * 2L,
                        bayer ? 1 : 0, &native, &solution, null, cancel);
                }
            }
            if (status == NativeFunctions.Error) {
                throw library.Failure(attachment.Thread, "Asterion Solver failed");
            }
            if (status == NativeFunctions.Cancelled) {
                cancellation.ThrowIfCancellationRequested();
            }
            return AsterionSolution.Of(status == NativeFunctions.Solved, solution);
        } finally {
            solving.ExitReadLock();
            NativeMemory.Free(cancel);
        }
    }

    /// <summary>The options of the library, with the ones of a solve.</summary>
    private NativeOptions Options(nint thread, SolveOptions options) {
        NativeOptions native;
        if (functions.DefaultOptions(thread, &native) != 0) {
            throw library.Failure(thread, "Asterion Solver failed");
        }
        native.Timeout = options.TimeoutSeconds;
        if (options.RaDeg is { } ra && options.DecDeg is { } dec && !double.IsNaN(ra) && !double.IsNaN(dec)) {
            native.Ra = ra;
            native.Dec = dec;
            if (options.RadiusDeg is > 0 and < 180) {
                native.Radius = options.RadiusDeg;
            }
        }
        return native;
    }

    /// <summary>Closes the catalogs, once the solves which run are over.</summary>
    public void Dispose() {
        solving.EnterWriteLock();
        try {
            if (handle == 0) {
                return;
            }
            using var attachment = library.Attach();
            if (functions.Close(attachment.Thread, handle) != 0) {
                throw library.Failure(attachment.Thread, "The catalogs can't be closed");
            }
            handle = 0;
        } finally {
            solving.ExitWriteLock();
        }
    }
}
