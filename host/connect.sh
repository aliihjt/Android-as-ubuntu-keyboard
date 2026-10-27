#!/usr/bin/env bash
# Connect an Android tablet over USB-C (power + data through ADB).
#
#   ./host/connect.sh [extend|mirror|input] [--watch]
#
#   extend  tablet becomes a second screen right of your main one
#   mirror  tablet shows the same picture as your main screen
#   input   no screen, tablet is only keyboard / trackpad / Touch Bar
#   --watch keep running and reconnect every time the tablet is plugged in
#
# Env overrides: TABLETDESK_MODE, TABLETDESK_SIZE=1920x1200, TABLETDESK_PORT
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PKG=io.github.aliihjt.tabletdesk
PORT="${TABLETDESK_PORT:-7777}"
VNC_PORT=5900
MODE="${TABLETDESK_MODE:-extend}"
WATCH=0
for arg in "$@"; do
  case "$arg" in
    extend|mirror|input) MODE="$arg" ;;
    --watch) WATCH=1 ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

RDP_PORT=3389
RDP_APPS="com.microsoft.rdc.androidx com.freerdp.afreerdp"
HOST_PID=""; VNC_PID=""; VIRT_OUT=""; VIEWER=""
log() { echo "[tabletdesk] $*"; }
# desktop pop-up (and log line) so you can see what happened without a terminal
notify() {
  log "$*"
  command -v notify-send >/dev/null && notify-send -a TabletDesk -i video-display "TabletDesk" "$*" 2>/dev/null
  return 0
}

# true while the Fedora screen is locked (GNOME only shares an unlocked session)
screen_locked() {
  gdbus call --session --dest org.gnome.ScreenSaver --object-path /org/gnome/ScreenSaver \
    --method org.gnome.ScreenSaver.GetActive 2>/dev/null | grep -q true
}

# turn the tablet screen on and remove a swipe-only lock screen
wake_tablet() {
  adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
  adb shell wm dismiss-keyguard >/dev/null 2>&1
}

cleanup_screen() {
  [ -n "$VNC_PID" ] && kill "$VNC_PID" 2>/dev/null
  VNC_PID=""; VIEWER=""
  if [ -n "$VIRT_OUT" ]; then
    xrandr --output "$VIRT_OUT" --off 2>/dev/null
    VIRT_OUT=""
  fi
}
cleanup() {
  cleanup_screen
  [ -n "$HOST_PID" ] && kill "$HOST_PID" 2>/dev/null
}
trap cleanup EXIT
trap 'exit 0' INT TERM

start_host() {
  if [ -n "$HOST_PID" ] && kill -0 "$HOST_PID" 2>/dev/null; then return; fi
  python3 "$HERE/tabletdesk_host.py" --port "$PORT" &
  HOST_PID=$!
}

# Tablet size in landscape, scaled down to max 1920 px wide for smooth VNC.
tablet_size() {
  if [ -n "${TABLETDESK_SIZE:-}" ]; then echo "$TABLETDESK_SIZE"; return; fi
  local s w h
  s=$(adb shell wm size | tr -d '\r' | awk -F': ' '/Override/{o=$2} /Physical/{p=$2} END{print (o?o:p)}')
  w=${s%x*}; h=${s#*x}
  [ -z "$w" ] || [ -z "$h" ] && { echo 1920x1200; return; }
  if [ "$h" -gt "$w" ]; then local t=$w; w=$h; h=$t; fi
  if [ "$w" -gt 1920 ]; then h=$(( h * 1920 / w )); w=1920; fi
  echo "$(( w / 8 * 8 ))x$(( h / 2 * 2 ))"
}

# Turn an unused GPU output into a virtual monitor (works on Intel/AMD with
# the modesetting driver; NVIDIA proprietary usually refuses -> mirror).
enable_virtual_output() {
  local size=$1 w h out primary ml name rest
  w=${size%x*}; h=${size#*x}
  out=$(xrandr | awk '$2=="disconnected"{print $1; exit}')
  [ -z "$out" ] && return 1
  primary=$(xrandr | awk '/ connected primary/{print $1; exit}')
  [ -z "$primary" ] && primary=$(xrandr | awk '/ connected/{print $1; exit}')
  ml=$(cvt "$w" "$h" 60 | awk '/Modeline/{ $1=""; print }')
  read -r name rest <<<"$ml"; name=${name//\"/}
  xrandr --newmode "$name" $rest 2>/dev/null
  xrandr --addmode "$out" "$name" 2>/dev/null || return 1
  xrandr --output "$out" --mode "$name" --right-of "$primary" 2>/dev/null || return 1
  # prints "<output> <WxH+X+Y>"
  xrandr | awk -v o="$out" '$1==o{for(i=2;i<=NF;i++) if($i ~ /^[0-9]+x[0-9]+\+[0-9]+\+[0-9]+$/){print o, $i; exit}}'
}

# Wayland (Fedora default): GNOME Remote Desktop serves the screen over RDP.
# In "extend" mode GNOME creates a real virtual monitor sized to the tablet.
start_rdp() {
  if [[ "${XDG_CURRENT_DESKTOP:-}" == *GNOME* ]] && command -v grdctl >/dev/null; then
    local share=mirror-primary
    [ "$MODE" = "extend" ] && share=extend
    gsettings set org.gnome.desktop.remote-desktop.rdp screen-share-mode "$share" 2>/dev/null
    grdctl rdp enable >/dev/null 2>&1
    systemctl --user restart gnome-remote-desktop.service 2>/dev/null
    log "GNOME Remote Desktop ($share) on 127.0.0.1:$RDP_PORT via USB"
  else
    log "Non-GNOME Wayland: turn on Remote Desktop in your desktop's settings"
    log "  (KDE: System Settings > Remote Desktop). Mirror only."
  fi
  adb reverse tcp:$RDP_PORT tcp:$RDP_PORT >/dev/null
  VIEWER=rdp
}

start_screen() {
  local session="${XDG_SESSION_TYPE:-x11}" clip=""
  if [ "$session" = "wayland" ]; then
    start_rdp
    return
  fi
  if [ "$MODE" = "extend" ]; then
    read -r VIRT_OUT clip <<<"$(enable_virtual_output "$(tablet_size)")"
    if [ -z "$clip" ]; then
      log "Could not create a virtual monitor on this GPU -> falling back to mirror."
      cleanup_screen
    else
      log "Virtual monitor $VIRT_OUT at $clip"
    fi
  fi
  local auth=()
  [ -z "${XAUTHORITY:-}" ] && auth=(-auth guess)
  x11vnc -display "${DISPLAY:-:0}" "${auth[@]}" -localhost -rfbport "$VNC_PORT" \
    -forever -shared -nopw -quiet -repeat -xkb -noxdamage -cursor arrow \
    ${clip:+-clip "$clip"} >/dev/null 2>&1 &
  VNC_PID=$!
  adb reverse tcp:$VNC_PORT tcp:$VNC_PORT >/dev/null
  VIEWER=vnc
}

open_viewer() {
  sleep 1
  if [ "$VIEWER" = vnc ]; then
    adb shell am start -a android.intent.action.VIEW -d "vnc://127.0.0.1:$VNC_PORT" 2>&1 \
      | grep -q Error && log "Install AVNC (Play Store / F-Droid) on the tablet to view the screen."
  elif [ "$VIEWER" = rdp ]; then
    if screen_locked; then
      notify "Unlock Fedora to show the screen on the tablet."
      while screen_locked; do sleep 2; [ "$(adb get-state 2>/dev/null)" = device ] || return; done
    fi
    local app uri
    # Remote Desktop URI: opens the connection directly (password is saved in the app)
    uri="rdp://full%20address=s:127.0.0.1:$RDP_PORT&username=s:$USER"
    for app in $RDP_APPS; do
      adb shell pm path "$app" 2>/dev/null | grep -q package: || continue
      if adb shell "am start -a android.intent.action.VIEW -d '$uri' $app" 2>&1 | grep -q Error; then
        adb shell monkey -p "$app" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
        log "Opened $app -> tap the 127.0.0.1 PC"
      else
        log "Connecting $app to 127.0.0.1"
      fi
      return
    done
    notify "Install 'Windows App' (Microsoft Remote Desktop) or aFreeRDP on the tablet."
  fi
}

session_once() {
  log "Waiting for tablet on USB (enable USB debugging, accept the prompt)..."
  adb wait-for-usb-device
  local model
  model=$(adb shell getprop ro.product.model | tr -d '\r')
  notify "$model connected ($MODE mode)."
  wake_tablet
  adb reverse tcp:"$PORT" tcp:"$PORT" >/dev/null
  adb shell svc power stayon usb                                   # screen stays on while charging
  adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow 2>/dev/null # menu bar + Touch Bar overlays
  start_host
  [ "$MODE" != "input" ] && start_screen
  # start the menu bar + Touch Bar overlays, then put the desktop screen under them
  adb shell am start -n "$PKG/.MainActivity" --ez autostart true 2>&1 \
    | grep -q Error && log "TabletDesk app not installed: adb install TabletDesk.apk"
  open_viewer
  while [ "$(adb get-state 2>/dev/null)" = "device" ]; do sleep 2; done
  notify "Tablet unplugged."
  cleanup_screen
}

if [ "$WATCH" = 1 ]; then
  while true; do session_once; sleep 1; done
else
  session_once
fi
