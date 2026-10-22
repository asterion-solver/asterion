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

/** A JSON object written on a single line, field by field. */
final class Json {
    private final StringBuilder sb = new StringBuilder("{");

    private Json() {
    }

    static Json object() {
        return new Json();
    }

    /** Adds a field: a string, a boolean or a number, where numbers which aren't finite become null. */
    Json field(String name, Object value) {
        if (sb.length() > 1) {
            sb.append(',');
        }
        quote(sb, name);
        sb.append(':');
        switch (value) {
            case null -> sb.append("null");
            case String s -> quote(sb, s);
            case Double d when !Double.isFinite(d) -> sb.append("null");
            case Float f when !Float.isFinite(f) -> sb.append("null");
            default -> sb.append(value);
        }
        return this;
    }

    @Override
    public String toString() {
        return sb + "}";
    }

    /** Appends a JSON string. */
    static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (var i = 0; i < s.length(); i++) {
            var c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u%04x".formatted((int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
