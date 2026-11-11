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

namespace Asterion.Nina.Core;

/// <summary>
/// A solve, as N.I.N.A. asks for it: for the primary solver, the region of the sky where the image is, which is a
/// limit of the search. The blind solver gets no region.
/// </summary>
/// <param name="RaDeg">the right ascension of the center of the region, J2000, in degrees, or null for a blind solve</param>
/// <param name="DecDeg">the declination of the center of the region, J2000, in degrees, or null for a blind solve</param>
/// <param name="RadiusDeg">the radius of the region, in degrees, or 0 for the default one of Asterion</param>
/// <param name="TimeoutSeconds">the time after which Asterion gives up</param>
public sealed record SolveOptions(double? RaDeg, double? DecDeg, double RadiusDeg, int TimeoutSeconds);
