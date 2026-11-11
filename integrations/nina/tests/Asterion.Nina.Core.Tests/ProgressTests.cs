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

using System.Text;
using Xunit;

namespace Asterion.Nina.Core.Tests;

/// <summary>The progress of the installation of a catalog, from the events of the library.</summary>
public unsafe class ProgressTests {
    /// <summary>The progress which an event tells, the strings of the event being given as they are.</summary>
    private static DownloadProgress? Of(int kind, string? step = null, string? message = null, long completed = 0, long total = -1,
                                        string? unit = null, double fraction = double.NaN) {
        fixed (byte* s = Utf8(step), m = Utf8(message), u = Utf8(unit)) {
            var progress = new NativeProgress {
                Event = kind, Step = s, Message = m, Unit = u, Completed = completed, Total = total, Bytes = -1, Fraction = fraction
            };
            return DownloadProgress.Of(&progress);
        }
    }

    private static byte[]? Utf8(string? text) => text == null ? null : Encoding.UTF8.GetBytes(text + '\0');

    [Fact]
    public void ReadsTheProgressOfADownload() {
        Assert.Equal(new DownloadProgress("Downloading tycho2 (280 MB)", null), Of(NativeFunctions.EventStep, "Downloading tycho2 (280 MB)"));
        Assert.Equal(new DownloadProgress("Downloading tycho2 (280 MB): 12/281 MB", 12 / 281d),
            Of(NativeFunctions.EventProgress, "Downloading tycho2 (280 MB)", completed: 12, total: 281, unit: "MB", fraction: 12 / 281d));
        // a step whose total isn't known
        Assert.Equal(new DownloadProgress("Indexing: 1200 stars", null),
            Of(NativeFunctions.EventProgress, "Indexing", completed: 1200, unit: "stars"));
        Assert.Equal(new DownloadProgress("Verifying: the checksum matches", null),
            Of(NativeFunctions.EventDetail, "Verifying", "the checksum matches"));
        Assert.Equal(new DownloadProgress("Resuming the download", null), Of(NativeFunctions.EventInfo, message: "Resuming the download"));
    }

    [Fact]
    public void IgnoresTheEndOfSteps() {
        Assert.Null(Of(4, "Downloading tycho2 (280 MB)"));
    }

    [Theory]
    [MemberData(nameof(Cultures.Unusual), MemberType = typeof(Cultures))]
    public void ReadsTheSameWhateverTheCulture(string culture) {
        Assert.Equal("Downloading: 1234567/2345678 MB", Cultures.With(culture, () =>
            Of(NativeFunctions.EventProgress, "Downloading", completed: 1234567, total: 2345678, unit: "MB")!.Message));
    }
}
