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

/// <summary>Reads the JSON output of asterion.</summary>
internal static class AsterionJson {
    /// <summary>Reads a JSON document which asterion printed.</summary>
    /// <param name="what">what the document is, for the message of the exception</param>
    /// <exception cref="AsterionException">if the document isn't the one which is expected</exception>
    public static T Read<T>(string json, string what, Func<JsonElement, T> read) {
        try {
            using var document = JsonDocument.Parse(json);
            return read(document.RootElement);
        } catch (Exception e) when (e is JsonException or KeyNotFoundException or InvalidOperationException or IndexOutOfRangeException
                                       or FormatException) {
            throw new AsterionException($"Unexpected {what} from asterion: {e.Message}", e);
        }
    }

    /// <summary>A string property, or an empty string if there is none.</summary>
    public static string String(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString()! : "";

    /// <summary>A number property, or null if there is none.</summary>
    public static double? Number(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.Number ? value.GetDouble() : null;
}
