# Fedora Pad — what the owner wants (read this first)

## The vision
One Apple-style ecosystem made from the owner's own devices, with the laptop in the middle:

- **Android tablet** — the main device: keyboard, trackpad and second screen for the laptop,
  its own home screen (launcher), an AI e-book reader, notes, handwriting, tools.
- **iPhone** — the same Fedora Pad tools as a web app in Safari (no App Store).
- **Laptop** — Fedora 44 (GNOME, Wayland); dual-boots Windows, so the Windows version matters too.
- **Everything connected and talking to each other** (like Apple Continuity): send files/text
  between devices, one clipboard, continue a book or page on another device, ring a device,
  control one from another. The PC is the hub (`host/hub.py`).
- All features matter equally: controlling the laptop, devices working together, the reading device.
- **Same features on every device** wherever the platform allows.
- Design: keep the **Apple (iPadOS / macOS) look**.

## The owner
- Persian-speaking, in Iran. Answer in **simple English with a short Persian summary**; short, clear, no jargon.
- Not a developer: give exact copy-paste steps, numbered, with a "✅ done when …" check for each.
- Wants to be asked questions when a request is unclear, instead of guessing.
- Devices in use: Android tablet + iPhone + the Fedora laptop.

## Constraints that shaped the code
- **Google servers are blocked** on the owner's network; GitHub works. Downloads (adb, scrcpy, the APK)
  are mirrored to this repo's `latest-apk` release by CI. Persian features must not depend on Google.
- Persian everywhere: keyboard layout, RTL text, Persian TTS hints, Ganjoor poetry, Iranian book stores.
- Scripts never change firewall settings themselves; they print the commands for the owner.
- The Android app is plain Java, no libraries (package `io.github.aliihjt.tabletdesk`, minSdk 26).
  CI builds it on every push; the app updates itself (Settings › App update).
- Push work to both `claude/tablet-desk` and `main`.

## Where things are
- `android/` — the tablet app. `host/` — Fedora host (`tabletdesk_host.py`), web app (`webapp.py`, `web/index.html`),
  hub between devices (`hub.py`), shared helpers (`pad_extras.py`). `windows/` — Windows host and setup.
- `android/.../Tools.java` is the one list of tools (symbol, colour, name): every home screen and Search
  read it. The web app's tiles (`host/web/index.html`, `PHONE_TILES` / `TILES`) use the same names and symbols.
- Code both hosts need goes in `host/pad_extras.py` or `host/hub.py`, not copied into the Fedora and Windows hosts.
- `tests/` (run `python3 -m unittest discover -s tests`); CI also runs ruff, Android Lint and syntax checks.
- Only pushes to `main` publish the APK that tablets install; other branches only build and test.
- `host/tablet-clean.txt` is the tablet's bloatware list (the owner's tablet is a Samsung), used by
  `host/tablet-clean.sh` and `windows/TabletClean.bat`.
- The laptop's mouse/keyboard hand-over (grab, touchpad/mouse conversion) lives in `host/universal.py`, used by
  Universal Control (to the tablet) and iPhone control (`host/iphone_hid.py`, Bluetooth HID); don't duplicate it.
- README.md explains every feature and the setup steps.
