#!/usr/bin/env bash
# Fedora Pad on an iPhone, iPad or any phone: a web app served by this PC on
# your home Wi-Fi, encrypted, for paired devices only.
#   ./host/webapp-setup.sh           turn on and show how to set up a phone
#   ./host/webapp-setup.sh --off     turn off
#   ./host/webapp-setup.sh --forget  unpair every phone / iPad
set -uo pipefail
CONF="$HOME/.config/tabletdesk"
mkdir -p "$CONF"

case "${1:-}" in
  --off)
    rm -f "$CONF/web"
    systemctl --user restart tabletdesk.service
    echo "The web app is off."
    exit 0 ;;
  --forget)
    rm -f "$CONF/web-devices.json"
    echo "All phones and iPads are unpaired. Each one needs a new code to connect again."
    exit 0 ;;
esac

command -v openssl >/dev/null || sudo dnf install -y openssl
command -v qrencode >/dev/null || sudo dnf install -y qrencode || true
touch "$CONF/web"
systemctl --user restart tabletdesk.service
sleep 3

host=$(hostname -s)
ip=$(ip -4 -o addr show scope global | awk '{print $4}' | cut -d/ -f1 | head -1)
url="http://${ip:-$host.local}:7781"
cat <<MSG

The Fedora Pad web app is on.

On the iPhone / iPad (same Wi-Fi), open this in Safari, or scan the code with the camera:
  $url
MSG
command -v qrencode >/dev/null && qrencode -t ANSIUTF8 "$url"
cat <<MSG
The page walks you through it:
  1. Install the "Fedora Pad local CA" certificate, then trust it in
     Settings › General › About › Certificate Trust Settings.
  2. Open https://$host.local:7780 and Share › Add to Home Screen.
  3. Open Fedora Pad from the Home Screen, tap "Show a code on the PC",
     and type the code that pops up here.

Fedora's firewall normally lets home-network devices reach ports 7780 and 7781.
If the phone can't open the page, allow them yourself (only on a network you trust):
  sudo firewall-cmd --permanent --add-port=7780-7781/tcp && sudo firewall-cmd --reload

Off:  ./host/webapp-setup.sh --off      Unpair all phones:  ./host/webapp-setup.sh --forget
MSG
