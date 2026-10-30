#!/usr/bin/env bash
# Makes Fedora (GNOME) look and feel like macOS, to match Fedora Pad:
#   look:     Dock at the bottom, window buttons on the left (close, minimise,
#             zoom), macOS window theme (also for new GNOME apps), cursors,
#             icons, blur, menu-bar status icons
#   features: Quick Look (select a file, press Space), natural scrolling,
#             tap to click, battery percentage, new windows centred
#   MacBook:  menu in the top-left corner (About, Settings, Force Quit, Sleep...),
#             genie minimise, rounded window corners, macOS wallpapers that
#             change with the time of day, Inter font (like San Francisco),
#             Mac shortcuts (Super = Cmd), two-finger right click, Firefox theme
#   ./host/macos-look.sh          apply
#   ./host/macos-look.sh --undo   back to the normal Fedora look
set -uo pipefail
DOCK=dash-to-dock@micxgx.gmail.com
BLUR=blur-my-shell@aunetx
THEME=user-theme@gnome-shell-extensions.gcampax.github.com
TRAY=appindicatorsupport@rgcjonas.gmail.com
MENU=logomenu@aryan_k
GENIE=compiz-alike-magic-lamp-effect@hermes83.github.com
CORNERS=rounded-window-corners@fxgn
SHELL_VER=$(gnome-shell --version 2>/dev/null | grep -oE '[0-9]+' | head -1)

# settings of an extension installed by dnf live next to the extension
ext_set() {  # ext_set <uuid> <schema> <key> <value>
  local d
  for d in "$HOME/.local/share/gnome-shell/extensions/$1/schemas" "/usr/share/gnome-shell/extensions/$1/schemas"; do
    [ -d "$d" ] && { gsettings --schemadir "$d" set "$2" "$3" "$4" 2>/dev/null; return; }
  done
  gsettings set "$2" "$3" "$4" 2>/dev/null
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

# install an extension from extensions.gnome.org, the version for this GNOME
ego() {  # ego <uuid>
  local url tmp r
  url=$(curl -fsSL "https://extensions.gnome.org/extension-info/?uuid=$1&shell_version=$SHELL_VER" |
    python3 -c 'import json, sys; print(json.load(sys.stdin)["download_url"])') || {
    echo "   (skipped: no version for GNOME $SHELL_VER yet)"; return 1; }
  tmp=$(mktemp --suffix=.zip)
  curl -fsSL -o "$tmp" "https://extensions.gnome.org$url" && gnome-extensions install --force "$tmp"
  r=$?
  rm -f "$tmp"
  [ $r -eq 0 ] && enable_ext "$1"
}

I=org.gnome.desktop.interface
TP=org.gnome.desktop.peripherals.touchpad
WM=org.gnome.desktop.wm.keybindings
SK=org.gnome.shell.keybindings
MK=org.gnome.settings-daemon.plugins.media-keys

if [ "${1:-}" = "--undo" ]; then
  for e in $DOCK $BLUR $THEME $TRAY $MENU $GENIE $CORNERS; do gnome-extensions disable "$e" 2>/dev/null; done
  for k in font-name document-font-name; do gsettings reset $I $k; done
  for k in click-method tap-and-drag; do gsettings reset $TP $k; done
  gsettings reset org.gnome.desktop.background picture-uri
  gsettings reset org.gnome.desktop.background picture-uri-dark
  for k in close toggle-fullscreen switch-input-source switch-input-source-backward; do gsettings reset $WM $k; done
  for k in screenshot show-screenshot-ui toggle-overview; do gsettings reset $SK $k; done
  gsettings reset $MK screensaver
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

ext_set $DOCK $D show-apps-at-top true     # Launchpad on the left of the Dock, like macOS
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
echo "==> Firefox in the macOS style too"
if [ -d "$tmp/gtk" ]; then
  (cd "$tmp/gtk" && ./tweaks.sh -f </dev/null >/dev/null 2>&1) || echo "   Firefox: open Firefox once, then run this script again."
fi

echo "==> macOS wallpapers that change with the time of day (Sonoma, Ventura, Monterey)"
if git clone --depth 1 https://github.com/vinceliuice/WhiteSur-wallpapers.git "$tmp/walls"; then
  (cd "$tmp/walls" && ./install-gnome-backgrounds.sh -t sonoma ventura monterey -s 4k </dev/null >/dev/null 2>&1)
  wall="$HOME/.local/share/backgrounds/Sonoma/Sonoma-timed.xml"
  if [ -f "$wall" ]; then
    gsettings set org.gnome.desktop.background picture-uri "file://$wall"
    gsettings set org.gnome.desktop.background picture-uri-dark "file://$wall"
  fi
fi
rm -rf "$tmp"

echo "==> Inter font, the closest free font to Apple's San Francisco"
sudo dnf install -y rsms-inter-vf-fonts 2>/dev/null || sudo dnf install -y rsms-inter-fonts 2>/dev/null || true
font=$(fc-list : family | tr ',' '\n' | grep -xE 'Inter( Variable)?' | sort -u | head -1)
if [ -n "$font" ]; then
  gsettings set $I font-name "$font 11"
  gsettings set $I document-font-name "$font 11"
fi

echo "==> Top-left menu like the Apple menu: About, Settings, Force Quit, Sleep, Restart"
if ego $MENU; then
  L=org.gnome.shell.extensions.logo-menu
  ext_set $MENU $L menu-button-icon-image 1          # Fedora logo
  ext_set $MENU $L symbolic-icon true
  ext_set $MENU $L show-power-options true
  ext_set $MENU $L show-lockscreen true
  ext_set $MENU $L show-activities-button false
  command -v ptyxis >/dev/null && ext_set $MENU $L menu-button-terminal 'ptyxis'
  command -v gnome-system-monitor >/dev/null || ext_set $MENU $L menu-button-system-monitor 'io.missioncenter.MissionCenter'
fi
echo "==> Genie effect when minimising, rounded window corners"
ego $GENIE
ego $CORNERS

echo "==> Trackpad like a MacBook: two-finger right click, tap and drag"
gsettings set $TP click-method 'fingers'
gsettings set $TP tap-and-drag true

echo "==> Mac shortcuts (Super is the Cmd key)"
gsettings set $WM close "['<Super>q', '<Alt>F4']"                       # Cmd+Q
gsettings set $WM toggle-fullscreen "['<Control><Super>f']"             # Ctrl+Cmd+F
gsettings set $MK screensaver "['<Control><Super>q', '<Super>l']"      # Ctrl+Cmd+Q lock
gsettings set $SK screenshot "['<Shift><Super>3', '<Shift>Print']"     # Cmd+Shift+3
gsettings set $SK show-screenshot-ui "['<Shift><Super>4', '<Shift><Super>5', 'Print']"  # Cmd+Shift+4 / 5
# Cmd+Space opens Spotlight (search); keyboard languages move to Ctrl+Cmd+Space
gsettings set $WM switch-input-source "['<Control><Super>space', 'XF86Keyboard']"
gsettings set $WM switch-input-source-backward "['<Shift><Control><Super>space', '<Shift>XF86Keyboard']"
gsettings set $SK toggle-overview "['<Super>space', '<Super>s']"

cat <<'MSG'

Done. LOG OUT and back in to see everything.
  * Quick Look: in Files, select a file and press Space.
  * Mission Control: Super key (or 3 fingers up on the tablet's trackpad).
  * Launchpad: Super + A.   Spotlight: Super + Space, then type.
  * Super works like Cmd:  Super+Q close,  Ctrl+Super+Q lock,  Ctrl+Super+F full screen,
    Super+Shift+3 screenshot,  Super+Shift+4 / 5 pick an area,  Super+Tab switch apps.
  * Keyboard language: Ctrl+Super+Space (Alt+Shift too, if you set it with fedora-plus.sh).
  * Two fingers click = right click.  Top-left menu = the Apple menu.
Undo any time:  ~/Android-as-ubuntu-keyboard/host/macos-look.sh --undo
MSG
