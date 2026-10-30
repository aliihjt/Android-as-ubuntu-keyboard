#!/usr/bin/env bash
# Voice typing for Fedora, fully offline: press Ctrl+Super+D, speak (English,
# Persian or ~100 other languages), press it again, and the words are typed
# where your cursor is.
#   ./host/voice-setup.sh            choose a model
#   ./host/voice-setup.sh small      fast, good English, OK Persian   (~0.5 GB)
#   ./host/voice-setup.sh medium     slower, much better Persian       (~1.5 GB)
#   ./host/voice-setup.sh --undo     remove it
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
DATA="$HOME/.local/share/fedora-pad/voice"
VENV="$DATA/venv"
CONFIG="$HOME/.config/tabletdesk/voice.json"
BASE=org.gnome.settings-daemon.plugins.media-keys
KEY=/org/gnome/settings-daemon/plugins/media-keys/custom-keybindings/fedorapad-voice/

keys() {  # keys add|remove
  local cur
  cur=$(gsettings get $BASE custom-keybindings)
  gsettings set $BASE custom-keybindings "$(python3 -c '
import ast, sys
cur, key, op = sys.argv[1], sys.argv[2], sys.argv[3]
items = [] if cur.strip() in ("@as []", "[]") else ast.literal_eval(cur.replace("@as ", ""))
items = [i for i in items if i != key] + ([key] if op == "add" else [])
print(str(items))' "$cur" "$KEY" "$1")"
}

if [ "${1:-}" = "--undo" ]; then
  keys remove
  rm -rf "$DATA" "$CONFIG"
  echo "Voice typing removed."
  exit 0
fi

model=${1:-}
if [ -z "$model" ]; then
  echo "Which voice model?"
  echo "  1) small   fast, good English, OK Persian      (~0.5 GB, recommended)"
  echo "  2) medium  slower, much better Persian          (~1.5 GB)"
  echo "  3) base    fastest, for slow laptops, English   (~0.15 GB)"
  read -rp "1, 2 or 3 [1]: " pick
  case "${pick:-1}" in 2) model=medium ;; 3) model=base ;; *) model=small ;; esac
fi

echo "==> Tools for recording, the clipboard and typing"
sudo dnf install -y python3-evdev wl-clipboard pipewire-utils libnotify python3-pip

echo "==> Whisper speech recognition (runs on this laptop, nothing is sent online)"
python3 -m venv --system-site-packages "$VENV"
"$VENV/bin/pip" install -q --upgrade pip
"$VENV/bin/pip" install -q faster-whisper

mkdir -p "$(dirname "$CONFIG")"
[ -f "$CONFIG" ] || echo '{"model": "small", "language": "auto"}' >"$CONFIG"
python3 - "$CONFIG" "$model" <<'PY'
import json, sys
path, model = sys.argv[1], sys.argv[2]
c = json.load(open(path))
c["model"] = model
json.dump(c, open(path, "w"), indent=1)
PY

echo "==> Downloading the '$model' model once (after this it works offline)"
"$VENV/bin/python" -c "
from faster_whisper import WhisperModel
WhisperModel('$model', device='cpu', compute_type='int8', download_root='$DATA/models')
print('Model ready.')"

echo "==> Shortcut: Ctrl+Super+D"
keys add
gsettings set $BASE.custom-keybinding:$KEY name "Voice typing"
gsettings set $BASE.custom-keybinding:$KEY command "$VENV/bin/python $HERE/voice-type.py"
gsettings set $BASE.custom-keybinding:$KEY binding "<Control><Super>d"

cat <<MSG

Done.
  * Click where you want to write, press Ctrl+Super+D, speak, press it again.
  * Also from the tablet: Fedora Pad › Spotlight › "Voice typing".
  * It finds the language by itself. To always use one, set "language" to
    "fa" or "en" in $CONFIG
  * Better Persian:  ./host/voice-setup.sh medium
MSG
