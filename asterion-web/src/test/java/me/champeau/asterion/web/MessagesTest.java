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

import me.champeau.asterion.catalog.DeepSkyObjects;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The messages of the page, in messages-en.js and messages-fr.js: every language has them all, and they are the
 * ones which the page names.
 */
class MessagesTest {
    private static final List<String> SCRIPTS = List.of("app.js", "charts.js", "skyview.js", "i18n.js");
    /** A message: one per line, "key": "text". */
    private static final Pattern MESSAGE = Pattern.compile("^\\s*\"([^\"]+)\": \"((?:[^\"\\\\]|\\\\.)*)\",?$", Pattern.MULTILINE);
    /** The values of a message, {name}, and its elements, {0}. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\w+}");
    /** The messages which the page names in its elements. */
    private static final Pattern PAGE_KEY = Pattern.compile("data-(i18n|i18n-title|i18n-aria-label|help)=\"([^\"]+)\"");
    /** A call of t or tn, whose first argument names the message, or two of them with a condition. */
    private static final Pattern CALL = Pattern.compile("\\b(tn?)\\(");
    private static final Pattern LITERAL = Pattern.compile("'([^']*)'");

    @Test
    void everyLanguageHasTheMessagesOfEnglishWithTheSameValues() {
        var english = messages("en");
        var french = messages("fr");
        assertEquals(english.keySet(), french.keySet());
        for (var entry : english.entrySet()) {
            assertEquals(placeholders(entry.getValue()), placeholders(french.get(entry.getKey())), entry.getKey());
        }
    }

    @Test
    void theMessagesAreTheOnesWhichThePageNames() {
        var english = messages("en");
        var named = new TreeSet<String>();
        var page = PAGE_KEY.matcher(resource("index.html"));
        while (page.find()) {
            named.add(page.group(1).equals("help") ? "help." + page.group(2) : page.group(2));
        }
        for (var script : SCRIPTS) {
            named.addAll(called(resource(script)));
        }
        for (var type : DeepSkyObjects.Type.values()) {
            named.add("type." + type.description());
        }
        // and only them: a message which nothing names is left over
        assertEquals(new TreeSet<>(english.keySet()), named);
    }

    /** The messages which the calls of t and tn of a script name. */
    private static Set<String> called(String script) {
        var keys = new TreeSet<String>();
        var calls = CALL.matcher(script);
        while (calls.find()) {
            var argument = firstArgument(script, calls.end());
            var literals = LITERAL.matcher(argument);
            while (literals.find()) {
                if (calls.group(1).equals("tn")) {
                    keys.add(literals.group(1) + ".one");
                    keys.add(literals.group(1) + ".other");
                } else {
                    keys.add(literals.group(1));
                }
            }
        }
        // the calls of the functions which i18n.js defines are not messages
        keys.removeIf(key -> !key.contains("."));
        return keys;
    }

    /** The first argument of a call, from the character which follows its parenthesis. */
    private static String firstArgument(String script, int start) {
        var depth = 0;
        for (var i = start; i < script.length(); i++) {
            var c = script.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && depth-- == 0 || c == ',' && depth == 0) {
                return script.substring(start, i);
            }
        }
        return script.substring(start);
    }

    private static Map<String, String> messages(String language) {
        var messages = new LinkedHashMap<String, String>();
        var matcher = MESSAGE.matcher(resource("messages-" + language + ".js"));
        while (matcher.find()) {
            assertEquals(null, messages.put(matcher.group(1), matcher.group(2)), "duplicate " + matcher.group(1));
        }
        return messages;
    }

    private static Set<String> placeholders(String message) {
        return PLACEHOLDER.matcher(message).results().map(m -> m.group()).collect(Collectors.toCollection(TreeSet::new));
    }

    private static String resource(String file) {
        try (var in = MessagesTest.class.getResourceAsStream("/web/" + file)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + file);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
