#!/usr/bin/env bash
# Browser test of the web UI against an OpenPnP *simulator* (never the real machine):
#   1. openpnp-uinav launches this branch's jar in sim mode: a COPY of the config dir, the driver
#      rewired to OpenPnP's simulated G-code server, simulated cameras, its own Xvfb display, device
#      stubs first on the classpath (no /dev/tty*, no /dev/video*).
#   2. The copy gets the plan §4.4 DISABLE_COMMAND and visual homing off (the simulated camera has
#      no homing fiducial). The web API listens on $IL_API_PORT (env var read by ApiSettings).
#   3. Playwright (headless Chromium) runs webui-sim.playwright.cjs.
#
# Needs: target/openpnp-gui-*.jar built (mvn -q -T1 -DskipTests package), openpnp-uinav built,
#        Playwright installed in $PLAYWRIGHT_DIR (npm i playwright; npx playwright install chromium).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
UINAV="${UINAV:-/home/lumen/instancelabs/openpnp-uinav/bin/uinav}"
SESSION="${UINAV_SESSION:-webui-test}"
JAR="${OPENPNP_JAR:-$REPO/target/openpnp-gui-0.0.1-alpha-SNAPSHOT.jar}"
CONFIG="${OPENPNP_CONFIG:-$HOME/.openpnp2}"       # only read; uinav copies it
export IL_API_PORT="${IL_API_PORT:-18765}"
export PLAYWRIGHT_DIR="${PLAYWRIGHT_DIR:-/tmp/il-webui-pw}"
export OUT_DIR="${OUT_DIR:-/tmp/il-webui-test-out}"

[ -f "$JAR" ] || { echo "build the jar first: $JAR" >&2; exit 2; }
if ss -ltn | grep -q ":$IL_API_PORT "; then echo "port $IL_API_PORT is in use" >&2; exit 2; fi

cleanup() { "$UINAV" --session "$SESSION" stop >/dev/null 2>&1 || true; }
trap cleanup EXIT

OPENPNP_IL_API_PORT="$IL_API_PORT" "$UINAV" --session "$SESSION" launch --mode sim \
  --config "$CONFIG" --openpnp-jar "$JAR" \
  --driver-command 'DISABLE_COMMAND=M410\nM107 P3\nM107 P2\nM107 P1\nM107 P0\nM18 B' \
  --xml-set ".//head@visual-homing-method=None"

for _ in $(seq 1 120); do
  curl -sf "http://127.0.0.1:$IL_API_PORT/api/status" >/dev/null && break
  sleep 0.5
done
curl -sf "http://127.0.0.1:$IL_API_PORT/api/status" >/dev/null || { echo "web API did not come up" >&2; exit 1; }

export IL_API_URL="http://127.0.0.1:$IL_API_PORT/"
export UINAV UINAV_SESSION="$SESSION"
export PLAYWRIGHT_BROWSERS_PATH="${PLAYWRIGHT_BROWSERS_PATH:-/tmp/il-webui-pw-browsers}"
node "$HERE/webui-sim.playwright.cjs"
