#!/bin/bash
#
# Builds the indexes of the catalogs which are published already indexed, packs them, and uploads
# them to a GitHub release of the catalog repository. Requires the GitHub CLI (gh), logged in with
# the right to create releases, about 30 GB of disk space and 8 GB of memory.
#
# usage: ./publish-catalogs.sh [work directory] [owner/repository]
#
# The work directory defaults to build/publish-catalogs: when the script is interrupted, running it
# again resumes the downloads. Indexes which are already in its catalogs directory aren't built again,
# and catalogs which are published already aren't either: an index which was built elsewhere is
# published by putting it in that directory. The repository defaults to asterion-solver/catalogs, the
# one Asterion downloads catalogs from.
#
# The release is named after the format of index files, e.g. catalogs-v1: Asterion downloads the
# catalogs of the format it reads. Running the script again uploads the files which changed, and the
# manifest. With DRY_RUN=1, it stops before uploading anything.
set -euo pipefail

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
    sed -n '3,15p' "$0" | sed 's/^# \{0,1\}//'
    exit 0
fi

ROOT=$(cd "$(dirname "$0")" && pwd)
WORK=${1:-$ROOT/build/publish-catalogs}
REPOSITORY=${2:-asterion-solver/catalogs}
CATALOGS=tycho2,gaia-500,gaia-1000,gaia-2000,gaia-3000
# the release of the format of index files of this version of Asterion
TAG=catalogs-v$(sed -n 's/.*int VERSION = \([0-9]*\);.*/\1/p' "$ROOT/asterion-core/src/main/java/me/champeau/asterion/index/StarIndex.java")

# the files of a previous run are packed again, with the manifest of the release: the catalogs which
# aren't packed again keep their published files
mkdir -p "$WORK/release"
rm -f "$WORK"/release/*
gh release download "$TAG" --repo "$REPOSITORY" --pattern catalogs.properties --dir "$WORK/release" 2> /dev/null || true
PUBLISHED_CATALOGS=$(sed -n 's/^catalogs=//p' "$WORK/release/catalogs.properties" 2> /dev/null || true)

"$ROOT/gradlew" -p "$ROOT" -q :asterion-cli:installDist
ASTERION=$(ls "$ROOT"/asterion-cli/build/install/*/bin/asterion)

# the indexes are built from the stars of the catalogs, not downloaded from a previous release;
# downloads resume where they stopped if the script is interrupted. Indexes which are already in the
# work directory are published as they are, and the ones which are published already are kept
MISSING=()
for CATALOG in ${CATALOGS//,/ }; do
    if [ ! -f "$WORK/catalogs/$CATALOG.astx" ] && [[ ",$PUBLISHED_CATALOGS," != *",$CATALOG,"* ]]; then
        MISSING+=("$CATALOG")
    fi
done
if [ ${#MISSING[@]} -gt 0 ]; then
    "$ASTERION" --plain --catalog-dir "$WORK/catalogs" --from-sources --keep-downloads \
        --download-catalog "$(IFS=,; echo "${MISSING[*]}")"
fi
"$ASTERION" --plain --catalog-dir "$WORK/catalogs" --pack-catalogs "$WORK/release"
if [ "${DRY_RUN:-}" = 1 ]; then
    echo "Dry run: the files to publish to $TAG are in $WORK/release"
    exit 0
fi

if ! gh release view "$TAG" --repo "$REPOSITORY" > /dev/null 2>&1; then
    gh release create "$TAG" --repo "$REPOSITORY" --title "Star catalogs, index format ${TAG#catalogs-v}" --notes-file - <<EOF
Star catalogs indexed for [Asterion Solver](https://github.com/asterion-solver/asterion), for the index format ${TAG#catalogs-v}.
They are installed with \`asterion --download-catalog <name>\`: there is no need to download these files by hand.

## Credits

- **Gaia DR3** (\`gaia-*\`): this work has made use of data from the European Space Agency (ESA) mission
  [Gaia](https://www.cosmos.esa.int/gaia), processed by the Gaia Data Processing and Analysis Consortium
  ([DPAC](https://www.cosmos.esa.int/web/gaia/dpac/consortium)). Funding for the DPAC has been provided by
  national institutions, in particular the institutions participating in the Gaia Multilateral Agreement.
  Gaia Collaboration, Vallenari et al. (2023), A&A 674, A1. The indexes derived from Gaia DR3 are distributed
  under the [CC BY-SA 3.0 IGO](https://creativecommons.org/licenses/by-sa/3.0/igo/) license.
- **Tycho-2** (\`tycho2\`): Høg et al. (2000), A&A 355, L27, retrieved from the
  [CDS](https://cdsarc.cds.unistra.fr/viz-bin/cat/I/259), Strasbourg.
EOF
fi

# only the parts which aren't published yet are uploaded: packing gives the same files for the same
# index, so they have the checksums of the published manifest
PUBLISHED=$(gh release download "$TAG" --repo "$REPOSITORY" --pattern catalogs.properties --output - 2> /dev/null || true)
UPLOADS=()
for PART in "$WORK"/release/*; do
    NAME=$(basename "$PART")
    [ "$NAME" = catalogs.properties ] && continue
    CHECKSUM=$(grep "^$NAME.sha256=" "$WORK/release/catalogs.properties" | cut -d= -f2)
    if echo "$PUBLISHED" | grep -qx "$NAME.sha256=$CHECKSUM"; then
        echo "$NAME is already published"
    else
        UPLOADS+=("$PART")
    fi
done
if [ ${#UPLOADS[@]} -gt 0 ]; then
    gh release upload "$TAG" "${UPLOADS[@]}" --repo "$REPOSITORY" --clobber
fi
# the manifest is uploaded last: it never describes files which aren't there yet
gh release upload "$TAG" "$WORK/release/catalogs.properties" --repo "$REPOSITORY" --clobber
echo "Published $CATALOGS to https://github.com/$REPOSITORY/releases/tag/$TAG"
