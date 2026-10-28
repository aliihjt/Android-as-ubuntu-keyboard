#!/usr/bin/env bash
# Essential apps for Fedora in one go: video codecs, VLC, Chrome, Telegram,
# VS Code, Persian fonts and handy tools.   ./host/apps-pack.sh
# Edit the APPS list below to add or remove apps.
set -uo pipefail
APPS=(
  "VLC|org.videolan.VLC"
  "Google Chrome|com.google.Chrome"
  "Telegram|org.telegram.desktop"
  "VS Code|com.visualstudio.code"
  "GIMP|org.gimp.GIMP"
  "OBS Studio|com.obsproject.Studio"
  "Extension Manager|com.mattjakeman.ExtensionManager"
  "Flatseal|com.github.tchx84.Flatseal"
)
ok=(); failed=()
try() { local name=$1; shift; echo; echo "==> $name"; if "$@"; then ok+=("$name"); else failed+=("$name"); fi; }

F=$(rpm -E %fedora)
try "RPM Fusion (extra software sources)" sudo dnf install -y \
  "https://mirrors.rpmfusion.org/free/fedora/rpmfusion-free-release-$F.noarch.rpm" \
  "https://mirrors.rpmfusion.org/nonfree/fedora/rpmfusion-nonfree-release-$F.noarch.rpm"
try "Full video codecs (ffmpeg)" sudo dnf swap -y ffmpeg-free ffmpeg --allowerasing
try "Multimedia codecs" sudo dnf group install -y multimedia
try "Persian fonts (Vazirmatn)" bash -c 'sudo dnf install -y vazirmatn-vf-fonts || sudo dnf install -y vazirmatn-fonts'
try "Tools (zip, rar, 7z, htop, fastfetch)" sudo dnf install -y unzip p7zip p7zip-plugins unrar htop fastfetch

flatpak remote-add --user --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
for entry in "${APPS[@]}"; do
  try "${entry%%|*}" flatpak install --user -y flathub "${entry##*|}"
done

echo
echo "Installed: ${ok[*]:-nothing}"
[ ${#failed[@]} -gt 0 ] && echo "Failed (try again later): ${failed[*]}"
echo "New apps are in Activities."
