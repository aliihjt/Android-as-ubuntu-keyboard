#!/usr/bin/env bash
# One-time setup for Fedora Workstation (GNOME, Wayland). Also works on Ubuntu.
# Run as your normal user:   ./host/setup.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CONF="$HOME/.config/tabletdesk"
mkdir -p "$CONF"

echo "==> Installing packages"
if command -v dnf >/dev/null; then
  # android-tools = adb (+ udev rules), x11vnc only used on Xorg sessions
  sudo dnf install -y android-tools python3-evdev openssl gnome-remote-desktop brightnessctl wl-clipboard \
    xprop xrandr x11vnc || sudo dnf install -y android-tools python3-evdev openssl gnome-remote-desktop
elif command -v apt-get >/dev/null; then
  sudo apt-get update
  sudo apt-get install -y adb python3-evdev openssl gnome-remote-desktop \
    x11vnc x11-utils x11-xserver-utils
else
  echo "Unsupported distro: install adb, python3-evdev, openssl, gnome-remote-desktop yourself." >&2
fi

echo "==> Allowing your user to create virtual input devices (/dev/uinput)"
echo 'KERNEL=="uinput", MODE="0660", GROUP="input", OPTIONS+="static_node=uinput"' |
  sudo tee /etc/udev/rules.d/60-tabletdesk-uinput.rules >/dev/null
echo uinput | sudo tee /etc/modules-load.d/tabletdesk-uinput.conf >/dev/null
sudo modprobe uinput
sudo udevadm control --reload-rules
sudo udevadm trigger --name-match=uinput || true
sudo usermod -aG input "$USER"
getent group plugdev >/dev/null && sudo usermod -aG plugdev "$USER"

if command -v grdctl >/dev/null; then
  echo "==> Configuring GNOME Remote Desktop (RDP) for the tablet screen"
  GRD="$HOME/.local/share/gnome-remote-desktop"
  mkdir -p "$GRD"
  if [ ! -f "$GRD/tabletdesk-tls.crt" ]; then
    openssl req -new -newkey rsa:3072 -days 3650 -nodes -x509 -subj "/CN=tabletdesk" \
      -keyout "$GRD/tabletdesk-tls.key" -out "$GRD/tabletdesk-tls.crt" 2>/dev/null
  fi
  if [ ! -f "$CONF/rdp-password" ]; then
    (umask 077; openssl rand -base64 12 | tr -d '/+=' >"$CONF/rdp-password")
  fi
  grdctl rdp set-tls-cert "$GRD/tabletdesk-tls.crt"
  grdctl rdp set-tls-key "$GRD/tabletdesk-tls.key"
  grdctl rdp set-credentials "$USER" "$(cat "$CONF/rdp-password")"
  grdctl rdp disable-view-only
  grdctl rdp enable
  systemctl --user enable gnome-remote-desktop.service 2>/dev/null || true
fi

if command -v gnome-extensions >/dev/null; then
  echo "==> Installing GNOME extension (lets the menu bar show the focused app on Wayland)"
  EXT="$HOME/.local/share/gnome-shell/extensions/tabletdesk@aliihjt.github.io"
  mkdir -p "$EXT"
  cp "$HERE/gnome-extension/metadata.json" "$HERE/gnome-extension/extension.js" "$EXT/"
  gnome-extensions enable tabletdesk@aliihjt.github.io 2>/dev/null || true
fi

echo "==> Installing auto-start service (runs whenever the tablet is plugged in)"
mkdir -p "$HOME/.config/systemd/user"
sed "s|@HERE@|$HERE|g" "$HERE/tabletdesk.service" >"$HOME/.config/systemd/user/tabletdesk.service"
systemctl --user daemon-reload
systemctl --user enable tabletdesk.service
# start now too if /dev/uinput is already usable; otherwise it starts at next login
if [ -w /dev/uinput ]; then systemctl --user restart tabletdesk.service; fi

cat <<EOF2

Done. Next steps:
  1. Log out and back in (group change + GNOME extension), then run:
       gnome-extensions enable tabletdesk@aliihjt.github.io
  2. On the tablet: Settings > About > tap "Build number" 7x,
     then Developer options > enable "USB debugging".
     Install "Windows App" (Microsoft Remote Desktop) or "aFreeRDP".
  3. Plug the tablet in with USB-C. On the "Allow USB debugging" prompt tick
     "Always allow from this computer" so it never asks again.
  4. From now on it starts by itself every time you log in and plug in.
     (Manual run: ./host/connect.sh   Logs: journalctl --user -u tabletdesk -f)
  5. First time only, in the RDP app add a PC:  127.0.0.1  and tick "Remember"/save password
       user:     $USER
       password: $(cat "$CONF/rdp-password" 2>/dev/null || echo "(see Settings > System > Remote Desktop)")
EOF2
