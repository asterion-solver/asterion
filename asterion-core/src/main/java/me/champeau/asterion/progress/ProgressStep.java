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
package me.champeau.asterion.progress;

/**
 * A step of a long operation, such as downloading a catalog.
 *
 * @param description what the step does, for people
 * @param total the amount of work of the step, or -1 if it isn't known
 * @param unit the unit of the work, in plural form, like "files"
 */
public record ProgressStep(
        String description,
        long total,
        String unit) {
    public boolean hasTotal() {
        return total >= 0;
    }
}
