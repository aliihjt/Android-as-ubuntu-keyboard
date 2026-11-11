#!/usr/bin/env bash
# Chrome Handoff: adds the "Fedora Pad Handoff" extension to Google Chrome, so the
# page open in Chrome can be continued on the tablet and iPhone (and back).
# Chrome only lets you add your own extension by hand, so this opens the page and
# tells you exactly what to click.
set -uo pipefail
DIR=$(cd "$(dirname "$0")/chrome-handoff" && pwd)
echo
echo "1. In Chrome, turn on \"Developer mode\" (top right of the page that opens)."
echo "2. Click \"Load unpacked\" and choose this folder:"
echo "      $DIR"
echo "3. \"Fedora Pad Handoff\" appears in the list. Done: open any page in Chrome,"
echo "   and the tablet's Dock shows 🌐 (the iPhone shows \"💻 Continue: …\")."
echo
printf '%s' "$DIR" | (wl-copy 2>/dev/null || xclip -selection clipboard 2>/dev/null) && echo "(The folder path is copied: paste it with Ctrl+L in the folder picker.)"
for c in google-chrome google-chrome-stable chromium-browser chromium; do
  if command -v "$c" >/dev/null; then "$c" chrome://extensions >/dev/null 2>&1 & exit 0; fi
done
if flatpak info com.google.Chrome >/dev/null 2>&1; then
  flatpak run com.google.Chrome chrome://extensions >/dev/null 2>&1 &
else
  echo "Chrome wasn't found: open chrome://extensions in Chrome yourself."
fi
