#!/usr/bin/env bash
# Build the runnable distribution of this branch:
#   make_dist.sh [DEST]        (default /home/lumen/instancelabs/dist/openpnp-il)
# DEST gets openpnp-il.jar, lib/ (runtime dependencies, the jar's manifest Class-Path),
# openpnp-il.sh (launcher) and BUILD-INFO (commit, build time, sha256). Run from a clean tree:
# it refuses to package uncommitted changes under src/main or pom.xml.
set -euo pipefail
REPO="$(cd "$(dirname "$(readlink -f "$0")")/../.." && pwd)"
DEST="${1:-/home/lumen/instancelabs/dist/openpnp-il}"
cd "$REPO"
if ! git diff --quiet HEAD -- src/main pom.xml; then
  echo "uncommitted changes under src/main or pom.xml; commit first" >&2; exit 2
fi
mvn -q -T1 -DskipTests package
JAR="$(ls target/openpnp-gui-*.jar | head -1)"
rm -rf "$DEST.tmp"
mkdir -p "$DEST.tmp/lib"
cp "$JAR" "$DEST.tmp/openpnp-il.jar"
cp target/lib/*.jar "$DEST.tmp/lib/"
cp tools/instancelabs/openpnp-il.sh "$DEST.tmp/openpnp-il.sh"
chmod +x "$DEST.tmp/openpnp-il.sh"
{
  echo "branch: $(git rev-parse --abbrev-ref HEAD)"
  echo "commit: $(git rev-parse HEAD)"
  echo "built:  $(date -Is)"
  echo "java:   $(java -version 2>&1 | head -1)"
  echo "jar sha256: $(sha256sum "$DEST.tmp/openpnp-il.jar" | cut -d' ' -f1)"
  echo "lib jars: $(ls "$DEST.tmp/lib" | wc -l)"
} > "$DEST.tmp/BUILD-INFO"
rm -rf "$DEST"
mv "$DEST.tmp" "$DEST"
cat "$DEST/BUILD-INFO"
