#!/usr/bin/env bash
# Wi-Fi mode: use Fedora Pad without the cable, on your home network.
# Encrypted (TLS), and only for tablets paired over the cable.
#   ./host/wifi-setup.sh        turn on
#   ./host/wifi-setup.sh --off  turn off
#
# Fedora's firewall blocks the tablet until you allow it yourself; see the
# message this script prints (it does not change the firewall for you).
set -uo pipefail
CONF="$HOME/.config/tabletdesk"
mkdir -p "$CONF"

if [ "${1:-}" = "--off" ]; then
  rm -f "$CONF/wifi"
  systemctl --user restart tabletdesk.service
  cat <<'MSG'
Wi-Fi mode is off. Fedora Pad works over the cable only.
If you opened the firewall for it, close it again with:
  sudo firewall-cmd --permanent --remove-port=7778/tcp --remove-service=rdp && sudo firewall-cmd --reload
MSG
  exit 0
fi

touch "$CONF/wifi"
systemctl --user restart tabletdesk.service

ip=$(ip -4 -o addr show scope global | awk '{print $4}' | cut -d/ -f1 | head -1)
zone=$(firewall-cmd --get-default-zone 2>/dev/null)
cat <<MSG

Wi-Fi mode is on.

One decision is yours: Fedora's firewall (zone "${zone:-?}") blocks the tablet.
Allowing it opens port 7778 (Fedora Pad, encrypted, paired tablets only) and the
Remote Desktop port (screen, password protected) to devices on your network.
Only do this on a network you trust, such as your home Wi-Fi:
  sudo firewall-cmd --permanent --add-port=7778/tcp --add-service=rdp && sudo firewall-cmd --reload

Then:
  1. Plug the tablet in with the cable ONCE: it pairs by itself (nothing to type).
  2. Unplug: Fedora Pad keeps working over Wi-Fi on the same network.
  3. For the Fedora screen over Wi-Fi, add a second PC in Windows App:
       PC name: ${ip:-<this PC's IP>}   (same user and password as 127.0.0.1)

Turn Wi-Fi mode off any time:  ~/Android-as-ubuntu-keyboard/host/wifi-setup.sh --off
MSG
