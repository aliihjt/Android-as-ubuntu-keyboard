#!/usr/bin/env bash
# Update everything in one go: Fedora side (git pull) and the tablet app.
#   ~/Android-as-ubuntu-keyboard/host/update.sh
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
echo "==> Updating Fedora side"
git -C "$HERE/.." pull --ff-only
EXT="$HOME/.local/share/gnome-shell/extensions/tabletdesk@aliihjt.github.io"
if ! cmp -s "$HERE/gnome-extension/extension.js" "$EXT/extension.js"; then
  mkdir -p "$EXT"
  cp "$HERE/gnome-extension/metadata.json" "$HERE/gnome-extension/extension.js" "$EXT/"
  NEED_RELOGIN=1
fi
# Files › right-click › Scripts › "Send to Fedora Pad"
mkdir -p "$HOME/.local/share/nautilus/scripts"
printf '#!/bin/sh\nexec python3 "%s/send_to_pad.py" "$@"\n' "$HERE" >"$HOME/.local/share/nautilus/scripts/Send to Fedora Pad"
chmod +x "$HOME/.local/share/nautilus/scripts/Send to Fedora Pad"
echo "==> Updating tablet app (plug the tablet in if it isn't)"
"$HERE/app-update.sh"
echo "==> Restarting TabletDesk"
systemctl --user restart tabletdesk.service
echo "Done. Unplug and plug the tablet in again."
if [ "${NEED_RELOGIN:-0}" = 1 ]; then
  echo "!! The GNOME extension was updated: LOG OUT and back in once for it to load."
fi
