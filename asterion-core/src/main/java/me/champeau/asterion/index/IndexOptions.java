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
package me.champeau.asterion.index;

/**
 * Parameters of the index builder.
 *
 * @param maxCellSizeDeg the cell size of the coarsest level of the star pyramid, which is also the size
 * of the largest quads
 * @param minCellSizeDeg the cell size under which no pyramid level is created
 * @param starsPerCell the number of stars each pyramid level adds to each of its cells
 * @param quadsPerCell the number of quads built in each cell of each pyramid level
 * @param minFill a pyramid level which is filled below this ratio is the last one: the catalog
 * isn't deep enough to go further
 * @param sortMemory the memory used to sort quads, in bytes, or 0 to adapt to the memory which is
 * available, which is the right choice for applications: with less memory, quads are sorted in
 * several passes, which is slower
 */
public record IndexOptions(
        double maxCellSizeDeg,
        double minCellSizeDeg,
        int starsPerCell,
        int quadsPerCell,
        double minFill,
        long sortMemory) {
    public static IndexOptions defaults() {
        return new IndexOptions(16, 0, 4, 8, 0.35, 0);
    }

    public IndexOptions withQuadsPerCell(int value) {
        return new IndexOptions(maxCellSizeDeg, minCellSizeDeg, starsPerCell, value, minFill, sortMemory);
    }

    /** Sets the memory used to sort quads, instead of adapting to the memory which is available. */
    public IndexOptions withSortMemory(long bytes) {
        return new IndexOptions(maxCellSizeDeg, minCellSizeDeg, starsPerCell, quadsPerCell, minFill, bytes);
    }
}
