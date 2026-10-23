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

using System.Text.Json;

namespace Asterion.Nina.Core;

/// <summary>
/// The linear part of a solution, with FITS conventions: N.I.N.A. doesn't use the distortion
/// polynomials which asterion also fits.
/// </summary>
public sealed record LinearWcs(double Crval1, double Crval2, double Crpix1, double Crpix2, double Cd11, double Cd12, double Cd21, double Cd22);

/// <summary>What asterion tells about an image, from its JSON output.</summary>
/// <param name="Stars">the number of stars which were detected</param>
/// <param name="TotalMillis">the time asterion took, from reading the image to solving it</param>
/// <param name="Wcs">the solution, or null if the image isn't solved</param>
/// <param name="MatchedStars">the number of stars of the catalog which match the solution</param>
/// <param name="RmsArcsec">the residuals of the matched stars</param>
public sealed record AsterionSolution(int Stars, double TotalMillis, LinearWcs? Wcs, int MatchedStars, double RmsArcsec) {
    public bool Solved => Wcs != null;

    /// <summary>Reads the JSON output of asterion for a single image.</summary>
    /// <exception cref="AsterionException">if the output isn't the one of asterion</exception>
    public static AsterionSolution Parse(string json) => AsterionJson.Read(json, "solution", root => {
        // the output of a single image may still be an array
        var image = root.ValueKind == JsonValueKind.Array ? root[0] : root;
        var stars = image.TryGetProperty("stars", out var s) ? s.GetInt32() : 0;
        var total = image.TryGetProperty("timing", out var timing) ? AsterionJson.Number(timing, "totalMillis") ?? 0 : 0;
        if (!image.GetProperty("solved").GetBoolean()) {
            return new AsterionSolution(stars, total, null, 0, double.NaN);
        }
        var wcs = image.GetProperty("wcs");
        double Keyword(string name) => wcs.GetProperty(name).GetDouble();
        return new AsterionSolution(stars, total,
            new LinearWcs(Keyword("CRVAL1"), Keyword("CRVAL2"), Keyword("CRPIX1"), Keyword("CRPIX2"),
                Keyword("CD1_1"), Keyword("CD1_2"), Keyword("CD2_1"), Keyword("CD2_2")),
            image.GetProperty("matchedStars").GetInt32(), image.GetProperty("rmsArcsec").GetDouble());
    });
}
