#!/usr/bin/env bash
# Builds the test-only PowerNukkitX plugin used by FormScenario/UiScenario.
# usage: build.sh <powernukkitx.jar> <output plugin jar>
set -euo pipefail
PNX_JAR=${1:?powernukkitx.jar}; OUT=${2:?output jar}
SRC=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
javac --release 21 -nowarn -cp "$PNX_JAR" -d "$WORK" "$SRC"/jtbtest/*.java
cp "$SRC/plugin.yml" "$WORK/"
mkdir -p "$(dirname "$OUT")"
(cd "$WORK" && jar cf "$OUT" plugin.yml jtbtest)
echo "built $OUT"
