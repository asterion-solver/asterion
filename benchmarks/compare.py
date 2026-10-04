#!/usr/bin/env python3
#
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
#
"""
Compares Asterion Solver with ASTAP on a set of FITS images: success rate, wall clock time of the
whole command, and accuracy of the solution.

Accuracy is measured the same way for both solvers: the stars which Asterion matched with Gaia
DR3 are converted to sky coordinates with the WCS written by each solver, and compared with their
catalog position. This requires a Gaia catalog to be installed for Asterion.

Requires numpy and astropy. See README.md.
"""
import argparse
import glob
import json
import os
import subprocess
import time
import warnings

import numpy as np
from astropy.io import fits
from astropy.wcs import WCS

warnings.simplefilter('ignore')


def separation(ra1, dec1, ra2, dec2):
    """Angular distance in arcseconds, for small angles."""
    return np.hypot((ra1 - ra2) * np.cos(np.radians(dec1)), dec1 - dec2) * 3600


def residuals(wcs_file, matches):
    wcs = WCS(fits.getheader(wcs_file))
    ra, dec = wcs.all_pix2world(matches[:, 0], matches[:, 1], 0)
    return separation(ra, dec, matches[:, 2], matches[:, 3])


def base(path):
    return os.path.splitext(path)[0]


def clean(path, extensions):
    for extension in extensions:
        if os.path.lexists(base(path) + extension):
            os.remove(base(path) + extension)


def run_asterion(args, path, blind):
    clean(path, ('.wcs', '.matches.csv'))
    command = [args.asterion, '--wcs', '--matches', path]
    if args.catalog_dir:
        command += ['--catalog-dir', args.catalog_dir]
    if blind:
        command.append('--no-hints')
    start = time.time()
    subprocess.run(command, capture_output=True)
    wall = time.time() - start
    return wall, os.path.exists(base(path) + '.wcs')


def run_astap(args, path, blind, hinted):
    clean(path, ('.wcs', '.ini'))
    command = [args.astap, '-f', path, '-d', args.astap_db, '-sip', '-wcs']
    if blind:
        # start from the north pole, and search the whole sky
        command += ['-r', '180', '-ra', '0', '-spd', '90']
    elif not hinted:
        command += ['-r', '180']
    if not hinted:
        command += ['-fov', '0']
    start = time.time()
    subprocess.run(command, capture_output=True)
    wall = time.time() - start
    return wall, os.path.exists(base(path) + '.wcs')


def summarize(name, records):
    walls = np.array([r['wall'] for r in records])
    solved = [r for r in records if r['solved']]
    line = '%-28s solved %d/%d, time median %.3f s, mean %.3f s, max %.3f s' % (name, len(solved), len(records), np.median(walls), walls.mean(), walls.max())
    rms = [r['rms'] for r in solved if 'rms' in r]
    if rms:
        line += ', RMS median %.2f", mean %.2f", max %.2f"' % (np.median(rms), np.mean(rms), np.max(rms))
    print(line)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('images', nargs='+', help='FITS images, or glob patterns')
    parser.add_argument('--asterion', default='asterion', help='path of the asterion executable')
    parser.add_argument('--catalog-dir', help='directory of Asterion catalogs')
    parser.add_argument('--astap', help='path of the astap_cli executable')
    parser.add_argument('--astap-db', help='directory of the ASTAP star database')
    parser.add_argument('--output', default='benchmark.json', help='where to write detailed results')
    args = parser.parse_args()

    images = sorted(f for pattern in args.images for f in glob.glob(pattern))
    results = {}
    for blind in (False, True):
        mode = 'blind' if blind else 'with header hints'
        ours = []
        theirs = []
        for path in images:
            header = fits.getheader(path)
            hinted = 'RA' in header or 'OBJCTRA' in header
            wall, solved = run_asterion(args, path, blind)
            record = {'file': path, 'wall': wall, 'solved': solved}
            matches = None
            if solved:
                matches = np.loadtxt(base(path) + '.matches.csv', delimiter=',', skiprows=1)
                record['rms'] = float(np.sqrt(np.mean(residuals(base(path) + '.wcs', matches) ** 2)))
                record['matches'] = len(matches)
            ours.append(record)
            if args.astap:
                wall, solved = run_astap(args, path, blind, hinted)
                record = {'file': path, 'wall': wall, 'solved': solved}
                if solved and matches is not None:
                    record['rms'] = float(np.sqrt(np.mean(residuals(base(path) + '.wcs', matches) ** 2)))
                theirs.append(record)
            clean(path, ('.wcs', '.ini', '.matches.csv'))
        summarize('Asterion, ' + mode, ours)
        results['Asterion, ' + mode] = ours
        if args.astap:
            summarize('ASTAP, ' + mode, theirs)
            results['ASTAP, ' + mode] = theirs
    with open(args.output, 'w') as out:
        json.dump(results, out, indent=1)


if __name__ == '__main__':
    main()
