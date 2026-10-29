#!/usr/bin/env bash
# Fedora Plus: handy features Fedora doesn't have out of the box. Pick the ones
# you want from a menu, or install them all.
#   ./host/fedora-plus.sh          menu
#   ./host/fedora-plus.sh all      everything
#   ./host/fedora-plus.sh 1 3 5    just these
# Everything can be turned off again in the Extensions app or Settings.
set -uo pipefail

SHELL_VER=$(gnome-shell --version 2>/dev/null | grep -oE '[0-9]+' | head -1)
ok=(); failed=()
try() { local name=$1; shift; echo; echo "==> $name"; if "$@"; then ok+=("$name"); else failed+=("$name"); fi; }

# turn an extension on now if possible, and for sure after the next log-in
enable_ext() {
  gnome-extensions enable "$1" 2>/dev/null && return
  local cur new
  cur=$(gsettings get org.gnome.shell enabled-extensions)
  new=$(python3 -c '
import ast, sys
cur, uuid = sys.argv[1], sys.argv[2]
items = [] if cur.strip() in ("@as []", "[]") else ast.literal_eval(cur.replace("@as ", ""))
if uuid not in items: items.append(uuid)
print(str(items))' "$cur" "$1")
  gsettings set org.gnome.shell enabled-extensions "$new"
}

# extension settings live next to the extension (dnf or extensions.gnome.org)
ext_set() {  # ext_set <uuid> <schema> <key> <value>
  local d
  for d in "$HOME/.local/share/gnome-shell/extensions/$1/schemas" "/usr/share/gnome-shell/extensions/$1/schemas"; do
    [ -d "$d" ] && { gsettings --schemadir "$d" set "$2" "$3" "$4" 2>/dev/null; return; }
  done
  gsettings set "$2" "$3" "$4" 2>/dev/null
}

# install an extension from extensions.gnome.org, the version for this GNOME
ego() {  # ego <uuid>
  local url tmp r
  url=$(curl -fsSL "https://extensions.gnome.org/extension-info/?uuid=$1&shell_version=$SHELL_VER" |
    python3 -c 'import json, sys; print(json.load(sys.stdin)["download_url"])') || {
    echo "   No version of this extension for GNOME $SHELL_VER yet."; return 1; }
  tmp=$(mktemp --suffix=.zip)
  curl -fsSL -o "$tmp" "https://extensions.gnome.org$url" && gnome-extensions install --force "$tmp"
  r=$?
  rm -f "$tmp"
  [ $r -eq 0 ] && enable_ext "$1"
}

flathub() {  # flathub <app id>
  flatpak remote-add --user --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo &&
    flatpak install --user -y flathub "$1"
}

# add a keyboard shortcut that runs a command
shortcut() {  # shortcut <id> <name> <command> <binding>
  local base=org.gnome.settings-daemon.plugins.media-keys
  local path=/org/gnome/settings-daemon/plugins/media-keys/custom-keybindings/fedoraplus-$1/
  local cur new
  cur=$(gsettings get $base custom-keybindings)
  new=$(python3 -c '
import ast, sys
cur, key = sys.argv[1], sys.argv[2]
items = [] if cur.strip() in ("@as []", "[]") else ast.literal_eval(cur.replace("@as ", ""))
if key not in items: items.append(key)
print(str(items))' "$cur" "$path")
  gsettings set $base custom-keybindings "$new" &&
    gsettings set $base.custom-keybinding:$path name "$2" &&
    gsettings set $base.custom-keybinding:$path command "$3" &&
    gsettings set $base.custom-keybinding:$path binding "$4"
}

# ---------------------------------------------------------------- features

f_clipboard() {  # 1
  ego clipboard-indicator@tudmotu.com || return 1
  local u=clipboard-indicator@tudmotu.com s=org.gnome.shell.extensions.clipboard-indicator
  # Super+V shows the notifications by default; keep those on Super+M
  gsettings set org.gnome.shell.keybindings toggle-message-tray "['<Super>m']"
  ext_set $u $s enable-keybindings true
  ext_set $u $s toggle-menu "['<Super>v']"
  ext_set $u $s history-size 50
}

f_tiling() {  # 2
  ego tiling-assistant@leleat-on-github
}

f_persian() {  # 3
  local cur
  cur=$(gsettings get org.gnome.desktop.input-sources sources)
  if ! grep -q "'ir'" <<<"$cur"; then
    gsettings set org.gnome.desktop.input-sources sources "$(python3 -c '
import ast, sys
cur = sys.argv[1].replace("@a(ss) ", "")
items = [] if cur.strip() == "[]" else ast.literal_eval(cur)
if not items: items = [("xkb", "us")]
items.append(("xkb", "ir"))
print(str(items))' "$cur")" || return 1
  fi
  # Alt+Shift switches language, like on Windows (Super+Space keeps working)
  cur=$(gsettings get org.gnome.desktop.input-sources xkb-options)
  gsettings set org.gnome.desktop.input-sources xkb-options "$(python3 -c '
import ast, sys
cur = sys.argv[1].replace("@as ", "")
items = [o for o in ([] if cur.strip() == "[]" else ast.literal_eval(cur)) if not o.startswith("grp:")]
items.append("grp:alt_shift_toggle")
print(str(items))' "$cur")"
  sudo dnf install -y vazirmatn-vf-fonts 2>/dev/null || sudo dnf install -y vazirmatn-fonts 2>/dev/null || true
}

f_jalali() {  # 4
  ego PersianCalendar@oxygenws.com
}

f_vitals() {  # 5
  ego Vitals@CoreCoding.com
}

f_caffeine() {  # 6
  sudo dnf install -y gnome-shell-extension-caffeine && enable_ext caffeine@patapon.info
}

f_gsconnect() {  # 7
  sudo dnf install -y gnome-shell-extension-gsconnect && enable_ext gsconnect@andyholmes.github.io || return 1
  echo "   On the phone: install KDE Connect (Play Store / F-Droid), same Wi-Fi, then pair."
  echo "   If the phone can't find Fedora, allow it in the firewall yourself:"
  echo "     sudo firewall-cmd --permanent --add-service=kdeconnect && sudo firewall-cmd --reload"
}

f_nightswitch() {  # 8
  ego nightthemeswitcher@romainvigier.fr
}

f_ocr() {  # 9
  flathub com.github.tenderowl.frog &&
    shortcut ocr "Copy text from screen" "flatpak run com.github.tenderowl.frog" "<Super><Shift>t"
}

f_emoji() {  # 10
  flathub it.mijorus.smile && shortcut emoji "Emoji picker" "flatpak run it.mijorus.smile" "<Super>period"
}

f_terminal() {  # 11
  shortcut terminal "Terminal" \
    "sh -c 'command -v ptyxis >/dev/null && exec ptyxis --new-window || exec gnome-terminal'" "<Control><Alt>t"
}

f_newfile() {  # 12
  local t
  t=$(xdg-user-dir TEMPLATES 2>/dev/null || echo "$HOME/Templates")
  mkdir -p "$t" && : >>"$t/Text file.txt" && printf '#!/usr/bin/env bash\n' >"$t/Script.sh" && printf '# Title\n' >"$t/Markdown.md"
}

f_numlock() {  # 13
  gsettings set org.gnome.desktop.peripherals.keyboard remember-numlock-state true &&
    gsettings set org.gnome.desktop.peripherals.keyboard numlock-state true
}

f_cleanup() {  # 14
  gsettings set org.gnome.desktop.privacy remove-old-trash-files true &&
    gsettings set org.gnome.desktop.privacy remove-old-temp-files true &&
    gsettings set org.gnome.desktop.privacy old-files-age 30
}

f_backup() {  # 15
  flathub org.gnome.DejaDup
}

f_extras() {  # 16
  flathub com.github.finefindus.eyedropper && flathub com.github.johnfactotum.Foliate &&
    flathub io.github.flattool.Warehouse && flathub io.missioncenter.MissionCenter
}

FEATURES=(
  "f_clipboard|📋 Clipboard history: Super+V shows the last 50 things you copied"
  "f_tiling|🪟 Snap layouts: drag windows to edges/corners for halves and quarters, like Windows 11"
  "f_persian|⌨️  Persian keyboard: switch with Alt+Shift, plus the Vazirmatn font"
  "f_jalali|📅 Persian (Jalali) date in the top bar"
  "f_vitals|🌡  CPU temperature, memory and internet speed in the top bar"
  "f_caffeine|☕ Caffeine: a top-bar switch that keeps the screen awake"
  "f_gsconnect|📱 Phone link (GSConnect): phone notifications, files, clipboard, SMS"
  "f_nightswitch|🌓 Dark mode at sunset, light mode at sunrise"
  "f_ocr|🔤 Copy text from the screen or any image (Super+Shift+T)"
  "f_emoji|😀 Emoji picker (Super+.)"
  "f_terminal|💻 Ctrl+Alt+T opens the terminal"
  "f_newfile|📄 Right-click › New Document in Files (text, script, markdown)"
  "f_numlock|🔢 Num Lock on at login"
  "f_cleanup|🧹 Empty old trash and temp files automatically after 30 days"
  "f_backup|💾 Backups (Déjà Dup): automatic, to a USB disk or the cloud"
  "f_extras|🧰 Handy apps: Mission Control (task manager), colour picker, e-book reader, Flatpak manager"
)

choices=("$@")
if [ ${#choices[@]} -eq 0 ]; then
  echo "Fedora Plus: pick what to add"
  echo
  for i in "${!FEATURES[@]}"; do printf ' %2d  %s\n' $((i + 1)) "${FEATURES[$i]#*|}"; done
  echo
  read -rp "Numbers separated by spaces, or 'all': " -a choices
fi
[ "${choices[0]:-}" = "all" ] && choices=($(seq 1 ${#FEATURES[@]}))

for n in "${choices[@]}"; do
  if [[ "$n" =~ ^[0-9]+$ ]] && [ "$n" -ge 1 ] && [ "$n" -le ${#FEATURES[@]} ]; then
    entry=${FEATURES[$((n - 1))]}
    try "${entry#*|}" "${entry%%|*}"
  else
    echo "Skipping '$n': not on the list."
  fi
done

echo
echo "Added:"; for x in "${ok[@]}"; do echo "  ✓ $x"; done
if [ ${#failed[@]} -gt 0 ]; then echo "Didn't work (try again later):"; for x in "${failed[@]}"; do echo "  ✗ $x"; done; fi
echo
echo "Log out and back in once so new top-bar items appear."
echo "Change or turn any of them off later in the Extensions app or Settings › Keyboard."
