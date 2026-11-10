#!/usr/bin/env bash
# The tablet and iPhone get a notification when a terminal command that took 30
# seconds or more finishes (dnf upgrade, a build, a download...). Adds a few lines
# to ~/.bashrc (between "fedora-pad" markers). Finished downloads and the battery
# need nothing: Fedora Pad watches them already.
#   ./host/status-setup.sh          turn on
#   ./host/status-setup.sh --off    turn off
set -euo pipefail
RC="$HOME/.bashrc"
START="# >>> fedora-pad: tell the tablet when long commands finish >>>"
END="# <<< fedora-pad <<<"

remove() { if [ -f "$RC" ]; then sed -i "\|^$START\$|,\|^$END\$|d" "$RC"; fi; }

if [ "${1:-}" = "--off" ]; then
  remove
  echo "Off. Open a new terminal."
  exit 0
fi
remove
cat >> "$RC" <<'BASH'
# >>> fedora-pad: tell the tablet when long commands finish >>>
__fp_ready=1
__fp_pre() { [ -n "${COMP_LINE:-}" ] && return; [ "$__fp_ready" = 1 ] || return; __fp_ready=0; __fp_t=$SECONDS; __fp_cmd=$BASH_COMMAND; }
__fp_post() {
  local code=$? took=$(( SECONDS - ${__fp_t:-$SECONDS} ))
  if [ -n "${__fp_t:-}" ] && [ "$took" -ge 30 ]; then
    mkdir -p ~/.cache/fedora-pad/events
    python3 -c 'import json,sys,time; c,code,took=sys.argv[1:4]; ok=code=="0"; m,s=divmod(int(took),60)
json.dump({"icon":"✅" if ok else "❌","title":("Finished" if ok else "Failed")+" on the laptop","text":c[:200]+"  ·  %dm %02ds"%(m,s)},
open("%s/.cache/fedora-pad/events/%d.json"%(__import__("os").path.expanduser("~"),time.time()*1000),"w"),ensure_ascii=False)' \
      "$__fp_cmd" "$code" "$took" 2>/dev/null
  fi
  __fp_t=; __fp_ready=1
  return $code
}
trap '__fp_pre' DEBUG
PROMPT_COMMAND="${PROMPT_COMMAND:+$PROMPT_COMMAND; }__fp_post"
# <<< fedora-pad <<<
BASH
echo "On. Open a new terminal; commands that take 30 s or more now tell the tablet and iPhone when they finish."
