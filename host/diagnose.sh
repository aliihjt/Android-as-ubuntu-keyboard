#!/usr/bin/env bash
# Collects what is needed to debug mouse / touchpad / keyboard problems into
# ~/tabletdesk-diagnose.txt (no passwords). Run it WHILE the problem happens:
#   ~/Android-as-ubuntu-keyboard/host/diagnose.sh
OUT="$HOME/tabletdesk-diagnose.txt"
{
  echo "== system";  date; cat /etc/fedora-release 2>/dev/null; gnome-shell --version 2>/dev/null
  echo "session=$XDG_SESSION_TYPE desktop=$XDG_CURRENT_DESKTOP"
  echo; echo "== touchpad settings"
  for k in send-events disable-while-typing tap-to-click natural-scroll; do
    echo "$k = $(gsettings get org.gnome.desktop.peripherals.touchpad $k 2>/dev/null)"
  done
  echo; echo "== input devices"
  grep -E '^(N|H|B: EV)' /proc/bus/input/devices
  echo; echo "== who has input devices open"
  for f in /dev/input/event*; do
    p=$(fuser "$f" 2>/dev/null); [ -n "$p" ] && echo "$f: $p"
  done
  echo; echo "== tabletdesk processes"; pgrep -af 'tabletdesk|connect.sh' || echo none
  echo; echo "== tablet"; adb devices 2>&1; adb shell getprop ro.product.model 2>/dev/null
  adb shell dumpsys package io.github.aliihjt.tabletdesk 2>/dev/null | grep -m1 versionCode
  echo; echo "== remote desktop"; grdctl status 2>&1 | grep -iv password
  gsettings get org.gnome.desktop.remote-desktop.rdp screen-share-mode 2>/dev/null
  echo; echo "== Wi-Fi mode"
  [ -e "$HOME/.config/tabletdesk/wifi" ] && echo "on" || echo "off (run host/wifi-setup.sh)"
  echo; echo "== Tailscale (use from anywhere)"
  command -v tailscale >/dev/null && { tailscale ip -4 2>/dev/null | head -1; tailscale status 2>&1 | head -5; } || echo "not installed (host/tailscale-setup.sh)"
  ip -4 -o addr show scope global | awk '{print $2, $4}'
  echo "listening:"; ss -ltn 2>/dev/null | grep -E ':(7778|3389) ' || echo "nothing on 7778/3389"
  echo "firewall ($(firewall-cmd --get-default-zone 2>/dev/null)):"
  firewall-cmd --list-ports 2>/dev/null; firewall-cmd --list-services 2>/dev/null
  echo; echo "== monitors"; (gdctl show 2>/dev/null || echo "gdctl not available") | head -40
  echo; echo "== tabletdesk log"; journalctl --user -u tabletdesk -n 80 --no-pager 2>&1
  echo; echo "== remote desktop log"; journalctl --user -u gnome-remote-desktop -n 30 --no-pager 2>&1
} >"$OUT" 2>&1
echo "Saved to $OUT"
echo "Open it with:  gnome-text-editor $OUT   (or xdg-open $OUT)"
echo "Copy everything in it and paste it to me."
