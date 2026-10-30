#!/usr/bin/env bash
# Phone or tablet as this PC's webcam and microphone (Fedora).
# Apps then list "Fedora Pad Camera" and "Fedora Pad Microphone".
#   ./host/webcam-setup.sh          install (once)
#   ./host/webcam-setup.sh --undo   remove the virtual camera again
set -uo pipefail
DATA="$HOME/.local/share/fedora-pad"
MODCONF=/etc/modprobe.d/fedorapad-camera.conf
LOADCONF=/etc/modules-load.d/fedorapad-camera.conf

if [ "${1:-}" = "--undo" ]; then
  sudo rm -f "$MODCONF" "$LOADCONF"
  sudo modprobe -r v4l2loopback 2>/dev/null || true
  rm -rf "$DATA/scrcpy"
  echo "The Fedora Pad camera is removed."
  exit 0
fi

echo "==> Virtual camera (v4l2loopback from RPM Fusion), ffmpeg and audio tools"
F=$(rpm -E %fedora)
rpm -q rpmfusion-free-release >/dev/null 2>&1 ||
  sudo dnf install -y "https://mirrors.rpmfusion.org/free/fedora/rpmfusion-free-release-$F.noarch.rpm"
sudo dnf install -y akmod-v4l2loopback v4l2loopback pipewire-utils pulseaudio-utils android-tools curl tar
command -v ffmpeg >/dev/null || sudo dnf install -y ffmpeg-free

echo "==> The camera device \"Fedora Pad Camera\" (also after every restart)"
echo 'options v4l2loopback devices=1 video_nr=42 card_label="Fedora Pad Camera" exclusive_caps=1' | sudo tee "$MODCONF" >/dev/null
echo v4l2loopback | sudo tee "$LOADCONF" >/dev/null
sudo akmods --force --kmod v4l2loopback >/dev/null 2>&1 || true
sudo modprobe -r v4l2loopback 2>/dev/null || true
if ! out=$(sudo modprobe v4l2loopback 2>&1); then
  echo "$out"
  if mokutil --sb-state 2>/dev/null | grep -qi enabled; then
    cat <<'MSG'

Secure Boot is on, so Fedora must be told to trust the driver it just built. Once:
  sudo mokutil --import /etc/pki/akmods/certs/public_key.der
  (choose a short password), restart, pick "Enroll MOK" › Continue › Yes, type the password.
Then run this script again.
MSG
  else
    echo "The driver didn't load. Restart the PC (a new kernel may be waiting) and run this again."
  fi
  exit 1
fi

echo "==> scrcpy (tablet camera over the USB cable)"
if [ "$(uname -m)" = x86_64 ]; then
  tag=$(curl -fsSIL -o /dev/null -w '%{url_effective}' https://github.com/Genymobile/scrcpy/releases/latest | sed 's#.*/##')
  case "$tag" in v[0-9]*) ;; *) tag=v5.0.1 ;; esac
  mkdir -p "$DATA/scrcpy"
  curl -fL --progress-bar "https://github.com/Genymobile/scrcpy/releases/download/$tag/scrcpy-linux-x86_64-$tag.tar.gz" |
    tar xz -C "$DATA/scrcpy" --strip-components=1 && echo "    scrcpy $tag"
else
  echo "    Not an x86_64 PC: the tablet camera needs scrcpy from your distribution; the iPhone camera still works."
fi

systemctl --user restart tabletdesk.service 2>/dev/null || true
cat <<'MSG'

Done.
  * Android tablet (Android 12+): Fedora Pad › Webcam › Start. Uses the cable.
  * iPhone / iPad: the Fedora Pad web app › Webcam › Start (keep it open in front).
  * In Zoom, Meet, OBS...: choose "Fedora Pad Camera" and "Fedora Pad Microphone".
    (Some apps list the camera only while it is running.)
MSG
