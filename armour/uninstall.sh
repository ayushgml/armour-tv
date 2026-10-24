#!/usr/bin/env bash
# Remove Armour and give the TV its previous home screen back: the stock Google TV / Android TV home
# (re-enabled in case a debloat list turned it off), or tv-vibehack's launcher if that was in use.
#   TV_HOST=<tv ip> armour/uninstall.sh
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address}"
tv() { ../tools/venv/bin/python -I ../tools/tv.py "$@"; }
PKG=com.ayush.armour
printf '%s\n' \
  "settings delete secure screensaver_components" \
  "settings delete secure enabled_accessibility_services" \
  "cmd notification disallow_listener $PKG/$PKG.MediaListener" \
  "pm enable --user 0 com.google.android.apps.tv.launcherx" \
  "pm enable --user 0 com.google.android.tvlauncher" \
  "pm enable --user 0 com.gathin.tvhome" \
  "pm uninstall $PKG" \
  | tv --batch >/dev/null 2>&1 || true
# Prefer the stock home; tv-vibehack's launcher only when there is no stock one.
for home in com.google.android.apps.tv.launcherx com.google.android.tvlauncher com.gathin.tvhome; do
  act="$(tv "cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN -p $home" | tail -n 1)"
  case "$act" in */*) tv "cmd package set-home-activity --user 0 $act" >/dev/null; echo "Home now: $act"; break ;; esac
done
tv "input keyevent KEYCODE_HOME" >/dev/null
