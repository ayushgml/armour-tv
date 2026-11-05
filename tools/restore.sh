#!/usr/bin/env bash
# Re-enable every package listed in a debloat file, restore the stock launcher as home and reset
# animation scales. Pass the same list you used with tools/disable.sh.
#
# Usage: TV_HOST=192.168.1.50 tools/restore.sh examples/croma-2k-nstv/debloat.txt [stock.launcher.package]
set -euo pipefail
cd "$(dirname "$0")/.."
LIST="${1:?usage: restore.sh <debloat list> [stock launcher package]}"
STOCK="${2:-com.google.android.tvlauncher}"
PY=tools/venv/bin/python
[ -x "$PY" ] || PY=tools/venv/Scripts/python.exe
export MSYS_NO_PATHCONV=1
# Complete validation before opening the ADB connection.
BATCH="$("$PY" -I tools/package_commands.py restore "$LIST" "$STOCK")"
printf '%s\n' "$BATCH" | "$PY" -I tools/tv.py --batch
