#!/usr/bin/env bash
# Use Fedora Pad from anywhere, not just on the same Wi-Fi: Tailscale makes a
# private, encrypted network between your laptop and tablet over the internet.
#   ./host/tailscale-setup.sh          install and sign in
#   ./host/tailscale-setup.sh --undo   sign out and remove
set -euo pipefail

if [ "${1:-}" = "--undo" ]; then
  sudo tailscale logout 2>/dev/null || true
  sudo systemctl disable --now tailscaled 2>/dev/null || true
  sudo dnf remove -y tailscale
  echo "Tailscale removed. Fedora Pad still works over the cable and the same Wi-Fi."
  exit 0
fi

echo "==> Installing Tailscale (official Fedora repository)"
if ! command -v tailscale >/dev/null; then
  sudo dnf config-manager addrepo --from-repofile=https://pkgs.tailscale.com/stable/fedora/tailscale.repo 2>/dev/null ||
    sudo dnf config-manager --add-repo https://pkgs.tailscale.com/stable/fedora/tailscale.repo
  sudo dnf install -y tailscale
fi
sudo systemctl enable --now tailscaled

echo "==> Signing in: a link opens (or is printed). Log in with Google, Microsoft, GitHub..."
sudo tailscale up --operator="$USER"

ip=$(tailscale ip -4 2>/dev/null | head -1)
name=$(tailscale status --self --json 2>/dev/null |
  python3 -c 'import json, sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))' 2>/dev/null || true)

wifi_on=no
[ -e "$HOME/.config/tabletdesk/wifi" ] && wifi_on=yes

cat <<MSG

Done. This laptop's private address: ${ip:-unknown}${name:+   (name: $name)}

On the tablet:
  1. Install "Tailscale" from the Play Store and sign in with the SAME account.
  2. Plug the tablet into the laptop once: Fedora Pad learns the new address.
  3. Now unplug and go anywhere: Fedora Pad connects over the internet,
     encrypted, whenever both are online.
  4. Fedora screen from anywhere: in Windows App add a PC with ${ip:-the address above}.
MSG
if [ "$wifi_on" = no ]; then
  echo
  echo "Wi-Fi mode is off, which Fedora Pad needs without the cable. Turn it on first:"
  echo "  ./host/wifi-setup.sh"
fi
