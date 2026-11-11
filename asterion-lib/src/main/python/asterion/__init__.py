# Copyright 2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""
Asterion Solver, a fast blind astrometric plate solver: finds the celestial coordinates, scale and orientation of
images which are in memory, such as the frames of a camera.

    import asterion

    asterion.install_catalog("tycho2")          # once per machine

    with asterion.Solver() as solver:           # the catalogs stay open between solves
        result = solver.solve(frame)            # a 2D array of gray levels
        if result.solved:
            print(result.ra, result.dec, result.scale)
"""
from importlib import metadata as _metadata

from ._catalogs import (Catalog, InstallationCancelled, Progress, catalogs, default_catalog_directory, install_catalog,
                        version)
from ._native import AsterionError, Cancellation
from ._solver import DeepSkyObject, Match, Result, SolveCancelled, Solver

try:
    __version__ = _metadata.version("asterion-solver")
except _metadata.PackageNotFoundError:
    # not installed: the sources
    __version__ = "unknown"

__all__ = ["AsterionError", "Cancellation", "Catalog", "DeepSkyObject", "InstallationCancelled", "Match", "Progress", "Result",
           "SolveCancelled", "Solver", "catalogs", "default_catalog_directory", "install_catalog", "version"]
