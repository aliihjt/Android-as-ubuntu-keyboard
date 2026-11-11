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
    local why="$out"
    case "$out" in
      *USER_RESTRICTED*|*"Install canceled by user"*)
        why="the tablet blocked it. Xiaomi/Redmi/POCO: Developer options > turn ON 'Install via USB' (and 'USB debugging (Security settings)'), then plug in again." ;;
      *VERIFICATION_FAILURE*|*"verifier"*)
        why="Play Protect blocked it. Watch the tablet screen and tap 'Install anyway', or turn off Developer options > 'Verify apps over USB'." ;;
    esac
    echo "[tabletdesk] App install failed: $why"
    command -v notify-send >/dev/null && notify-send -a TabletDesk "TabletDesk app update failed" "$why"
    return 1
  fi
}

# iiSU game launcher on the tablet (Controller page › "When a controller connects")
IISU_PKG=com.iisulauncher
IISU_RELEASES="https://api.github.com/repos/iisu-network/iiSU/releases?per_page=5"

install_iisu() {
  if adb shell pm list packages "$IISU_PKG" 2>/dev/null | tr -d '\r' | grep -qx "package:$IISU_PKG"; then
    echo "[tabletdesk] iiSU is already on the tablet."
    return 0
  fi
  local cache="$HOME/.cache/tabletdesk" url out
  mkdir -p "$cache"
  # newest release (alpha builds included) that has an APK attached
  url=$(curl -fsSL --max-time 15 -H "Accept: application/vnd.github+json" "$IISU_RELEASES" 2>/dev/null | python3 -c '
import json, sys
try:
    releases = json.load(sys.stdin)
except ValueError:
    releases = []
for r in releases:
    for a in r.get("assets", []):
        if a.get("name", "").lower().endswith(".apk"):
            print(a["browser_download_url"]); sys.exit()')
  if [ -z "$url" ]; then
    echo "[tabletdesk] Could not find the iiSU download (offline?). Get it from github.com/iisu-network/iiSU/releases"
    return 1
  fi
  echo "[tabletdesk] Installing iiSU on the tablet: ${url##*/}"
  curl -fsSL --max-time 300 -o "$cache/iiSU.apk" "$url" || { echo "[tabletdesk] iiSU download failed."; return 1; }
  out=$(adb install -r "$cache/iiSU.apk" 2>&1)
  if echo "$out" | grep -q Success; then
    echo "[tabletdesk] iiSU installed."
  else
    echo "[tabletdesk] iiSU install failed: $out"
    echo "             Watch the tablet for 'Install anyway', or Xiaomi: Developer options › Install via USB."
    return 1
  fi
}

# run directly: ./host/app-update.sh
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  adb wait-for-usb-device
  update_app
fi
