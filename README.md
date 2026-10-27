# TabletDesk 💻📱

Turn an Android tablet into a laptop-style screen, keyboard, trackpad and **macOS-style Touch Bar** for **Ubuntu 22.04**. Everything runs over **one USB-C cable**, which also charges the tablet. A **macOS-style menu bar** sits at the top of the tablet screen.

```
 ┌──────────────────────────── tablet ─────────────────────────────┐
 │ ◆  Firefox  File  Edit  View  Go  Window  Help     ⌨ ▭ ● ⚡92% 23:10 │  ← menu bar
 │                                                                  │
 │            Ubuntu desktop (extended or mirrored screen)          │
 │                                                                  │
 │ [esc] [◀][▶][⟳][🔍 Address][＋Tab] … [fn] │ 🔅 🔆 🔇 🔉 🔊 ⏮ ⏯ ⏭ ⌄ │  ← Touch Bar
 └──────────────────────────────────────────────────────────────────┘
                  USB-C  ⇄  power + ADB data  ⇄  Ubuntu laptop / PC
```

## Features

| Feature | What it does |
|---|---|
| 🖥 **Second screen** | `extend` mode adds the tablet as a monitor to the right of your main screen. `mirror` mode shows the same picture on both. |
| 👆 **Touch on screen** | Tap and drag directly on the Ubuntu screen, using the AVNC viewer. |
| ⌨ **Keyboard** | Uses the tablet's on-screen keyboard. Sticky Ctrl, Alt, Super and Shift keys. Non-English text (e.g. Persian) is typed through Ubuntu's Unicode input. A Bluetooth keyboard paired with the tablet also works. |
| 🖱 **Trackpad** | Laptop-style gestures. 1 finger moves the pointer, a tap clicks, and tap-then-drag drags. 2 fingers scroll, and a 2-finger tap right-clicks. A 3-finger swipe opens Activities or switches workspaces. |
| 🍎 **Menu bar** | macOS-style bar at the top showing the name of the focused Ubuntu app. The File, Edit, View, Go, Window and Help menus send the matching Ubuntu shortcuts. The ◆ menu has Settings, Lock, Log Out and Shut Down. The right side shows the clock, the tablet's battery and the connection status. |
| ▭ **Touch Bar** | Esc key, plus buttons that change with the focused app (browser, terminal, VS Code, Files, desktop). Includes an **fn** layer with F1–F12, brightness, volume and media keys. Hold a key to repeat it. Tap ⌄ to collapse the bar. |
| 🔌 **One cable** | USB-C charges the tablet while ADB tunnels all data. The Ubuntu side only listens on `127.0.0.1`, so nothing is exposed on Wi-Fi. |
| ⚡ **Plug & play** | A systemd user service waits for the tablet and sets everything up each time you plug it in. |

## Setup

### 1. Tablet (once)
1. Install **TabletDesk.apk**. Download it from this repo's **Actions** tab (artifact `TabletDesk-apk`) or from **Releases**.
2. Install **AVNC** from Play Store or F-Droid. It shows the Ubuntu screen.
3. Turn on USB debugging: Settings › About tablet › tap **Build number** 7×, then Developer options › **USB debugging** ON.

### 2. Ubuntu 22.04 (once)
```bash
git clone https://github.com/aliihjt/Android-as-ubuntu-keyboard.git
cd Android-as-ubuntu-keyboard
./host/setup-ubuntu.sh
```
This installs `adb`, `x11vnc` and `python3-evdev`, gives your user access to `/dev/uinput`, and installs the auto-start service.

Then **log out**. On the login screen, click ⚙ and choose **"Ubuntu on Xorg"**, then log back in. Xorg is required for the extend and mirror screen modes.

### 3. Connect
Plug in the USB-C cable and tap **Allow USB debugging** on the tablet. Then run:
```bash
systemctl --user start tabletdesk        # auto mode (extend), reconnects on every plug-in
# or run it by hand:
./host/connect.sh extend                 # tablet = 2nd monitor
./host/connect.sh mirror                 # tablet = same picture
./host/connect.sh input                  # tablet = keyboard / trackpad / Touch Bar only
```
The tablet then opens the menu bar and Touch Bar, followed by the Ubuntu screen in AVNC. Open **⌨** in the menu bar for the full keyboard and trackpad.

To change the default mode, edit `Environment=TABLETDESK_MODE=` in `~/.config/systemd/user/tabletdesk.service`. To force a resolution, set `TABLETDESK_SIZE=1920x1200`.

## How it works
```
Android app ──TCP 127.0.0.1:7777──► adb reverse (USB) ──► tabletdesk_host.py ──► /dev/uinput (keys, mouse)
AVNC viewer ──TCP 127.0.0.1:5900──► adb reverse (USB) ──► x11vnc (clipped to the virtual monitor)
```
- **`host/tabletdesk_host.py`** turns JSON messages from the tablet into real input events through `uinput`, which works on Xorg and Wayland. It also reports the focused app back to the tablet for the menu bar and Touch Bar.
- **`host/connect.sh`** sets up `adb reverse` and keeps the tablet screen awake while charging. In extend mode it turns an unused GPU output into a virtual monitor with `xrandr` and serves it with `x11vnc`.
- **`android/`** is the app, written in plain Java with no extra libraries. It has the overlay service (menu bar and Touch Bar), the trackpad and keyboard screen, and the connection client.

## Troubleshooting
| Problem | Fix |
|---|---|
| "Could not create a virtual monitor" | Your GPU driver doesn't allow it (common with NVIDIA's proprietary driver). The script falls back to `mirror`. |
| Using the Wayland session | Input, the menu bar and the Touch Bar still work. For the screen, turn on Settings › Sharing › **Remote Desktop**, then tap **Open Ubuntu screen (RDP)** in the app and connect to `127.0.0.1`. The menu bar can't see the app name on Wayland, so it shows "Ubuntu". |
| `Cannot open /dev/uinput` | Run `setup-ubuntu.sh`, then log out and back in. |
| Tablet charges slowly | Use a USB-C port on the laptop, not a USB-A adapter. USB-C ports usually supply 1.5–3 A. |
| `adb` shows "unauthorized" | Unplug, replug and accept the prompt on the tablet. Or run `adb kill-server`. |
| Persian/Unicode text doesn't appear | Unicode typing uses Ctrl+Shift+U, which works in GTK apps (GNOME apps, Firefox). Some Qt/Electron apps ignore it. |

## Build the APK yourself
```bash
cd android && ./gradlew assembleDebug    # needs Android SDK 34 + JDK 17
adb install app/build/outputs/apk/debug/app-debug.apk
```
