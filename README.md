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
| ⌨ | **Keyboard** | Full laptop keyboard: number row, Ctrl / Alt / ⌘, arrows, a **123 number pad**, a **فا** key for the Persian layout (Fedora switches with it and gets the Persian layout added by itself), and 🌐 for the tablet's own keyboard. |
| 🖱 | **Trackpad** | Big trackpad with click buttons; show the keyboard under it for a laptop layout. Tap, drag, 2-finger scroll / right-click / pinch zoom, 3 and 4-finger swipes. |
| ↔️ | **Universal Control** | Push the laptop's pointer past the right edge of its screen and it moves on to the tablet: the laptop's mouse, touchpad and keyboard then work there (tap to click, two-finger scroll, drag, typing; Esc = Back). Move off the tablet's left edge to come back; Ctrl+Alt+Esc always brings them back. Over the cable or Wi-Fi. Once: Laptop folder › Universal Control › *Turn on the helper* (Android's accessibility setting). Choose which side the tablet is on there. Typing needs Android 13+. Not while the tablet is the laptop's second screen. Fedora. |
| ✏️ | **Pen Tablet** | The tablet (or iPad / iPhone) as a drawing tablet for the laptop, like Apple's Sidecar: Fedora sees a real pen, so it works in Krita, GIMP, Xournal++ and Inkscape. The pad has the laptop screen's shape. One finger draws; two-finger tap = right-click; Eraser and Undo buttons. A stylus or Apple Pencil gives pressure and hover. Fedora; after updating, log out and in once. |
| 🎵 | **Media** | Play / pause, next, previous and volume for whatever plays on the PC. |
| 🎛 | **Controls** | Brightness, volume, dark mode, Night Light, Do Not Disturb, Wi-Fi, Bluetooth, mic, keep awake, power mode, lock / log out / restart / shut down. |
| ⚡ | **Shortcuts** | One-tap copy, paste, undo, windows, screenshots, recording, emoji, plus shortcuts for the app you're using (browser, terminal, VS Code). |
| 🚀 | **PC Apps** | Opens the apps pinned in Fedora's dash, plus search, all apps and AI. |
| 📊 | **Stats** | Live CPU, memory, temperature, disk, battery and network speed. |
| 📝 | **Type Text** | Write on the tablet (any language) and type it on Fedora in one tap; clipboard both ways. |
| 📁 | **Send Files** | Photos and files from the tablet to Fedora's Downloads › Fedora Pad, over the cable. |
| 🎤 | **Presenter** | Slide remote with huge ◀ ▶ buttons, start / end, black screen and a timer. |
| 🎚 | **Stream Deck** | Big buttons you make yourself; each runs several steps: open an app, open a website, press keys, type text, wait. |
| 🗂 | **Windows** | Every open Fedora window as a card: tap to jump to it, ✕ to close it, **📱 To tablet** to put just that window full size on the tablet (Second screen mode). |
| 🎙 | **Push to Talk** | Hold the mic (or the volume-down key), speak, let go: the words are typed where the cursor is on Fedora. Tablet mic shows the words live (English, فارسی or automatic); Laptop mic is fully offline. Also as a floating mic over the Fedora screen. |
| 🗒 | **Notes** | Sticky notes written on the tablet. **Show on Fedora** opens them as coloured cards on the Fedora desktop, and they update live. Also saved as `~/Documents/Fedora Pad Notes.md`. |
| >_ | **Terminal** | Your saved Fedora commands as big buttons: one tap runs it, the output shows on the tablet. Edit the list in `~/.config/tabletdesk/commands.json`. |
| 🌈 | **Ambient Light** | The tablet glows in the colours of the Fedora screen, like a bias light behind a TV. |
| 🎮 | **Controller** | A **PS4 DualShock 4**, Xbox, 8BitDo or any controller paired with the tablet becomes a real controller on Fedora for Steam, emulators and games, with **rumble**. Fedora sees it as an Xbox controller (works everywhere) or a PlayStation one (✕ ○ □ △). Up to 4 players, over the cable or Wi-Fi; a live picture shows every button. Keep the page open while you play. **When a controller connects:** open the **iiSU** game launcher to play on the tablet, or this page to play on Fedora (*Smart* picks Fedora while connected, iiSU otherwise). **Steam Big Picture** can open on Fedora whenever a controller connects to the laptop, even without the tablet (`python3 host/gamepad.py --steam on`). |
| ⚙️ | **Settings** | Screen mode, floating button, text size, light / dark, **black & white screen**, trackpad speed and scrolling. |
| ◐ | **Black & white screen** | For a tablet set to greyscale: plain white (or black) pages, black outlines and black icons, on/off switches filled black, so nothing depends on colour. Settings › Black & white screen: *Automatic* follows Android's greyscale setting; *On* forces it. |
| ● | **Floating button** | The only thing over the Fedora screen. Drag it anywhere; tap it for a floating **keyboard** or **trackpad**, Media, Shortcuts or Home. |
| ⌨️ | **Laptop keyboard** | Stays on by default. Settings › Laptop keyboard can turn it off while the tablet is connected (touchpad stays on); unplug and it's back. |
| ⚡ | **Plug & play** | Plug in and everything starts by itself, including app updates. |

## 🏠 The tablet on its own
Fedora Pad can be the tablet's **home screen** (Settings › Home screen), so it's useful even without the laptop:

| | Page | What it does |
|---|---|---|
| 📚 | **Books** | PDF, EPUB and text books with covers and reading progress. Text size, night mode, swipe to turn pages. Select text to ask Claude about it or keep it as a note; on a PDF, Claude gets a picture of the page. |
| 🔊 | **Read aloud** | 🔊 Listen in a book (EPUB / text): reads paragraph by paragraph and highlights it, ⏮ ⏭, speed 0.8–2×, sleep timer (15/30/60 min or end of part), goes on to the next part. Persian books use a Persian voice (install eSpeak NG or RHVoice if Android has none). |
| 🔎 | **Tap a word** | Double-tap any word: translate or define it with your apps (Google Translate, a dictionary…), Wiktionary, or Claude (English ↔ Persian), or ⭐ save it to **Words** with its sentence. Words has search and ✨ Quiz me. |
| 🖍 | **Highlights** | Select text › 🖍 Highlight: 4 colours and an optional note; they stay in the book. 📑 shows a book's highlights; ⇪ Export as Markdown to Downloads, the PC, Notes or any app (Obsidian, Keep…). |
| ⬇️ | **Get Books** | Free classics from Project Gutenberg (search, one tap downloads the EPUB into Books); 🌹 Persian poetry from Ganjoor — Hafez, Saadi, Rumi, Ferdowsi, Khayyam… with meanings, read aloud, and 🎲 **فال حافظ**; 🇮🇷 Iranian stores: Taaghche, Fidibo, Ketabrah, Navaar, Ketabnak, Wikisource. |
| ✨ | **Reading helper** | ✨ in a book: summarise this part, quiz me, explain like I'm 10, key words with Persian meanings, خلاصه به فارسی, what happened so far, or explain the selected text — sent to the Claude app. Switch *Answers in Persian* on in the same menu. |
| 🎧 | **Audiobook** | 🎧 in a book (EPUB / text): keeps reading aloud with the screen off, part after part, from where you are. Play / pause / skip on the lock screen, with headphone buttons, or from the iPhone (Devices › the tablet). Remembers the place. |
| 📅 | **Persian calendar** | On the iPad home, the iPhone home and StandBy: today in the Jalali calendar (پنجشنبه ۲۱ آبان ۱۴۰۵), today's holiday or the next one ("۲ روز دیگر"), and a couplet of Hafez for the day (tap for فال حافظ). Solar holidays are exact; lunar ones (≈) follow the Islamic calendar a day later, as Iran usually does, and can be a day off. |
| 🔋 | **Batteries** | Like the iPhone's Batteries widget: the tablet's battery shows in the laptop's top bar (click it for every device), and the iPad home's *Batteries* card and the iPhone's Laptop widget show the laptop's and the tablet's. (Safari doesn't tell web pages the iPhone's own battery.) |
| 🧩 | **Widgets** | On both home screens: weather (tap to set your city), the next event in the tablet's calendar, the laptop's battery and CPU, and on the Reader home what the laptop is playing (tap to pause). |
| 📱 | **iPad home screen** | The default home: app pages you swipe, widgets on the first page (date, the book you're reading, weather, calendar, laptop, music), folders (*Laptop*, *Reading*), and the Dock. **Swipe down** from the top right for **Control Center** (PC volume, brightness, music, Devices, unlock), from the top left for **Notification Center**, in the middle for **Search**. Settings › Home screen switches to the Mac desktop or the Reader home. The iPhone web app's home looks the same way (wallpaper, pages, widgets, Dock, Control Center). |
| 📖 | **Reader home** | Settings › Home screen › 📖 Reader (or the Reader Home icon): a calm home with a big clock, Continue reading, today's goal ring, 🔥 streak and the week, reading tools, your pinned apps and ✨ Ask Claude. 🖥 switches back to the desktop. Tap the goal card to change the daily minutes. |
| ✍️ | **Handwriting** | Write or draw with a finger or stylus (palm ignored with a stylus): pens, highlighter, eraser, undo, lined or grid paper. Save to the Gallery, send to Fedora, or let Claude read it. |
| 🗒 | **Notes** | Sticky notes, also on the Fedora desktop. Book quotes and AI answers can be saved here. |
| ✨ | **Claude** | Opens the official Claude app (free on the Play Store; Fedora Pad offers to install it). **Ask Claude** in Books, Handwriting and Search sends your question, and a picture of the page or your writing, straight to it; the question is also copied in case you need to paste it. |
| 🔍 | **Search** | One box for the tablet's apps, notes, books, Fedora Pad pages and Fedora apps, plus Ask Claude and Search the web. |
| 🕰 | **StandBy** | A big clock while the tablet charges: date, weather (tap to set your city), what's playing on the PC with buttons, PC load, battery and the focus timer. Red and dim at night. Opens by itself when charging starts (Settings › StandBy), or as Android's screen saver. |
| ⏱ | **Focus** | A 15–90 minute timer; the PC (and, if you allow it, the tablet) stays on Do Not Disturb until it rings. |
| 📄 | **Scanner** | Photograph pages, drag the corners onto the paper, and get a straight, clean PDF on the PC, in Downloads, or shared to any app. |
| 🖥 | **Show on PC** | The tablet's screen in a window on Fedora or Windows (scrcpy over the cable): use it with the PC's mouse and keyboard, with its sound. An iPhone / iPad shows on Fedora with AirPlay (UxPlay): start it from the tablet or the web app, then Control Center › Screen Mirroring. Fedora: `./host/mirror-setup.sh` once; Windows: Setup.bat adds Tablet Mirroring. |
| 📷 | **Webcam** | The tablet's camera and mic become “Fedora Pad Camera” and “Fedora Pad Microphone” on Fedora for Zoom, Meet and OBS (USB cable, Android 12+). The iPhone web app has a Webcam page too (Wi-Fi). Run `./host/webcam-setup.sh` once. |
| 📤 | **AirDrop from the PC** | Fedora: Files › right-click › **Scripts › Send to Fedora Pad**. Windows: right-click › **Send to › Fedora Pad**. Files (folders as a zip) and links appear on the tablet (Downloads › Fedora Pad, with a notification) and on the iPhone web app (Save to Photos / Files). |
| 📋 | **Clipboard** | The last 50 things copied on the PC; tap one to paste it again, long-press to copy it to the tablet or keep it as a note. Password managers' copies are skipped. Also in the iPhone web app. |
| ⏰ | **Wake & Sleep** | Wake the sleeping PC from the tablet (run `./host/wake-setup.sh` once), and sleep it now, in 30–90 minutes, or every night, with a minute's warning you can cancel. The iPhone web app has the sleep timers. |
| ▦ | **App Library** | All the tablet's apps with search; long-press to pin one to the home screen. |

## 🔗 All your devices, connected

The tablet, the iPad and iPhone (web app) and the PC work as one set, like Apple's Continuity. The PC is the hub: everything connected to it can talk to everything else. Open **🔗 Devices** on any of them.

| | Feature | How |
|---|---|---|
| 📤 | **Send between devices** | Devices › pick one › Photos and files / Text or link. On the tablet also **Share › Send to device** from any app. Files land in Downloads › Fedora Pad (tablet) or a Save / Share sheet (iPhone, iPad). |
| 📋 | **Universal Clipboard** | Copy on the PC, tablet, iPad or iPhone, paste on any other. On the iPhone / iPad a banner appears: tap it to copy (Safari only copies on a tap). The tablet shares what it copied when a Fedora Pad screen opens (Android's rule), or with *Send the tablet's clipboard now*. Turn it off in Devices. |
| 📲 | **Handoff** | 📲 in the reader and on a poem: continue on another device. Free (Gutenberg) books download there; other books are sent as a file. From the iPad: Get Books › *To the tablet*. |
| 📖 | **Reading everywhere** | The tablet's library, progress, highlights and Words, kept on the PC: the iPhone / iPad **Reading** page shows them; *Read here* or *Get it from the tablet*. |
| 🔔 | **Notifications everywhere** | Devices › *Show notifications on other devices* (Android asks to allow it once): the tablet's messages and alerts appear on the iPhone (banner + Notifications page) and the laptop. **Reply** to Telegram, WhatsApp and other messages from the iPhone. |
| 💻 | **The laptop's notifications** | Chrome, Telegram, mail and other alerts on Fedora also appear on the tablet (as a notification) and on the iPhone (as a banner while the web app is open). Devices › *Show the laptop's notifications here* turns it off on that device. |
| 🌐 | **Chrome Handoff** | The page open in Chrome on the laptop shows as 🌐 at the end of the tablet's Dock and as *💻 Continue: …* on the iPhone's home: one tap opens it there. The other way: Share › Fedora Pad › *Open on the laptop* on the tablet, or Devices › your PC › *Open a link on the laptop* on the iPhone. Once: `./host/handoff-setup.sh` (adds the small *Fedora Pad Handoff* extension to Chrome; it only talks to Fedora Pad on the same computer, never in private windows). |
| 📝 | **Notes everywhere** | One set of notes: a change on the tablet, the iPhone or the PC shows on the others right away. |
| 📷 | **iPhone camera for the tablet** | Scanner and Handwriting on the iPhone › **📲 To the tablet**: the picture lands in the tablet's Downloads › Fedora Pad with a notification. |
| 📖 | **Continue reading** | Close a book on the tablet and the iPhone's home shows *Continue reading* with the part you were on: **Read** (free books) or **Get** it from the tablet. |
| 💻 | **The laptop tells you** | A notification on the tablet and iPhone when a download finishes, the battery is low or full, or a long terminal command ends (`./host/status-setup.sh` once). |
| 🍏 | **iPhone notifications, music and battery on the laptop** | Like a Mac: every iPhone notification (Telegram, WhatsApp, SMS, calls) shows on the laptop and the tablet; the iPhone is a music player in GNOME's top bar (play, pause, next, title and artist; the laptop's media keys and the tablet's Media page control it too); its battery shows in the top bar's Batteries and the widgets. Over Bluetooth, even with the web app closed. Uses the pairing from `./host/iphone-setup.sh`; on the iPhone: Settings › Bluetooth › Fedora Pad › ⓘ › *Share System Notifications*. Fedora. |
| 💻 | **Laptop Screen on the iPhone** | iPhone web app › *Laptop Screen*: the laptop's screen live (about 12 pictures a second over Wi-Fi). Tap = click, hold = right-click, drag = drag, two fingers = scroll, pinch = zoom the picture, ⌨️ = type with the iPhone keyboard. The first time, the laptop asks "Share your screen?": pick the screen and press Share (it remembers). If the page says something is missing, run the `sudo dnf install …` command it shows. Fedora. |
| 📱 | **Control the iPhone from the laptop** | Press **Super+Shift+I**: the laptop's touchpad, mouse and keyboard work on the iPhone (the laptop acts as a Bluetooth keyboard and mouse for it; the iPhone shows a round pointer). **Ctrl+Alt+Esc** gives them back. Watch the iPhone on the laptop with AirPlay (`./host/mirror-setup.sh`, then Control Center › Screen Mirroring). Once: `./host/iphone-setup.sh` (pairs the iPhone, adds the shortcut and *iPhone Control* in Activities) and on the iPhone Settings › Accessibility › Touch › AssistiveTouch: on. Only your paired iPhone gets the keys. The lock screen's passcode and Face ID can't be done this way (Apple's rule). Fedora. |
| 🚶 | **Lock when you walk away** | The laptop locks about a minute after your iPhone leaves (a few steps, the room, or out of reach: you choose). It never unlocks by itself. Pair the iPhone with the laptop in Bluetooth settings once, then `./host/walkaway-setup.sh` (tests the iPhone's signal first). Off: `--off`. Fedora. |
| 🔓 | **Unlock the laptop from the phone** | `./host/unlock-setup.sh` once (choose a PIN). Then Devices › your PC › **Unlock** (the tablet asks for its own fingerprint/PIN first; the iPhone asks for the PIN) or **Lock**. Fedora only; 5 wrong PINs block it for 5 minutes. |
| 📍 | **Remote control each other** | Ring a lost device, send a message, open StandBy / Focus / فال حافظ / Books / Home on it, and play, pause, skip or change the volume of the tablet's music from the iPad. |

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

### 🧹 Make the tablet just Fedora Pad (optional)
Removes the tablet maker's extra apps (on Samsung: Bixby, Samsung Free, Game Launcher, AR Emoji, Tips, preinstalled Facebook / Netflix / OneDrive…) and makes Fedora Pad the home screen. Over the cable, no root, and it can be undone.

1. Plug in the tablet.
2. Run it: Fedora `./host/tablet-clean.sh` · Windows double-click `windows\TabletClean.bat`.
3. It shows the apps it found and asks once (**Enter** = yes). Then it asks about apps you may still want (Samsung Internet, Samsung Notes, YouTube, Gmail…) one by one (**Enter** = keep).

✅ Done when it says *Fedora Pad is now the home screen*, and the Home button opens Fedora Pad.
See the list first: `--list` (Windows: `TabletClean.bat list`). Put everything back: `./host/tablet-clean.sh --undo` (Windows: `TabletClean.bat undo`).
It never touches Google Play, Play Services, keyboards, voices for read aloud, the camera or settings. The list is `host/tablet-clean.txt`.

## 📱 iPhone, iPad and other phones (web app)
Fedora Pad also runs as a web app on any phone or tablet on your home Wi-Fi: Push to Talk, Keyboard (with فا, plus a **phone keyboard** mode that types live in any language), full-screen Trackpad, Media, Controls, Shortcuts, Apps, Windows, Notes, Terminal Pad, Text & Clipboard, Send Files, Presenter, Stats and **Webcam** (the phone's camera and mic as the PC's webcam, Fedora). Encrypted, and only for devices you pair.

1. Turn it on: Fedora `./host/webapp-setup.sh` · Windows double-click `windows\WebApp.bat`.
2. On the iPhone / iPad, open the address it shows in **Safari** (or scan the QR code), and follow the page:
   install the certificate, then **Settings › General › About › Certificate Trust Settings** › turn on *Fedora Pad local CA*.
3. Open the https link, then **Share › Add to Home Screen**.
4. Open Fedora Pad from the Home Screen, tap **Show a code on the PC**, and type the 6-digit code that pops up on the PC.

Off: `./host/webapp-setup.sh --off` · unpair every phone: `./host/webapp-setup.sh --forget` (Windows: `WebApp.bat off` / `WebApp.bat forget`).
**🎙 Push to Talk** works on the phone too: hold the big button and speak, let go and the words are typed on the PC. *iPhone speech* shows the words live (internet; Fedora and Windows); *PC (offline)* sends the phone's mic to Whisper on Fedora (`./host/voice-setup.sh`, better for Persian).
**The phone's own tools** (top of the web app's home, like the tablet's): 📚 **Get Books** (Project Gutenberg classics → Open in Books or send to the PC; 🌹 Persian poetry from Ganjoor with meanings and read aloud; 🇮🇷 Iranian stores), 🎲 **فال حافظ**, 🗒 Notes, ✍️ **Handwriting** (finger or Apple Pencil → PC or share), 📄 **Scanner** (camera → clean black-and-white page → PC), ⏱ **Focus** timer, 🕰 **StandBy** clock with the PC's music, and ✨ Claude. Book and poetry pages are fetched by the PC, so they work wherever the PC has internet. 🔍 Search on the home screen finds any page.

Safari can't do a few things the Android app does: the floating button over other apps, reading books inside the app (use Apple Books), and being the home screen.

## 🪟 Windows version
Booted into Windows? Fedora Pad works there too: keyboard (with فا Persian), trackpad, Media, Controls, Shortcuts, Apps, Stream Deck, Windows switcher, Notes, Terminal Pad, Send Files, Text & Clipboard, Stats and Ambient Light.

1. Download **[the project as a ZIP](https://github.com/aliihjt/Android-as-ubuntu-keyboard/archive/refs/heads/main.zip)** and unzip it.
2. Open the `windows` folder and double-click **`Setup.bat`** (if Windows says *"Windows protected your PC"*, click **More info › Run anyway**). It gets Python and the Android tools (with winget, or straight from python.org and Google when winget is missing or fails), Git if it can, starts Fedora Pad at every log-in, and can add the Persian keyboard. Everything is logged in `%LOCALAPPDATA%\FedoraPad\setup-log.txt`.
3. Plug in the tablet and tap **Allow** on it. The app installs or updates itself and opens.
4. **Screen:** Windows shows its screen on the tablet with **spacedesk** (free for personal use; works on Windows Home): install the *spacedesk Windows driver* on the PC (Setup opens the page) and *spacedesk* on the tablet. Fedora Pad's **Show Screen** button opens it. Connect them over Wi-Fi.

| | On Windows |
|---|---|
| 🔄 Update | double-click `windows\Update.bat` |
| 🎙 Voice typing | Push to Talk with *Laptop mic* uses Windows' own voice typing (Win+H) |
| ⏻ Shut down / Restart / Log out | opens Windows' own "Shut Down Windows" box, so you confirm on the PC |
| 🔊 Volume | exact with pycaw (Setup installs it), otherwise in 2 % steps |
| 📄 Log | `%LOCALAPPDATA%\FedoraPad\fedorapad.log` |

Not on Windows: turning the laptop keyboard off, "Same picture" mode, the GNOME tools (macOS look, Fedora Plus, Tailscale scripts) and now-playing titles (the media buttons still work).
If spacedesk is set to *USB cable* mode it brings its own adb, which can fight with Fedora Pad's; use spacedesk over Wi-Fi.

## 🧰 Fedora extras (optional)
| Script | What it does |
|---|---|
| `./host/macos-look.sh` | Makes Fedora look and feel like a MacBook: Dock, window buttons on the left, macOS theme, icons and cursors, top-left menu like the Apple menu, genie minimise, rounded corners, day/night macOS wallpapers, Inter font, Mac shortcuts (Super = Cmd: Super+Q, Super+Space Spotlight, Super+Shift+3/4/5 screenshots), two-finger right click, Firefox theme, **window snapping** (drag to an edge or corner, Super+arrows) and the blue accent colour of the tablet. `--undo` puts it back. |
| `./host/tune-up.sh` | Safe speed and battery tune-up: faster boot and downloads, cleanup, SSD TRIM, firmware check. |
| `./host/apps-pack.sh` | Installs codecs, VLC, Chrome, Telegram, VS Code, GIMP, OBS, Persian fonts and handy tools. |
| `./host/ai-setup.sh` | Adds an AI assistant on Ctrl+Alt+Space. |
| `./host/unlock-setup.sh` | Choose the PIN that unlocks the laptop from the tablet or iPhone (only a salted hash is kept). `--off` turns it off. |
| `./host/status-setup.sh` | The tablet and iPhone get a notification when a terminal command of 30 s or more finishes (a few lines in `~/.bashrc`). `--off` removes them. |
| `./host/mirror-setup.sh` | Tablet Mirroring (scrcpy) and iPhone Mirroring (AirPlay receiver UxPlay with its plugins), plus launchers in Activities. `--undo` removes them. |
| `./host/wake-setup.sh` | Lets the tablet wake this PC from sleep (wake-on-LAN on Ethernet, wake-on-Wi-Fi when the card supports it). `--off` turns it off. |
| `./host/webcam-setup.sh` | Makes the phone or tablet a webcam and microphone on Fedora: virtual camera driver (with the Secure Boot step if needed) and scrcpy. `--undo` removes it. |
| `./host/voice-setup.sh` | **Voice typing, offline:** press Ctrl+Super+D, speak English or Persian, press again: the words are typed where your cursor is. Also from the tablet (Spotlight › Voice typing). |
| `./host/tailscale-setup.sh` | **Fedora Pad from anywhere:** a private encrypted link over the internet, so the tablet works away from home too. |
| `./host/chrome-safari.sh` | Makes Google Chrome look and feel like Safari: Safari Light / Dark themes (with the macOS wallpaper on new tabs), two-finger swipe for back / forward, and a short list of Safari-like settings. `--undo` puts Chrome back. |
| `./host/handoff-setup.sh` | Chrome Handoff: adds the Fedora Pad Handoff extension to Chrome (shows what to click). |
| `./host/iphone-setup.sh` | Control the iPhone from the laptop (Super+Shift+I): pairs the iPhone as a Bluetooth keyboard + mouse, adds the shortcut. `--off` removes it. |
| `./host/walkaway-setup.sh` | Lock the laptop when your iPhone walks away (Bluetooth): pick the iPhone, test its signal, choose the distance. `--off` turns it off. |
| `./host/tablet-clean.sh` | Removes the tablet maker's extra apps and makes Fedora Pad the home screen (see *Make the tablet just Fedora Pad*). `--undo` puts them back. |
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
It also installs **Steam** (Flathub, no password) and the **iiSU** launcher on the plugged-in tablet if they are missing. The tablet app also updates itself every time you plug in. The app shows its **Build** number on its home screen.

## 🛟 Troubleshooting

| Problem | Fix |
|---|---|
| Keyboard or touchpad stuck after unplugging | `~/Android-as-ubuntu-keyboard/host/reset.sh` |
| Anything else | `~/Android-as-ubuntu-keyboard/host/diagnose.sh`, then open an issue with `~/tabletdesk-diagnose.txt` |
| Screen doesn't appear | Unlock Fedora. GNOME only shares an unlocked screen. |
| App doesn't update | Watch the tablet for an install prompt. Xiaomi: turn on **Install via USB**. |
| Text on the tablet is tiny | Fedora Settings › Displays › tablet monitor › **Scale 200%** |
| See what's happening | `journalctl --user -u tabletdesk -f` |
| A game doesn't see the controller | Controller page › **Fedora sees it as: Xbox controller**. Check it with `evtest` or the Steam controller settings. |

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
| `host/hub.py` | The PC as a hub between devices: the device list, messages passed between them, one clipboard for all, and the reading data they share (saved in `shared.json`). |
| `host/webapp.py`, `host/web/` | The phone / iPad web app: HTTPS with a local certificate authority, pairing codes, and a WebSocket carrying the same messages as the Android app. |
| `host/gamepad.py` | Controller page: turns the tablet's controllers into virtual Xbox 360 / DualShock 4 pads (uinput) and sends rumble back. |
| `host/gnome-extension/` | Reports the focused app and the pointer position (GNOME 45–50). |
| `windows/` | Fedora Pad for Windows: the same protocol with SendInput, plus setup and plug-in scripts. |
| `android/` | The app, in plain Java with no extra libraries. |

With the cable, everything listens on `127.0.0.1` only and travels over the USB cable. Wi-Fi mode and the web app listen on the home network, always encrypted and only for paired devices.

### Build the APK yourself
```bash
cd android && ./gradlew assembleDebug    # Android SDK 34 + JDK 17
```
GitHub Actions builds and tests it on every push. Only pushes to `main` publish it to the [newest-build release](https://github.com/aliihjt/Android-as-ubuntu-keyboard/releases/tag/latest-apk), which tablets install automatically. Tests: `python3 -m unittest discover -s tests`.

### Private signing key (optional)
The app is signed with the public `android/debug.keystore`, so anyone could make an app that Android accepts as a Fedora Pad update. To use your own key instead:
1. Make a key: `keytool -genkeypair -keystore fedorapad.jks -alias fedorapad -keyalg RSA -keysize 2048 -validity 10000`
2. GitHub › the repo › Settings › Secrets and variables › Actions › add `FEDORAPAD_KEYSTORE` (the output of `base64 -w0 fedorapad.jks`), `FEDORAPAD_KEYSTORE_PASSWORD` and `FEDORAPAD_KEY_ALIAS` (`fedorapad`).
3. The next build is signed with it. Android won't update over the old key, so **uninstall Fedora Pad on the tablet once** and plug in again (books, notes and settings on the tablet are lost; notes and reading data on the PC stay).

Keep `fedorapad.jks` and its password safe: without them no update can install.
