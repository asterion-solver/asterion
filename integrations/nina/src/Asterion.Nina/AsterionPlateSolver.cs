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

using System.IO;
using Asterion.Nina.Core;
using NINA.Astrometry;
using NINA.Core.Enum;
using NINA.Core.Model;
using NINA.Core.Utility;
using NINA.Core.Utility.Notification;
using NINA.Image.FileFormat;
using NINA.Image.Interfaces;
using NINA.PlateSolving;
using NINA.PlateSolving.Interfaces;

namespace Asterion.Nina;

/// <summary>
/// Solves an image of N.I.N.A. with asterion. N.I.N.A. gives the image in memory: it is written to
/// a temporary FITS file, which asterion solves.
/// </summary>
public sealed class AsterionPlateSolver : IPlateSolver {
    private static readonly string WorkingDirectory = Path.Combine(CoreUtil.APPLICATIONTEMPPATH, "PlateSolver", "Asterion");
    private readonly AsterionSettings settings;

    public AsterionPlateSolver(AsterionSettings settings) {
        this.settings = settings;
    }

    public async Task<PlateSolveResult> SolveAsync(IImageData source, PlateSolveParameter parameter, IProgress<ApplicationStatus> progress,
                                                   CancellationToken canceltoken) {
        string? file = null;
        try {
            var executable = settings.Executable();
            Directory.CreateDirectory(WorkingDirectory);
            progress?.Report(new ApplicationStatus { Status = "Asterion: saving the image" });
            file = await source.SaveToDisk(new FileSaveInfo {
                FilePath = WorkingDirectory,
                FilePattern = Path.GetRandomFileName(),
                FileType = FileTypeEnum.FITS
            }, canceltoken, forceFileType: true);

            // the primary solver searches the region N.I.N.A. gives, the blind one the whole sky
            var coordinates = parameter.Coordinates;
            var request = new SolveRequest(file, coordinates?.RADegrees, coordinates?.Dec, parameter.SearchRadius, settings.TimeoutSeconds,
                settings.CatalogDirectory);
            progress?.Report(new ApplicationStatus { Status = coordinates == null ? "Asterion: blind solving" : "Asterion: solving" });
            Logger.Info($"Asterion: solving with {executable.Path} {string.Join(" ", request.Arguments())}");
            var solution = await executable.SolveAsync(request, canceltoken);
            if (solution.Wcs is not { } wcs) {
                Logger.Info($"Asterion: no solution found in {solution.TotalMillis:0} ms, with {solution.Stars} stars");
                return new PlateSolveResult { Success = false };
            }
            Logger.Info($"Asterion: solved in {solution.TotalMillis:0} ms, {solution.MatchedStars} stars matched, RMS {solution.RmsArcsec:0.00}\"");
            return Result(wcs, source.Properties.Width, source.Properties.Height);
        } catch (AsterionException e) {
            Logger.Error($"Asterion: {e.Message}");
            if (!parameter.DisableNotifications) {
                Notification.ShowError(e is NoCatalogException
                    ? "Asterion: no catalog is installed. Download one in Options > Plate Solving, with Asterion as the solver."
                    : $"Asterion: {e.Message}");
            }
            return new PlateSolveResult { Success = false };
        } finally {
            progress?.Report(new ApplicationStatus { Status = string.Empty });
            if (file != null) {
                try {
                    File.Delete(file);
                } catch (IOException e) {
                    Logger.Error($"Asterion: {e.Message}");
                }
            }
        }
    }

    /// <summary>
    /// The result of a solution, computed as N.I.N.A.'s ASTAP solver computes it from the WCS, so
    /// that the orientation and the flip of the image mean the same as for every other solver: see
    /// the tests, which compare them.
    /// </summary>
    internal static PlateSolveResult Result(LinearWcs solution, double width, double height) {
        var wcs = new WorldCoordinateSystem(solution.Crval1, solution.Crval2, solution.Crpix1, solution.Crpix2,
            solution.Cd11, solution.Cd12, solution.Cd21, solution.Cd22);
        var result = new PlateSolveResult {
            Success = true,
            Coordinates = new Coordinates(solution.Crval1, solution.Crval2, Epoch.J2000, Coordinates.RAType.Degrees),
            Pixscale = AstroUtil.DegreeToArcsec(Math.Sqrt(solution.Cd12 * solution.Cd12 + solution.Cd22 * solution.Cd22)),
            PositionAngle = 360 - (wcs.Rotation - 180),
            Flipped = !wcs.Flipped
        };
        result.Radius = AstroUtil.ArcsecToDegree(Math.Sqrt(Math.Pow(width * result.Pixscale, 2) + Math.Pow(height * result.Pixscale, 2)) / 2d);
        return result;
    }
}
