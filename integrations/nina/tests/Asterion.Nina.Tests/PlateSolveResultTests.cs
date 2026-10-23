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
using System.IO;
using System.Reflection;
using Asterion.Nina.Core;
using Moq;
using NINA.Image.ImageData;
using NINA.Image.Interfaces;
using NINA.PlateSolving;
using Xunit;

namespace Asterion.Nina.Tests;

/// <summary>
/// The result of a solution, which must be the one N.I.N.A. computes for ASTAP: the orientation
/// and the flip of an image mean the same for every solver, and N.I.N.A. relies on them to center
/// and rotate. Each solution is given to N.I.N.A.'s ASTAP solver, as an .ini file of ASTAP. The
/// ASTAP solver of N.I.N.A. is internal: it is reached by reflection.
/// </summary>
public class PlateSolveResultTests {
    private const int Width = 6000;
    private const int Height = 4000;

    /// <summary>Solutions: CRVAL1, CRVAL2, CD1_1, CD1_2, CD2_1 and CD2_2.</summary>
    public static readonly TheoryData<double, double, double, double, double, double> Solutions = new() {
        // north up, east left, as seen through a telescope, then flipped
        { 201.5, -43.25, -0.00027, 0, 0, 0.00027 },
        { 201.5, -43.25, 0.00027, 0, 0, 0.00027 },
        // rotated
        { 83.8, -5.4, -0.000234, 0.000135, 0.000135, 0.000234 },
        { 10.57, 41.27, 0.00060996, 0.00059872, 0.00059873, -0.00061007 },
        // the image of the tests of asterion
        { 270.305, 66.73, 4.5174e-06, 0.00015943, -0.00016011, 3.906e-06 },
        // near a pole, and around RA 0
        { 359.9, 89.5, -0.0001, -0.00002, -0.00002, 0.0001 },
    };

    [Theory]
    [MemberData(nameof(Solutions))]
    public void ComputesTheResultAsForASTAP(double crval1, double crval2, double cd11, double cd12, double cd21, double cd22) {
        var wcs = new LinearWcs(crval1, crval2, (Width + 1) / 2d, (Height + 1) / 2d, cd11, cd12, cd21, cd22);
        var expected = ResultOfASTAP(wcs);
        var result = AsterionPlateSolver.Result(wcs, Width, Height);

        Assert.True(expected.Success);
        Assert.True(result.Success);
        Assert.Equal(expected.Coordinates.RADegrees, result.Coordinates.RADegrees, 1e-9);
        Assert.Equal(expected.Coordinates.Dec, result.Coordinates.Dec, 1e-9);
        Assert.Equal(expected.Pixscale, result.Pixscale, 1e-9);
        Assert.Equal(expected.PositionAngle, result.PositionAngle, 1e-9);
        Assert.Equal(expected.Flipped, result.Flipped);
        Assert.Equal(expected.Radius, result.Radius, 1e-9);
    }

    private static PlateSolveResult ResultOfASTAP(LinearWcs wcs) {
        var file = Path.Combine(Path.GetTempPath(), $"asterion-{Guid.NewGuid():N}.ini");
        string Line(string key, double value) => string.Create(CultureInfo.InvariantCulture, $"{key}={value:R}");
        File.WriteAllLines(file, [
            "PLTSOLVD=T",
            Line("CRVAL1", wcs.Crval1), Line("CRVAL2", wcs.Crval2), Line("CRPIX1", wcs.Crpix1), Line("CRPIX2", wcs.Crpix2),
            Line("CD1_1", wcs.Cd11), Line("CD1_2", wcs.Cd12), Line("CD2_1", wcs.Cd21), Line("CD2_2", wcs.Cd22)
        ]);
        try {
            var parameter = new PlateSolveParameter { FocalLength = 600, PixelSize = 3.76, Binning = 1, DisableNotifications = true };
            var image = new Mock<IImageData>();
            image.SetupGet(i => i.Properties).Returns(new ImageProperties(Width, Height, 16, false, 0, 0));
            var assembly = typeof(PlateSolveParameter).Assembly;
            var properties = Internal(assembly, "NINA.PlateSolving.PlateSolveImageProperties", "Create", null, parameter, image.Object);
            var astap = Activator.CreateInstance(assembly.GetType("NINA.PlateSolving.Solvers.ASTAPSolver", true)!, "astap.exe");
            return (PlateSolveResult) Internal(assembly, "NINA.PlateSolving.Solvers.ASTAPSolver", "ReadResult", astap, file, parameter, properties);
        } finally {
            File.Delete(file);
        }
    }

    /// <summary>Calls a method of N.I.N.A. which isn't public.</summary>
    private static object Internal(Assembly assembly, string type, string method, object? target, params object[] arguments) {
        var flags = BindingFlags.Public | BindingFlags.NonPublic | (target == null ? BindingFlags.Static : BindingFlags.Instance);
        var called = assembly.GetType(type, true)!.GetMethod(method, flags)
                     ?? throw new MissingMethodException(type, method);
        return called.Invoke(target, arguments)!;
    }
}
