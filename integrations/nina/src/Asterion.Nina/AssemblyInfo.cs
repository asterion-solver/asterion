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

using System.Reflection;
using System.Runtime.InteropServices;

// The identity of the plugin for N.I.N.A.: it must never change
[assembly: Guid("029fe5f1-f00b-4d8e-8273-c78c091b3b94")]
[assembly: ComVisible(false)]

// The metadata of the plugin, which N.I.N.A. shows. The minimum version of N.I.N.A. is generated from
// NinaMinimumVersion, see Directory.Build.props
[assembly: AssemblyMetadata("License", "Apache-2.0")]
[assembly: AssemblyMetadata("LicenseURL", "https://github.com/asterion-solver/asterion/blob/main/LICENSE.txt")]
[assembly: AssemblyMetadata("Repository", "https://github.com/asterion-solver/asterion")]
[assembly: AssemblyMetadata("Homepage", "https://github.com/asterion-solver/asterion")]
[assembly: AssemblyMetadata("ChangelogURL", "https://github.com/asterion-solver/asterion/releases")]
[assembly: AssemblyMetadata("Tags", "plate solving,blind solving,astrometry,centering,offline")]
[assembly: AssemblyMetadata("LongDescription", """
Asterion Solver is a very fast, modern plate solver. It can generally solve any image within milliseconds even in blind mode: no mount position, no focal length, no search radius needed. A 61 megapixel frame is read, analyzed and solved in about 50 ms on a modern desktop computer, so centering and plate-solve-and-sync stop being something you wait for, and a blind solve costs no more than a hinted one.

It is accurate too: solutions are refined against Gaia DR3 or Tycho-2, with proper motions applied at the date of the frame, and their residuals are typically around 0.1 arcsecond. A bayesian verification makes false solutions extremely unlikely, while tolerating spurious detections, missing stars and optical distortion.

Asterion runs entirely on your computer: once a catalog is installed, no internet connection is needed. Choose it as the plate solver, the blind solver, or both, in Options > Plate Solving.

The plugin includes Asterion Solver itself: download a star catalog once, from the settings of the plugin in Options > Plate Solving. gaia-500 or gaia-1000 is usually a good start.
""")]
