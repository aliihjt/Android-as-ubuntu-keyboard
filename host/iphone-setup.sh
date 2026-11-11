#!/usr/bin/env bash
# Control the iPhone from the laptop: the laptop becomes a Bluetooth keyboard and
# mouse for your iPhone. Press Super+Shift+I to start, Ctrl+Alt+Esc to come back.
#   ./host/iphone-setup.sh        pair the iPhone, add the shortcut
#   ./host/iphone-setup.sh --off  remove the shortcut and forget the iPhone
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
CONF="${XDG_CONFIG_HOME:-$HOME/.config}/tabletdesk/iphone.json"
APPS="$HOME/.local/share/applications"
BASE=org.gnome.settings-daemon.plugins.media-keys
KEYPATH=/org/gnome/settings-daemon/plugins/media-keys/custom-keybindings/fedorapad-iphone/
CMD="python3 $HERE/iphone_hid.py --toggle"

keybindings() {  # keybindings add|remove: the shortcut's place in GNOME's list
  local cur new
  cur=$(gsettings get $BASE custom-keybindings)
  new=$(python3 -c '
import ast, sys
cur, key, how = sys.argv[1], sys.argv[2], sys.argv[3]
items = [] if cur.strip() in ("@as []", "[]") else ast.literal_eval(cur.replace("@as ", ""))
items = [i for i in items if i != key] + ([key] if how == "add" else [])
print(str(items))' "$cur" "$KEYPATH" "$1")
  gsettings set $BASE custom-keybindings "$new"
}

if [ "${1:-}" = "--off" ]; then
  keybindings remove
  rm -f "$APPS/fedorapad-iphone.desktop" "$CONF"
  echo "✅ iPhone control is off. On the iPhone you can also remove it: Settings › Bluetooth › Fedora Pad › ⓘ › Forget."
  exit 0
fi

python3 -c "import gi" 2>/dev/null || { echo "Run first: sudo dnf install python3-gobject"; exit 1; }
if ! bluetoothctl show 2>/dev/null | grep -q "Powered: yes"; then
  echo "Turn Bluetooth on first: Settings › Bluetooth. Then run this again."; exit 1
fi
if ! systemctl --user is-active --quiet tabletdesk 2>/dev/null; then
  systemctl --user start tabletdesk 2>/dev/null; sleep 2
fi

echo "==> 1/3 Pairing (you have 3 minutes)"
answer=$(python3 "$HERE/iphone_hid.py" --pair)
case "$answer" in
  pairing*) ;;
  *) echo "Fedora Pad answered: $answer"; echo "Is Fedora Pad running? Run ./host/setup.sh once, or log out and in."; exit 1 ;;
esac
rm -f "$CONF"  # a new pairing replaces the old one
echo "   On the iPhone: Settings › Bluetooth › under OTHER DEVICES tap  \"Fedora Pad\"."
echo "   Tap Pair on the iPhone, and confirm the same code if the laptop asks."
for _ in $(seq 1 90); do
  if [ -s "$CONF" ] && grep -q '"device"' "$CONF"; then break; fi
  sleep 2
done
if ! { [ -s "$CONF" ] && grep -q '"device"' "$CONF"; }; then
  echo "⚠️  The iPhone didn't pair. Keep Bluetooth on (Settings › Bluetooth, not just Control Center) and run this again."
  exit 1
fi
echo "   ✅ Paired."

echo "==> 2/3 The shortcut: Super+Shift+I"
keybindings add &&
  gsettings set $BASE.custom-keybinding:$KEYPATH name "Fedora Pad: control the iPhone" &&
  gsettings set $BASE.custom-keybinding:$KEYPATH command "$CMD" &&
  gsettings set $BASE.custom-keybinding:$KEYPATH binding "<Super><Shift>i"
mkdir -p "$APPS"
cat >"$APPS/fedorapad-iphone.desktop" <<DESK
[Desktop Entry]
Type=Application
Name=iPhone Control
Comment=Use the laptop's mouse and keyboard on the iPhone (Ctrl+Alt+Esc to come back)
Exec=$CMD
Icon=phone
Terminal=false
Categories=Utility;
DESK
echo "   ✅ Added (also in Activities as \"iPhone Control\")."

echo "==> 3/3 On the iPhone, once:"
echo "   Settings › Accessibility › Touch › AssistiveTouch: ON  (the round pointer for the mouse)."
echo "   Optional: Settings › Accessibility › Pointer Control: pointer size and speed."
echo
echo "✅ Done. Press Super+Shift+I: the laptop's touchpad, mouse and keyboard now work on the iPhone."
echo "   Ctrl+Alt+Esc gives them back to the laptop."
echo "   To see the iPhone's screen on the laptop: ./host/mirror-setup.sh once, then on the iPhone"
echo "   Control Center › Screen Mirroring › this laptop."
