#!/usr/bin/env bash
# Safe speed + battery tune-up for Fedora. Nothing risky, nothing hidden:
# every step prints what it does.   ./host/tune-up.sh
set -uo pipefail
step() { echo; echo "==> $*"; }

step "Boot time now"
systemd-analyze 2>/dev/null | head -1

step "Faster dnf downloads (10 at a time)"
grep -q '^max_parallel_downloads' /etc/dnf/dnf.conf \
  || echo 'max_parallel_downloads=10' | sudo tee -a /etc/dnf/dnf.conf >/dev/null

step "Faster boot: don't wait for the network before showing the login screen"
sudo systemctl disable NetworkManager-wait-online.service 2>/dev/null

step "Battery: balanced power profile (switch in the tablet's Tools menu)"
command -v powerprofilesctl >/dev/null && powerprofilesctl set balanced

step "SSD health: weekly TRIM"
sudo systemctl enable --now fstrim.timer 2>/dev/null

step "Cleaning: unused packages, old Flatpak runtimes, big system logs"
sudo dnf autoremove -y
flatpak uninstall -y --unused 2>/dev/null
sudo journalctl --vacuum-size=200M

step "Firmware (BIOS, SSD, touchpad) updates available"
fwupdmgr refresh --force >/dev/null 2>&1
fwupdmgr get-updates 2>/dev/null || echo "No firmware updates."

cat <<'MSG'

Done. Also recommended now and then:
  sudo dnf upgrade --refresh      # all updates
  fwupdmgr update                 # install firmware updates listed above
Restart to feel the faster boot; check it with:  systemd-analyze
MSG
