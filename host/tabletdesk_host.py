#!/usr/bin/env python3
"""TabletDesk host daemon for Linux (Fedora / Ubuntu, GNOME).

Listens on 127.0.0.1 (reached from the tablet through `adb reverse`) and turns
JSON-line messages from the Android app into real keyboard / mouse events via
/dev/uinput, so it works on both Xorg and Wayland sessions.

Tablet -> host messages (one JSON object per line):
  {"t":"move","dx":3.5,"dy":-1}            relative pointer motion
  {"t":"btn","b":"left|right|middle","s":"click|down|up"}
  {"t":"scroll","dx":0,"dy":12}            pixels; converted to wheel notches
  {"t":"combo","keys":"ctrl+shift+z"}      press keys together, then release
  {"t":"key","k":"ctrl","s":"down|up"}     hold / release a single key
  {"t":"text","s":"Hello"}                 type text (US layout, unicode fallback)
  {"t":"cmd","name":"settings"}            run a whitelisted desktop command
  {"t":"mode","m":"extend|mirror|input"}   remember the screen mode
  {"t":"set","what":"volume","value":40}   volume/brightness 0-100, dark/dnd/laptopkbd 0|1
  {"t":"state?"}                           ask for the values above
  {"t":"cursor"}                           make a hidden mouse pointer visible again
  {"t":"clip?"} / {"t":"setclip","s":"…"}  read / set the PC clipboard
  {"t":"media?"} / {"t":"mediactl","a":"next"}  what's playing / play-pause, next, previous
  {"t":"stats?"}                           CPU, RAM, temperature, disk, network, battery
  {"t":"allapps?"} / {"t":"url","u":"https://…"}  Stream Deck: installed apps / open a website
  {"t":"windows?"} / {"t":"winfocus"|"winclose"|"wintablet"|"winlaptop","id":"123"}  windows
  {"t":"notes?"} / {"t":"setnotes","list":[…]}  sticky notes
  {"t":"termlist?"} / {"t":"termrun","name":"…"} / {"t":"termstop"}  Terminal Pad
  {"t":"ambient?"}                         screen colours for ambient light
  {"t":"lang","id":"fa"|"en"}              keyboard language for the Persian keys
  {"t":"webcam","on":true,"src":"android|web","facing":"front|back","mic":true} / {"t":"webcam?"}
  {"t":"camframe","jpg":"<base64>"} / {"t":"micpcm","pcm":"<base64 s16>","rate":48000}  web app webcam
  {"t":"pttstart","rate":48000} / {"t":"pttpcm","pcm":"<base64 s16>"} / {"t":"pttstop"}  phone Push to Talk
  {"t":"file","id":"…","name":"a.jpg","data":"<base64>","last":true}  file -> ~/Downloads/Fedora Pad
  {"t":"apps?"} / {"t":"launch","id":"firefox.desktop"}   favourite apps (Dock)
  {"t":"ping"}
The same messages also arrive from the phone / iPad web app (host/webapp.py).

Host -> tablet messages:
  {"t":"hello","host":"my-laptop","session":"x11"}
  {"t":"app","name":"Firefox"}             focused application
  {"t":"state","volume":40,"brightness":70,"dark":true,"dnd":false,"touch":true}
  {"t":"apps","list":[{"id":"firefox.desktop","name":"Firefox"}]}
  {"t":"ptr","x":0.4,"y":0.7}              pointer is on the tablet's screen ({"t":"ptr"} = not)
"""

import argparse
import hashlib
import hmac
import secrets
import ssl
import base64
import glob
import ast
import json
import os
import shutil
import socket
import socketserver
import subprocess
import sys
import threading
import time

try:  # PyGObject (installed on Fedora Workstation): fast D-Bus calls
    from gi.repository import Gio, GLib
except ImportError:
    Gio = GLib = None
try:
    from evdev import InputDevice, UInput, ecodes as e, list_devices
except ImportError:  # allows --dry-run on machines without python3-evdev
    InputDevice = UInput = e = list_devices = None

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import webcam as webcam_module  # noqa: E402

webcam = webcam_module.Webcam()

# Whitelisted commands the tablet's menu bar may trigger. Nothing else runs.
# A list of lists means "first one that is installed".
COMMANDS = {
    "settings": ["gnome-control-center"],
    "files": ["nautilus"],
    "terminal": [["ptyxis", "--new-window"], ["gnome-terminal"], ["konsole"]],
    "monitor": [["gnome-system-monitor"], ["flatpak", "run", "org.gnome.SystemMonitor"]],
    "about": [["gnome-control-center", "system"], ["gnome-control-center", "info-overview"]],
    "lock": ["loginctl", "lock-session"],
    "suspend": ["systemctl", "suspend"],
    "logout": ["gnome-session-quit", "--logout"],
    "poweroff": ["gnome-session-quit", "--power-off"],
    "restart": ["gnome-session-quit", "--reboot"],
    # AI assistant (Newelle / Alpaca / Claude on the web), see host/ai-setup.sh
    "ai": [os.path.join(os.path.dirname(os.path.abspath(__file__)), "ai-open.sh")],
    # sticky notes window on the Fedora desktop
    "notes": [sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "sticky_notes.py")],
    # offline voice typing (start / stop), see host/voice-setup.sh
    "voice": [os.path.expanduser("~/.local/share/fedora-pad/voice/venv/bin/python"),
              os.path.join(os.path.dirname(os.path.abspath(__file__)), "voice-type.py")],
    # Push to Talk with the laptop mic: hold = start, let go = stop
    "voicestart": [os.path.expanduser("~/.local/share/fedora-pad/voice/venv/bin/python"),
                   os.path.join(os.path.dirname(os.path.abspath(__file__)), "voice-type.py"), "start"],
    "voicestop": [os.path.expanduser("~/.local/share/fedora-pad/voice/venv/bin/python"),
                  os.path.join(os.path.dirname(os.path.abspath(__file__)), "voice-type.py"), "stop"],
    # Terminal Pad: edit the list of commands
    "editcommands": ["xdg-open", os.path.expanduser("~/.config/tabletdesk/commands.json")],
}

KEY_ALIASES = {
    "ctrl": "KEY_LEFTCTRL", "control": "KEY_LEFTCTRL",
    "shift": "KEY_LEFTSHIFT", "alt": "KEY_LEFTALT", "option": "KEY_LEFTALT",
    "super": "KEY_LEFTMETA", "meta": "KEY_LEFTMETA", "win": "KEY_LEFTMETA",
    "cmd": "KEY_LEFTCTRL",  # macOS Cmd shortcuts map to Ctrl on Linux
    "altgr": "KEY_RIGHTALT",
    "esc": "KEY_ESC", "escape": "KEY_ESC", "enter": "KEY_ENTER", "return": "KEY_ENTER",
    "backspace": "KEY_BACKSPACE", "bksp": "KEY_BACKSPACE", "del": "KEY_DELETE",
    "delete": "KEY_DELETE", "ins": "KEY_INSERT", "insert": "KEY_INSERT",
    "tab": "KEY_TAB", "space": "KEY_SPACE",
    "pgup": "KEY_PAGEUP", "pageup": "KEY_PAGEUP",
    "pgdn": "KEY_PAGEDOWN", "pagedown": "KEY_PAGEDOWN",
    "print": "KEY_SYSRQ", "printscreen": "KEY_SYSRQ",
    "plus": "KEY_EQUAL", "equal": "KEY_EQUAL", "minus": "KEY_MINUS",
    "volup": "KEY_VOLUMEUP", "voldown": "KEY_VOLUMEDOWN",
    "mute": "KEY_MUTE", "playpause": "KEY_PLAYPAUSE",
    "next": "KEY_NEXTSONG", "prev": "KEY_PREVIOUSSONG",
    "brightup": "KEY_BRIGHTNESSUP", "brightdown": "KEY_BRIGHTNESSDOWN",
}

# US keyboard layout: character -> (key name, needs shift)
_US_UNSHIFTED = "`1234567890-=qwertyuiop[]\\asdfghjkl;'zxcvbnm,./"
_US_SHIFTED = "~!@#$%^&*()_+QWERTYUIOP{}|ASDFGHJKL:\"ZXCVBNM<>?"
_US_NAMES = ["GRAVE", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "MINUS",
             "EQUAL", "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P",
             "LEFTBRACE", "RIGHTBRACE", "BACKSLASH", "A", "S", "D", "F", "G",
             "H", "J", "K", "L", "SEMICOLON", "APOSTROPHE", "Z", "X", "C", "V",
             "B", "N", "M", "COMMA", "DOT", "SLASH"]
CHAR_MAP = {" ": ("KEY_SPACE", False), "\n": ("KEY_ENTER", False),
            "\t": ("KEY_TAB", False)}
for _u, _s, _n in zip(_US_UNSHIFTED, _US_SHIFTED, _US_NAMES):
    CHAR_MAP[_u] = ("KEY_" + _n, False)
    CHAR_MAP[_s] = ("KEY_" + _n, True)


def key_name(name):
    """Turn 'ctrl', 'f5', 'a' or 'KEY_A' into an evdev KEY_* name."""
    n = name.strip()
    low = n.lower()
    if low in KEY_ALIASES:
        return KEY_ALIASES[low]
    if n.upper().startswith("KEY_"):
        return n.upper()
    if len(n) == 1 and n in CHAR_MAP:
        return CHAR_MAP[n][0]
    return "KEY_" + n.upper()


class Injector:
    """Writes input events to two uinput devices (keyboard + mouse)."""

    def __init__(self, dry_run=False):
        self.dry_run = dry_run or UInput is None
        self.lock = threading.Lock()
        self.scroll_acc = [0.0, 0.0]
        self.move_acc = [0.0, 0.0]
        self.held_keys = set()     # keys pressed by the tablet and not released yet
        self.held_buttons = set()
        if self.dry_run:
            self.kbd = self.mouse = None
            return
        keys = [v for k, v in e.ecodes.items()
                if k.startswith("KEY_") and isinstance(v, int) and v < 0x2ff]
        self.kbd = UInput({e.EV_KEY: sorted(set(keys))},
                          name="TabletDesk Keyboard")
        self.mouse = UInput({
            e.EV_KEY: [e.BTN_LEFT, e.BTN_RIGHT, e.BTN_MIDDLE],
            e.EV_REL: [e.REL_X, e.REL_Y, e.REL_WHEEL, e.REL_HWHEEL],
        }, name="TabletDesk Trackpad")

    def _code(self, name):
        if self.dry_run:
            return name
        code = e.ecodes.get(name)
        if code is None:
            raise ValueError("unknown key " + name)
        return code

    def _key(self, name, value):
        if value:
            self.held_keys.add(name)
        else:
            self.held_keys.discard(name)
        if self.dry_run:
            print("key", name, value)
            return
        self.kbd.write(e.EV_KEY, self._code(name), value)
        self.kbd.syn()

    def key(self, name, state):
        k = key_name(name)
        with self.lock:
            if state in ("down", "click"):
                self._key(k, 1)
            if state in ("up", "click"):
                self._key(k, 0)

    def combo(self, spec):
        parts = [key_name(p) for p in spec.split("+") if p.strip()]
        with self.lock:
            for k in parts:
                self._key(k, 1)
            for k in reversed(parts):
                self._key(k, 0)

    def text(self, s):
        with self.lock:
            for ch in s:
                if ch in CHAR_MAP:
                    k, shift = CHAR_MAP[ch]
                    if shift:
                        self._key("KEY_LEFTSHIFT", 1)
                    self._key(k, 1)
                    self._key(k, 0)
                    if shift:
                        self._key("KEY_LEFTSHIFT", 0)
                else:
                    self._unicode(ch)

    def _unicode(self, ch):
        # GTK / IBus unicode entry: Ctrl+Shift+U, hex code, Space
        self._key("KEY_LEFTCTRL", 1)
        self._key("KEY_LEFTSHIFT", 1)
        self._key("KEY_U", 1)
        self._key("KEY_U", 0)
        self._key("KEY_LEFTSHIFT", 0)
        self._key("KEY_LEFTCTRL", 0)
        for digit in format(ord(ch), "x"):
            k = "KEY_" + digit.upper()
            self._key(k, 1)
            self._key(k, 0)
        self._key("KEY_SPACE", 1)
        self._key("KEY_SPACE", 0)

    def release_all(self):
        """Let go of everything, so a dropped cable never leaves Ctrl / a click stuck."""
        with self.lock:
            for k in list(self.held_keys):
                self._key(k, 0)
            for b in list(self.held_buttons):
                self.held_buttons.discard(b)
                if self.dry_run:
                    print("btn", b, 0)
                else:
                    self.mouse.write(e.EV_KEY, self._code(b), 0)
                    self.mouse.syn()

    def nudge(self):
        """GNOME hides the pointer after touch input until a mouse moves: wiggle it."""
        with self.lock:
            if self.dry_run:
                print("nudge")
                return
            for d in (1, -1):
                self.mouse.write(e.EV_REL, e.REL_X, d)
                self.mouse.syn()

    def move(self, dx, dy):
        # keep sub-pixel remainders so slow finger motion is not lost
        self.move_acc[0] += dx
        self.move_acc[1] += dy
        ix, iy = int(self.move_acc[0]), int(self.move_acc[1])
        self.move_acc[0] -= ix
        self.move_acc[1] -= iy
        if not ix and not iy:
            return
        with self.lock:
            if self.dry_run:
                print("move", ix, iy)
                return
            if ix:
                self.mouse.write(e.EV_REL, e.REL_X, ix)
            if iy:
                self.mouse.write(e.EV_REL, e.REL_Y, iy)
            self.mouse.syn()

    def button(self, b, state):
        name = {"left": "BTN_LEFT", "right": "BTN_RIGHT",
                "middle": "BTN_MIDDLE"}.get(b, "BTN_LEFT")
        with self.lock:
            for value in ((1, 0) if state == "click" else
                          (1,) if state == "down" else (0,)):
                if value:
                    self.held_buttons.add(name)
                else:
                    self.held_buttons.discard(name)
                if self.dry_run:
                    print("btn", name, value)
                    continue
                self.mouse.write(e.EV_KEY, self._code(name), value)
                self.mouse.syn()

    def scroll(self, dx, dy, step=40.0):
        self.scroll_acc[0] += dx
        self.scroll_acc[1] += dy
        nx = int(self.scroll_acc[0] / step)
        ny = int(self.scroll_acc[1] / step)
        self.scroll_acc[0] -= nx * step
        self.scroll_acc[1] -= ny * step
        if not nx and not ny:
            return
        with self.lock:
            if self.dry_run:
                print("scroll", nx, ny)
                return
            # natural scrolling: finger up -> content up (wheel down)
            if ny:
                self.mouse.write(e.EV_REL, e.REL_WHEEL, -ny)
            if nx:
                self.mouse.write(e.EV_REL, e.REL_HWHEEL, nx)
            self.mouse.syn()


def active_app_name():
    """Focused application's name, via the GNOME extension or xprop (Xorg)."""
    name = gnome_focused_app()
    if name:
        return name
    if os.environ.get("XDG_SESSION_TYPE") == "wayland" \
            or not os.environ.get("DISPLAY") or not shutil.which("xprop"):
        return None
    try:
        out = subprocess.run(["xprop", "-root", "_NET_ACTIVE_WINDOW"],
                             capture_output=True, text=True, timeout=1).stdout
        wid = out.strip().split()[-1]
        if wid in ("0x0", "0"):
            return "Desktop"
        out = subprocess.run(["xprop", "-id", wid, "WM_CLASS"],
                             capture_output=True, text=True, timeout=1).stdout
        # WM_CLASS(STRING) = "instance", "Class"
        names = [p.strip().strip('"') for p in out.split("=", 1)[1].split(",")]
        name = names[-1] if names else None
        return pretty_app(name)
    except (subprocess.SubprocessError, IndexError, OSError):
        return None


def gnome_focused_app():
    """Asks host/gnome-extension (works on Wayland). None if not installed."""
    try:
        out = subprocess.run(
            ["gdbus", "call", "--session", "--dest", "org.gnome.Shell",
             "--object-path", "/io/github/aliihjt/TabletDesk",
             "--method", "io.github.aliihjt.TabletDesk.FocusedApp"],
            capture_output=True, text=True, timeout=1)
    except (subprocess.SubprocessError, OSError):
        return None
    # output looks like: ('Firefox',)
    out = out.stdout.strip()
    if not out.startswith("('"):
        return None
    return out[2:out.rfind("'")] or None


def pretty_app(name):
    if not name:
        return None
    known = {"firefox": "Firefox", "gnome-terminal-server": "Terminal",
             "org.gnome.nautilus": "Files", "code": "VS Code",
             "google-chrome": "Chrome", "chromium": "Chromium",
             "org.gnome.texteditor": "Text Editor", "gedit": "Text Editor",
             "org.gnome.ptyxis": "Terminal", "org.mozilla.firefox": "Firefox",
             "libreoffice": "LibreOffice"}
    low = name.lower()
    for key, pretty in known.items():
        if low.startswith(key):
            return pretty
    return name.split(".")[-1].replace("-", " ").title()


LAPTOP_KBD_FILE = os.path.expanduser("~/.config/tabletdesk/laptop-keyboard")


class LaptopKeyboard:
    """Turns the laptop's built-in keyboard off while a tablet is connected.

    It "grabs" the keyboard device, so its keys stop reaching the desktop; the
    touchpad is left alone. The kernel drops the grab automatically if this
    program stops, so the keyboard can never stay off by accident.
    """
    # USB, Bluetooth and virtual devices are external keyboards: keep those on
    EXTERNAL_BUSES = (0x03, 0x05, 0x06)

    def __init__(self, dry_run=False):
        self.dry_run = dry_run or InputDevice is None
        self.grabbed = []
        self.lock = threading.Lock()

    @staticmethod
    def enabled_by_user():
        """True = turn the laptop keyboard off when the tablet connects.
        Default: keep it on (Fedora Pad › Settings › Laptop keyboard)."""
        try:
            with open(LAPTOP_KBD_FILE) as f:
                return f.read().strip() == "turn-off"
        except OSError:
            return False

    @staticmethod
    def save_choice(turn_off):
        os.makedirs(os.path.dirname(LAPTOP_KBD_FILE), exist_ok=True)
        with open(LAPTOP_KBD_FILE, "w") as f:
            f.write("turn-off\n" if turn_off else "keep-on\n")

    @staticmethod
    def _hardware_of(path):
        """The physical device behind an input node (shared by e.g. a touchpad
        and its built-in number pad)."""
        node = os.path.basename(path)
        return os.path.realpath(f"/sys/class/input/{node}/device/device")

    def _built_in_keyboards(self):
        """Only the laptop's main keyboard. Never anything that moves a pointer,
        and never a "keyboard" that shares hardware with a touchpad (ASUS
        NumberPad, Elan/Synaptics hotkeys): turning that off kills the touchpad."""
        devices = []
        for path in list_devices():
            try:
                devices.append(InputDevice(path))
            except OSError:
                pass
        pointer_hw = set()
        for d in devices:
            caps = d.capabilities()
            keys = caps.get(e.EV_KEY, [])
            if e.EV_ABS in caps or e.EV_REL in caps or e.BTN_LEFT in keys or e.BTN_TOUCH in keys:
                pointer_hw.add(self._hardware_of(d.path))
        candidates = []
        for d in devices:
            caps = d.capabilities()
            keys = caps.get(e.EV_KEY, [])
            name = d.name.lower()
            letters = all(k in keys for k in (e.KEY_A, e.KEY_Q, e.KEY_Z, e.KEY_SPACE, e.KEY_ENTER))
            pointer = (e.EV_ABS in caps or e.EV_REL in caps or e.BTN_LEFT in keys
                       or e.BTN_TOUCH in keys
                       or any(w in name for w in ("touchpad", "numberpad", "numpad", "mouse")))
            if (letters and not pointer
                    and d.info.bustype not in self.EXTERNAL_BUSES
                    and not d.name.startswith("TabletDesk")
                    and self._hardware_of(d.path) not in pointer_hw):
                candidates.append(d)
        # the classic built-in keyboard (i8042 / "AT Translated Set 2") wins if present
        main = [d for d in candidates if d.info.bustype == 0x11]
        chosen = main or candidates
        for d in devices:
            if d not in chosen:
                d.close()
        return chosen

    def turn_off(self):
        with self.lock:
            if self.grabbed:
                return
            if self.dry_run:
                print("laptop keyboard off")
                self.grabbed.append(None)
                return
            for d in self._built_in_keyboards():
                try:
                    # Wait until no key is held (e.g. the Enter that started us).
                    # Grabbing mid-press hides the key-up from GNOME: the key
                    # then counts as held forever, and "disable touchpad while
                    # typing" keeps the touchpad off even after unplugging.
                    for _ in range(50):
                        if not d.active_keys():
                            break
                        time.sleep(0.1)
                    d.grab()
                    self.grabbed.append(d)
                    print("laptop keyboard off:", d.name, flush=True)
                except OSError as err:
                    print("cannot turn off", d.name, err, file=sys.stderr)
                    d.close()

    def turn_on(self):
        with self.lock:
            for d in self.grabbed:
                if d is None:
                    print("laptop keyboard on")
                    continue
                try:
                    d.ungrab()
                except OSError:
                    pass
                d.close()
            self.grabbed = []

    def is_off(self):
        return bool(self.grabbed)


laptop_kbd = None  # set in main()


class Clients:
    """Connected tablets, so focus changes can be pushed to all of them."""

    def __init__(self):
        self.lock = threading.Lock()
        self.files = set()
        self.last_app = None

    def add(self, f):
        with self.lock:
            self.files.add(f)
        if laptop_kbd and laptop_kbd.enabled_by_user():
            laptop_kbd.turn_off()

    def remove(self, f):
        with self.lock:
            self.files.discard(f)
            last = not self.files
        if last and laptop_kbd:  # last tablet gone: give the keyboard back
            laptop_kbd.turn_on()

    def broadcast(self, msg):
        data = (json.dumps(msg) + "\n").encode()
        with self.lock:
            for f in list(self.files):
                try:
                    f.write(data)
                    f.flush()
                except OSError:
                    self.files.discard(f)


def watch_focus(clients, interval=0.7):
    while True:
        name = active_app_name()
        if name and name != clients.last_app:
            clients.last_app = name
            clients.broadcast({"t": "app", "name": name})
        time.sleep(interval)


def follow_pointer(clients, fps=30):
    """Tell the tablet where the mouse pointer is on its screen.

    The RDP app on the tablet doesn't draw the PC's pointer, so without this
    the pointer seems to vanish when the touchpad moves it onto the tablet.
    """
    if Gio is None:
        return
    try:
        bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
    except GLib.Error:
        return
    last = None
    while True:
        if not clients.files:
            last = None
            time.sleep(0.5)
            continue
        try:
            res = bus.call_sync("org.gnome.Shell", "/io/github/aliihjt/TabletDesk",
                                "io.github.aliihjt.TabletDesk", "Pointer", None,
                                GLib.VariantType("(s)"), Gio.DBusCallFlags.NONE, 200, None)
            value = res.unpack()[0]
        except GLib.Error:  # extension not loaded yet (log out/in once)
            time.sleep(3)
            continue
        if value != last:
            last = value
            msg = {"t": "ptr"}
            if value:
                p = json.loads(value)
                msg.update(x=round(p["x"], 4), y=round(p["y"], 4))
            clients.broadcast(msg)
        time.sleep(1 / fps)


def run_command(name):
    argv = COMMANDS.get(name)
    if not argv:
        return
    if isinstance(argv[0], list):
        argv = next((a for a in argv if shutil.which(a[0])), argv[-1])
    try:
        subprocess.Popen(argv, stdout=subprocess.DEVNULL,
                         stderr=subprocess.DEVNULL, start_new_session=True)
    except OSError as err:
        print("command failed:", name, err, file=sys.stderr)


def _run(argv):
    try:
        return subprocess.run(argv, capture_output=True, text=True, timeout=2).stdout
    except (subprocess.SubprocessError, OSError):
        return ""


def set_value(what, value):
    """Control Center switches. Fixed commands only, the value is a clamped int."""
    v = max(0, min(100, int(value)))
    if what == "volume":
        _run(["wpctl", "set-volume", "@DEFAULT_AUDIO_SINK@", f"{v}%"])
        _run(["wpctl", "set-mute", "@DEFAULT_AUDIO_SINK@", "0"])
    elif what == "brightness":
        _run(["brightnessctl", "--quiet", "set", f"{max(v, 1)}%"])
    elif what == "dark":
        _run(["gsettings", "set", "org.gnome.desktop.interface", "color-scheme",
              "prefer-dark" if v else "default"])
    elif what == "laptopkbd":  # 1 = laptop keyboard on, 0 = off while connected
        if laptop_kbd:
            laptop_kbd.save_choice(turn_off=not v)
            if v:
                laptop_kbd.turn_on()
            else:
                laptop_kbd.turn_off()
    elif what == "touch":  # touching the desktop image on the tablet
        _run(["grdctl", "rdp", "disable-view-only" if v else "enable-view-only"])
    elif what == "dnd":
        _run(["gsettings", "set", "org.gnome.desktop.notifications", "show-banners",
              "false" if v else "true"])
    elif what == "nightlight":
        _run(["gsettings", "set", "org.gnome.settings-daemon.plugins.color",
              "night-light-enabled", "true" if v else "false"])
    elif what == "wifi":
        _run(["nmcli", "radio", "wifi", "on" if v else "off"])
    elif what == "bluetooth":
        _run(["bluetoothctl", "power", "on" if v else "off"])
    elif what == "micmute":
        _run(["wpctl", "set-mute", "@DEFAULT_AUDIO_SOURCE@", "1" if v else "0"])
    elif what == "power":  # 0 saver, 1 balanced, 2 performance
        _run(["powerprofilesctl", "set",
              ("power-saver", "balanced", "performance")[min(v, 2)]])
    elif what == "awake":  # keep the PC from going idle / locking
        set_keep_awake(bool(v))


AWAKE_FILE = os.path.expanduser("~/.config/tabletdesk/idle-delay")


def set_keep_awake(on):
    """idle-delay 0 = never blank; the old value is remembered and restored."""
    key = ["org.gnome.desktop.session", "idle-delay"]
    if on:
        old = _run(["gsettings", "get"] + key).split()[-1:] or ["300"]
        if old[0] != "0":
            os.makedirs(os.path.dirname(AWAKE_FILE), exist_ok=True)
            with open(AWAKE_FILE, "w") as f:
                f.write(old[0])
        _run(["gsettings", "set"] + key + ["0"])
    else:
        try:
            with open(AWAKE_FILE) as f:
                old = int(f.read().strip())
        except (OSError, ValueError):
            old = 300
        _run(["gsettings", "set"] + key + [str(old or 300)])


MEDIA_ACTIONS = ("play-pause", "next", "previous")


def media_state():
    """What is playing (Spotify, Firefox, VLC...) via MPRIS / playerctl."""
    if not shutil.which("playerctl"):
        return {"t": "media", "status": "Missing", "title": "Install playerctl",
                "artist": "sudo dnf install playerctl", "player": ""}
    out = _run(["playerctl", "metadata", "--format",
                "{{status}}\t{{title}}\t{{artist}}\t{{playerName}}"]).rstrip("\n")
    parts = out.split("\t")
    if len(parts) < 4:
        return {"t": "media", "status": "Stopped", "title": "Nothing playing",
                "artist": "", "player": ""}
    status, title, artist, player = parts[:4]
    return {"t": "media", "status": status, "title": title or "Unknown title",
            "artist": artist, "player": player}


_cpu_last = [0, 0]      # idle, total
_net_last = [0, 0, 0.0]  # rx, tx, time


def _cpu_percent():
    with open("/proc/stat") as f:
        values = [int(v) for v in f.readline().split()[1:]]
    idle, total = values[3] + values[4], sum(values)
    d_idle, d_total = idle - _cpu_last[0], total - _cpu_last[1]
    _cpu_last[0], _cpu_last[1] = idle, total
    return round(100 * (1 - d_idle / d_total)) if d_total > 0 else 0


def stats():
    """Live PC numbers for the tablet's stats widget (only /proc and /sys reads)."""
    out = {"t": "stats"}
    out.update(pc_info())
    try:
        out["cpu"] = _cpu_percent()
    except (OSError, ValueError, IndexError):
        pass
    temps = []
    for zone in glob.glob("/sys/class/thermal/thermal_zone*/temp"):
        try:
            with open(zone) as f:
                t = int(f.read()) / 1000
            if 0 < t < 120:
                temps.append(t)
        except (OSError, ValueError):
            pass
    if temps:
        out["temp"] = round(max(temps))
    try:
        du = shutil.disk_usage("/")
        out["disk"] = round(du.used * 100 / du.total)
        out["disk_free_gb"] = round(du.free / 1e9)
    except OSError:
        pass
    try:
        rx = tx = 0
        with open("/proc/net/dev") as f:
            for line in f.readlines()[2:]:
                name, data = line.split(":", 1)
                if name.strip() == "lo":
                    continue
                fields = data.split()
                rx += int(fields[0])
                tx += int(fields[8])
        now = time.time()
        if _net_last[2]:
            dt = max(now - _net_last[2], 0.1)
            out["down_kbs"] = round((rx - _net_last[0]) / 1024 / dt)
            out["up_kbs"] = round((tx - _net_last[1]) / 1024 / dt)
        _net_last[:] = [rx, tx, now]
    except (OSError, ValueError, IndexError):
        pass
    try:
        with open("/proc/uptime") as f:
            out["uptime_min"] = int(float(f.read().split()[0]) // 60)
    except (OSError, ValueError):
        pass
    return out


def pc_info():
    """CPU load, memory use and laptop battery for the menu header."""
    info = {}
    try:
        with open("/proc/loadavg") as f:
            load = float(f.read().split()[0])
        info["cpu"] = min(100, round(load / (os.cpu_count() or 1) * 100))
    except (OSError, ValueError):
        pass
    try:
        mem = {}
        with open("/proc/meminfo") as f:
            for line in f:
                k, v = line.split(":", 1)
                mem[k] = int(v.split()[0])
        info["ram"] = round(100 - mem["MemAvailable"] * 100 / mem["MemTotal"])
    except (OSError, ValueError, KeyError):
        pass
    for bat in sorted(glob.glob("/sys/class/power_supply/BAT*")):
        try:
            with open(bat + "/capacity") as f:
                info["battery"] = int(f.read())
            with open(bat + "/status") as f:
                info["charging"] = f.read().strip() in ("Charging", "Full")
        except (OSError, ValueError):
            pass
        break
    return info


def get_clipboard():
    out = _run(["wl-paste", "--no-newline", "--type", "text"]) if os.environ.get(
        "WAYLAND_DISPLAY") else _run(["xclip", "-o", "-selection", "clipboard"])
    return out[:65536]


def set_clipboard(text):
    argv = ["wl-copy"] if os.environ.get("WAYLAND_DISPLAY") else ["xclip", "-selection", "clipboard"]
    try:
        subprocess.run(argv, input=text[:65536], text=True, timeout=2)
    except (subprocess.SubprocessError, OSError):
        pass


def read_state():
    state = {"t": "state"}
    out = _run(["wpctl", "get-volume", "@DEFAULT_AUDIO_SINK@"])  # "Volume: 0.45 [MUTED]"
    try:
        state["volume"] = round(float(out.split()[1]) * 100)
    except (IndexError, ValueError):
        pass
    out = _run(["brightnessctl", "-m"])  # "intel_backlight,backlight,120,50%,240"
    try:
        state["brightness"] = int(out.split(",")[3].rstrip("%\n"))
    except (IndexError, ValueError):
        pass
    state["dark"] = "prefer-dark" in _run(
        ["gsettings", "get", "org.gnome.desktop.interface", "color-scheme"])
    if laptop_kbd:
        state["laptopkbd"] = not laptop_kbd.is_off()
    grd = _run(["grdctl", "status"]).lower()
    if "view-only" in grd:
        state["touch"] = "view-only: no" in grd
    state["dnd"] = _run(["gsettings", "get", "org.gnome.desktop.notifications",
                         "show-banners"]).strip() == "false"
    state["nightlight"] = _run(["gsettings", "get", "org.gnome.settings-daemon.plugins.color",
                                "night-light-enabled"]).strip() == "true"
    state["wifi"] = _run(["nmcli", "radio", "wifi"]).strip() == "enabled"
    state["bluetooth"] = "powered: yes" in _run(["bluetoothctl", "show"]).lower()
    state["micmute"] = "MUTED" in _run(["wpctl", "get-volume", "@DEFAULT_AUDIO_SOURCE@"])
    profile = _run(["powerprofilesctl", "get"]).strip()
    if profile in ("power-saver", "balanced", "performance"):
        state["power"] = ("power-saver", "balanced", "performance").index(profile)
    state["awake"] = _run(["gsettings", "get", "org.gnome.desktop.session",
                           "idle-delay"]).split()[-1:] == ["0"]
    state.update(pc_info())
    return state


APP_DIRS = ["~/.local/share/applications", "~/.local/share/flatpak/exports/share/applications",
            "/var/lib/flatpak/exports/share/applications", "/usr/local/share/applications",
            "/usr/share/applications"]


def favourite_apps():
    """The apps pinned in GNOME's dash, as [{"id", "name", "path"}]."""
    out = _run(["gsettings", "get", "org.gnome.shell", "favorite-apps"])
    ids = [x.strip().strip("'\"") for x in out.strip().strip("[]@as ").split(",") if x.strip()]
    apps = []
    for app_id in ids:
        for d in APP_DIRS:
            path = os.path.join(os.path.expanduser(d), app_id)
            if not os.path.isfile(path):
                continue
            name = app_id.rsplit(".desktop", 1)[0].split(".")[-1].title()
            try:
                with open(path, encoding="utf-8", errors="replace") as f:
                    for line in f:
                        if line.startswith("Name="):
                            name = line[5:].strip()
                            break
            except OSError:
                pass
            apps.append({"id": app_id, "name": name, "path": path})
            break
    return apps


def all_apps():
    """Every installed app with a menu entry, as [{"id", "name", "path"}], by name."""
    apps, seen = [], set()
    for d in APP_DIRS:
        for path in glob.glob(os.path.join(os.path.expanduser(d), "*.desktop")):
            app_id = os.path.basename(path)
            if app_id in seen:
                continue
            name, hidden, is_app = None, False, False
            try:
                with open(path, encoding="utf-8", errors="replace") as f:
                    in_entry = False
                    for line in f:
                        line = line.strip()
                        if line.startswith("["):
                            in_entry = line == "[Desktop Entry]"
                        elif in_entry and line.startswith("Name=") and name is None:
                            name = line[5:]
                        elif in_entry and line in ("NoDisplay=true", "Hidden=true"):
                            hidden = True
                        elif in_entry and line == "Type=Application":
                            is_app = True
            except OSError:
                continue
            seen.add(app_id)
            if name and is_app and not hidden:
                apps.append({"id": app_id, "name": name, "path": path})
    return sorted(apps, key=lambda a: a["name"].lower())


def launch_app(app_id):
    # only real, installed apps (by their .desktop id) can be started
    for app in favourite_apps() + all_apps():
        if app["id"] == app_id:
            try:
                subprocess.Popen(["gio", "launch", app["path"]], stdout=subprocess.DEVNULL,
                                 stderr=subprocess.DEVNULL, start_new_session=True)
            except OSError as err:
                print("launch failed:", app_id, err, file=sys.stderr)
            return


def open_url(url):
    """Stream Deck "website" step: only plain web links."""
    url = str(url).strip()
    if url.startswith(("https://", "http://")) and not any(c.isspace() for c in url):
        subprocess.Popen(["xdg-open", url], stdout=subprocess.DEVNULL,
                         stderr=subprocess.DEVNULL, start_new_session=True)


_bus = []


def shell_call(method, arg=None):
    """Calls the GNOME extension (window list etc.); None if it isn't loaded."""
    if Gio is None:
        return None
    try:
        if not _bus:
            _bus.append(Gio.bus_get_sync(Gio.BusType.SESSION, None))
        params = GLib.Variant("(s)", (arg,)) if arg is not None else None
        out = GLib.VariantType("(s)") if arg is None else None
        res = _bus[0].call_sync("org.gnome.Shell", "/io/github/aliihjt/TabletDesk",
                                "io.github.aliihjt.TabletDesk", method, params, out,
                                Gio.DBusCallFlags.NONE, 1000, None)
        return res.unpack()[0] if arg is None else ""
    except GLib.Error as err:
        print("GNOME extension call failed:", method, err.message, file=sys.stderr)
        return None


NOTES_FILE = os.path.expanduser("~/.config/tabletdesk/notes.json")
NOTES_MD = os.path.expanduser("~/Documents/Fedora Pad Notes.md")


def load_notes():
    try:
        with open(NOTES_FILE) as f:
            notes = json.load(f)
        return notes if isinstance(notes, list) else []
    except (OSError, ValueError):
        return []


def save_notes(notes):
    """Notes from the tablet: kept as JSON for the sticky window, plus a readable copy."""
    clean = []
    for n in notes[:200]:
        if isinstance(n, dict):
            clean.append({"id": str(n.get("id", ""))[:40], "text": str(n.get("text", ""))[:5000],
                          "color": str(n.get("color", "yellow"))[:16]})
    os.makedirs(os.path.dirname(NOTES_FILE), exist_ok=True)
    tmp = NOTES_FILE + ".tmp"
    with open(tmp, "w") as f:
        json.dump(clean, f, ensure_ascii=False, indent=1)
    os.replace(tmp, NOTES_FILE)
    try:
        os.makedirs(os.path.dirname(NOTES_MD), exist_ok=True)
        with open(NOTES_MD, "w") as f:
            f.write("# Fedora Pad Notes\n\n" + "\n\n---\n\n".join(n["text"] for n in clean) + "\n")
    except OSError:
        pass


# Terminal Pad: the commands live on Fedora; the tablet can only run them by name.
COMMANDS_FILE = os.path.expanduser("~/.config/tabletdesk/commands.json")
DEFAULT_COMMANDS = [
    {"name": "System info", "icon": "\U0001F4BB", "cmd": "fastfetch --pipe 2>/dev/null || (uname -a; uptime)"},
    {"name": "Disk space", "icon": "\U0001F4BE", "cmd": "df -h / /home 2>/dev/null"},
    {"name": "Busiest programs", "icon": "\U0001F525", "cmd": "ps -eo comm,%cpu,%mem --sort=-%cpu | head -12"},
    {"name": "Network address", "icon": "\U0001F310", "cmd": "ip -br -4 addr"},
    {"name": "Update apps (Flatpak)", "icon": "\U0001F4E6", "cmd": "flatpak update -y --noninteractive"},
    {"name": "Update system", "icon": "\u2B06\uFE0F",
     "cmd": "pkexec dnf upgrade -y --refresh   # asks for your password on Fedora"},
    {"name": "Update Fedora Pad", "icon": "\U0001F504",
     "cmd": "git -C " + os.path.dirname(os.path.dirname(os.path.abspath(__file__))) + " pull --ff-only"},
]
_term = {"proc": None}


def load_commands():
    if not os.path.exists(COMMANDS_FILE):
        os.makedirs(os.path.dirname(COMMANDS_FILE), exist_ok=True)
        with open(COMMANDS_FILE, "w") as f:
            json.dump(DEFAULT_COMMANDS, f, ensure_ascii=False, indent=2)
    try:
        with open(COMMANDS_FILE) as f:
            cmds = json.load(f)
        return [c for c in cmds if isinstance(c, dict) and c.get("name") and c.get("cmd")]
    except (OSError, ValueError):
        return []


def run_terminal(name, reply):
    """Runs one saved command and streams its output to the tablet."""
    cmd = next((c["cmd"] for c in load_commands() if c["name"] == name), None)
    if cmd is None or (_term["proc"] and _term["proc"].poll() is None):
        reply({"t": "termout", "line": "Another command is still running." if cmd else "Unknown command."})
        reply({"t": "termdone", "code": -1})
        return

    def worker():
        try:
            proc = subprocess.Popen(["bash", "-lc", cmd], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    stdin=subprocess.DEVNULL, text=True, errors="replace",
                                    start_new_session=True)
        except OSError as err:
            reply({"t": "termout", "line": str(err)})
            reply({"t": "termdone", "code": -1})
            return
        _term["proc"] = proc
        for line in proc.stdout:
            reply({"t": "termout", "line": line.rstrip("\n")[:2000]})
        reply({"t": "termdone", "code": proc.wait()})

    threading.Thread(target=worker, daemon=True).start()


def stop_terminal():
    proc = _term["proc"]
    if proc and proc.poll() is None:
        try:
            os.killpg(proc.pid, 15)
        except OSError:
            pass


_ptt = {"pcm": bytearray(), "rate": 48000}
VOICE_PY = os.path.expanduser("~/.local/share/fedora-pad/voice/venv/bin/python")


def phone_ptt(t, msg, reply):
    """The phone's mic while its button is held; on release Whisper (voice-setup.sh)
    turns it into text, typed where the cursor is."""
    if t == "pttstart":
        rate = msg.get("rate", 48000)
        _ptt["rate"] = int(rate) if str(rate).isdigit() and 8000 <= int(rate) <= 96000 else 48000
        _ptt["pcm"] = bytearray()
    elif t == "pttpcm":
        if len(_ptt["pcm"]) < _ptt["rate"] * 2 * 180:  # at most 3 minutes
            try:
                _ptt["pcm"] += base64.b64decode(str(msg.get("pcm", "")))
            except ValueError:
                pass
    elif t == "pttstop" and reply:
        pcm, rate = bytes(_ptt["pcm"]), _ptt["rate"]
        _ptt["pcm"] = bytearray()
        if len(pcm) < rate // 2:  # under a quarter of a second
            reply({"t": "ptt", "state": "error", "error": "Too short: hold the button while you speak."})
            return
        if not os.path.exists(VOICE_PY):
            reply({"t": "ptt", "state": "error", "error": "Run ./host/voice-setup.sh on Fedora once."})
            return

        def work():
            import wave
            path = os.path.expanduser("~/.cache/fedora-pad/phone-ptt.wav")
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with wave.open(path, "wb") as w:
                w.setnchannels(1)
                w.setsampwidth(2)
                w.setframerate(rate)
                w.writeframes(pcm)
            reply({"t": "ptt", "state": "writing"})
            try:  # Whisper can take a while on a slow laptop
                out = subprocess.run([VOICE_PY, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                                             "voice-type.py"), "--file", path],
                                     capture_output=True, text=True, timeout=180).stdout
            except (subprocess.SubprocessError, OSError):
                out = ""
            try:
                os.remove(path)
            except OSError:
                pass
            try:
                result = json.loads(out.strip().splitlines()[-1])
            except (ValueError, IndexError):
                result = {"error": "Whisper didn't answer."}
            if result.get("error"):
                reply({"t": "ptt", "state": "error", "error": result["error"]})
            else:
                reply({"t": "ptt", "state": "done", "text": result.get("text", "")})

        threading.Thread(target=work, daemon=True).start()


def set_keyboard_language(lang):
    """Tablet's Persian keys: switch Fedora's keyboard to Persian ('fa') or back ('en').
    Adds the Persian layout to Fedora the first time."""
    if lang == "fa":
        cur = _run(["gsettings", "get", "org.gnome.desktop.input-sources", "sources"]).strip()
        try:
            items = ast.literal_eval(cur.replace("@a(ss) ", "")) if cur not in ("", "@a(ss) []") else []
        except (ValueError, SyntaxError):
            items = []
        if not any(t == "xkb" and i.split("+")[0] == "ir" for t, i in items):
            items = (items or [("xkb", "us")]) + [("xkb", "ir")]
            _run(["gsettings", "set", "org.gnome.desktop.input-sources", "sources", str(items)])
            time.sleep(0.6)  # let GNOME load the new layout
    shell_call("SetInputSource", lang)


def ambient_colors():
    raw = shell_call("Ambient")
    try:
        colors = json.loads(raw or "[]")
    except ValueError:
        colors = []
    # newer GNOME reports 0..1 instead of 0..255
    if colors and all(v <= 1 for c in colors for v in c):
        colors = [[round(v * 255) for v in c] for c in colors]
    return {"t": "ambient", "colors": colors}


def windows_list():
    raw = shell_call("Windows")
    if raw is None:
        return {"t": "windows", "error": "Log out and back in once so the GNOME extension loads.",
                "list": []}
    try:
        return {"t": "windows", "list": json.loads(raw)}
    except ValueError:
        return {"t": "windows", "list": []}


INBOX = os.path.expanduser("~/Downloads/Fedora Pad")
_transfers = {}  # transfer id -> [final path, open file]


def receive_file(msg, reply):
    """Files sent from the tablet, in base64 chunks, saved to ~/Downloads/Fedora Pad."""
    tid = str(msg.get("id", ""))[:64]
    if not tid:
        return
    if tid not in _transfers:
        name = os.path.basename(str(msg.get("name", "file"))).strip().lstrip(".") or "file"
        os.makedirs(INBOX, exist_ok=True)
        base, ext = os.path.splitext(name)
        path, n = os.path.join(INBOX, name), 1
        while os.path.exists(path):  # never overwrite: "photo (1).jpg"
            path = os.path.join(INBOX, f"{base} ({n}){ext}")
            n += 1
        _transfers[tid] = [path, open(path + ".part", "wb")]
    path, f = _transfers[tid]
    try:
        f.write(base64.b64decode(str(msg.get("data", ""))))
    except (ValueError, OSError) as err:
        print("file chunk failed:", err, file=sys.stderr)
    if msg.get("last"):
        f.close()
        os.replace(path + ".part", path)
        del _transfers[tid]
        if reply:
            reply({"t": "filedone", "id": tid, "name": os.path.basename(path)})
        if shutil.which("notify-send"):
            subprocess.Popen(["notify-send", "-a", "Fedora Pad", "File received",
                              os.path.basename(path) + " is in Downloads › Fedora Pad"])


MODE_FILE = os.path.expanduser("~/.config/tabletdesk/mode")


def save_mode(mode):
    """Remember the screen mode; connect.sh applies it now and on every plug-in."""
    if mode not in ("extend", "mirror", "input"):
        return
    os.makedirs(os.path.dirname(MODE_FILE), exist_ok=True)
    with open(MODE_FILE, "w") as f:
        f.write(mode + "\n")


def handle(inj, msg, reply=None):
    t = msg.get("t")
    if t == "move":
        inj.move(float(msg.get("dx", 0)), float(msg.get("dy", 0)))
    elif t == "btn":
        inj.button(msg.get("b", "left"), msg.get("s", "click"))
    elif t == "scroll":
        inj.scroll(float(msg.get("dx", 0)), float(msg.get("dy", 0)))
    elif t == "combo":
        inj.combo(str(msg.get("keys", "")))
    elif t == "key":
        inj.key(str(msg.get("k", "")), msg.get("s", "click"))
    elif t == "text":
        inj.text(str(msg.get("s", "")))
    elif t == "cmd":
        run_command(str(msg.get("name", "")))
    elif t == "mode":
        save_mode(str(msg.get("m", "")))
    elif t == "set":
        set_value(str(msg.get("what", "")), msg.get("value", 0))
    elif t == "state?" and reply:
        # several helper programs: don't hold up keys and mouse meanwhile
        threading.Thread(target=lambda: reply(read_state()), daemon=True).start()
    elif t == "file":
        receive_file(msg, reply)
    elif t == "media?" and reply:
        reply(media_state())
    elif t == "mediactl":
        if msg.get("a") in MEDIA_ACTIONS:
            _run(["playerctl", msg["a"]])
    elif t == "stats?" and reply:
        reply(stats())
    elif t == "clip?" and reply:
        reply({"t": "clip", "s": get_clipboard()})
    elif t == "setclip":
        set_clipboard(str(msg.get("s", "")))
    elif t == "cursor":
        inj.nudge()
    elif t == "apps?" and reply:
        reply({"t": "apps", "list": [{"id": a["id"], "name": a["name"]}
                                     for a in favourite_apps()]})
    elif t == "allapps?" and reply:
        reply({"t": "allapps", "list": [{"id": a["id"], "name": a["name"]} for a in all_apps()]})
    elif t == "url":
        open_url(msg.get("u", ""))
    elif t == "windows?" and reply:
        reply(windows_list())
    elif t in ("winfocus", "winclose", "wintablet", "winlaptop"):
        wid = str(msg.get("id", ""))
        if wid.isdigit():
            shell_call({"winfocus": "Activate", "winclose": "Close", "wintablet": "MoveToTablet",
                        "winlaptop": "MoveToLaptop"}[t], wid)
    elif t == "notes?" and reply:
        reply({"t": "notes", "list": load_notes()})
    elif t == "setnotes":
        save_notes(msg.get("list", []) if isinstance(msg.get("list"), list) else [])
    elif t == "termlist?" and reply:
        reply({"t": "termlist", "list": load_commands(), "file": COMMANDS_FILE})
    elif t == "termrun" and reply:
        run_terminal(str(msg.get("name", "")), reply)
    elif t == "termstop":
        stop_terminal()
    elif t == "webcam":  # phone / tablet as the PC's webcam and mic
        def go():
            r = webcam.start(str(msg.get("src", "android")), str(msg.get("facing", "front")),
                             bool(msg.get("mic", True))) if msg.get("on") else webcam.stop()
            if reply:
                reply(r)
        threading.Thread(target=go, daemon=True).start()
    elif t == "webcam?" and reply:
        reply(webcam.status())
    elif t == "camframe":
        webcam.frame(str(msg.get("jpg", "")))
    elif t == "micpcm":
        webcam.pcm(str(msg.get("pcm", "")), msg.get("rate", 48000))
    elif t in ("pttstart", "pttpcm", "pttstop"):  # Push to Talk from the phone web app
        phone_ptt(t, msg, reply)
    elif t == "lang" and msg.get("id") in ("fa", "en"):
        threading.Thread(target=set_keyboard_language, args=(msg["id"],), daemon=True).start()
    elif t == "ambient?" and reply:
        reply(ambient_colors())
    elif t == "launch":
        launch_app(str(msg.get("id", "")))


CONF_DIR = os.path.expanduser("~/.config/tabletdesk")
WIFI_FLAG = os.path.join(CONF_DIR, "wifi")
LAN_PORT = 7778


def wifi_enabled():
    return os.path.exists(WIFI_FLAG)


WEB_FLAG = os.path.join(CONF_DIR, "web")


def start_web_app(inj, clients):
    """Fedora Pad for iPhone, iPad and any browser (host/webapp.py), when turned on
    with host/webapp-setup.sh."""
    try:
        import webapp
    except ImportError as err:
        print("web app unavailable:", err, file=sys.stderr)
        return

    def hello():
        return {"t": "hello", "host": socket.gethostname(), "os": "linux",
                "session": os.environ.get("XDG_SESSION_TYPE", "unknown"), "user": os.environ.get("USER", "")}

    def opened(ws):
        clients.add(ws)
        if clients.last_app:
            ws.send({"t": "app", "name": clients.last_app})

    def notify(title, text):
        if shutil.which("notify-send"):
            subprocess.Popen(["notify-send", "-u", "critical", "-a", "Fedora Pad", title, text])

    def closed(ws):
        clients.remove(ws)
        if webcam.source == "web":  # the phone that was the webcam went away
            webcam.stop()

    webapp.WebApp(CONF_DIR, lambda msg, reply: handle(inj, msg, reply), hello, opened, closed,
                  notify, lan_ips).start()


def lan_identity():
    """Secret token + TLS certificate for Wi-Fi mode, created on first use.
    Returns (token, cert path, key path, sha256 fingerprint of the cert)."""
    os.makedirs(CONF_DIR, exist_ok=True)
    token_file = os.path.join(CONF_DIR, "lan-token")
    cert = os.path.join(CONF_DIR, "lan.crt")
    key = os.path.join(CONF_DIR, "lan.key")
    if not os.path.exists(token_file):
        with open(os.open(token_file, os.O_WRONLY | os.O_CREAT, 0o600), "w") as f:
            f.write(secrets.token_urlsafe(32))
    if not (os.path.exists(cert) and os.path.exists(key)):
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "3650",
                        "-subj", "/CN=Fedora Pad", "-keyout", key, "-out", cert],
                       capture_output=True, check=True)
        os.chmod(key, 0o600)
    with open(token_file) as f:
        token = f.read().strip()
    with open(cert) as f:
        der = ssl.PEM_cert_to_DER_cert(f.read())
    return token, cert, key, hashlib.sha256(der).hexdigest()


def lan_ips():
    """This PC's addresses on the home network."""
    out = _run(["ip", "-4", "-o", "addr", "show", "scope", "global"])
    ips = []
    for line in out.splitlines():
        parts = line.split()
        if "inet" in parts:
            ip = parts[parts.index("inet") + 1].split("/")[0]
            if not parts[1].startswith(("docker", "virbr", "veth", "br-")):
                ips.append(ip)
    return ips


def make_handler(inj, clients, lan=False):
    class Handler(socketserver.StreamRequestHandler):
        def handle(self):
            if lan and not self.authorised():
                return
            self.request.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            clients.add(self.wfile)
            inj.release_all()
            print("tablet connected", flush=True)
            hello = {"t": "hello", "host": socket.gethostname(),
                     "session": os.environ.get("XDG_SESSION_TYPE", "unknown"),
                     # for "Show Fedora Screen" over Wi-Fi (Remote Desktop login name)
                     "user": os.environ.get("USER", "")}
            try:
                self.wfile.write((json.dumps(hello) + "\n").encode())
                if not lan and wifi_enabled():
                    # pairing over the trusted cable: the tablet learns where and how to
                    # reach this PC over Wi-Fi, and which certificate to trust
                    token, _, _, fp = lan_identity()
                    self.wfile.write((json.dumps({"t": "pair", "ips": lan_ips(), "port": LAN_PORT,
                                                  "token": token, "fp": fp}) + "\n").encode())
                if clients.last_app:
                    self.wfile.write((json.dumps(
                        {"t": "app", "name": clients.last_app}) + "\n").encode())
                self.wfile.flush()
                def reply(obj):
                    with clients.lock:  # don't interleave with focus broadcasts
                        try:
                            self.wfile.write((json.dumps(obj) + "\n").encode())
                            self.wfile.flush()
                        except (OSError, ValueError):  # tablet already gone
                            pass

                for line in self.rfile:
                    try:
                        handle(inj, json.loads(line), reply)
                    except (ValueError, TypeError) as err:
                        print("bad message:", err, file=sys.stderr)
            except OSError:
                pass
            finally:
                clients.remove(self.wfile)
                inj.release_all()
                print("tablet disconnected", flush=True)

        def authorised(self):
            """Wi-Fi clients must first send the token they got over the cable."""
            try:
                self.request.settimeout(10)
                first = json.loads(self.rfile.readline(4096) or b"{}")
                self.request.settimeout(None)
                token = lan_identity()[0]
                ok = first.get("t") == "auth" and hmac.compare_digest(
                    str(first.get("token", "")).encode(), token.encode())
            except (OSError, ValueError, ssl.SSLError):
                ok = False
            if not ok:
                print("refused a Wi-Fi connection without the right key", file=sys.stderr)
            return ok

    return Handler


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


class LanServer(Server):
    """Wi-Fi mode: the same protocol over TLS, for paired tablets only."""

    def __init__(self, addr, handler, cert, key):
        self.tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        self.tls.minimum_version = ssl.TLSVersion.TLSv1_2
        self.tls.load_cert_chain(cert, key)
        super().__init__(addr, handler)

    def get_request(self):
        sock, addr = self.socket.accept()
        # the handshake happens in the client's own thread on first read
        return self.tls.wrap_socket(sock, server_side=True, do_handshake_on_connect=False), addr


def start_wifi(inj, clients):
    try:
        _, cert, key, _ = lan_identity()
        srv = LanServer(("0.0.0.0", LAN_PORT), make_handler(inj, clients, lan=True), cert, key)
    except (OSError, subprocess.CalledProcessError, ssl.SSLError) as err:
        print("Wi-Fi mode could not start:", err, file=sys.stderr)
        return
    print(f"Wi-Fi mode on, port {LAN_PORT} (encrypted, paired tablets only)", flush=True)
    threading.Thread(target=srv.serve_forever, daemon=True).start()


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--port", type=int, default=7777)
    ap.add_argument("--dry-run", action="store_true",
                    help="print events instead of injecting them")
    args = ap.parse_args()

    try:
        inj = Injector(dry_run=args.dry_run)
    except (OSError, PermissionError) as err:
        sys.exit(f"Cannot open /dev/uinput ({err}). Run host/setup.sh "
                 "and log out/in once.")
    global laptop_kbd
    laptop_kbd = LaptopKeyboard(dry_run=args.dry_run)
    clients = Clients()
    threading.Thread(target=watch_focus, args=(clients,), daemon=True).start()
    threading.Thread(target=follow_pointer, args=(clients,), daemon=True).start()
    if wifi_enabled():
        start_wifi(inj, clients)
    if os.path.exists(WEB_FLAG):
        start_web_app(inj, clients)
    # bind to localhost only: the tablet reaches it through `adb reverse`
    with Server(("127.0.0.1", args.port), make_handler(inj, clients)) as srv:
        print(f"TabletDesk host listening on 127.0.0.1:{args.port}", flush=True)
        srv.serve_forever()


if __name__ == "__main__":
    main()
