# TabletDesk 💻📱

Turn an Android tablet into a **second screen, keyboard, trackpad and Touch Bar** for your **Fedora** laptop. Everything runs over **one USB-C cable**, which also charges the tablet. A **macOS-style menu bar** with Liquid Glass menus sits at the top of the tablet.

**⬇️ [Download the app (APK)](https://github.com/aliihjt/Android-as-ubuntu-keyboard/releases/download/latest-apk/TabletDesk.apk)** · Fedora Workstation 40+ (GNOME) · Android 8+

```
 ┌──────────────────────────────── tablet ─────────────────────────────────┐
 │ ◆  TabletDesk  Firefox  File  Edit  View  Go  Window  Help  Tools       │  ← menus
 │ 🚀 🔍 ⌨ ☰                                          ●  ⚡92%  Wed 10:42  │  ← icons
 │                                                                         │
 │           Fedora desktop (extended second screen, or mirrored)          │
 │                                                                         │
 │   [esc] [◀][▶][⟳][🔍 Address][＋Tab] … [fn] │ 🔅 🔆 🔇 🔉 🔊 ⏮ ⏯ ⏭ ⌄    │  ← Touch Bar
 └─────────────────────────────────────────────────────────────────────────┘
                 USB-C  ⇄  power + data (ADB)  ⇄  Fedora laptop
```

## ✨ Features

| | Feature | What it does |
|---|---|---|
| 🖥 | **Second screen** | **Extend** adds the tablet as a real extra monitor (drag windows across), **Mirror** shows the same picture, **Input only** skips the screen. The choice is remembered. |
| 🖱 | **One desktop** | The laptop touchpad moves the pointer onto the tablet and the tablet draws it there. Touch works directly on the tablet too. |
| ⌨ | **Keyboard** | Full-screen keyboard & trackpad page with sticky Ctrl / Alt / Super / Shift, arrows and a one-tap shortcut row (copy, paste, undo, save, find, switch app…). Persian and other languages work. A Bluetooth keyboard on the tablet types into Fedora too. |
| 🚫 | **Laptop keyboard off** | While the tablet is connected, the laptop's own keyboard is switched off (touchpad stays on). Unplug and it's back. |
| 👆 | **Trackpad** | 1 finger move · tap click · tap + drag · 2 fingers scroll / right click · 3 fingers Activities and workspaces. Speed and natural scrolling are adjustable. |
| 🍎 | **Menu bar** | Two lines: menus on top (with the focused app's name), icons, battery and clock below. Glass drop-down menus show the shortcut for every command. |
| ⚙️ | **TabletDesk menu** | Every setting in one place: mode, laptop keyboard, touch, bars, size, theme, trackpad, PC info (CPU, RAM, battery), reconnect. |
| 🧰 | **Tools menu** | Screenshots, screen recording, clipboard, emoji, Wi-Fi, Bluetooth, microphone, Night Light, dark mode, Do Not Disturb, keep PC awake, power profile. |
| 🎛 | **Control Center** | Brightness and volume sliders, switches and quick buttons, like macOS. |
| ▭ | **Touch Bar** | Esc, keys that change with the app (browser, terminal, VS Code, Files), an **fn** layer with F1–F12, brightness, volume and media. |
| 🚀 | **Dock** | Opens the apps pinned in Fedora's dash. |
| 📋 | **Clipboard** | Copy text from the tablet to the PC and back. |
| 🎨 | **Liquid Glass** | Frosted, blurred glass design (Android 12+), light and dark themes, three sizes. |
| ⚡ | **Plug & play** | Plug in and everything starts by itself: screen, bars, the right mode, and even **app updates**. |

## 🚀 Setup (once)

### 📱 Tablet
1. Settings › About tablet › tap **Build number** 7 times. Then Developer options › turn on **USB debugging**.
   *Xiaomi / Redmi / POCO: also turn on **Install via USB**.*
2. Install **Windows App** (Microsoft Remote Desktop) from the Play Store.

### 🐧 Fedora
```bash
sudo dnf install -y git
git clone https://github.com/aliihjt/Android-as-ubuntu-keyboard.git
cd Android-as-ubuntu-keyboard && ./host/setup.sh
```
`setup.sh` installs what's needed, sets up GNOME Remote Desktop and **prints a user name and password**. Write them down.

Then **log out and back in**, and run:
```bash
gnome-extensions enable tabletdesk@aliihjt.github.io
```

### 🔌 First connection
1. Plug in the USB-C cable. On the tablet, tick **Always allow from this computer** and tap **Allow**.
2. The TabletDesk app installs itself and opens. Windows App opens next.
3. In Windows App, add the PC **`127.0.0.1`** with the user and password from `setup.sh`, and save the password.

**From now on: unlock Fedora and plug in the tablet. That's it.**

## 🔄 Update
```bash
~/Android-as-ubuntu-keyboard/host/update.sh
```
The tablet app also updates itself every time you plug in. The app shows its **Build** number on its home screen.

## 🛟 Troubleshooting

| Problem | Fix |
|---|---|
| Keyboard or touchpad stuck after unplugging | `~/Android-as-ubuntu-keyboard/host/reset.sh` |
| Anything else | `~/Android-as-ubuntu-keyboard/host/diagnose.sh`, then open an issue with `~/tabletdesk-diagnose.txt` |
| Screen doesn't appear | Unlock Fedora. GNOME only shares an unlocked screen. |
| App doesn't update | Watch the tablet for an install prompt. Xiaomi: turn on **Install via USB**. |
| Text on the tablet is tiny | Fedora Settings › Displays › tablet monitor › **Scale 200%** |
| Menu bar shows "Fedora" instead of the app | Log out and in, then `gnome-extensions enable tabletdesk@aliihjt.github.io` |
| See what's happening | `journalctl --user -u tabletdesk -f` |

## 🔧 How it works
```
TabletDesk app ──127.0.0.1:7777──► adb reverse (USB) ──► tabletdesk_host.py ──► /dev/uinput (keys, mouse)
Windows App    ──127.0.0.1:3389──► adb reverse (USB) ──► GNOME Remote Desktop (virtual monitor)
GNOME extension ──D-Bus──► tabletdesk_host.py ──► app name + pointer position ──► tablet
```

| Path | What it is |
|---|---|
| `host/tabletdesk_host.py` | Turns messages from the tablet into real keyboard and mouse input (works on Wayland), runs the whitelisted Tools and Control Center actions, and turns the laptop keyboard off while connected. |
| `host/connect.sh` | Runs on every plug-in: `adb reverse`, screen mode, app updates, and a clean hand-back on unplug. |
| `host/gnome-extension/` | Reports the focused app and the pointer position (GNOME 45–50). |
| `android/` | The app, in plain Java with no extra libraries. |

Everything listens on `127.0.0.1` only and travels over the USB cable. Nothing is exposed on your network.

### Build the APK yourself
```bash
cd android && ./gradlew assembleDebug    # Android SDK 34 + JDK 17
```
GitHub Actions builds it on every push and publishes it to the [newest-build release](https://github.com/aliihjt/Android-as-ubuntu-keyboard/releases/tag/latest-apk).
