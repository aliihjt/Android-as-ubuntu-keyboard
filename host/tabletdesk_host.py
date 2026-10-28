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
  {"t":"apps?"} / {"t":"launch","id":"firefox.desktop"}   favourite apps (Dock)
  {"t":"ping"}

Host -> tablet messages:
  {"t":"hello","host":"my-laptop","session":"x11"}
  {"t":"app","name":"Firefox"}             focused application
  {"t":"state","volume":40,"brightness":70,"dark":true,"dnd":false,"touch":true}
  {"t":"apps","list":[{"id":"firefox.desktop","name":"Firefox"}]}
  {"t":"ptr","x":0.4,"y":0.7}              pointer is on the tablet's screen ({"t":"ptr"} = not)
"""

import argparse
import glob
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
        """True = turn the laptop keyboard off when the tablet connects (default)."""
        try:
            with open(LAPTOP_KBD_FILE) as f:
                return f.read().strip() != "keep-on"
        except OSError:
            return True

    @staticmethod
    def save_choice(turn_off):
        os.makedirs(os.path.dirname(LAPTOP_KBD_FILE), exist_ok=True)
        with open(LAPTOP_KBD_FILE, "w") as f:
            f.write("turn-off\n" if turn_off else "keep-on\n")

    def _built_in_keyboards(self):
        for path in list_devices():
            try:
                d = InputDevice(path)
            except OSError:
                continue
            caps = d.capabilities()
            keys = caps.get(e.EV_KEY, [])
            letters = all(k in keys for k in (e.KEY_A, e.KEY_Q, e.KEY_Z, e.KEY_SPACE, e.KEY_ENTER))
            # anything that moves a pointer (touchpad, mouse, touchscreen) is never touched
            pointer = e.EV_ABS in caps or e.EV_REL in caps or e.BTN_LEFT in keys \
                or e.BTN_TOUCH in keys or "touchpad" in d.name.lower()
            if (letters and not pointer
                    and d.info.bustype not in self.EXTERNAL_BUSES
                    and not d.name.startswith("TabletDesk")):
                yield d
            else:
                d.close()

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


def launch_app(app_id):
    # only apps from the user's own favourites can be started
    for app in favourite_apps():
        if app["id"] == app_id:
            try:
                subprocess.Popen(["gio", "launch", app["path"]], stdout=subprocess.DEVNULL,
                                 stderr=subprocess.DEVNULL, start_new_session=True)
            except OSError as err:
                print("launch failed:", app_id, err, file=sys.stderr)
            return


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
    elif t == "clip?" and reply:
        reply({"t": "clip", "s": get_clipboard()})
    elif t == "setclip":
        set_clipboard(str(msg.get("s", "")))
    elif t == "cursor":
        inj.nudge()
    elif t == "apps?" and reply:
        reply({"t": "apps", "list": [{"id": a["id"], "name": a["name"]}
                                     for a in favourite_apps()]})
    elif t == "launch":
        launch_app(str(msg.get("id", "")))


def make_handler(inj, clients):
    class Handler(socketserver.StreamRequestHandler):
        def handle(self):
            self.request.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            clients.add(self.wfile)
            inj.release_all()
            print("tablet connected", flush=True)
            hello = {"t": "hello", "host": socket.gethostname(),
                     "session": os.environ.get("XDG_SESSION_TYPE", "unknown")}
            try:
                self.wfile.write((json.dumps(hello) + "\n").encode())
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

    return Handler


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


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
    # bind to localhost only: the tablet reaches it through `adb reverse`
    with Server(("127.0.0.1", args.port), make_handler(inj, clients)) as srv:
        print(f"TabletDesk host listening on 127.0.0.1:{args.port}", flush=True)
        srv.serve_forever()


if __name__ == "__main__":
    main()
