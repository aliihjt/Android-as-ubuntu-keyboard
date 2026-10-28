#!/usr/bin/env bash
# Adds an AI assistant to Fedora, like Copilot on Windows:
#  - installs Newelle (GNOME AI assistant: Claude, other online models or local ones)
#  - Ctrl+Alt+Space opens it from anywhere
#   ./host/ai-setup.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

echo "==> Installing Newelle from Flathub"
flatpak remote-add --user --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
flatpak install --user -y flathub io.github.qwersyk.Newelle

echo "==> Shortcut: Ctrl+Alt+Space opens the AI assistant"
BASE=org.gnome.settings-daemon.plugins.media-keys
PATH_KEY=/org/gnome/settings-daemon/plugins/media-keys/custom-keybindings/tabletdesk-ai/
current=$(gsettings get $BASE custom-keybindings)
new=$(python3 -c '
import ast, sys
cur, key = sys.argv[1], sys.argv[2]
items = [] if cur.strip() in ("@as []", "[]") else ast.literal_eval(cur.replace("@as ", ""))
if key not in items:
    items.append(key)
print(str(items))' "$current" "$PATH_KEY")
gsettings set $BASE custom-keybindings "$new"
gsettings set $BASE.custom-keybinding:$PATH_KEY name "AI assistant"
gsettings set $BASE.custom-keybinding:$PATH_KEY command "$HERE/ai-open.sh"
gsettings set $BASE.custom-keybinding:$PATH_KEY binding "<Control><Alt>space"

cat <<'EOF2'

Done.
  * Press Ctrl+Alt+Space (or the AI button on the tablet) to open the assistant.
  * First time in Newelle: open its settings and pick a model.
      - Claude: choose the Claude / Anthropic provider and paste an API key
        from https://console.anthropic.com
      - Free and offline: choose a local model (needs a fast PC)
EOF2
