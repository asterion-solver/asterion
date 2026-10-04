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

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The memory which can be used without slowing down the machine: what's left of the heap, but
 * also of the physical memory, since the heap limit of the JVM may be larger than the memory which
 * other programs leave free.
 */
final class AvailableMemory {
    private static final Path MEMINFO = Path.of("/proc/meminfo");

    private AvailableMemory() {
    }

    /** The free memory of the heap, in bytes, including the part which isn't allocated yet. */
    static long heap() {
        var runtime = Runtime.getRuntime();
        return runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
    }

    /** The physical memory which programs can use, in bytes, or {@link Long#MAX_VALUE} if unknown. */
    static long system() {
        try {
            // on Linux, the free memory doesn't include the caches which can be reclaimed: this does
            if (Files.isReadable(MEMINFO)) {
                for (var line : Files.readAllLines(MEMINFO)) {
                    if (line.startsWith("MemAvailable:")) {
                        return Long.parseLong(line.replaceAll("\\D", "")) * 1024;
                    }
                }
            }
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                return os.getFreeMemorySize();
            }
        } catch (IOException | RuntimeException | LinkageError _) {
            // unknown
        }
        return Long.MAX_VALUE;
    }
}
