# Fedora Pad 📱💻

Your Android tablet as a **second screen, keyboard and trackpad** for your **Fedora** laptop, over **one USB-C cable** that also charges it. Clean iPadOS-style design: a simple home screen with big tiles, and one small floating button over the Fedora screen.

**⬇️ [Download the app (APK)](https://github.com/aliihjt/Android-as-ubuntu-keyboard/releases/download/latest-apk/TabletDesk.apk)** · Fedora Workstation 40+ (GNOME) · Android 8+

```
┌───────────────────── Fedora Pad ─────────────────────┐
│ Fedora Pad                               ● Connected │
│ ┌──────────────────────────────────────────────────┐ │
│ │ 🖥  fedora-laptop                                 │ │
│ │     Connected with the USB-C cable                │ │
│ │ [            Show Fedora Screen            ]      │ │
│ │ ( Second screen | Same picture | Keyboard only )  │ │
│ └──────────────────────────────────────────────────┘ │
│ TOOLS                                                │
│ [⌨ Keyboard] [🖱 Trackpad] [🎵 Media]  [🎛 Controls]   │
│ [⚡ Shortcuts] [🚀 Apps]   [📊 Stats]  [⚙ Settings]   │
└──────────────────────────────────────────────────────┘
```

## ✨ What's inside

| | Page | What it does |
|---|---|---|
| 🖥 | **Show Fedora Screen** | Fedora on the tablet as a **second screen** (drag windows across) or the **same picture**. The laptop's mouse pointer shows on the tablet too. |
| ⌨ | **Keyboard** | Full laptop keyboard: number row, Ctrl / Alt / ⌘, arrows, a **123 number pad**, and 🌐 for the tablet's own keyboard (Persian and other languages). |
| 🖱 | **Trackpad** | Big trackpad with click buttons; show the keyboard under it for a laptop layout. Tap, drag, 2-finger scroll / right-click / pinch zoom, 3 and 4-finger swipes. |
| 🎵 | **Media** | Play / pause, next, previous and volume for whatever plays on the PC. |
| 🎛 | **Controls** | Brightness, volume, dark mode, Night Light, Do Not Disturb, Wi-Fi, Bluetooth, mic, keep awake, power mode, lock / log out / restart / shut down. |
| ⚡ | **Shortcuts** | One-tap copy, paste, undo, windows, screenshots, recording, emoji, plus shortcuts for the app you're using (browser, terminal, VS Code). |
| 🚀 | **Apps** | Opens the apps pinned in Fedora's dash, plus search, all apps and AI. |
| 📊 | **Stats** | Live CPU, memory, temperature, disk, battery and network speed. |
| ✍️ | **Text & Clipboard** | Write on the tablet (any language) and type it on Fedora in one tap; clipboard both ways. |
| 📁 | **Send Files** | Photos and files from the tablet to Fedora's Downloads › Fedora Pad, over the cable. |
| 🎤 | **Presenter** | Slide remote with huge ◀ ▶ buttons, start / end, black screen and a timer. |
| 🎚 | **Stream Deck** | Big buttons you make yourself; each runs several steps: open an app, open a website, press keys, type text, wait. |
| 🗂 | **Windows** | Every open Fedora window as a card: tap to jump to it, ✕ to close it, **📱 To tablet** to put just that window full size on the tablet (Second screen mode). |
| 🗒 | **Notes** | Sticky notes written on the tablet. **Show on Fedora** opens them as coloured cards on the Fedora desktop, and they update live. Also saved as `~/Documents/Fedora Pad Notes.md`. |
| >_ | **Terminal Pad** | Your saved Fedora commands as big buttons: one tap runs it, the output shows on the tablet. Edit the list in `~/.config/tabletdesk/commands.json`. |
| 🌈 | **Ambient Light** | The tablet glows in the colours of the Fedora screen, like a bias light behind a TV. |
| ⚙️ | **Settings** | Screen mode, floating button, text size, light / dark, trackpad speed and scrolling. |
| ● | **Floating button** | The only thing over the Fedora screen. Drag it anywhere; tap it for a floating **keyboard** or **trackpad**, Media, Shortcuts or Home. |
| 🚫 | **Laptop keyboard off** | While the tablet is connected, the laptop's own keyboard is off (touchpad stays on). Unplug and it's back. |
| ⚡ | **Plug & play** | Plug in and everything starts by itself, including app updates. |

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
2. The Fedora Pad app installs itself and opens. Windows App opens next.
3. In Windows App, add the PC **`127.0.0.1`** with the user and password from `setup.sh`, and save the password.

**From now on: unlock Fedora and plug in the tablet. That's it.**

## 🧰 Fedora extras (optional)
| Script | What it does |
|---|---|
| `./host/macos-look.sh` | Makes Fedora look and feel like a MacBook: Dock, window buttons on the left, macOS theme, icons and cursors, top-left menu like the Apple menu, genie minimise, rounded corners, day/night macOS wallpapers, Inter font, Mac shortcuts (Super = Cmd: Super+Q, Super+Space Spotlight, Super+Shift+3/4/5 screenshots), two-finger right click, Firefox theme. `--undo` puts it back. |
| `./host/tune-up.sh` | Safe speed and battery tune-up: faster boot and downloads, cleanup, SSD TRIM, firmware check. |
| `./host/apps-pack.sh` | Installs codecs, VLC, Chrome, Telegram, VS Code, GIMP, OBS, Persian fonts and handy tools. |
| `./host/ai-setup.sh` | Adds an AI assistant on Ctrl+Alt+Space. |
| `./host/voice-setup.sh` | **Voice typing, offline:** press Ctrl+Super+D, speak English or Persian, press again: the words are typed where your cursor is. Also from the tablet (Spotlight › Voice typing). |
| `./host/tailscale-setup.sh` | **Fedora Pad from anywhere:** a private encrypted link over the internet, so the tablet works away from home too. |
| `./host/fedora-plus.sh` | Pick from 16 extras: clipboard history (Super+V), snap layouts, Persian keyboard on Alt+Shift, Jalali date, CPU temp and net speed in the top bar, phone link, auto dark mode, copy text from screen, emoji picker, Ctrl+Alt+T, New Document, Num Lock at login, auto-cleanup, backups. |

## 📶 Wi-Fi mode (no cable)
1. On Fedora: `./host/wifi-setup.sh`. It explains the one firewall command needed and asks you to run it yourself.
2. Plug the tablet in once: it pairs automatically (nothing to type).
3. Unplug: Fedora Pad keeps working on the same Wi-Fi. The connection is encrypted and only paired tablets get in.
4. For the screen, add a second PC in Windows App with the laptop's IP address.

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
