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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AstapIniTest {
    private static final String COMMAND_LINE = "astap/astap_cli -f img/m31.fit -d astap/db -o astap/out/m31";

    @Test
    void writesSolutionsLikeAstap() {
        var outcome = new AstapOutcome(AstapStatus.SOLVED, null, AstapSamples.solution(AstapSamples.M31),
                List.of("Warning scale was inaccurate! Set FOV=1.72d, scale=3.1\", FL=523mm"), 3.4, 42.4);
        // the .ini file written by ASTAP for the same solution
        assertEquals("""
                PLTSOLVD=T
                CRPIX1= 1.5205000000000000E+003
                CRPIX2= 1.0085000000000000E+003
                CRVAL1= 1.0575983821916944E+001
                CRVAL2= 4.1277119598828868E+001
                CDELT1=-8.5470206556581920E-004
                CDELT2= 8.5478911162897170E-004
                CROTA1=-1.3553273021453424E+002
                CROTA2=-1.3553735313328852E+002
                CD1_1= 6.0995875061270910E-004
                CD1_2= 5.9872025473793752E-004
                CD2_1= 5.9873200907712722E-004
                CD2_2=-6.1006918186867418E-004
                CMDLINE=astap/astap_cli -f img/m31.fit -d astap/db -o astap/out/m31
                WARNING=Warning scale was inaccurate! Set FOV=1.72d, scale=3.1", FL=523mm
                """.lines().toList(), AstapIni.format(outcome, COMMAND_LINE).lines().toList());
    }

    @Test
    void writesErrors() {
        var outcome = AstapOutcome.failure(AstapStatus.NOT_ENOUGH_STARS, List.of());
        assertEquals(List.of("PLTSOLVD=F", "CMDLINE=" + COMMAND_LINE, "ERROR=Not enough stars."),
                AstapIni.format(outcome, COMMAND_LINE).lines().toList());
    }

    @Test
    void writesNoErrorWhenThereIsNoSolution() {
        var outcome = AstapOutcome.failure(AstapStatus.NO_SOLUTION, List.of());
        assertEquals(List.of("PLTSOLVD=F", "CMDLINE=" + COMMAND_LINE), AstapIni.format(outcome, COMMAND_LINE).lines().toList());
    }
}
