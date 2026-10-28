#!/usr/bin/env bash
# Launch the Instance Labs OpenPnP build (il/integration) from a distribution directory:
#   <dist>/openpnp-il.jar, <dist>/lib/*.jar (the jar's manifest Class-Path), this script.
#
#   openpnp-il.sh [--config DIR] [--api-port N] [--force] [extra JVM options...]
#
#   --config DIR   OpenPnP config dir (default: OpenPnP's own default, ~/.openpnp2). Passed as
#                  -DconfigDir (OpenPnP Main.java:174-178).
#   --api-port N   port of the local web UI / API (default 8765, 127.0.0.1 only; see WEB_API.md)
#   --force        start even if another OpenPnP process is running (only one may own the port)
#
# The web UI is at http://127.0.0.1:<port>/ once the configuration has loaded.
# OpenPnP rewrites <config>/machine.xml when it exits: close OpenPnP before editing that file.
set -euo pipefail
DIST="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
JAR="$DIST/openpnp-il.jar"
CONFIG=""
FORCE=0
JVM=()
while [ $# -gt 0 ]; do
  case "$1" in
    --config) CONFIG="$2"; shift 2 ;;
    --api-port) export OPENPNP_IL_API_PORT="$2"; shift 2 ;;
    --force) FORCE=1; shift ;;
    -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
    *) JVM+=("$1"); shift ;;
  esac
done

command -v java >/dev/null || { echo "java not found (JDK/JRE 17 required)" >&2; exit 2; }
JV="$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
if [ -z "$JV" ] || [ "$JV" -lt 17 ]; then echo "Java 17 or newer required (found: ${JV:-unknown})" >&2; exit 2; fi
[ -f "$JAR" ] || { echo "missing $JAR" >&2; exit 2; }

if [ "$FORCE" != 1 ]; then
  # Another OpenPnP (stock /opt/openpnp or this build) would fight over the serial port.
  if pgrep -u "$(id -u)" -f '[o]rg\.openpnp\.Main|[o]penpnp-gui-.*\.jar|[o]penpnp-il\.jar' >/dev/null; then
    echo "Another OpenPnP is running (pgrep: $(pgrep -u "$(id -u)" -f '[o]rg\.openpnp\.Main|[o]penpnp-gui-.*\.jar|[o]penpnp-il\.jar' | tr '\n' ' '))." >&2
    echo "Close it first (only one program may own the serial port), or pass --force." >&2
    exit 3
  fi
fi

ARGS=(--add-opens=java.base/java.lang=ALL-UNNAMED
      --add-opens=java.desktop/java.awt=ALL-UNNAMED
      --add-opens=java.desktop/java.awt.color=ALL-UNNAMED)
[ -n "$CONFIG" ] && ARGS+=("-DconfigDir=$CONFIG")
exec java "${ARGS[@]}" "${JVM[@]+"${JVM[@]}"}" -jar "$JAR"
