#!/bin/sh
# Local loopback run of the backend + UI without Android:
#   tools/run-local.sh            (starts codexd on 127.0.0.1:8123)
# Uses CX_TOKEN/CX_PIN from the environment; prints the URL to open.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=${WORK:-/tmp/cx-local}
mkdir -p "$WORK/root" "$WORK/ui" "$WORK/codexweb"
# Sources live under src/; assets/payload/ is a build artifact that build-apk.sh
# regenerates and may be stale. Always run from src/ so local verification uses
# the exact code that gets packaged.
cp "$ROOT/src/frontend/index.html" "$WORK/ui/index.html"
cp "$ROOT/src/backend/codexd.py" "$WORK/codexweb/codexd.py"
[ -n "$CX_TOKEN" ] || CX_TOKEN=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
[ -n "$CX_PIN" ] || CX_PIN=0000
echo "open: http://127.0.0.1:${CX_PORT:-8123}/?token=$CX_TOKEN   (PIN $CX_PIN)"
exec env CX_TOKEN="$CX_TOKEN" CX_PIN="$CX_PIN" CX_PORT="${CX_PORT:-8123}" \
  CX_BIND="${CX_BIND:-127.0.0.1}" CX_ROOT="$WORK/root" CX_UI="$WORK/ui" \
  python3 -B "$WORK/codexweb/codexd.py"
