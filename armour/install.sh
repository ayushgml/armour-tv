#!/usr/bin/env bash
# Build Armour and install it on the TV as the home screen.
#   TV_HOST=<tv ip> armour/install.sh
# The OpenRouter key is read from ~/.config/vibe-tv/openrouter.key (if present) and handed to the app;
# it is never printed. Undo: TV_HOST=... armour/uninstall.sh
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address}"
export JAVA_HOME="${JAVA_HOME:-$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$(brew --prefix)/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:$PATH"
PY=../tools/venv/bin/python
tv() { "$PY" -I ../tools/tv.py "$@"; }
PKG=com.ayush.armour

# Self-signed certificate for the phone page (HTTPS is what lets the phone use its microphone).
P12=app/src/main/res/raw/tls.p12
if [ ! -f "$P12" ]; then
  mkdir -p app/src/main/res/raw
  keytool -genkeypair -alias armour -keyalg RSA -keysize 2048 -validity 3650 -storetype PKCS12 \
    -keystore "$P12" -storepass armourtls -keypass armourtls -dname "CN=Armour TV" \
    -ext "SAN=ip:$TV_HOST,dns:android.local" >/dev/null 2>&1
fi

[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease -q --console=plain
APK=app/build/outputs/apk/release/app-release.apk

"$PY" -I ../tools/b64push.py "$APK" /data/local/tmp/armour.apk
printf '%s\n' \
  "pm install -r /data/local/tmp/armour.apk" \
  "rm -f /data/local/tmp/armour.apk" \
  "pm grant $PKG android.permission.CAMERA" \
  "appops set $PKG SYSTEM_ALERT_WINDOW allow" \
  "cmd package compile -m speed -f $PKG" \
  "mkdir -p /sdcard/Android/data/$PKG/files" \
  | tv --batch

if [ -f ~/.config/vibe-tv/openrouter.key ]; then
  tv --push ~/.config/vibe-tv/openrouter.key /sdcard/Android/data/$PKG/files/openrouter.key >/dev/null
  echo "OpenRouter key handed to the TV"
fi

# Start it, then make it the default home.
printf '%s\n' \
  "am start -W -n $PKG/.MainActivity | grep -E '^(Status|Error)'" \
  "cmd package set-home-activity --user 0 $PKG/.MainActivity" \
  "settings put secure screensaver_components $PKG/.NightDream" \
  | tv --batch
# Turn the old launcher off so Android never falls back to it (whenever Armour restarts, a second HOME
# app can win). It stays installed: uninstall.sh re-enables it. Its Input-button service goes with it,
# so Armour's own takes over: the remote's Input button opens Armour's Inputs screen.
printf '%s\n' \
  "pm disable-user --user 0 com.gathin.tvhome" \
  "cmd package set-home-activity --user 0 $PKG/.MainActivity" \
  "settings put secure enabled_accessibility_services $PKG/.InputKeyService" \
  "settings put secure accessibility_enabled 1" \
  "cmd notification allow_listener $PKG/$PKG.MediaListener" \
  | tv --batch >/dev/null
echo "Home now: $(tv 'cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN' | tail -n 1)"
echo "Phone remote: https://$TV_HOST:8443"
