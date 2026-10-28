#!/usr/bin/env bash
# Emergency reset: gives the laptop keyboard, touchpad and screen back to normal.
#   ~/Android-as-ubuntu-keyboard/host/reset.sh
systemctl --user stop tabletdesk.service 2>/dev/null
pkill -f tabletdesk_host.py 2>/dev/null          # releases the keyboard + removes the virtual mouse
systemctl --user restart gnome-remote-desktop.service 2>/dev/null   # ends the tablet screen session
gsettings set org.gnome.desktop.peripherals.touchpad send-events enabled
echo "Laptop keyboard, touchpad and screens are back to normal."
echo "Start TabletDesk again with:  systemctl --user start tabletdesk"
