#!/usr/bin/env bash
# Show the tablet's or the iPhone's screen in a window on Fedora.
#   tablet  scrcpy over the USB cable (Android 5+, sound from Android 11): you can
#           use it with the PC's mouse and keyboard
#   iPhone  AirPlay: UxPlay makes this PC an AirPlay receiver on the home Wi-Fi
#   ./host/mirror-setup.sh          install (once)
#   ./host/mirror-setup.sh --undo   remove the launchers and scrcpy
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
APPS="$HOME/.local/share/applications"
CONF="$HOME/.config/tabletdesk"

if [ "${1:-}" = "--undo" ]; then
  rm -f "$APPS/fedorapad-tablet-mirror.desktop" "$APPS/fedorapad-airplay.desktop"
  rm -rf "$HOME/.local/share/fedora-pad/scrcpy"
  echo "Removed. (uxplay stays installed: sudo dnf remove uxplay)"
  exit 0
fi

echo "==> Tablet on the PC: scrcpy"
. "$HERE/get-scrcpy.sh"
get_scrcpy
command -v adb >/dev/null || sudo dnf install -y android-tools

echo "==> iPhone on the PC: UxPlay (AirPlay receiver) and its video and sound plugins"
sudo dnf install -y --skip-unavailable uxplay gstreamer1-plugins-bad-free gstreamer1-plugin-libav avahi
# AAC sound in AirPlay mirroring needs the full ffmpeg from RPM Fusion (./host/apps-pack.sh adds it)
if rpm -q rpmfusion-free-release >/dev/null 2>&1; then
  sudo dnf install -y ffmpeg-libs 2>/dev/null || true
else
  echo "    (For iPhone sound too, run ./host/apps-pack.sh once: it adds RPM Fusion's full codecs.)"
fi
sudo systemctl enable --now avahi-daemon >/dev/null 2>&1 || true

mkdir -p "$APPS" "$CONF"
pin=$(python3 -c "import sys; sys.path.insert(0, '$HERE'); import pad_extras; print(pad_extras.airplay_pin('$CONF'))")
name="$(hostname -s) (Fedora Pad)"
cat >"$APPS/fedorapad-tablet-mirror.desktop" <<DESK
[Desktop Entry]
Type=Application
Name=Tablet Mirroring
Comment=Show and use the tablet in a window (USB cable)
Icon=phone
Exec=env ADB=$(command -v adb) $HOME/.local/share/fedora-pad/scrcpy/scrcpy --window-title="Tablet · Fedora Pad" --stay-awake --max-size=1920
Categories=Utility;
DESK
cat >"$APPS/fedorapad-airplay.desktop" <<DESK
[Desktop Entry]
Type=Application
Name=iPhone Mirroring (AirPlay)
Comment=Show the iPhone or iPad here: Control Center › Screen Mirroring › "$name"
Icon=video-display
Exec=uxplay -n "$name" -nh -pin $pin -reg $CONF/airplay-devices
Categories=Utility;
DESK
update-desktop-database "$APPS" 2>/dev/null || true
systemctl --user restart tabletdesk.service 2>/dev/null || true

cat <<MSG

Done.
  Tablet:  Fedora Pad › Show on PC › Start   (or "Tablet Mirroring" in Activities)
           Over the USB cable. Click and type in the window to use the tablet.
  iPhone:  Fedora Pad web app › Show on PC › Start AirPlay   (or "iPhone Mirroring" in Activities)
           Then on the iPhone: Control Center › Screen Mirroring › "$name"
           First time it asks for a code: $pin
  Same Wi-Fi for the iPhone. If it can't find the PC, allow AirPlay in the firewall yourself:
    sudo firewall-cmd --permanent --add-service=mdns --add-port=7000-7001/tcp --add-port=7100/tcp \\
      --add-port=6000-6001/udp --add-port=7011/udp && sudo firewall-cmd --reload
MSG
