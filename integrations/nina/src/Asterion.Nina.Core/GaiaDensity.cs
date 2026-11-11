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

/// <summary>
/// A catalog of the stars of Gaia DR3 with a density of its choice, gaia-N, which Asterion installs for any density
/// from 10 to 10,000 stars per square degree: downloaded already indexed when it's published, built from the stars
/// which the Gaia archives give otherwise.
/// </summary>
public static class GaiaDensity {
    public const int Min = 10;
    public const int Max = 10000;

    /// <summary>The square degrees of the sky.</summary>
    private const double SkySquareDegrees = 41253;

    /// <summary>The bytes of an index for each star, about, from the indexes which are published.</summary>
    private const double IndexBytesPerStar = 100;

    /// <summary>The memory which building an index needs for each star, to build it in a single pass.</summary>
    private const double BuildBytesPerStar = 80;

    /// <summary>Whether Asterion installs a catalog of a density.</summary>
    public static bool IsValid(int density) => density is >= Min and <= Max;

    /// <summary>The name of the catalog of a density, such as gaia-1500.</summary>
    public static string Name(int density) => string.Create(CultureInfo.InvariantCulture, $"gaia-{density}");

    /// <summary>
    /// What installing the catalog of a density does, whether it's published already indexed or not, with what it takes.
    /// </summary>
    /// <param name="published">the names of the catalogs which are published already indexed</param>
    public static string Describe(int density, IEnumerable<string> published) {
        if (!IsValid(density)) {
            return string.Create(CultureInfo.InvariantCulture, $"Asterion installs densities from {Min} to {Max:N0} stars per square degree.");
        }
        var stars = density * SkySquareDegrees;
        var millions = Math.Round(stars / 1e6).ToString(CultureInfo.InvariantCulture);
        var size = $"{Name(density)}: {millions} million stars, an index of about {Gigabytes(stars * IndexBytesPerStar)}";
        return published.Contains(Name(density), StringComparer.OrdinalIgnoreCase)
            ? $"{size}. It's published already indexed: it's downloaded."
            : $"{size}. It isn't published: it's built from the stars of Gaia DR3, queried from the Gaia archives, which takes "
              + $"hours, about twice that space on disk meanwhile, and up to {Gigabytes(stars * BuildBytesPerStar)} of memory in "
              + "N.I.N.A., in several slower passes when there's less: better done when N.I.N.A. doesn't image.";
    }

    private static string Gigabytes(double bytes) =>
        bytes >= 1e9
            ? (bytes / 1e9).ToString("0.#", CultureInfo.InvariantCulture) + " GB"
            : (bytes / 1e6).ToString("0", CultureInfo.InvariantCulture) + " MB";
}
