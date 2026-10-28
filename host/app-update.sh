#!/usr/bin/env bash
# Installs the newest TabletDesk app on the connected tablet if it is out of date.
# Used by connect.sh on every plug-in; can also be run by hand.
PKG=io.github.aliihjt.tabletdesk
APK_URL=https://github.com/aliihjt/Android-as-ubuntu-keyboard/releases/download/latest-apk

update_app() {
  local cache="$HOME/.cache/tabletdesk" latest installed out
  mkdir -p "$cache"
  latest=$(curl -fsSL --max-time 10 "$APK_URL/version.txt" 2>/dev/null | tr -dc '0-9')
  if [ -z "$latest" ]; then
    echo "[tabletdesk] Could not check for app updates (offline?)."
    return 0
  fi
  installed=$(adb shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r' \
    | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)
  if [ -n "$installed" ] && [ "$installed" -ge "$latest" ]; then
    echo "[tabletdesk] Tablet app is up to date (build $installed)."
    return 0
  fi
  echo "[tabletdesk] Installing TabletDesk app build $latest on the tablet..."
  curl -fsSL --max-time 120 -o "$cache/TabletDesk.apk" "$APK_URL/TabletDesk.apk" || {
    echo "[tabletdesk] Download failed."; return 1; }
  out=$(adb install -r "$cache/TabletDesk.apk" 2>&1)
  if echo "$out" | grep -q -e INSTALL_FAILED_UPDATE_INCOMPATIBLE -e "signatures do not match"; then
    # older builds were signed with a random key: replace the app once
    adb uninstall "$PKG" >/dev/null 2>&1
    out=$(adb install "$cache/TabletDesk.apk" 2>&1)
  fi
  if echo "$out" | grep -q Success; then
    adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow 2>/dev/null
    echo "[tabletdesk] Tablet app updated to build $latest."
  else
    echo "[tabletdesk] App install failed: $out"
    return 1
  fi
}

# run directly: ./host/app-update.sh
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  adb wait-for-usb-device
  update_app
fi
