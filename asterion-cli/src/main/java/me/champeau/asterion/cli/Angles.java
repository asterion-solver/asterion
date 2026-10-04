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
package me.champeau.asterion.cli;

/** Parsing and formatting of angles. */
final class Angles {
    private Angles() {
    }

    /** Parses a right ascension: degrees, or sexagesimal hours. */
    static double parseRa(String value) {
        return isSexagesimal(value) ? 15 * parseSexagesimal(value) : Double.parseDouble(value.trim());
    }

    /** Parses a declination: degrees, as a decimal or sexagesimal number. */
    static double parseDec(String value) {
        return isSexagesimal(value) ? parseSexagesimal(value) : Double.parseDouble(value.trim());
    }

    private static boolean isSexagesimal(String value) {
        var v = value.trim();
        return v.contains(":") || v.contains(" ") || v.contains("h") || v.contains("d");
    }

    private static double parseSexagesimal(String value) {
        var parts = value.trim().split("[:\\shdms°'\"]+");
        var negative = parts[0].startsWith("-");
        var v = 0.0;
        var unit = 1.0;
        for (var part : parts) {
            if (!part.isEmpty()) {
                v += Math.abs(Double.parseDouble(part)) / unit;
                unit *= 60;
            }
        }
        return negative ? -v : v;
    }

    static String formatRa(double degrees) {
        var hours = ((degrees % 360) + 360) % 360 / 15;
        var hundredths = Math.round(hours * 360000) % (24 * 360000);
        return Main.format("%02dh %02dm %05.2fs", hundredths / 360000, hundredths / 6000 % 60, hundredths % 6000 / 100.0);
    }

    static String formatDec(double degrees) {
        var tenths = Math.round(Math.abs(degrees) * 36000);
        return Main.format("%s%02d° %02d' %04.1f\"", degrees < 0 ? "-" : "+", tenths / 36000, tenths / 600 % 60, tenths % 600 / 10.0);
    }
}
