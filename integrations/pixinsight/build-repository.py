#!/usr/bin/env python3
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
Builds the PixInsight update repository of the Asterion Solver script: the package of a version, and
updates.xri, the index which lists it. PixInsight reads the index from the URL of the repository, which
users add in Resources > Updates > Manage Repositories, and installs the files of the package relative
to its installation directory.

    build-repository.py 0.3.0 [--output site/pixinsight] [--working-tree]

The package has the script and the license of the release: the ones of its tag, v0.3.0, dated with
the publication of its GitHub release, which the GitHub CLI reads. With --working-tree, it has the ones
of the working tree, dated now, to test a package. A package is deterministic: the same release gives the
same zip, and so the same SHA-1, which is how PixInsight knows whether a package is installed already.
The index lists only that package, and the previous ones are removed: PixInsight would install every
listed package which isn't installed, in no defined order.
"""

import argparse
import hashlib
import json
import re
import subprocess
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[2]
# the files of the package, relative to the root of the repository
SCRIPT = "integrations/pixinsight/AsterionSolver.js"
LICENSE = "LICENSE.txt"
GITHUB_REPOSITORY = "asterion-solver/asterion"
# where PixInsight installs the files of the package, relative to its installation directory
INSTALLED = "src/scripts/AsterionSolver/"
# the versions of PixInsight which the script runs on: it checks the minimum itself
PIXINSIGHT_VERSIONS = "1.9.4:1.9.99"


def released(version: str) -> tuple[bytes, bytes, datetime]:
    """The script and the license of a release, and the date of its publication."""
    def show(path: str) -> bytes:
        return subprocess.run(["git", "-C", str(ROOT), "show", f"v{version}:{path}"], check=True, capture_output=True).stdout
    published = json.loads(subprocess.run(
        ["gh", "release", "view", f"v{version}", "--repo", GITHUB_REPOSITORY, "--json", "publishedAt"],
        check=True, capture_output=True, text=True).stdout)["publishedAt"]
    return show(SCRIPT), show(LICENSE), datetime.strptime(published, "%Y-%m-%dT%H:%M:%SZ")


def package(version: str, script: bytes, license_text: bytes, output: Path) -> Path:
    """Writes the zip of the package, with the script stamped with its version."""
    stamped, count = re.subn(r'#define ASTERION_VERSION "[^"]*"', f'#define ASTERION_VERSION "{version}"', script.decode("utf-8"))
    if count != 1:
        raise SystemExit(f"The version of the script wasn't found in {SCRIPT}")
    target = output / f"asterion-pixinsight-{version}.zip"
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, content in ((INSTALLED + "AsterionSolver.js", stamped.encode("utf-8")),
                              (INSTALLED + "LICENSE.txt", license_text)):
            # a fixed date makes the zip deterministic
            entry = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o644 << 16
            archive.writestr(entry, content)
    return target


def index(version: str, zip_file: Path, release: datetime) -> str:
    """The index of the repository, which lists the package."""
    sha1 = hashlib.sha1(zip_file.read_bytes()).hexdigest()
    paragraphs = [
        "Solves images blindly in a fraction of a second, and writes the astrometric solution in them, "
        "where the astrometry tools of PixInsight find it. The script is in Script > Astrometry > Asterion Solver.",
        "It runs the asterion command line tool, which is installed separately: see "
        "https://github.com/asterion-solver/asterion. Its star catalogs are downloaded by asterion from the "
        "dialog of the script, over HTTPS, and checked with their SHA-256 checksums.",
    ]
    description = "\n".join(f"            <p>{escape(p)}</p>" for p in paragraphs)
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<xri version="1.0">
   <description>
      <p>Asterion Solver for PixInsight: a fast blind plate solver.</p>
   </description>
   <platform os="all" arch="noarch" version="{PIXINSIGHT_VERSIONS}">
      <package fileName="{zip_file.name}" sha1="{sha1}" type="script" releaseDate="{release:%Y%m%d%H%M%S}">
         <title>Asterion Solver {escape(version)}</title>
         <description>
{description}
         </description>
      </package>
   </platform>
</xri>
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("version", help="the version of Asterion, such as 0.3.0")
    parser.add_argument("--output", type=Path, default=ROOT / "site" / "pixinsight",
                        help="the directory of the repository: site/pixinsight by default")
    parser.add_argument("--working-tree", action="store_true",
                        help="package the script of the working tree, dated now, instead of the one of the release")
    arguments = parser.parse_args()
    if arguments.working_tree:
        script, license_text, date = (ROOT / SCRIPT).read_bytes(), (ROOT / LICENSE).read_bytes(), datetime.now(timezone.utc)
    else:
        script, license_text, date = released(arguments.version)
    arguments.output.mkdir(parents=True, exist_ok=True)
    for previous in arguments.output.glob("asterion-pixinsight-*.zip"):
        previous.unlink()
    zip_file = package(arguments.version, script, license_text, arguments.output)
    (arguments.output / "updates.xri").write_text(index(arguments.version, zip_file, date), encoding="utf-8")
    print(f"Wrote {zip_file} and {arguments.output / 'updates.xri'}")


if __name__ == "__main__":
    main()
