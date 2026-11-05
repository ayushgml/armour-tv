#!/usr/bin/env bash
# Disable every package listed in a debloat file on the TV (one package per line, # comments allowed).
# Packages are disabled for user 0, never uninstalled. Undo with tools/restore.sh <same file>.
#
# Usage: TV_HOST=192.168.1.50 tools/disable.sh examples/croma-2k-nstv/debloat.txt
set -euo pipefail
cd "$(dirname "$0")/.."
LIST="${1:?usage: disable.sh <debloat list>}"
PY=tools/venv/bin/python
[ -x "$PY" ] || PY=tools/venv/Scripts/python.exe
export MSYS_NO_PATHCONV=1
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt"; exit 1; }
# Complete validation before opening the ADB connection.
BATCH="$("$PY" -I tools/package_commands.py disable "$LIST")"
printf '%s\n' "$BATCH" | "$PY" -I tools/tv.py --batch
