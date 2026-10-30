#!/usr/bin/env bash
# Puts the newest scrcpy (official static Linux build from GitHub) in
# ~/.local/share/fedora-pad/scrcpy. Used by webcam-setup.sh and mirror-setup.sh.
get_scrcpy() {
  local dir="$HOME/.local/share/fedora-pad/scrcpy" tag
  if [ "$(uname -m)" != x86_64 ]; then
    echo "    Not an x86_64 PC: install scrcpy from your distribution instead."
    return 1
  fi
  tag=$(curl -fsSIL -o /dev/null -w '%{url_effective}' https://github.com/Genymobile/scrcpy/releases/latest | sed 's#.*/##')
  case "$tag" in v[0-9]*) ;; *) tag=v5.0.1 ;; esac
  if [ -x "$dir/scrcpy" ] && [ "$(cat "$dir/.version" 2>/dev/null)" = "$tag" ]; then
    echo "    scrcpy $tag (already there)"
    return 0
  fi
  mkdir -p "$dir"
  curl -fL --progress-bar "https://github.com/Genymobile/scrcpy/releases/download/$tag/scrcpy-linux-x86_64-$tag.tar.gz" |
    tar xz -C "$dir" --strip-components=1 && echo "$tag" >"$dir/.version" && echo "    scrcpy $tag"
}
