#!/usr/bin/env bash
# One-time setup for Ubuntu 22.04 (GNOME). Run as your normal user:
#   ./host/setup-ubuntu.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

echo "==> Installing packages (adb, x11vnc, python3-evdev, xprop)"
sudo apt-get update
sudo apt-get install -y adb x11vnc python3-evdev x11-utils x11-xserver-utils

echo "==> Allowing your user to create virtual input devices (/dev/uinput)"
echo 'KERNEL=="uinput", MODE="0660", GROUP="input", OPTIONS+="static_node=uinput"' |
  sudo tee /etc/udev/rules.d/60-tabletdesk-uinput.rules >/dev/null
echo uinput | sudo tee /etc/modules-load.d/tabletdesk-uinput.conf >/dev/null
sudo modprobe uinput
sudo udevadm control --reload-rules
sudo udevadm trigger --name-match=uinput || true
sudo usermod -aG input,plugdev "$USER"

echo "==> Installing auto-start service (runs whenever the tablet is plugged in)"
mkdir -p "$HOME/.config/systemd/user"
sed "s|@HERE@|$HERE|g" "$HERE/tabletdesk.service" >"$HOME/.config/systemd/user/tabletdesk.service"
systemctl --user daemon-reload
systemctl --user enable tabletdesk.service

cat <<'EOF'

Done. Next steps:
  1. Log out and back in (group change), choose "Ubuntu on Xorg" on the
     login screen gear icon for the screen-extension feature.
  2. On the tablet: Settings > About > tap "Build number" 7x,
     then Developer options > enable "USB debugging".
  3. Plug the tablet in with USB-C, accept the "Allow USB debugging" prompt.
  4. Start it:  systemctl --user start tabletdesk   (or ./host/connect.sh)
EOF
