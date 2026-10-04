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

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AstapArgumentsTest {
    private static AstapArguments parse(String commandLine) {
        return AstapArguments.parse(List.of(commandLine.split(" ")));
    }

    @Test
    void parsesTheCommandLineOfNina() {
        var args = parse("-f /tmp/nina/abc.fits -fov 1.234567 -z 0 -s 500 -r 30 -ra 0.7 -spd 131.3");
        assertEquals(Path.of("/tmp/nina/abc.fits"), args.image());
        assertEquals(1.234567, args.fovDeg());
        assertEquals(0, args.downsample());
        assertEquals(30, args.radiusDeg());
        assertEquals(0.7, args.raHours());
        assertEquals(131.3, args.southPoleDistanceDeg());
        assertTrue(args.hasPosition());
        assertTrue(args.problems().isEmpty());
        assertTrue(args.unsupported().isEmpty());
    }

    @Test
    void parsesTheCommandLineOfCcdciel() {
        var args = parse("-log -z 2 -r 180 -f /home/user/ccdciel/tmp/capture.fits");
        assertEquals(Path.of("/home/user/ccdciel/tmp/capture.fits"), args.image());
        assertEquals(2, args.downsample());
        assertEquals(180, args.radiusDeg());
        assertTrue(args.log());
        assertFalse(args.hasPosition());
        assertTrue(Double.isNaN(args.fovDeg()));
        assertTrue(args.problems().isEmpty());
    }

    @Test
    void sipTakesAnOptionalValue() {
        assertTrue(parse("-f a.fits -sip").sip());
        assertTrue(parse("-sip -f a.fits").sip());
        assertTrue(parse("-sip y -f a.fits").sip());
        assertFalse(parse("-sip n -f a.fits").sip());
        assertFalse(parse("-f a.fits").sip());
        assertEquals(Path.of("a.fits"), parse("-sip n -f a.fits").image());
    }

    @Test
    void ignoresTheOptionsOfTheAstapAlgorithm() {
        var args = parse("-f a.fits -s 500 -t 0.007 -m 1.5 -check y -d /opt/astap -D d50 -speed slow -progress -wcs -update -o out/base");
        assertTrue(args.problems().isEmpty());
        assertTrue(args.unsupported().isEmpty());
        assertTrue(args.fitsWcs());
        assertTrue(args.update());
        assertEquals(Path.of("out/base"), args.outputBase());
    }

    @Test
    void reportsUnsupportedOptions() {
        var args = parse("-f a.fits -analyse 30");
        assertEquals(List.of("-analyse"), args.unsupported());
        assertEquals(Path.of("a.fits"), args.image());
    }

    @Test
    void ignoresUnknownAndInvalidOptions() {
        var args = parse("-f a.fits -foo -fov abc -r");
        assertEquals(List.of("Unknown option -foo", "Invalid value for -fov: abc", "Missing value for -r"), args.problems());
        assertTrue(Double.isNaN(args.fovDeg()));
        assertTrue(Double.isNaN(args.radiusDeg()));
    }

    @Test
    void acceptsDecimalCommas() {
        assertEquals(1.5, parse("-fov 1,5").fovDeg());
    }

    @Test
    void helpNeedsNoImage() {
        var args = parse("-h");
        assertTrue(args.help());
        assertNull(args.image());
    }
}
