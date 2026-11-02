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
package me.champeau.asterion.lib;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The structures of src/main/c/asterion.h, field by field, in the same order: their sizes are
 * checked by asterion_check_header, see {@link NativeLibrary#sizeOf}.
 */
final class CStructs {
    private CStructs() {
    }

    /** The largest order of the SIP polynomials, ASTERION_MAX_SIP_ORDER. */
    static final int MAX_SIP_ORDER = 5;

    /** The largest order of the inverse SIP polynomials, ASTERION_MAX_SIP_INVERSE_ORDER: they're 2 orders higher. */
    static final int MAX_SIP_INVERSE_ORDER = MAX_SIP_ORDER + 2;

    private static final MemoryLayout SIP_POLYNOMIAL = MemoryLayout.sequenceLayout(MAX_SIP_ORDER + 1,
            MemoryLayout.sequenceLayout(MAX_SIP_ORDER + 1, JAVA_DOUBLE));

    private static final MemoryLayout SIP_INVERSE_POLYNOMIAL = MemoryLayout.sequenceLayout(MAX_SIP_INVERSE_ORDER + 1,
            MemoryLayout.sequenceLayout(MAX_SIP_INVERSE_ORDER + 1, JAVA_DOUBLE));

    static final StructLayout OPTIONS = MemoryLayout.structLayout(
            JAVA_DOUBLE.withName("ra"),
            JAVA_DOUBLE.withName("dec"),
            JAVA_DOUBLE.withName("radius"),
            JAVA_DOUBLE.withName("scale_low"),
            JAVA_DOUBLE.withName("scale_high"),
            JAVA_DOUBLE.withName("fov"),
            JAVA_DOUBLE.withName("pixel_size"),
            JAVA_DOUBLE.withName("timeout"),
            JAVA_DOUBLE.withName("sigma"),
            JAVA_DOUBLE.withName("epoch"),
            JAVA_INT.withName("parity"),
            JAVA_INT.withName("sip_order"),
            JAVA_INT.withName("binning"),
            JAVA_INT.withName("max_stars"),
            JAVA_INT.withName("quad_stars"),
            JAVA_INT.withName("padding"));

    static final StructLayout SOLUTION = MemoryLayout.structLayout(
            JAVA_DOUBLE.withName("ra"),
            JAVA_DOUBLE.withName("dec"),
            JAVA_DOUBLE.withName("scale"),
            JAVA_DOUBLE.withName("rotation"),
            JAVA_DOUBLE.withName("field_width"),
            JAVA_DOUBLE.withName("field_height"),
            JAVA_DOUBLE.withName("rms"),
            JAVA_DOUBLE.withName("log_odds"),
            JAVA_DOUBLE.withName("focal_length"),
            JAVA_DOUBLE.withName("pixel_size"),
            JAVA_DOUBLE.withName("fwhm_pixels"),
            JAVA_DOUBLE.withName("fwhm_arcsec"),
            MemoryLayout.sequenceLayout(2, JAVA_DOUBLE).withName("crpix"),
            MemoryLayout.sequenceLayout(2, JAVA_DOUBLE).withName("crval"),
            MemoryLayout.sequenceLayout(4, JAVA_DOUBLE).withName("cd"),
            SIP_POLYNOMIAL.withName("sip_a"),
            SIP_POLYNOMIAL.withName("sip_b"),
            SIP_INVERSE_POLYNOMIAL.withName("sip_ap"),
            SIP_INVERSE_POLYNOMIAL.withName("sip_bp"),
            JAVA_DOUBLE.withName("read_ms"),
            JAVA_DOUBLE.withName("detect_ms"),
            JAVA_DOUBLE.withName("solve_ms"),
            JAVA_INT.withName("sip_order"),
            JAVA_INT.withName("sip_inverse_order"),
            JAVA_INT.withName("flipped"),
            JAVA_INT.withName("matched_stars"),
            JAVA_INT.withName("stars"),
            MemoryLayout.sequenceLayout(64, JAVA_BYTE).withName("index"),
            JAVA_INT.withName("padding"));

    static final StructLayout OBJECT = MemoryLayout.structLayout(
            JAVA_DOUBLE.withName("ra"),
            JAVA_DOUBLE.withName("dec"),
            JAVA_DOUBLE.withName("x"),
            JAVA_DOUBLE.withName("y"),
            JAVA_DOUBLE.withName("distance"),
            JAVA_DOUBLE.withName("major_arcmin"),
            JAVA_DOUBLE.withName("minor_arcmin"),
            JAVA_DOUBLE.withName("angle"),
            JAVA_DOUBLE.withName("magnitude"),
            JAVA_INT.withName("messier"),
            MemoryLayout.sequenceLayout(32, JAVA_BYTE).withName("name"),
            MemoryLayout.sequenceLayout(32, JAVA_BYTE).withName("designation"),
            MemoryLayout.sequenceLayout(128, JAVA_BYTE).withName("common_name"),
            MemoryLayout.sequenceLayout(256, JAVA_BYTE).withName("aliases"),
            MemoryLayout.sequenceLayout(32, JAVA_BYTE).withName("type"),
            // the size of the structure is a multiple of the alignment of its doubles, as in C
            MemoryLayout.paddingLayout(4));

    static final StructLayout MATCH = MemoryLayout.structLayout(
            JAVA_DOUBLE.withName("x"),
            JAVA_DOUBLE.withName("y"),
            JAVA_DOUBLE.withName("ra"),
            JAVA_DOUBLE.withName("dec"),
            JAVA_DOUBLE.withName("residual"));

    static final StructLayout CATALOG = MemoryLayout.structLayout(
            MemoryLayout.sequenceLayout(64, JAVA_BYTE).withName("name"),
            MemoryLayout.sequenceLayout(256, JAVA_BYTE).withName("description"),
            JAVA_INT.withName("installed"),
            JAVA_INT.withName("available"),
            JAVA_LONG.withName("size"),
            JAVA_LONG.withName("stars"),
            JAVA_LONG.withName("quads"),
            JAVA_DOUBLE.withName("magnitude_limit"),
            JAVA_DOUBLE.withName("min_quad_arcmin"),
            JAVA_DOUBLE.withName("max_quad_deg"),
            MemoryLayout.sequenceLayout(256, JAVA_BYTE).withName("error"));

    static final StructLayout PROGRESS = MemoryLayout.structLayout(
            JAVA_INT.withName("event"),
            JAVA_INT.withName("padding"),
            JAVA_LONG.withName("completed"),
            JAVA_LONG.withName("total"),
            JAVA_LONG.withName("bytes"),
            JAVA_DOUBLE.withName("fraction"),
            ADDRESS.withName("step"),
            ADDRESS.withName("unit"),
            ADDRESS.withName("message"));

    /** The structures by their names in C, for asterion_sizeof. */
    static final Map<String, StructLayout> BY_NAME = Map.of(
            "asterion_options", OPTIONS,
            "asterion_solution", SOLUTION,
            "asterion_object", OBJECT,
            "asterion_match", MATCH,
            "asterion_catalog", CATALOG,
            "asterion_progress", PROGRESS);

    static double getDouble(StructLayout layout, MemorySegment struct, String field) {
        return struct.get(JAVA_DOUBLE, layout.byteOffset(groupElement(field)));
    }

    static int getInt(StructLayout layout, MemorySegment struct, String field) {
        return struct.get(JAVA_INT, layout.byteOffset(groupElement(field)));
    }

    static void setDouble(StructLayout layout, MemorySegment struct, String field, double value) {
        struct.set(JAVA_DOUBLE, layout.byteOffset(groupElement(field)), value);
    }

    static void setInt(StructLayout layout, MemorySegment struct, String field, int value) {
        struct.set(JAVA_INT, layout.byteOffset(groupElement(field)), value);
    }

    static void setLong(StructLayout layout, MemorySegment struct, String field, long value) {
        struct.set(JAVA_LONG, layout.byteOffset(groupElement(field)), value);
    }

    static void setAddress(StructLayout layout, MemorySegment struct, String field, MemorySegment value) {
        struct.set(ADDRESS, layout.byteOffset(groupElement(field)), value);
    }

    /** Sets an element of an array of doubles: crpix[1] is setDouble(..., "crpix", 1, value). */
    static void setDouble(StructLayout layout, MemorySegment struct, String field, int index, double value) {
        struct.set(JAVA_DOUBLE, layout.byteOffset(groupElement(field)) + 8L * index, value);
    }

    /**
     * Sets a string field: UTF-8, null terminated, truncated to its size without splitting a
     * character.
     */
    static void setString(StructLayout layout, MemorySegment struct, String field, String value) {
        var size = layout.select(groupElement(field)).byteSize();
        copyString(value, struct.asSlice(layout.byteOffset(groupElement(field)), size));
    }

    /**
     * Copies a string into a buffer: UTF-8, null terminated, truncated to its size without splitting
     * a character. An empty buffer receives nothing.
     *
     * @return the length of the whole string, in bytes
     */
    static int copyString(String value, MemorySegment buffer) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        if (buffer.byteSize() > 0) {
            var length = (int) Math.min(bytes.length, buffer.byteSize() - 1);
            // the continuation bytes of UTF-8 are 10xxxxxx: a cut before one is in the middle of a character
            while (length < bytes.length && length > 0 && (bytes[length] & 0xC0) == 0x80) {
                length--;
            }
            MemorySegment.copy(bytes, 0, buffer, JAVA_BYTE, 0, length);
            buffer.set(JAVA_BYTE, length, (byte) 0);
        }
        return bytes.length;
    }
}
