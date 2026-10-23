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

using System.ComponentModel.Composition;
using NINA.PlateSolving.Interfaces;
using NINA.Profile.Interfaces;
using NINA.WPF.Base.Interfaces.Mediator;

namespace Asterion.Nina;

/// <summary>
/// Asterion, as a plate solver of N.I.N.A.: the primary solver, which searches the region of the
/// sky N.I.N.A. gives, and the blind solver, which searches the whole sky. Its settings are shown
/// in Options > Plate Solving, see Options.xaml.
/// </summary>
[Export(typeof(IPlateSolverProvider))]
public sealed class AsterionSolverProvider : IPlateSolverProvider {
    [ImportingConstructor]
    public AsterionSolverProvider(IProfileService profileService, IApplicationStatusMediator statusMediator) {
        Settings = AsterionSettings.Shared(profileService, statusMediator);
    }

    /// <summary>The identity of the solver in the profiles of N.I.N.A.: it must never change.</summary>
    public string Id => "io.github.asterion-solver.asterion";

    public string Name => "Asterion";

    public bool SupportsPrimary => true;

    public bool SupportsBlind => true;

    public AsterionSettings Settings { get; }

    public IPlateSolver CreateSolver(IPlateSolveSettings settings) => new AsterionPlateSolver(Settings);
}
