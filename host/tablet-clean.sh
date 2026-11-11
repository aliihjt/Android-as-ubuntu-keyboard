#!/usr/bin/env bash
# Makes the tablet just Fedora Pad: removes the maker's extra apps (Samsung's
# Bixby, Game Launcher, AR Emoji, preinstalled Facebook/Netflix...) and makes
# Fedora Pad the home screen. Over the USB cable, no root.
#   ./host/tablet-clean.sh           show the list, ask, then remove
#   ./host/tablet-clean.sh --list    only show what would be removed
#   ./host/tablet-clean.sh --undo    put back every app this script removed
# The list is host/tablet-clean.txt. Removed apps stay in the tablet's system
# partition: --undo (or a factory reset) brings them back.
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
LIST="$HERE/tablet-clean.txt"
LOG="${XDG_CONFIG_HOME:-$HOME/.config}/tabletdesk/tablet-clean.log"
PKG=io.github.aliihjt.tabletdesk
mode=${1:-clean}

command -v adb >/dev/null || { echo "adb is missing. Run ./host/setup.sh first."; exit 1; }
echo "Waiting for the tablet (plug in the USB-C cable, allow USB debugging)..."
timeout 60 adb wait-for-usb-device || { echo "No tablet found. Check the cable and USB debugging."; exit 1; }
maker=$(adb shell getprop ro.product.manufacturer | tr -d '\r')
model=$(adb shell getprop ro.product.model | tr -d '\r')
echo "Tablet: $maker $model"

if [ "$mode" = "--undo" ]; then
  [ -s "$LOG" ] || { echo "Nothing to put back: this script hasn't removed anything yet."; exit 0; }
  left=()
  while read -r p; do
    [ -n "$p" ] || continue
    if adb shell cmd package install-existing "$p" </dev/null 2>&1 | grep -qi "installed"; then
      echo "  ✅ back: $p"
    else
      echo "  ⚠️  couldn't put back: $p"; left+=("$p")
    fi
  done < <(sort -u "$LOG")
  printf '%s\n' "${left[@]}" | grep . > "$LOG"  # what failed stays for the next try
  echo "Done. The removed apps are back${left:+ (except ${#left[@]})}."
  exit 0
fi

installed=$(adb shell pm list packages --user 0 | tr -d '\r' | sed 's/^package://')
is_installed() { grep -qxF "$1" <<<"$installed"; }
trim() { local s="${1#"${1%%[![:space:]]*}"}"; printf '%s' "${s%"${s##*[![:space:]]}"}"; }

junk=() junk_names=() optional=() optional_names=()
while IFS='|' read -r group pkg what; do
  group=$(trim "$group"); pkg=$(trim "$pkg"); what=$(trim "$what")
  case "$group" in junk|optional) ;; *) continue ;; esac
  is_installed "$pkg" || continue
  if [ "$group" = junk ]; then junk+=("$pkg"); junk_names+=("$what")
  else optional+=("$pkg"); optional_names+=("$what"); fi
done < "$LIST"

echo
echo "Extra apps found on the tablet: ${#junk[@]}"
for i in "${!junk[@]}"; do echo "  • ${junk_names[$i]}  (${junk[$i]})"; done
[ ${#optional[@]} -gt 0 ] && echo "Apps you may still want (asked one by one): ${#optional[@]}"
case "$maker" in
  [Ss]amsung) ;;
  *) echo "(This list is made for Samsung tablets; on a $maker only the common apps match.)" ;;
esac
[ "$mode" = "--list" ] && { for i in "${!optional[@]}"; do echo "  ? ${optional_names[$i]}  (${optional[$i]})"; done; exit 0; }

remove=()
if [ ${#junk[@]} -gt 0 ]; then
  read -r -p "Remove these ${#junk[@]} apps? [Y/n] " a
  [[ "$a" =~ ^[Nn] ]] || remove+=("${junk[@]}")
fi
for i in "${!optional[@]}"; do
  read -r -p "Remove ${optional_names[$i]}? [y/N] " a
  [[ "$a" =~ ^[Yy] ]] && remove+=("${optional[$i]}")
done

mkdir -p "$(dirname "$LOG")"
gone=0
for p in "${remove[@]}"; do
  [ "$p" = "$PKG" ] && continue  # never Fedora Pad itself
  if adb shell pm uninstall -k --user 0 "$p" </dev/null 2>&1 | grep -q Success; then
    echo "$p" >> "$LOG"; gone=$((gone + 1)); echo "  🗑  removed: $p"
  else
    echo "  ⚠️  the tablet kept: $p (it protects this one)"
  fi
done

# Fedora Pad as the home screen (the Home button opens it)
if adb shell pm path "$PKG" 2>/dev/null | grep -q package:; then
  if adb shell cmd package set-home-activity "$PKG/.MainActivity" 2>&1 | grep -qi "success"; then
    echo "  🏠 Fedora Pad is now the home screen"
  else
    echo "  🏠 To make Fedora Pad the home screen: tablet Settings › Apps › Choose default apps › Home app › Fedora Pad"
  fi
else
  echo "  Fedora Pad isn't installed on the tablet yet: plug in with ./host/connect.sh running, then run this again."
fi

echo
echo "Done: $gone apps removed. To put them all back: ./host/tablet-clean.sh --undo"
