#!/usr/bin/env bash
# Make Google Chrome on Fedora look and feel like Safari:
#   - a Safari theme (light and dark): Safari's greys, white tabs, a quiet
#     address bar, and the macOS wallpaper on the new tab page
#   - two-finger swipe left / right on the touchpad = back / forward
#   - window buttons on the left (with ./host/macos-look.sh)
#   - a short list of Safari-like Chrome settings to switch on
#   ./host/chrome-safari.sh          set up
#   ./host/chrome-safari.sh --undo   back to normal Chrome
set -uo pipefail
DL=$(xdg-user-dir DOWNLOAD 2>/dev/null || echo "$HOME/Downloads")
THEMES="$DL/Safari for Chrome"   # in Downloads, so Flatpak Chrome can open it too
APPS="$HOME/.local/share/applications"
FLAGS="--enable-features=TouchpadOverscrollHistoryNavigation --ozone-platform-hint=auto"

if [ "${1:-}" = "--undo" ]; then
  rm -f "$APPS/google-chrome.desktop" "$APPS/com.google.Chrome.desktop"
  rm -rf "$THEMES"
  update-desktop-database "$APPS" 2>/dev/null || true
  echo "Done. In Chrome: Settings › Appearance › Theme › Reset to default."
  exit 0
fi

# ---- which Chrome? ----
if command -v google-chrome-stable >/dev/null; then
  kind=rpm; desktop=/usr/share/applications/google-chrome.desktop; open_cmd=(google-chrome-stable)
elif flatpak info com.google.Chrome >/dev/null 2>&1; then
  kind=flatpak; open_cmd=(flatpak run com.google.Chrome)
  for d in "$HOME/.local/share/flatpak/exports/share/applications" /var/lib/flatpak/exports/share/applications; do
    [ -f "$d/com.google.Chrome.desktop" ] && desktop="$d/com.google.Chrome.desktop"
  done
else
  echo "Google Chrome isn't installed. Install it first, for example:"
  echo "  flatpak install -y flathub com.google.Chrome      (or ./host/apps-pack.sh)"
  exit 1
fi
echo "==> Found Chrome ($kind)"

# ---- the Safari themes ----
echo "==> Making the Safari theme (light and dark) in: $THEMES"
mkdir -p "$THEMES"
python3 - "$THEMES" <<'PY'
import glob, json, os, sys
out = sys.argv[1]
# Safari's own greys (macOS Sonoma / Sequoia)
THEMES = {
    "Safari Light": {"frame": [236, 236, 236], "frame_inactive": [246, 246, 246], "toolbar": [250, 250, 250],
                     "background_tab": [228, 228, 228], "tab_text": [29, 29, 31], "tab_background_text": [110, 110, 115],
                     "bookmark_text": [29, 29, 31], "toolbar_button_icon": [100, 100, 105],
                     "omnibox_background": [232, 232, 234], "omnibox_text": [29, 29, 31],
                     "ntp_background": [245, 245, 247], "ntp_text": [29, 29, 31], "ntp_link": [0, 122, 255]},
    "Safari Dark": {"frame": [42, 42, 44], "frame_inactive": [36, 36, 38], "toolbar": [50, 50, 52],
                    "background_tab": [38, 38, 40], "tab_text": [245, 245, 247], "tab_background_text": [152, 152, 157],
                    "bookmark_text": [235, 235, 240], "toolbar_button_icon": [200, 200, 205],
                    "omnibox_background": [30, 30, 32], "omnibox_text": [245, 245, 247],
                    "ntp_background": [30, 30, 32], "ntp_text": [245, 245, 247], "ntp_link": [10, 132, 255]},
}
# the macOS wallpaper from macos-look.sh, if it's there
walls = {"Safari Light": ["*Sonoma*light*", "*Ventura*light*", "*WhiteSur*light*"],
         "Safari Dark": ["*Sonoma*dark*", "*Ventura*dark*", "*WhiteSur*dark*"]}
home_bg = os.path.expanduser("~/.local/share/backgrounds")
try:
    import gi
    gi.require_version("GdkPixbuf", "2.0")
    from gi.repository import GdkPixbuf
except (ImportError, ValueError):
    GdkPixbuf = None
for name, colors in THEMES.items():
    d = os.path.join(out, name)
    os.makedirs(os.path.join(d, "images"), exist_ok=True)
    manifest = {"manifest_version": 3, "name": name, "version": "1.0",
                "description": "Safari's look for Chrome (Fedora Pad)",
                "theme": {"colors": colors, "properties": {"ntp_background_alignment": "center"}}}
    found = [f for pat in walls[name] for f in glob.glob(os.path.join(home_bg, "**", pat + ".jpg"), recursive=True)]
    if found and GdkPixbuf:
        try:
            pix = GdkPixbuf.Pixbuf.new_from_file_at_scale(found[0], 2560, -1, True)
            pix.savev(os.path.join(d, "images", "ntp.png"), "png", [], [])
            manifest["theme"]["images"] = {"theme_ntp_background": "images/ntp.png"}
            manifest["theme"]["properties"]["ntp_background_repeat"] = "no-repeat"
        except Exception:  # noqa: BLE001 - the colours alone are fine
            pass
    with open(os.path.join(d, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=1)
    print("    " + name + (" (with the macOS wallpaper)" if "images" in manifest["theme"] else ""))
PY

# ---- swipe back / forward, like Safari ----
echo "==> Two-finger swipe for back / forward"
mkdir -p "$APPS"
if [ -n "${desktop:-}" ] && [ -f "$desktop" ]; then
  target="$APPS/$(basename "$desktop")"
  if [ "$kind" = rpm ]; then
    sed -E "s#^(Exec=/usr/bin/google-chrome-stable)#\\1 $FLAGS#" "$desktop" >"$target"
  else
    sed -E "s#(com\\.google\\.Chrome) #\\1 $FLAGS #" "$desktop" >"$target"
  fi
  update-desktop-database "$APPS" 2>/dev/null || true
  echo "    on (open Chrome from the Dock or Activities)"
else
  echo "    skipped: Chrome's launcher file wasn't found"
fi

cat <<MSG

Almost done. Two steps only you can do in Chrome (it doesn't let scripts install themes):

  1. Theme: Chrome opens its Extensions page now.
     Turn on "Developer mode" (top right) › "Load unpacked" › choose
       $THEMES/Safari Light    (or "Safari Dark")
     Then you can turn Developer mode off again.

  2. Safari-like settings (Chrome › Settings):
     • Appearance › Show bookmarks bar: on       (Safari's Favorites bar)
     • Appearance › Show home button: off
     • Appearance › Customize fonts › Standard: Inter   (the San Francisco look, from macos-look.sh)
     • Privacy and security › Third-party cookies › Block   (like Safari's tracking prevention)
     • Performance › Memory Saver and Energy Saver: on      (Safari-style battery life)
     • Safari's Reader: right-click a page › "Open in reading mode"

Close Chrome completely and open it again for the swipe gestures.
Undo everything:  ./host/chrome-safari.sh --undo
MSG
"${open_cmd[@]}" "chrome://extensions" >/dev/null 2>&1 &
