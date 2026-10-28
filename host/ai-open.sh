#!/usr/bin/env bash
# Opens the AI assistant: Newelle, then Alpaca (local models), then Claude on the web.
# Bound to Ctrl+Alt+Space by ai-setup.sh and used by the tablet's AI button.
for app in io.github.qwersyk.Newelle com.jeffser.Alpaca; do
  if flatpak info "$app" >/dev/null 2>&1; then
    exec flatpak run "$app"
  fi
done
exec xdg-open "https://claude.ai/new"
