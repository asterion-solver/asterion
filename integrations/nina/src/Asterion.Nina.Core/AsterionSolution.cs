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
/// The linear part of a solution, with FITS conventions: N.I.N.A. doesn't use the distortion
/// polynomials which asterion also fits.
/// </summary>
public sealed record LinearWcs(double Crval1, double Crval2, double Crpix1, double Crpix2, double Cd11, double Cd12, double Cd21, double Cd22);

/// <summary>What Asterion tells about an image.</summary>
/// <param name="Stars">the number of stars which were detected</param>
/// <param name="TotalMillis">the time asterion took, from reading the image to solving it</param>
/// <param name="Wcs">the solution, or null if the image isn't solved</param>
/// <param name="MatchedStars">the number of stars of the catalog which match the solution</param>
/// <param name="RmsArcsec">the residuals of the matched stars</param>
public sealed record AsterionSolution(int Stars, double TotalMillis, LinearWcs? Wcs, int MatchedStars, double RmsArcsec) {
    public bool Solved => Wcs != null;

    /// <summary>The solution which the library wrote.</summary>
    internal static unsafe AsterionSolution Of(bool solved, NativeSolution solution) {
        var total = solution.ReadMs + solution.DetectMs + solution.SolveMs;
        if (!solved) {
            return new AsterionSolution(solution.Stars, total, null, 0, double.NaN);
        }
        return new AsterionSolution(solution.Stars, total,
            new LinearWcs(solution.Crval[0], solution.Crval[1], solution.Crpix[0], solution.Crpix[1],
                solution.Cd[0], solution.Cd[1], solution.Cd[2], solution.Cd[3]),
            solution.MatchedStars, solution.Rms);
    }
}
