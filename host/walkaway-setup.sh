#!/usr/bin/env bash
# Lock the laptop when your iPhone walks away (Bluetooth). Locks only, never unlocks.
#   ./host/walkaway-setup.sh        choose the iPhone, test it, choose how far
#   ./host/walkaway-setup.sh --off  turn it off
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
CONF="${XDG_CONFIG_HOME:-$HOME/.config}/tabletdesk/walkaway.json"
mkdir -p "$(dirname "$CONF")"

if [ "${1:-}" = "--off" ]; then
  python3 - "$CONF" <<'PY'
import json, sys
p = sys.argv[1]
try:
    c = json.load(open(p))
except Exception:
    c = {}
c["on"] = False
json.dump(c, open(p, "w"), indent=1)
PY
  echo "✅ Walk-away lock is off."
  exit 0
fi

command -v bluetoothctl >/dev/null || { echo "Bluetooth tools are missing: sudo dnf install bluez"; exit 1; }
python3 -c "import gi" 2>/dev/null || { echo "Run first: sudo dnf install python3-gobject"; exit 1; }

mapfile -t paired < <(bluetoothctl devices Paired 2>/dev/null | sed -n 's/^Device \([0-9A-F:]\{17\}\) \(.*\)$/\1|\2/p')
if [ ${#paired[@]} -eq 0 ]; then
  echo "No paired phone yet. On the laptop: Settings › Bluetooth, and on the iPhone: Settings › Bluetooth."
  echo "Pair them (tap the laptop's name on the iPhone, confirm the code on both), then run this again."
  exit 1
fi
echo "Paired devices:"
for i in "${!paired[@]}"; do echo "  $((i + 1))) ${paired[$i]#*|}"; done
pick=1
for i in "${!paired[@]}"; do [[ "${paired[$i]}" == *[Ii][Pp]hone* ]] && pick=$((i + 1)) && break; done
read -r -p "Which one is your iPhone? [$pick] " a
[[ "$a" =~ ^[0-9]+$ ]] && pick=$a
entry=${paired[$((pick - 1))]:-}
[ -n "$entry" ] || { echo "No such number."; exit 1; }
mac=${entry%%|*}; name=${entry#*|}

echo
echo "Testing for 30 seconds: keep the iPhone near the laptop, unlocked, Bluetooth on."
if ! python3 "$HERE/walkaway.py" --test "$mac" 30; then
  echo
  echo "⚠️  The laptop didn't hear $name. Unlock the iPhone, keep Bluetooth on (not just Control Center:"
  echo "    Settings › Bluetooth), and run this again. If it still isn't heard, this iPhone"
  echo "    hides its signal from this laptop and walk-away lock can't work with it."
  exit 1
fi

echo
echo "Lock the laptop when the iPhone is:"
echo "  1) a few steps away (leaving the desk)"
echo "  2) out of the room            (recommended)"
echo "  3) out of reach (leaving home or the office)"
read -r -p "Choose 1, 2 or 3 [2]: " d
case "${d:-2}" in 1) rssi=-68 ;; 3) rssi=null ;; *) rssi=-80 ;; esac

cat > "$CONF" <<JSON
{"on": true, "device": "$mac", "name": "${name//\"/}", "rssi": $rssi, "after": 60}
JSON
echo
echo "✅ Done: the laptop locks about a minute after $name goes away (it never unlocks by itself)."
echo "   It starts within a few seconds (Fedora Pad reads the setting)."
echo "   Locks too often? Run this again and choose a bigger distance.  Off: ./host/walkaway-setup.sh --off"
