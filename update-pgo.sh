#!/bin/bash
#
# Regenerates the profile used for profile-guided optimization (PGO) of the native executable.
# Requires Oracle GraalVM. The profile should be refreshed when the solver changes significantly.
#
# usage: ./update-pgo.sh <asterion options and images>
#
# The arguments are passed to an instrumented build of asterion: the workload should look
# like real use, with images of various kinds, including some which can't be solved, for example:
#
#   ./update-pgo.sh --wcs --json ~/frames/*.fits ~/frames/*.jpg
#
# To profile the index builder too, install a catalog in a scratch directory as part of the run:
#
#   ./update-pgo.sh --catalog-dir /tmp/catalogs --download-catalog tycho2 ~/frames/*.fits
#
set -e

if [ $# -eq 0 ]; then
    sed -n '3,15p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
fi

ROOT=$(cd "$(dirname "$0")" && pwd)
PROFILES="$ROOT/asterion-cli/src/pgo-profiles/main"
WORK=$(mktemp -d)

# the terminal backend is left out: GraalVM fails to instrument it, and the workload doesn't use it
"$ROOT/gradlew" -p "$ROOT" :asterion-cli:nativeCompile --pgo-instrument -PwithoutTerminalBackend

# the profile is written to the working directory when the process exits;
# a failure to solve some of the images is not an error here
(cd "$WORK" && "$ROOT/asterion-cli/build/native/nativeCompile/asterion-instrumented" "$@" > /dev/null) || true

if [ ! -f "$WORK/default.iprof" ]; then
    echo "No profile was generated" >&2
    exit 1
fi
mkdir -p "$PROFILES"
mv "$WORK/default.iprof" "$PROFILES/default.iprof"
rmdir "$WORK" 2>/dev/null || true

"$ROOT/gradlew" -p "$ROOT" :asterion-cli:nativeCompile
