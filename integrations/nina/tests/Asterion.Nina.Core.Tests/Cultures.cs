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
using Xunit;

namespace Asterion.Nina.Core.Tests;

/// <summary>Runs code with another culture, to check that what is given to asterion doesn't depend on it.</summary>
internal static class Cultures {
    /// <summary>
    /// Cultures which format numbers differently: French writes decimals with a comma, Arabic and
    /// Persian write digits which aren't ASCII.
    /// </summary>
    public static readonly TheoryData<string> Unusual = new("fr-FR", "ar-EG", "fa-IR");

    /// <summary>What the code returns when the current culture is the given one.</summary>
    public static T With<T>(string name, Func<T> code) {
        var previous = (CultureInfo.CurrentCulture, CultureInfo.CurrentUICulture);
        CultureInfo.CurrentCulture = CultureInfo.CurrentUICulture = new CultureInfo(name);
        try {
            return code();
        } finally {
            (CultureInfo.CurrentCulture, CultureInfo.CurrentUICulture) = previous;
        }
    }
}
