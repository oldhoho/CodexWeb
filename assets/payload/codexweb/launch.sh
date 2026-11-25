#!/bin/sh
# Runs inside the proot guest. Token and PIN arrive from the launcher through
# the environment so neither is ever written to disk or printed.
set -u
cd /codexweb || exit 1
export PATH=/opt/codex:/usr/local/bin:/usr/bin:/bin
export HOME=/root
export TERM=dumb
export LANG=C.UTF-8
export CX_TOKEN="${CX_TOKEN:?missing token}"
export CX_PIN="${CX_PIN:?missing pin}"
export CX_LAUNCH_KEY="${CX_LAUNCH_KEY:-}"
mkdir -p /root/codex-web/sessions /root/.codex
# No "ready" line here: codexd.py prints CXINFO ready itself, after the HTTP
# socket is actually bound. Printing it before exec caused a startup race where
# the app opened the page before /api/health could answer.
exec python3 -B /codexweb/codexd.py
