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
package me.champeau.asterion;

import java.util.Locale;
import java.util.function.Supplier;

/** Runs code with another default locale, to check that what is written for other programs doesn't depend on it. */
public final class Locales {
    /**
     * Locales which format numbers differently: French writes decimals with a comma, Arabic and
     * Persian write digits which aren't ASCII.
     */
    public static final String[] UNUSUAL = {"fr-FR", "ar-EG", "fa-IR"};

    private Locales() {
    }

    /** What the code returns when the default locale is the given one. */
    public static <T> T with(String languageTag, Supplier<T> code) {
        var previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag(languageTag));
        try {
            return code.get();
        } finally {
            Locale.setDefault(previous);
        }
    }
}
