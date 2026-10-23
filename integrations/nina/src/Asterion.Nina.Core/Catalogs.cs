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
using System.Text.Json;

namespace Asterion.Nina.Core;

/// <summary>A catalog which asterion can download, and whether it is installed.</summary>
/// <param name="Size">the size of its index in bytes, when it is installed, or else 0</param>
public sealed record Catalog(string Name, string Description, bool Installed, long Size);

/// <summary>The catalogs of asterion, from <c>asterion --list-catalogs --json</c>.</summary>
/// <param name="Directory">the directory where the catalogs are installed</param>
/// <param name="Catalogs">the catalogs which can be downloaded, then the ones which are installed but can't be downloaded</param>
public sealed record CatalogList(string Directory, IReadOnlyList<Catalog> Catalogs) {
    /// <summary>The index file of an installed catalog.</summary>
    public string IndexFile(string name) => Path.Combine(Directory, name + ".astx");

    /// <exception cref="AsterionException">if the output isn't the one of asterion</exception>
    public static CatalogList Parse(string json) => AsterionJson.Read(json, "list of catalogs", root => {
        var sizes = root.GetProperty("installed").EnumerateArray()
            .ToDictionary(c => c.GetProperty("name").GetString()!, c => c.GetProperty("size").GetInt64());
        var catalogs = new List<Catalog>();
        foreach (var available in root.GetProperty("available").EnumerateArray()) {
            var name = available.GetProperty("name").GetString()!;
            var size = sizes.Remove(name, out var s) ? s : 0;
            catalogs.Add(new Catalog(name, AsterionJson.String(available, "description"), size > 0, size));
        }
        // the catalogs which were built by hand, or which are no longer published
        catalogs.AddRange(sizes.OrderBy(e => e.Key, StringComparer.Ordinal).Select(e => new Catalog(e.Key, "", true, e.Value)));
        return new CatalogList(root.GetProperty("directory").GetString()!, catalogs);
    });
}

/// <summary>Where the download of a catalog is.</summary>
/// <param name="Message">what is done, such as "Downloading tycho2 (280 MB): 12/281 MB"</param>
/// <param name="Fraction">how much of the current step is done, from 0 to 1, or null when it isn't known</param>
public sealed record DownloadProgress(string Message, double? Fraction) {
    /// <summary>
    /// Reads a line that <c>asterion --download-catalog NAME --json</c> prints: a JSON object whose
    /// <c>event</c> tells what happens, such as the start of a <c>step</c>, or the <c>progress</c> of
    /// the current one. See the README of asterion.
    /// </summary>
    /// <returns>the progress the line tells, or null if it tells nothing to show</returns>
    public static DownloadProgress? Read(string line) {
        if (!line.TrimStart().StartsWith('{')) {
            return null;
        }
        try {
            return AsterionJson.Read(line, "progress", root => {
                var step = AsterionJson.String(root, "step");
                var message = AsterionJson.String(root, "message");
                return AsterionJson.String(root, "event") switch {
                    "step" => new DownloadProgress(step, null),
                    "progress" => new DownloadProgress($"{step}: {Done(root)}", AsterionJson.Number(root, "fraction")),
                    "detail" => new DownloadProgress($"{step}: {message}", null),
                    "info" => new DownloadProgress(message, null),
                    _ => null
                };
            });
        } catch (AsterionException) {
            // a line which isn't understood doesn't stop the download
            return null;
        }
    }

    /// <summary>The work which is done, such as "12/281 MB", or "1200 stars" when the total isn't known.</summary>
    private static string Done(JsonElement progress) {
        var completed = progress.GetProperty("completed").GetInt64();
        var unit = AsterionJson.String(progress, "unit");
        // the same as the messages of asterion, whatever the culture
        return progress.TryGetProperty("total", out var total) && total.ValueKind == JsonValueKind.Number
            ? string.Create(CultureInfo.InvariantCulture, $"{completed}/{total.GetInt64()} {unit}")
            : string.Create(CultureInfo.InvariantCulture, $"{completed} {unit}");
    }
}
