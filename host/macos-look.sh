#!/usr/bin/env bash
# Makes Fedora (GNOME) look like macOS, to match the tablet's menu bar:
# dock at the bottom, window buttons on the left, blur, macOS-style icons.
#   ./host/macos-look.sh          apply
#   ./host/macos-look.sh --undo   back to the normal Fedora look
set -uo pipefail
DOCK=dash-to-dock@micxgx.gmail.com
BLUR=blur-my-shell@aunetx

# settings of an extension installed by dnf live next to the extension
ext_set() {  # ext_set <uuid> <schema> <key> <value>
  local dir=/usr/share/gnome-shell/extensions/$1/schemas
  if [ -d "$dir" ]; then gsettings --schemadir "$dir" set "$2" "$3" "$4"
  else gsettings set "$2" "$3" "$4"; fi
}

# turn extensions on now if possible, and for sure after the next log-in
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

if [ "${1:-}" = "--undo" ]; then
  gnome-extensions disable $DOCK 2>/dev/null
  gnome-extensions disable $BLUR 2>/dev/null
  gsettings reset org.gnome.desktop.wm.preferences button-layout
  gsettings reset org.gnome.desktop.interface icon-theme
  gsettings reset org.gnome.desktop.interface enable-hot-corners
  echo "Back to the normal Fedora look (log out and in to finish)."
  exit 0
fi

echo "==> Installing the dock, blur and Tweaks"
sudo dnf install -y gnome-shell-extension-dash-to-dock gnome-shell-extension-blur-my-shell \
  gnome-tweaks git

echo "==> Dock at the bottom, like macOS"
D=org.gnome.shell.extensions.dash-to-dock
ext_set $DOCK $D dock-position 'BOTTOM'
ext_set $DOCK $D extend-height false
ext_set $DOCK $D dock-fixed false
ext_set $DOCK $D intellihide true
ext_set $DOCK $D dash-max-icon-size 48
ext_set $DOCK $D transparency-mode 'DYNAMIC'
ext_set $DOCK $D click-action 'minimize-or-previews'
ext_set $DOCK $D show-trash false
enable_ext $DOCK
enable_ext $BLUR

echo "==> Window buttons on the left (close, minimize, maximize)"
gsettings set org.gnome.desktop.wm.preferences button-layout 'close,minimize,maximize:appmenu'
gsettings set org.gnome.desktop.interface enable-hot-corners false

echo "==> macOS-style icons (WhiteSur)"
tmp=$(mktemp -d)
if git clone --depth 1 https://github.com/vinceliuice/WhiteSur-icon-theme.git "$tmp/icons"; then
  (cd "$tmp/icons" && ./install.sh >/dev/null) && gsettings set org.gnome.desktop.interface icon-theme 'WhiteSur'
fi
rm -rf "$tmp"

cat <<'MSG'

Done. LOG OUT and back in to see the dock and blur.
Undo any time:  ~/Android-as-ubuntu-keyboard/host/macos-look.sh --undo
More options:   open "Tweaks" and "Extensions" from Activities.
MSG
