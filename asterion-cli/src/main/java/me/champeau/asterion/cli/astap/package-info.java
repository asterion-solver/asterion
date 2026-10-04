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
/**
 * A <b>best effort</b> emulation of the command line interface of the
 * <a href="https://www.hnsky.org/astap.htm">ASTAP</a> plate solver, so that programs which know how
 * to call ASTAP (N.I.N.A., CCDciel, SharpCap...) can use Asterion instead. It is used when the
 * executable is started under the name {@code astap} or {@code astap_cli}, or with
 * {@code asterion astap <options>}.
 * <p>
 * The interface is specified in {@code docs/astap-interface.md}: the options and the files which
 * clients rely on are supported, with the formats of ASTAP. The search itself is Asterion's: the
 * options which tune the algorithm or the star database of ASTAP are accepted, and ignored. The
 * emulation is tested against the outputs of ASTAP and the source of its clients, but ASTAP has no
 * formal specification: details may differ.
 */
package me.champeau.asterion.cli.astap;
