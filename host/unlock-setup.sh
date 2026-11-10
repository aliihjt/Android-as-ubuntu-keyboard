#!/usr/bin/env bash
# Unlock this laptop from the tablet or iPhone (Fedora Pad › Devices › your PC › Unlock).
# You choose an unlock PIN (6 digits or more); only a salted hash of it is kept, in
# ~/.config/tabletdesk/unlock.json. The tablet asks for its own screen lock first;
# the iPhone asks for the PIN. 5 wrong PINs block unlocking for 5 minutes.
#   ./host/unlock-setup.sh          choose (or change) the PIN
#   ./host/unlock-setup.sh --off    turn unlocking from devices off
set -euo pipefail
FILE="$HOME/.config/tabletdesk/unlock.json"

if [ "${1:-}" = "--off" ]; then
  rm -f "$FILE"
  echo "Unlocking from devices is off."
  exit 0
fi

while true; do
  read -rsp "Choose an unlock PIN (6+ digits, not your password): " pin; echo
  read -rsp "Type it again: " again; echo
  if [ "$pin" != "$again" ]; then echo "They don't match. Try again."; continue; fi
  if ! [[ "$pin" =~ ^[0-9]{6,}$ ]]; then echo "Use 6 or more digits."; continue; fi
  break
done
mkdir -p "$(dirname "$FILE")"
PIN="$pin" python3 - "$FILE" <<'PY'
import hashlib, json, os, secrets, sys
salt, it = secrets.token_bytes(16), 200_000
h = hashlib.pbkdf2_hmac("sha256", os.environ["PIN"].encode(), salt, it).hex()
fd = os.open(sys.argv[1], os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(fd, "w") as f:
    json.dump({"salt": salt.hex(), "iter": it, "hash": h}, f)
PY
echo "Done. On the tablet or iPhone: Devices › $(hostname) › 🔓 Unlock."
echo "Restart Fedora Pad once:  systemctl --user restart tabletdesk"
