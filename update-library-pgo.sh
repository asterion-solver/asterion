#!/bin/bash
#
# Regenerates the profile used for profile-guided optimization (PGO) of the native library. Requires
# Oracle GraalVM. The profile should be refreshed when the solver or the library change significantly.
#
# usage: ./update-library-pgo.sh <images>
#
# An instrumented build of the library solves the images with the installed catalogs, the way
# programs do: the workload should look like real use, with images of various kinds, including some
# which can't be solved, for example:
#
#   ./update-library-pgo.sh ~/frames/*.fits ~/frames/*.jpg
#
set -e

if [ $# -eq 0 ]; then
    sed -n '3,13p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
fi

ROOT=$(cd "$(dirname "$0")" && pwd)
PROFILES="$ROOT/asterion-lib/src/pgo-profiles/main"
WORK="$ROOT/asterion-lib/build/pgo"
CATALOGS="${ASTERION_CATALOGS:-$HOME/.asterion/catalogs}"

"$ROOT/gradlew" -p "$ROOT" :asterion-lib:nativeCompile --pgo-instrument
LIBRARY=$(ls "$ROOT"/asterion-lib/build/native/nativeCompile/libasterion-instrumented.{so,dylib,dll} 2>/dev/null | head -1)
if [ -z "$LIBRARY" ]; then
    echo "No instrumented library was built" >&2
    exit 1
fi

# the profile is written to the working directory when the isolate of the library is torn down
rm -f "$WORK/default.iprof"
ARGS="\"$LIBRARY\" \"$CATALOGS\""
for image in "$@"; do
    ARGS="$ARGS \"$(cd "$(dirname "$image")" && pwd)/$(basename "$image")\""
done
"$ROOT/gradlew" -p "$ROOT" :asterion-lib:pgoWorkload --args="$ARGS"

if [ ! -f "$WORK/default.iprof" ]; then
    echo "No profile was generated" >&2
    exit 1
fi
mkdir -p "$PROFILES"
mv "$WORK/default.iprof" "$PROFILES/default.iprof"

"$ROOT/gradlew" -p "$ROOT" :asterion-lib:nativeCompile
