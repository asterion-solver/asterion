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
package me.champeau.asterion.cli.astap;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Locale;

/** The number formats of ASTAP's output files. */
final class AstapNumbers {
    private AstapNumbers() {
    }

    /** Formats a number of the .ini file: 17 significant digits, e.g. {@code " 1.5205000000000000E+003"}. */
    static String ini(double value) {
        return scientific(value, 17);
    }

    /** Formats a number of a .wcs card: 13 significant digits, e.g. {@code " 1.520500000000E+003"}, 20 characters. */
    static String wcs(double value) {
        return scientific(value, 13);
    }

    /**
     * Pascal's scientific notation: a sign or a space, and an exponent of 3 digits. The digits are
     * the ones of the exact value of the double: {@code %E} pads the shortest representation with
     * zeros instead.
     */
    private static String scientific(double value, int digits) {
        var rounded = new BigDecimal(Math.abs(value)).round(new MathContext(digits, RoundingMode.HALF_EVEN));
        var significand = rounded.signum() == 0 ? "0" : rounded.unscaledValue().toString();
        var exponent = rounded.signum() == 0 ? 0 : rounded.precision() - rounded.scale() - 1;
        significand += "0".repeat(digits - significand.length());
        return (value < 0 ? "-" : " ") + significand.charAt(0) + "." + significand.substring(1)
                + (exponent < 0 ? "E-" : "E+") + String.format(Locale.ROOT, "%03d", Math.abs(exponent));
    }

    /** Formats a number with a dot, whatever the locale. */
    static String fixed(String format, double value) {
        return String.format(Locale.ROOT, format, value);
    }

    /** Formats a right ascension, in degrees, like ASTAP: {@code "00: 42  18.2"}. */
    static String ra(double degrees) {
        var tenths = Math.round((degrees % 360 + 360) % 360 / 15 * 36000) % (24 * 36000);
        return String.format(Locale.ROOT, "%02d: %02d  %04.1f", tenths / 36000, tenths / 600 % 60, tenths % 600 / 10.0);
    }

    /** Formats a declination, in degrees, like ASTAP: {@code "+41d 16  38"}. */
    static String dec(double degrees) {
        var seconds = Math.round(Math.abs(degrees) * 3600);
        return String.format(Locale.ROOT, "%s%02dd %02d  %02d", degrees < 0 ? "-" : "+", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    /** Formats an angular distance, like ASTAP: in degrees, arcminutes or arcseconds. */
    static String distance(double degrees) {
        if (degrees >= 1) {
            return fixed("%.1fd", degrees);
        }
        return degrees * 60 >= 1 ? fixed("%.1f'", degrees * 60) : fixed("%.0f\"", degrees * 3600);
    }
}
