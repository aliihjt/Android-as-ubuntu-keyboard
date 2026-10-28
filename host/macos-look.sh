#!/usr/bin/env bash
# Makes Fedora (GNOME) look and feel like macOS, to match Fedora Pad:
#   look:     Dock at the bottom, window buttons on the left (close, minimise,
#             zoom), macOS window theme (also for new GNOME apps), cursors,
#             icons, blur, menu-bar status icons
#   features: Quick Look (select a file, press Space), natural scrolling,
#             tap to click, battery percentage, new windows centred
#   ./host/macos-look.sh          apply
#   ./host/macos-look.sh --undo   back to the normal Fedora look
set -uo pipefail
DOCK=dash-to-dock@micxgx.gmail.com
BLUR=blur-my-shell@aunetx
THEME=user-theme@gnome-shell-extensions.gcampax.github.com
TRAY=appindicatorsupport@rgcjonas.gmail.com

# settings of an extension installed by dnf live next to the extension
ext_set() {  # ext_set <uuid> <schema> <key> <value>
  local dir=/usr/share/gnome-shell/extensions/$1/schemas
  if [ -d "$dir" ]; then gsettings --schemadir "$dir" set "$2" "$3" "$4" 2>/dev/null
  else gsettings set "$2" "$3" "$4" 2>/dev/null; fi
}

# turn an extension on now if possible, and for sure after the next log-in
enable_ext() {
  gnome-extensions enable "$1" 2>/dev/null && return
  local cur new
  cur=$(gsettings get org.gnome.shell enabled-extensions)
  new=$(python3 -c '
import ast, sys
cur, uuid = sys.argv[1], sys.argv[2]
items = [] if cur.strip() in ("@as []", "[]") else ast.literal_eval(cur.replace("@as ", ""))
if uuid not in items: items.append(uuid)
print(str(items))' "$cur" "$1")
  gsettings set org.gnome.shell enabled-extensions "$new"
}

I=org.gnome.desktop.interface
TP=org.gnome.desktop.peripherals.touchpad

if [ "${1:-}" = "--undo" ]; then
  for e in $DOCK $BLUR $THEME $TRAY; do gnome-extensions disable "$e" 2>/dev/null; done
  gsettings reset org.gnome.desktop.wm.preferences button-layout
  for k in icon-theme gtk-theme cursor-theme enable-hot-corners show-battery-percentage clock-show-weekday; do
    gsettings reset $I $k
  done
  gsettings reset org.gnome.mutter center-new-windows
  gsettings reset $TP natural-scroll
  gsettings reset $TP tap-to-click
  # remove the theme's override for new GNOME (libadwaita) apps
  for f in gtk.css gtk-dark.css assets; do
    [ -L "$HOME/.config/gtk-4.0/$f" ] && rm "$HOME/.config/gtk-4.0/$f"
  done
  echo "Back to the normal Fedora look. Log out and in to finish."
  exit 0
fi

echo "==> Installing the Dock, blur, theme support, status icons, Quick Look, Tweaks"
sudo dnf install -y gnome-shell-extension-dash-to-dock gnome-shell-extension-blur-my-shell \
  gnome-shell-extension-user-theme gnome-shell-extension-appindicator sushi gnome-tweaks git

echo "==> Dock at the bottom, like macOS"
D=org.gnome.shell.extensions.dash-to-dock
ext_set $DOCK $D dock-position 'BOTTOM'
ext_set $DOCK $D extend-height false
ext_set $DOCK $D dock-fixed false
ext_set $DOCK $D intellihide true
ext_set $DOCK $D dash-max-icon-size 52
ext_set $DOCK $D transparency-mode 'DYNAMIC'
ext_set $DOCK $D click-action 'minimize-or-previews'
ext_set $DOCK $D show-trash true
ext_set $DOCK $D running-indicator-style 'DOTS'
for e in $DOCK $BLUR $THEME $TRAY; do enable_ext "$e"; done

echo "==> Window buttons on the left: close, minimise, zoom"
gsettings set org.gnome.desktop.wm.preferences button-layout 'close,minimize,maximize:appmenu'
gsettings set org.gnome.mutter center-new-windows true
gsettings set $I enable-hot-corners false

echo "==> Mac habits: natural scrolling, tap to click, battery %, weekday in the clock"
gsettings set $TP natural-scroll true
gsettings set $TP tap-to-click true
gsettings set $I show-battery-percentage true
gsettings set $I clock-show-weekday true

# light or dark macOS theme, following Fedora's own setting
variant=Light
gsettings get $I color-scheme | grep -q dark && variant=Dark

tmp=$(mktemp -d)
echo "==> macOS window theme (WhiteSur), also for new GNOME apps"
if git clone --depth 1 https://github.com/vinceliuice/WhiteSur-gtk-theme.git "$tmp/gtk"; then
  (cd "$tmp/gtk" && ./install.sh -l >/dev/null 2>&1)
  gsettings set $I gtk-theme "WhiteSur-$variant"
  ext_set $THEME org.gnome.shell.extensions.user-theme name "WhiteSur-$variant"
fi
echo "==> macOS icons and cursors"
if git clone --depth 1 https://github.com/vinceliuice/WhiteSur-icon-theme.git "$tmp/icons"; then
  (cd "$tmp/icons" && ./install.sh >/dev/null 2>&1) && gsettings set $I icon-theme 'WhiteSur'
fi
if git clone --depth 1 https://github.com/vinceliuice/WhiteSur-cursors.git "$tmp/cursors"; then
  (cd "$tmp/cursors" && ./install.sh >/dev/null 2>&1) && gsettings set $I cursor-theme 'WhiteSur-cursors'
fi
rm -rf "$tmp"

cat <<'MSG'

Done. LOG OUT and back in to see everything.
  * Quick Look: in Files, select a file and press Space.
  * Mission Control: Super key (or 3 fingers up on the tablet's trackpad).
  * Launchpad: Super + A.   Spotlight: Super, then type.
Undo any time:  ~/Android-as-ubuntu-keyboard/host/macos-look.sh --undo
MSG
