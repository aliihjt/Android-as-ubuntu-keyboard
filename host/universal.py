"""Universal Control (Fedora): the laptop's mouse, touchpad and keyboard move on to the tablet.

Push the pointer past the edge of the laptop screen on the tablet's side (right
by default) and Fedora Pad takes the laptop's mouse, touchpad and keyboard
("grabs" them, so the desktop stops seeing them) and sends what they do to the
tablet. There Fedora Pad's accessibility helper (UniversalService.java) moves a
pointer, taps, drags, scrolls and types. Moving off the tablet's other edge, or
Ctrl+Alt+Esc, gives them back. It travels over Fedora Pad's own link, so it
works over the cable and over Wi-Fi.

Safety: the kernel drops the grab if this program stops; the tablet must answer
within 2 s and say it's alive every second, or the laptop gets its devices back;
and it never starts while the tablet is a second screen (the pointer already
goes there).

Host <-> tablet:
  tablet -> {"t":"ucready","on":true}             the helper is on (or off)
  host   -> {"t":"uc","a":"enter","y":0.4,"side":"right"}
  host   -> {"t":"uc","a":"move","dx":3,"dy":-1}   laptop pixels
  host   -> {"t":"uc","a":"button","b":"left","v":1}  1 down, 0 up
  host   -> {"t":"uc","a":"scroll","dx":0,"dy":40}    + = the page moves down (fingers down)
  host   -> {"t":"uc","a":"key","k":"KEYCODE_A","v":1}  Android key names; v 1 down, 0 up, 2 repeat
  host   -> {"t":"uc","a":"leave"}
  tablet -> {"t":"uc","a":"entered"} / {"t":"uc","a":"alive"} / {"t":"uc","a":"exit"}
  device -> {"t":"ucset","on":true,"side":"right"}  /  {"t":"uc?"} -> {"t":"ucstate",...}
"""
import json
import os
import select
import sys
import threading
import time

try:
    from evdev import InputDevice, ecodes as e, list_devices
except ImportError:  # --dry-run on machines without python3-evdev
    InputDevice = e = list_devices = None

PUSH_MOUSE = 40       # counts pushed past the edge before the pointer crosses (no accidental jumps)
PUSH_TOUCHPAD = 4.0   # mm
TOUCHPAD_PX_PER_MM = 11.0
WHEEL_PX = 90         # one mouse-wheel notch
TAP_MS, TAP_MM = 200, 1.5
ACK_S, ALIVE_S = 2.0, 3.0

# evdev key names -> Android KeyEvent names (the rest map by name: KEY_A -> KEYCODE_A)
KEYS = {
    "KEY_BACKSPACE": "KEYCODE_DEL", "KEY_DELETE": "KEYCODE_FORWARD_DEL", "KEY_ESC": "KEYCODE_ESCAPE",
    "KEY_LEFTSHIFT": "KEYCODE_SHIFT_LEFT", "KEY_RIGHTSHIFT": "KEYCODE_SHIFT_RIGHT",
    "KEY_LEFTCTRL": "KEYCODE_CTRL_LEFT", "KEY_RIGHTCTRL": "KEYCODE_CTRL_RIGHT",
    "KEY_LEFTALT": "KEYCODE_ALT_LEFT", "KEY_RIGHTALT": "KEYCODE_ALT_RIGHT",
    "KEY_LEFTMETA": "KEYCODE_META_LEFT", "KEY_RIGHTMETA": "KEYCODE_META_RIGHT",
    "KEY_UP": "KEYCODE_DPAD_UP", "KEY_DOWN": "KEYCODE_DPAD_DOWN",
    "KEY_LEFT": "KEYCODE_DPAD_LEFT", "KEY_RIGHT": "KEYCODE_DPAD_RIGHT",
    "KEY_EQUAL": "KEYCODE_EQUALS", "KEY_LEFTBRACE": "KEYCODE_LEFT_BRACKET",
    "KEY_RIGHTBRACE": "KEYCODE_RIGHT_BRACKET", "KEY_DOT": "KEYCODE_PERIOD",
    "KEY_CAPSLOCK": "KEYCODE_CAPS_LOCK", "KEY_HOME": "KEYCODE_MOVE_HOME", "KEY_END": "KEYCODE_MOVE_END",
    "KEY_PAGEUP": "KEYCODE_PAGE_UP", "KEY_PAGEDOWN": "KEYCODE_PAGE_DOWN",
    "KEY_VOLUMEUP": "KEYCODE_VOLUME_UP", "KEY_VOLUMEDOWN": "KEYCODE_VOLUME_DOWN", "KEY_MUTE": "KEYCODE_VOLUME_MUTE",
    "KEY_PLAYPAUSE": "KEYCODE_MEDIA_PLAY_PAUSE", "KEY_NEXTSONG": "KEYCODE_MEDIA_NEXT",
    "KEY_PREVIOUSSONG": "KEYCODE_MEDIA_PREVIOUS", "KEY_KPENTER": "KEYCODE_NUMPAD_ENTER",
    "KEY_BRIGHTNESSUP": "KEYCODE_BRIGHTNESS_UP", "KEY_BRIGHTNESSDOWN": "KEYCODE_BRIGHTNESS_DOWN",
}
SAME = {"A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U",
        "V", "W", "X", "Y", "Z", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "ENTER", "TAB", "SPACE",
        "MINUS", "SEMICOLON", "APOSTROPHE", "GRAVE", "BACKSLASH", "COMMA", "SLASH", "INSERT",
        "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12"}


def android_key(name):
    """'KEY_A' -> 'KEYCODE_A', 'KEY_BACKSPACE' -> 'KEYCODE_DEL'; None for keys the tablet has no use for."""
    if name in KEYS:
        return KEYS[name]
    short = name[4:] if name.startswith("KEY_") else ""
    return "KEYCODE_" + short if short in SAME else None


class Touchpad:
    """A laptop touchpad's raw events -> pointer moves, taps, clicks and two-finger scrolling,
    like the desktop does (natural scrolling: the page follows the fingers)."""

    def __init__(self, out, res_x=12, res_y=12, speed=1.0):
        self.out, self.res_x, self.res_y, self.speed = out, max(1, res_x), max(1, res_y), speed
        self.x = self.y = None
        self.fingers = 0
        self.touch_at = 0.0
        self.travel = 0.0
        self.max_fingers = 0
        self.dx = self.dy = 0.0
        self.button = None
        self.push = 0.0   # mm moved towards the edge, before Universal Control starts

    def event(self, kind, code, value, now):
        if kind == "EV_ABS" and code in ("ABS_X", "ABS_Y"):
            axis = "x" if code == "ABS_X" else "y"
            old = getattr(self, axis)
            if old is not None and self.fingers:
                d = (value - old) / (self.res_x if axis == "x" else self.res_y)
                if axis == "x":
                    self.dx += d
                else:
                    self.dy += d
                self.travel += abs(d)
            setattr(self, axis, value)
        elif kind == "EV_KEY":
            counts = {"BTN_TOOL_FINGER": 1, "BTN_TOOL_DOUBLETAP": 2, "BTN_TOOL_TRIPLETAP": 3, "BTN_TOOL_QUADTAP": 4}
            if code in counts:
                if value:
                    self.fingers = counts[code]
                    self.max_fingers = max(self.max_fingers, self.fingers)
                    self.x = self.y = None  # a new finger: no jump
                elif self.fingers == counts[code]:
                    self.fingers = 0
            elif code == "BTN_TOUCH":
                if value:
                    self.touch_at, self.travel, self.max_fingers = now, 0.0, max(1, self.fingers)
                else:
                    if now - self.touch_at < TAP_MS / 1000 and self.travel < TAP_MM and self.button is None:
                        b = "right" if self.max_fingers == 2 else "left"
                        self.out({"a": "button", "b": b, "v": 1})
                        self.out({"a": "button", "b": b, "v": 0})
                    self.x = self.y = None
                    self.max_fingers = 0
            elif code == "BTN_LEFT":  # pressing the pad: two fingers on it = right click
                if value:
                    self.button = "right" if self.fingers == 2 else "left"
                    self.out({"a": "button", "b": self.button, "v": 1})
                elif self.button:
                    self.out({"a": "button", "b": self.button, "v": 0})
                    self.button = None
        elif kind == "EV_SYN" and code == "SYN_REPORT":
            self.flush()

    def flush(self):
        dx, dy = self.dx, self.dy
        self.dx = self.dy = 0.0
        if not dx and not dy:
            return
        self.push += dx
        if self.fingers >= 2 and self.button is None:
            self.out({"a": "scroll", "dx": round(dx * TOUCHPAD_PX_PER_MM), "dy": round(dy * TOUCHPAD_PX_PER_MM)})
        elif self.fingers == 1 or self.button:
            k = TOUCHPAD_PX_PER_MM * self.speed
            self.out({"a": "move", "dx": round(dx * k, 1), "dy": round(dy * k, 1)})


class Mouse:
    """A mouse's raw events -> pointer moves, buttons and wheel scrolling."""

    BUTTONS = {"BTN_LEFT": "left", "BTN_RIGHT": "right", "BTN_MIDDLE": "middle"}

    def __init__(self, out, speed=1.0):
        self.out, self.speed = out, speed
        self.dx = self.dy = 0
        self.push = 0

    def event(self, kind, code, value, now):
        if kind == "EV_REL":
            if code == "REL_X":
                self.dx += value
            elif code == "REL_Y":
                self.dy += value
            elif code == "REL_WHEEL":   # +1 = wheel up = the page moves down
                self.out({"a": "scroll", "dx": 0, "dy": value * WHEEL_PX})
            elif code == "REL_HWHEEL":
                self.out({"a": "scroll", "dx": -value * WHEEL_PX, "dy": 0})
        elif kind == "EV_KEY" and code in self.BUTTONS and value in (0, 1):
            self.out({"a": "button", "b": self.BUTTONS[code], "v": value})
        elif kind == "EV_SYN" and code == "SYN_REPORT" and (self.dx or self.dy):
            self.push += self.dx
            self.out({"a": "move", "dx": round(self.dx * self.speed, 1), "dy": round(self.dy * self.speed, 1)})
            self.dx = self.dy = 0


class Universal:
    def __init__(self, screens, conf_dir, dry_run=False):
        self.screens = screens          # (max_age) -> the GNOME extension's Screens() or None
        self.file = os.path.join(conf_dir, "universal.json")
        self.dry_run = dry_run or InputDevice is None
        self.conf = self._load()
        self.target = None              # reply function of the tablet whose helper is on
        self.active = False
        self.acked = False
        self.alive_at = 0.0
        self.enter_at = 0.0
        self.at_edge = False
        self.edge_y = 0.5
        self.lock = threading.RLock()
        self.devices = {}               # fd -> (InputDevice, converter or "keyboard")
        self.grabbed = []
        self.mods = set()

    # ---- settings

    def _load(self):
        try:
            with open(self.file, encoding="utf-8") as f:
                c = json.load(f)
            if isinstance(c, dict):
                return {"on": bool(c.get("on", True)), "side": "left" if c.get("side") == "left" else "right",
                        "speed": float(c.get("speed") or 1.0)}
        except (OSError, ValueError, TypeError):
            pass
        return {"on": True, "side": "right", "speed": 1.0}

    def _save(self):
        try:
            os.makedirs(os.path.dirname(self.file), exist_ok=True)
            with open(self.file, "w", encoding="utf-8") as f:
                json.dump(self.conf, f)
        except OSError:
            pass

    def state(self):
        return {"t": "ucstate", "on": self.conf["on"], "side": self.conf["side"], "ready": self.target is not None,
                "active": self.active}

    # ---- messages from the tablet

    def handle(self, msg, reply):
        """True when the message was for Universal Control."""
        t = msg.get("t")
        if t == "ucready":
            with self.lock:
                if msg.get("on"):
                    self.target = reply
                elif self.target is reply:
                    self.target = None
                    self.leave(tell=False)
            return True
        if t == "ucset":
            if "on" in msg:
                self.conf["on"] = bool(msg.get("on"))
            if msg.get("side") in ("left", "right"):
                self.conf["side"] = msg["side"]
            self._save()
            if not self.conf["on"]:
                self.leave()
            reply(self.state())
            return True
        if t == "uc?":
            reply(self.state())
            return True
        if t == "uc" and reply is self.target:
            a = msg.get("a")
            if a == "entered":
                self.acked, self.alive_at = True, time.monotonic()
            elif a == "alive":
                self.alive_at = time.monotonic()
            elif a == "exit":
                self.leave(tell=False)
            return True
        return t in ("uc",)

    def gone(self, reply):
        """A device disconnected."""
        with self.lock:
            if reply is self.target:
                self.target = None
                self.leave(tell=False)

    # ---- crossing over

    def _send(self, msg):
        target = self.target
        if not target:
            return
        try:
            target(dict(msg, t="uc"))
        except Exception:  # noqa: BLE001 - the tablet went away
            self.leave(tell=False)

    def enter(self):
        with self.lock:
            if self.active or not self.target or not self.conf["on"]:
                return
            self._grab()
            self.active, self.acked = True, False
            self.enter_at = self.alive_at = time.monotonic()
            self.mods.clear()
            print("Universal Control: on the tablet", flush=True)
        self._send({"a": "enter", "y": round(self.edge_y, 4), "side": self.conf["side"]})

    def leave(self, tell=True):
        with self.lock:
            if not self.active:
                return
            self.active = False
            self._ungrab()
            for conv in self.devices.values():
                if hasattr(conv[1], "push"):
                    conv[1].push = 0
            print("Universal Control: back on the laptop", flush=True)
        if tell:
            self._send({"a": "leave"})

    def check_alive(self, now):
        """Give the devices back if the tablet doesn't answer (its helper stopped)."""
        if self.active and ((not self.acked and now - self.enter_at > ACK_S)
                            or (self.acked and now - self.alive_at > ALIVE_S)):
            print("Universal Control: the tablet stopped answering", file=sys.stderr)
            self.leave(tell=False)

    def edge(self, sc):
        """From Screens(): is the pointer at the tablet's edge of the laptop screen?
        Never while the tablet is a second screen (more than one monitor)."""
        if not sc or len(sc.get("monitors") or []) != 1:
            return False
        x, y, w, h = sc.get("x", 0), sc.get("y", 0), sc.get("w", 0), sc.get("h", 0)
        px, py = sc.get("px", -1), sc.get("py", -1)
        if not w or not h:
            return False
        self.edge_y = min(1.0, max(0.0, (py - y) / h))
        return px >= x + w - 2 if self.conf["side"] == "right" else px <= x + 1

    def pushed(self, conv):
        """Moved far enough past the edge, towards the tablet?"""
        limit = PUSH_TOUCHPAD if isinstance(conv, Touchpad) else PUSH_MOUSE
        push = conv.push if self.conf["side"] == "right" else -conv.push
        return push >= limit

    # ---- input devices

    def _out(self, msg):
        if self.active:
            self._send(msg)

    def _key(self, name, value):
        if name in ("KEY_LEFTCTRL", "KEY_RIGHTCTRL", "KEY_LEFTALT", "KEY_RIGHTALT"):
            (self.mods.add if value else self.mods.discard)(name[-4:] if "CTRL" in name else name[-3:])
        if name == "KEY_ESC" and value == 1 and {"CTRL", "ALT"} <= self.mods:
            self.leave()  # Ctrl+Alt+Esc: always back to the laptop
            return
        code = android_key(name)
        if code:
            self._out({"a": "key", "k": code, "v": value})

    def _scan(self):
        """Opens the laptop's keyboards, mice and touchpads (not Fedora Pad's own virtual ones)."""
        if self.dry_run:
            return
        known = {d.path for d, _ in self.devices.values()}
        for path in list_devices():
            if path in known:
                continue
            try:
                d = InputDevice(path)
            except OSError:
                continue
            caps = d.capabilities()
            keys = caps.get(e.EV_KEY, [])
            abs_codes = [a[0] if isinstance(a, tuple) else a for a in caps.get(e.EV_ABS, [])]
            name = d.name or ""
            speed = self.conf.get("speed", 1.0)
            conv = None
            if name.startswith(("TabletDesk", "Fedora Pad")):
                conv = None
            elif e.BTN_TOOL_FINGER in keys and e.ABS_X in abs_codes and e.INPUT_PROP_DIRECT not in d.input_props():
                info = dict(caps.get(e.EV_ABS, []))
                conv = Touchpad(self._out, info[e.ABS_X].resolution or 12, info[e.ABS_Y].resolution or 12, speed)
            elif e.EV_REL in caps and e.BTN_LEFT in keys:
                conv = Mouse(self._out, speed)
            elif all(k in keys for k in (e.KEY_A, e.KEY_Z, e.KEY_SPACE, e.KEY_ENTER)):
                conv = "keyboard"
            if conv is None:
                d.close()
            else:
                self.devices[d.fd] = (d, conv)

    def _grab(self):
        self.grabbed = []
        for d, conv in list(self.devices.values()):
            if conv == "keyboard":  # no key held while grabbing, or the desktop thinks it stays held
                for _ in range(10):
                    if not d.active_keys():
                        break
                    time.sleep(0.05)
            try:
                d.grab()
                self.grabbed.append(d)
            except OSError:  # already taken (e.g. Settings › Laptop keyboard: off)
                pass

    def _ungrab(self):
        for d in self.grabbed:
            try:
                d.ungrab()
            except OSError:
                pass
        self.grabbed = []

    def _read(self, fd):
        d, conv = self.devices[fd]
        try:
            events = list(d.read())
        except OSError:  # unplugged
            if d in self.grabbed:
                self.grabbed.remove(d)
            del self.devices[fd]
            return
        now = time.monotonic()
        for ev in events:
            kind = e.EV.get(ev.type, "")
            code = e.bytype.get(ev.type, {}).get(ev.code, "")
            if isinstance(code, list):
                code = code[0]
            if conv == "keyboard":
                if kind == "EV_KEY" and self.active:
                    self._key(code, ev.value)
                continue
            conv.event(kind, code, ev.value, now)
            if kind == "EV_SYN" and not self.active:
                if self.at_edge and self.pushed(conv):
                    self.enter()
                elif not self.at_edge:
                    conv.push = 0

    def start(self):
        if self.dry_run:
            return
        threading.Thread(target=self._edge_loop, daemon=True).start()
        threading.Thread(target=self._input_loop, daemon=True).start()

    def _edge_loop(self):
        while True:
            if self.conf["on"] and self.target and not self.active:
                self.at_edge = self.edge(self.screens(0.04))
                time.sleep(0.05)
            else:
                self.at_edge = False
                self.check_alive(time.monotonic())
                time.sleep(0.25)

    def _input_loop(self):
        scanned = 0.0
        while True:
            if not (self.conf["on"] and self.target):
                if self.devices and not self.active:
                    for d, _ in self.devices.values():
                        d.close()
                    self.devices = {}
                time.sleep(1)
                continue
            if time.monotonic() - scanned > 5:  # new mouse or keyboard plugged in
                self._scan()
                scanned = time.monotonic()
            fds = list(self.devices)
            if not fds:
                time.sleep(1)
                continue
            try:
                ready, _, _ = select.select(fds, [], [], 0.5)
            except (OSError, ValueError):
                self.devices = {fd: v for fd, v in self.devices.items() if fd >= 0}
                continue
            for fd in ready:
                if fd in self.devices:
                    with self.lock:
                        self._read(fd)
