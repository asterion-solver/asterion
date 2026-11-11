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

using Xunit;

namespace Asterion.Nina.Core.Tests;

/// <summary>The catalogs of Gaia DR3 of a density of the user's choice.</summary>
public class GaiaDensityTests {
    private static readonly string[] Published = ["tycho2", "gaia-500", "gaia-1000", "gaia-2000", "gaia-3000"];

    [Fact]
    public void NamesTheCatalogOfADensity() {
        Assert.Equal("gaia-1500", GaiaDensity.Name(1500));
    }

    [Theory]
    [InlineData(9, false)]
    [InlineData(10, true)]
    [InlineData(1500, true)]
    [InlineData(10000, true)]
    [InlineData(10001, false)]
    public void InstallsTheDensitiesOfAsterion(int density, bool valid) {
        Assert.Equal(valid, GaiaDensity.IsValid(density));
    }

    [Fact]
    public void DownloadsAPublishedDensity() {
        var description = GaiaDensity.Describe(3000, Published);
        Assert.StartsWith("gaia-3000: 124 million stars, an index of about 12.4 GB.", description);
        Assert.EndsWith("It's published already indexed: it's downloaded.", description);
    }

    [Fact]
    public void WarnsThatAnotherDensityIsBuilt() {
        var description = GaiaDensity.Describe(1500, Published);
        Assert.StartsWith("gaia-1500: 62 million stars, an index of about 6.2 GB. It isn't published: it's built", description);
        Assert.Contains("up to 5 GB of memory in N.I.N.A.", description);
    }

    [Fact]
    public void TellsTheDensitiesWhichCanBeInstalled() {
        Assert.Equal("Asterion installs densities from 10 to 10,000 stars per square degree.", GaiaDensity.Describe(5, Published));
    }

    [Theory]
    [MemberData(nameof(Cultures.Unusual), MemberType = typeof(Cultures))]
    public void DescribesTheSameWhateverTheCulture(string culture) {
        Assert.Equal(GaiaDensity.Describe(1500, Published), Cultures.With(culture, () => GaiaDensity.Describe(1500, Published)));
        Assert.Equal("gaia-1500", Cultures.With(culture, () => GaiaDensity.Name(1500)));
    }
}
