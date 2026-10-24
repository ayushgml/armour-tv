#!/usr/bin/env bash
# Go back to the previous launcher (tv-vibehack's tvhome) and remove Armour.
#   TV_HOST=<tv ip> armour/uninstall.sh
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address}"
printf '%s\n' \
  "pm enable --user 0 com.gathin.tvhome" \
  "cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity" \
  "settings delete secure screensaver_components" \
  "settings put secure enabled_accessibility_services com.gathin.tvhome/.InputKeyService" \
  "cmd notification disallow_listener com.ayush.armour/com.ayush.armour.MediaListener" \
  "pm uninstall com.ayush.armour" \
  "input keyevent KEYCODE_HOME" \
  | ../tools/venv/bin/python -I ../tools/tv.py --batch
