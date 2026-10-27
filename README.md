# TabletDesk 💻📱

Turn an Android tablet into a second screen, keyboard, trackpad and **macOS-style Touch Bar** for **Fedora Workstation** (GNOME). Everything runs over **one USB-C cable**, which also charges the tablet. A **macOS-style menu bar** sits at the top of the tablet screen. Ubuntu is still supported too.

```
 ┌──────────────────────────── tablet ─────────────────────────────┐
 │ ◆  Firefox  File  Edit  View  Go  Window  Help     ⌨ ▭ ● ⚡92% 23:10 │  ← menu bar
 │                                                                  │
 │           Fedora desktop (extended or mirrored screen)           │
 │                                                                  │
 │ [esc] [◀][▶][⟳][🔍 Address][＋Tab] … [fn] │ 🔅 🔆 🔇 🔉 🔊 ⏮ ⏯ ⏭ ⌄ │  ← Touch Bar
 └──────────────────────────────────────────────────────────────────┘
                 USB-C  ⇄  power + ADB data  ⇄  Fedora laptop / PC
```

## Features

| Feature | What it does |
|---|---|
| 🖥 **Second screen** | `extend` mode uses GNOME Remote Desktop to add the tablet as a real virtual monitor. `mirror` mode shows the same picture on both. |
| 👆 **Touch on screen** | Tap and drag directly on the Fedora screen inside the RDP app. |
| ⌨ **Keyboard** | Uses the tablet's on-screen keyboard. Sticky Ctrl, Alt, Super and Shift keys. Non-English text (e.g. Persian) is typed through GNOME's Unicode input. A Bluetooth keyboard paired with the tablet also works. |
| 🖱 **Trackpad** | Laptop-style gestures. 1 finger moves the pointer, a tap clicks, and tap-then-drag drags. 2 fingers scroll, and a 2-finger tap right-clicks. A 3-finger swipe opens Activities or switches workspaces. |
| 🍎 **Menu bar** | macOS-style bar at the top showing the focused app's name. A small GNOME extension provides it, because Wayland hides other apps' windows. The File, Edit, View, Go, Window and Help menus send the matching shortcuts. The ◆ menu has Settings, Lock, Log Out and Shut Down. The right side shows the clock, the tablet's battery and the connection status. |
| ▭ **Touch Bar** | Esc key, plus buttons that change with the focused app (browser, Ptyxis/terminal, VS Code, Files, desktop). Includes an **fn** layer with F1–F12, brightness, volume and media keys. Hold a key to repeat it. Tap ⌄ to collapse the bar. |
| 🔌 **One cable** | USB-C charges the tablet while ADB tunnels all data through `127.0.0.1`. |
| ⚡ **Plug & play** | A systemd user service sets everything up each time you plug the tablet in. |

## Setup

### 1. Tablet (once)
1. Install **TabletDesk.apk**. Download it from this repo's **Actions** tab (artifact `TabletDesk-apk`) or from **Releases**.
2. Install an RDP app to see the Fedora screen. **Windows App** (Microsoft Remote Desktop) is recommended; **aFreeRDP** is the alternative.
3. Turn on USB debugging: Settings › About tablet › tap **Build number** 7×, then Developer options › **USB debugging** ON.

### 2. Fedora (once)
```bash
git clone https://github.com/aliihjt/Android-as-ubuntu-keyboard.git
cd Android-as-ubuntu-keyboard
./host/setup.sh
```
The script does the following:
- Installs `android-tools` (adb), `python3-evdev`, `gnome-remote-desktop` and `openssl` with `dnf`.
- Gives your user access to `/dev/uinput`.
- Sets up GNOME Remote Desktop (RDP) with its own certificate and a random password. It **prints the user name and password** at the end.
- Installs the small GNOME extension and the auto-start service.

Then **log out and back in**, and run `gnome-extensions enable tabletdesk@aliihjt.github.io`.

### 3. Connect
Plug in the USB-C cable and tap **Allow USB debugging** on the tablet. Then run:
```bash
systemctl --user start tabletdesk        # auto mode (extend), reconnects on every plug-in
# or run it by hand:
./host/connect.sh extend                 # tablet = 2nd monitor
./host/connect.sh mirror                 # tablet = same picture
./host/connect.sh input                  # tablet = keyboard / trackpad / Touch Bar only
```
The first time, add a PC in the RDP app with these settings:
- **PC name:** `127.0.0.1`
- **User and password:** the ones `setup.sh` printed (also saved in `~/.config/tabletdesk/rdp-password`).
- Accept the certificate warning. The certificate is the one `setup.sh` created.

After that, each plug-in opens the menu bar, the Touch Bar and the RDP app automatically.

To change the default mode, edit `Environment=TABLETDESK_MODE=` in `~/.config/systemd/user/tabletdesk.service`.

## How it works
```
TabletDesk app ──TCP 127.0.0.1:7777──► adb reverse (USB) ──► tabletdesk_host.py ──► /dev/uinput (keys, mouse)
RDP app        ──TCP 127.0.0.1:3389──► adb reverse (USB) ──► GNOME Remote Desktop (virtual monitor)
GNOME extension ──D-Bus──► tabletdesk_host.py ──► focused app name ──► menu bar + Touch Bar
```
- **`host/tabletdesk_host.py`** turns JSON messages from the tablet into real input events through `uinput`, which works on Wayland.
- **`host/connect.sh`** sets up `adb reverse` and keeps the tablet screen awake while charging. It also switches GNOME Remote Desktop between `extend` and `mirror-primary`. On an Xorg session (other desktops, or Ubuntu) it falls back to `xrandr` plus `x11vnc` and the AVNC app.
- **`host/gnome-extension/`** reports the focused app on D-Bus. It supports GNOME 45–50.
- **`android/`** is the app, written in plain Java with no extra libraries. It has the overlay service (menu bar and Touch Bar), the trackpad and keyboard screen, and the connection client.

## Troubleshooting
| Problem | Fix |
|---|---|
| RDP app can't connect | Check `grdctl status` shows RDP **enabled**, then run `systemctl --user restart gnome-remote-desktop`. Make sure the screen is unlocked: GNOME only shares an active session. |
| Tablet screen is not a separate monitor | Make sure the mode is `extend`, then reconnect the RDP app. GNOME applies the mode change on a new connection. |
| Menu bar always says "Fedora" | The extension isn't active. Log out and back in, run `gnome-extensions enable tabletdesk@aliihjt.github.io`, and check that `gnome-extensions list --enabled` lists it. |
| `Cannot open /dev/uinput` | Run `setup.sh`, then log out and back in. |
| KDE Plasma (Fedora KDE) | Input, the menu bar and the Touch Bar work. For the screen, turn on System Settings › Remote Desktop. It can mirror the screen only, and the menu bar shows "Fedora" instead of the app name. |
| Tablet charges slowly | Use a USB-C port on the laptop, not a USB-A adapter. |
| `adb` shows "unauthorized" | Unplug, replug and accept the prompt on the tablet. Or run `adb kill-server`. |
| Persian/Unicode text doesn't appear | Unicode typing uses Ctrl+Shift+U, which works in GTK apps (GNOME apps, Firefox). Some Qt/Electron apps ignore it. |

## Build the APK yourself
```bash
cd android && ./gradlew assembleDebug    # needs Android SDK 34 + JDK 17
adb install app/build/outputs/apk/debug/app-debug.apk
```
