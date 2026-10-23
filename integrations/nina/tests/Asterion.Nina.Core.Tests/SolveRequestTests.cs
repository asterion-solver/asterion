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

public class SolveRequestTests {
    [Fact]
    public void AsksForTheRegionOfThePrimarySolve() {
        var request = new SolveRequest("image.fits", 270.5, -12.25, 5, 60);
        Assert.Equal(["--json", "--timeout", "60", "--ra", "270.5", "--dec", "-12.25", "--radius", "5", "image.fits"], request.Arguments());
    }

    [Fact]
    public void SearchesTheWholeSkyForTheBlindSolve() {
        var request = new SolveRequest("image.fits", null, null, 5, 30);
        Assert.Equal(["--json", "--timeout", "30", "image.fits"], request.Arguments());
    }

    [Fact]
    public void SearchesTheWholeRegionWhenItsRadiusIsntUseful() {
        Assert.DoesNotContain("--radius", new SolveRequest("image.fits", 270.5, -12.25, 0, 60).Arguments());
        Assert.DoesNotContain("--radius", new SolveRequest("image.fits", 270.5, -12.25, 180, 60).Arguments());
    }

    [Fact]
    public void UsesTheDirectoryOfTheCatalogs() {
        var request = new SolveRequest("image.fits", null, null, 0, 30, @"D:\Catalogs");
        Assert.Equal(["--json", "--timeout", "30", "--catalog-dir", @"D:\Catalogs", "image.fits"], request.Arguments());
        Assert.Equal(request.Arguments(), (request with { CatalogDirectory = @"D:\Catalogs " }).Arguments());
        Assert.DoesNotContain("--catalog-dir", (request with { CatalogDirectory = " " }).Arguments());
    }

    [Theory]
    [MemberData(nameof(Cultures.Unusual), MemberType = typeof(Cultures))]
    public void GivesTheSameArgumentsWhateverTheCulture(string culture) {
        var request = new SolveRequest("image.fits", 270.5, -12.25, 5.5, 60);
        Assert.Equal(["--json", "--timeout", "60", "--ra", "270.5", "--dec", "-12.25", "--radius", "5.5", "image.fits"],
            Cultures.With(culture, request.Arguments));
    }
}
