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

using System.Globalization;

namespace Asterion.Nina.Core;

/// <summary>A catalog which Asterion can install, and whether it is installed.</summary>
/// <param name="Size">the size of its index in bytes, when it is installed, or else 0</param>
/// <param name="Error">why its index can't be read, or an empty string</param>
public sealed record Catalog(string Name, string Description, bool Installed, long Size, string Error = "");

/// <summary>The catalogs of a directory.</summary>
/// <param name="Directory">the directory where the catalogs are installed</param>
/// <param name="Catalogs">the catalogs which can be installed, then the ones which are installed but can't be installed</param>
public sealed record CatalogList(string Directory, IReadOnlyList<Catalog> Catalogs) {
    /// <summary>The index file of an installed catalog.</summary>
    public string IndexFile(string name) => Path.Combine(Directory, name + ".astx");
}

/// <summary>Where the download of a catalog is.</summary>
/// <param name="Message">what is done, such as "Downloading tycho2 (280 MB): 12/281 MB"</param>
/// <param name="Fraction">how much of the current step is done, from 0 to 1, or null when it isn't known</param>
public sealed record DownloadProgress(string Message, double? Fraction) {
    /// <summary>
    /// The progress which an event of the installation of a catalog tells: the start of a step, its progress, what it
    /// did, or something else. See asterion_progress in asterion.h.
    /// </summary>
    /// <returns>the progress, or null if the event tells nothing to show</returns>
    internal static unsafe DownloadProgress? Of(NativeProgress* progress) {
        var step = NativeFunctions.Text(progress->Step);
        var message = NativeFunctions.Text(progress->Message);
        return progress->Event switch {
            NativeFunctions.EventStep => new DownloadProgress(step, null),
            NativeFunctions.EventProgress => new DownloadProgress($"{step}: {Done(progress->Completed, progress->Total, NativeFunctions.Text(progress->Unit))}",
                double.IsNaN(progress->Fraction) ? null : progress->Fraction),
            NativeFunctions.EventDetail => new DownloadProgress($"{step}: {message}", null),
            NativeFunctions.EventInfo => new DownloadProgress(message, null),
            _ => null
        };
    }

    /// <summary>The work which is done, such as "12/281 MB", or "1200 stars" when the total isn't known.</summary>
    internal static string Done(long completed, long total, string unit) =>
        // the same as the messages of Asterion, whatever the culture
        total >= 0
            ? string.Create(CultureInfo.InvariantCulture, $"{completed}/{total} {unit}")
            : string.Create(CultureInfo.InvariantCulture, $"{completed} {unit}");
}
