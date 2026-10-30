#!/usr/bin/env bash
# Let the tablet wake this PC from sleep: Fedora Pad › Wake & Sleep › Wake the PC.
# Turns on "wake on magic packet" for Ethernet, and for Wi-Fi when the card can do it.
#   ./host/wake-setup.sh          turn on
#   ./host/wake-setup.sh --off    turn off
set -uo pipefail
mode=magic
[ "${1:-}" = "--off" ] && mode=ignore
any=0

while IFS=: read -r name type dev; do
  [ -n "$dev" ] || continue
  case "$type" in
    802-3-ethernet)
      if nmcli connection modify "$name" 802-3-ethernet.wake-on-lan "$mode" 2>/dev/null ||
         sudo nmcli connection modify "$name" 802-3-ethernet.wake-on-lan "$mode"; then
        nmcli device reapply "$dev" >/dev/null 2>&1 || true
        echo "  Ethernet \"$name\": wake-on-LAN $([ $mode = magic ] && echo on || echo off)"
        any=1
      fi ;;
    802-11-wireless)
      phy=$(cat "/sys/class/net/$dev/phy80211/name" 2>/dev/null)
      if [ "$mode" = magic ] && ! iw phy "$phy" info 2>/dev/null | grep -A12 "WoWLAN support" | grep -qi "magic"; then
        echo "  Wi-Fi \"$name\": this Wi-Fi card can't wake the PC (no wake-on-Wi-Fi). Use Ethernet for waking."
        continue
      fi
      if nmcli connection modify "$name" 802-11-wireless.wake-on-wlan "$mode" 2>/dev/null ||
         sudo nmcli connection modify "$name" 802-11-wireless.wake-on-wlan "$mode"; then
        nmcli device reapply "$dev" >/dev/null 2>&1 || true
        echo "  Wi-Fi \"$name\": wake-on-Wi-Fi $([ $mode = magic ] && echo on || echo off)"
        any=1
      fi ;;
  esac
done < <(nmcli -t -f NAME,TYPE,DEVICE connection show --active)

if [ "$mode" = ignore ]; then
  echo "Waking from the tablet is off."
  exit 0
fi
if [ $any = 1 ]; then
  cat <<'MSG'

Done. Plug the tablet in (or open Fedora Pad on Wi-Fi) once so it learns this PC's
address. Then: put the PC to sleep, and Fedora Pad › Wake & Sleep › Wake the PC.

Good to know:
  * It wakes the PC from SLEEP. From fully off it only works on Ethernet, and only
    if "Wake on LAN" is on in the BIOS/UEFI settings.
  * The tablet must be on the same network as the PC.
MSG
else
  echo "No Ethernet or wake-capable Wi-Fi connection is active. The sleep timers still work."
fi
