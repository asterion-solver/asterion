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

/// <summary>Reading what asterion prints.</summary>
public class OutputTests {
    private const string SolvedJson = """
        {"image":"a.fits","solved":true,"stars":412,"fwhmPixels":2.1,"ra":270.30512,"dec":66.73004,"scale":0.5812,
         "rotation":12.5,"flipped":false,"fieldWidth":0.3,"fieldHeight":0.18,"matchedStars":20,"rmsArcsec":0.12,"logOdds":45.2,
         "index":"gaia-500","wcs":{"CTYPE1":"RA---TAN-SIP","CTYPE2":"DEC--TAN-SIP","CRPIX1":972.5,"CRPIX2":548.5,
         "CRVAL1":270.30512,"CRVAL2":66.73004,"CD1_1":-0.000157,"CD1_2":0.0000349,"CD2_1":0.0000349,"CD2_2":0.000157,"A_ORDER":2},
         "objects":[],"timing":{"readMillis":3.0,"detectMillis":5.0,"solveMillis":4.0,"totalMillis":12.5}}
        """;

    [Fact]
    public void ReadsTheLinearPartOfTheSolution() {
        var solution = AsterionSolution.Parse(SolvedJson);
        Assert.True(solution.Solved);
        Assert.Equal(new LinearWcs(270.30512, 66.73004, 972.5, 548.5, -0.000157, 0.0000349, 0.0000349, 0.000157), solution.Wcs);
        Assert.Equal(412, solution.Stars);
        Assert.Equal(20, solution.MatchedStars);
        Assert.Equal(0.12, solution.RmsArcsec);
        Assert.Equal(12.5, solution.TotalMillis);
        // the output of several images is an array
        Assert.Equal(solution, AsterionSolution.Parse($"[{SolvedJson}]"));
    }

    [Fact]
    public void ReadsTheOutputOfAnImageWhichIsntSolved() {
        var solution = AsterionSolution.Parse("""{"image":"a.fits","solved":false,"stars":17,"fwhmPixels":null,"timing":{"totalMillis":250.0}}""");
        Assert.False(solution.Solved);
        Assert.Null(solution.Wcs);
        Assert.Equal(17, solution.Stars);
        Assert.Equal(250.0, solution.TotalMillis);
    }

    [Theory]
    [InlineData("")]
    [InlineData("Error: no catalog")]
    [InlineData("[]")]
    [InlineData("""{"image":"a.fits"}""")]
    [InlineData("""{"solved":true,"ra":1}""")]
    public void TellsWhenTheOutputIsntTheOneOfAsterion(string output) {
        var e = Assert.Throws<AsterionException>(() => AsterionSolution.Parse(output));
        Assert.StartsWith("Unexpected solution from asterion", e.Message);
    }

    [Fact]
    public void ReadsTheListOfCatalogs() {
        var list = CatalogList.Parse("""
            {"directory":"C:\\Users\\me\\.asterion\\catalogs","version":"0.2.0",
             "installed":[{"name":"gaia-500","size":1906443560,"stars":20626944},{"name":"custom","size":1000,"stars":10}],
             "available":[{"name":"tycho2","description":"Tycho-2","installed":false},{"name":"gaia-500","description":"Gaia DR3","installed":true}]}
            """);
        Assert.Equal(@"C:\Users\me\.asterion\catalogs", list.Directory);
        Assert.Equal([
            new Catalog("tycho2", "Tycho-2", false, 0),
            new Catalog("gaia-500", "Gaia DR3", true, 1906443560),
            new Catalog("custom", "", true, 1000)
        ], list.Catalogs);
        Assert.Equal(Path.Combine(list.Directory, "custom.astx"), list.IndexFile("custom"));
    }

    [Fact]
    public void TellsWhenTheListOfCatalogsIsntTheOneOfAsterion() {
        var e = Assert.Throws<AsterionException>(() => CatalogList.Parse("""{"directory":"/catalogs"}"""));
        Assert.StartsWith("Unexpected list of catalogs from asterion", e.Message);
    }

    [Fact]
    public void ReadsTheProgressOfADownload() {
        Assert.Equal(new DownloadProgress("Downloading tycho2 (280 MB)", null),
            DownloadProgress.Read("""{"event":"step","step":"Downloading tycho2 (280 MB)","total":281,"unit":"MB"}"""));
        Assert.Equal(new DownloadProgress("Downloading tycho2 (280 MB): 0/281 MB", 0),
            DownloadProgress.Read("""{"event":"progress","step":"Downloading tycho2 (280 MB)","completed":0,"total":281,"unit":"MB","fraction":0.0}"""));
        Assert.Equal(new DownloadProgress("Downloading tycho2 (280 MB): 140/281 MB", 0.498),
            DownloadProgress.Read("""{"event":"progress","step":"Downloading tycho2 (280 MB)","completed":140,"total":281,"unit":"MB","fraction":0.498,"bytes":146800640}"""));
        Assert.Equal(new DownloadProgress("Indexing: 1200 stars", null),
            DownloadProgress.Read("""{"event":"progress","step":"Indexing","completed":1200,"unit":"stars"}"""));
        Assert.Equal(new DownloadProgress("Downloading tycho2 (280 MB): from a mirror", null),
            DownloadProgress.Read("""{"event":"detail","step":"Downloading tycho2 (280 MB)","message":"from a mirror"}"""));
        Assert.Equal(new DownloadProgress("Decompressing tycho2", null),
            DownloadProgress.Read("""{"event":"info","message":"Decompressing tycho2"}"""));
    }

    [Theory]
    [InlineData("""{"event":"done","step":"Downloading tycho2 (280 MB)"}""")]
    [InlineData("""{"event":"installed","catalog":"tycho2","file":"tycho2.astx","size":378244396}""")]
    [InlineData("""{"event":"progress","step":"Indexing"}""")]
    [InlineData("Downloading tycho2")]
    [InlineData("{not json")]
    [InlineData("")]
    public void IgnoresWhatIsntProgress(string line) {
        Assert.Null(DownloadProgress.Read(line));
    }

    [Theory]
    [MemberData(nameof(Cultures.Unusual), MemberType = typeof(Cultures))]
    public void ReadsTheSameWhateverTheCulture(string culture) {
        Assert.Equal(AsterionSolution.Parse(SolvedJson), Cultures.With(culture, () => AsterionSolution.Parse(SolvedJson)));
        Assert.Equal(new DownloadProgress("Indexing: 1200/5000 stars", 0.24),
            Cultures.With(culture, () => DownloadProgress.Read("""{"event":"progress","step":"Indexing","completed":1200,"total":5000,"unit":"stars","fraction":0.24}""")));
    }
}
