#!/usr/bin/env bash
# Update everything in one go: Fedora side (git pull) and the tablet app.
#   ~/Android-as-ubuntu-keyboard/host/update.sh
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
echo "==> Updating Fedora side"
git -C "$HERE/.." pull --ff-only
echo "==> Updating tablet app (plug the tablet in if it isn't)"
"$HERE/app-update.sh"
echo "==> Restarting TabletDesk"
systemctl --user restart tabletdesk.service
echo "Done. Unplug and plug the tablet in again."
