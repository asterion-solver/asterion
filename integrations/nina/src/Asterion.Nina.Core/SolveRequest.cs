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
/// A solve, as N.I.N.A. asks for it: an image, and for the primary solver the region of the sky
/// where it is, which is a limit of the search. The blind solver gets no region.
/// </summary>
/// <param name="ImagePath">the image, a FITS file written by N.I.N.A.</param>
/// <param name="RaDeg">the right ascension of the center of the region, J2000, in degrees, or null for a blind solve</param>
/// <param name="DecDeg">the declination of the center of the region, J2000, in degrees, or null for a blind solve</param>
/// <param name="RadiusDeg">the radius of the region, in degrees, or 0 for the whole sky</param>
/// <param name="TimeoutSeconds">the time after which asterion gives up</param>
/// <param name="CatalogDirectory">the directory of the catalogs, or null for the default one of asterion</param>
public sealed record SolveRequest(
    string ImagePath,
    double? RaDeg,
    double? DecDeg,
    double RadiusDeg,
    int TimeoutSeconds,
    string? CatalogDirectory = null) {

    /// <summary>The arguments of asterion for this solve: its JSON output, and the region of the sky when there is one.</summary>
    public IReadOnlyList<string> Arguments() {
        var arguments = new List<string> { "--json", "--timeout", Format(TimeoutSeconds) };
        if (RaDeg is { } ra && DecDeg is { } dec && !double.IsNaN(ra) && !double.IsNaN(dec)) {
            arguments.AddRange(["--ra", Format(ra), "--dec", Format(dec)]);
            if (RadiusDeg is > 0 and < 180) {
                arguments.AddRange(["--radius", Format(RadiusDeg)]);
            }
        }
        arguments.AddRange(AsterionExecutable.CatalogDirectoryArguments(CatalogDirectory));
        arguments.Add(ImagePath);
        return arguments;
    }

    private static string Format(double value) => value.ToString("R", CultureInfo.InvariantCulture);
}
