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
package me.champeau.asterion.web;

import java.util.Map;

/** Writes JSON documents: the web interface has no dependency for this. */
final class Json {
    private final StringBuilder sb = new StringBuilder();
    private boolean first = true;

    private Json(char open) {
        sb.append(open);
    }

    static Json object() {
        return new Json('{');
    }

    static Json array() {
        return new Json('[');
    }

    private void separator() {
        if (!first) {
            sb.append(',');
        }
        first = false;
    }

    /** Adds a field to an object: strings, numbers, booleans, maps, or other JSON documents. */
    Json field(String name, Object value) {
        separator();
        string(name);
        sb.append(':');
        value(value);
        return this;
    }

    /** Adds an element to an array. */
    Json add(Object value) {
        separator();
        value(value);
        return this;
    }

    private void value(Object value) {
        switch (value) {
            case null -> sb.append("null");
            case Json json -> sb.append(json);
            case Boolean b -> sb.append(b);
            case Double d -> sb.append(Double.isFinite(d) ? d.toString() : "null");
            case Float f -> sb.append(Float.isFinite(f) ? f.toString() : "null");
            case Number n -> sb.append(n);
            case Map<?, ?> map -> {
                var object = object();
                map.forEach((k, v) -> object.field(String.valueOf(k), v));
                sb.append(object);
            }
            default -> string(value.toString());
        }
    }

    private void string(String s) {
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
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    @Override
    public String toString() {
        // the closing character, without changing the builder: toString may be called twice
        return sb + (sb.charAt(0) == '{' ? "}" : "]");
    }
}
