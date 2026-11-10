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
echo "==> Checking Steam (for the Controller page's Big Picture option)"
if command -v steam >/dev/null || flatpak info com.valvesoftware.Steam >/dev/null 2>&1; then
  echo "Steam is installed."
elif command -v flatpak >/dev/null; then
  # Flathub, for this user only: no password needed. The first download is large.
  echo "Installing Steam from Flathub (this can take a while)..."
  flatpak remote-add --user --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo &&
    flatpak install --user -y --noninteractive flathub com.valvesoftware.Steam ||
    echo "!! Steam could not be installed. Try: flatpak install flathub com.valvesoftware.Steam"
else
  echo "!! flatpak is missing: install Steam from GNOME Software."
fi
echo "==> Updating tablet app (plug the tablet in if it isn't)"
"$HERE/app-update.sh"
if [ "$(adb get-state 2>/dev/null)" = device ]; then
  # shellcheck source=app-update.sh
  . "$HERE/app-update.sh"
  install_iisu || true
else
  echo "Tablet not plugged in: iiSU is checked next time you run update.sh with it connected."
fi
echo "==> Restarting TabletDesk"
systemctl --user restart tabletdesk.service
echo "Done. Unplug and plug the tablet in again."
if [ "${NEED_RELOGIN:-0}" = 1 ]; then
  echo "!! The GNOME extension was updated: LOG OUT and back in once for it to load."
fi
