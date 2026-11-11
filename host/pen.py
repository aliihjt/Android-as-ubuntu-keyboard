"""Pen Tablet: the tablet (or an iPad / iPhone) as a drawing tablet for Fedora.

A virtual pen tablet (like a small Wacom) made with uinput: GIMP, Krita,
Xournal++ and Inkscape see a real pen, with pressure when the device gives it
(Apple Pencil, a stylus) and a steady medium pressure for a finger.

Messages from the device, positions as fractions 0..1 of the laptop screen:
  {"t":"pen","s":"down"|"move"|"up"|"hover"|"out","x":0.5,"y":0.5,"p":0.6,"tool":"pen"|"eraser"}
  {"t":"pen","s":"right"}       a right click (the pen's side button) where the pen is
  {"t":"pen?"}                  -> {"t":"penarea","w":1920,"h":1080}: the laptop screen, for the pad's shape
"""
import sys
import threading

try:
    from evdev import AbsInfo, UInput, ecodes as e
except ImportError:  # --dry-run on machines without python3-evdev
    AbsInfo = UInput = e = None

MAX = 32767          # position range of the virtual tablet
MAX_PRESSURE = 4095


def clamp(v, lo=0.0, hi=1.0):
    try:
        v = float(v)
    except (TypeError, ValueError):
        return lo
    return lo if v != v else max(lo, min(hi, v))  # v != v: NaN


class Pen:
    def __init__(self, dry_run=False, screen=None):
        self.dry_run = dry_run or UInput is None
        self.screen = screen        # () -> {"w","h","x","y","sw","sh"}: the laptop screen inside the desktop
        self.ui = None
        self.tool = None            # "pen" / "eraser" while the pen is near the pad
        self.touching = False
        self.lock = threading.Lock()
        self.log = []               # what a dry run would have written (tests)

    # ---- the virtual device

    def _device(self):
        if self.ui is None and not self.dry_run:
            res = 100  # units per mm: a pad about 33 cm wide, like a large Wacom
            caps = {
                e.EV_KEY: [e.BTN_TOOL_PEN, e.BTN_TOOL_RUBBER, e.BTN_TOUCH, e.BTN_STYLUS],
                e.EV_ABS: [(e.ABS_X, AbsInfo(0, 0, MAX, 0, 0, res)),
                           (e.ABS_Y, AbsInfo(0, 0, MAX, 0, 0, res)),
                           (e.ABS_PRESSURE, AbsInfo(0, 0, MAX_PRESSURE, 0, 0, 0))],
            }
            self.ui = UInput(caps, name="Fedora Pad Pen", vendor=0x1209, product=0x7a1d,
                             input_props=[e.INPUT_PROP_POINTER])
        return self.ui

    def _write(self, events):
        if self.dry_run:
            self.log.append(events)
            return
        ui = self._device()
        for kind, code, value in events:
            ui.write(getattr(e, kind), getattr(e, code), value)
        ui.syn()

    def _position(self, x, y):
        """Pad fractions -> device units. The pad is the laptop screen; when the desktop
        is wider (the tablet as a second screen), the laptop screen is a part of it."""
        s = self.screen() if self.screen else None
        if s and s.get("sw") and s.get("sh"):
            x = (s.get("x", 0) + x * s["w"]) / s["sw"]
            y = (s.get("y", 0) + y * s["h"]) / s["sh"]
        return round(clamp(x) * MAX), round(clamp(y) * MAX)

    # ---- messages

    def handle(self, msg):
        state = msg.get("s")
        tool = "BTN_TOOL_RUBBER" if msg.get("tool") == "eraser" else "BTN_TOOL_PEN"
        x, y = self._position(clamp(msg.get("x")), clamp(msg.get("y")))
        with self.lock:
            try:
                self._handle(state, tool, x, y, clamp(msg.get("p", 0.6)))
            except OSError as err:  # /dev/uinput not allowed: the host's setup does that
                print("pen:", err, file=sys.stderr)

    def _handle(self, state, tool, x, y, pressure):
        ev = []
        if state in ("down", "move", "hover"):
            if self.tool and self.tool != tool:  # pen <-> eraser: lift the old tool first
                self._write([("EV_KEY", "BTN_TOUCH", 0), ("EV_ABS", "ABS_PRESSURE", 0), ("EV_KEY", self.tool, 0)])
                self.tool, self.touching = None, False
            if not self.tool:
                ev.append(("EV_KEY", tool, 1))
                self.tool = tool
            ev += [("EV_ABS", "ABS_X", x), ("EV_ABS", "ABS_Y", y)]
            touching = state != "hover"
            ev.append(("EV_ABS", "ABS_PRESSURE", round(pressure * MAX_PRESSURE) if touching else 0))
            if touching != self.touching:
                ev.append(("EV_KEY", "BTN_TOUCH", 1 if touching else 0))
                self.touching = touching
            self._write(ev)
        elif state in ("up", "out"):
            if self.touching:
                self._write([("EV_ABS", "ABS_PRESSURE", 0), ("EV_KEY", "BTN_TOUCH", 0)])
                self.touching = False
            if state == "out" and self.tool:
                self._write([("EV_KEY", self.tool, 0)])
                self.tool = None
        elif state == "right":
            if not self.tool:
                self._write([("EV_KEY", "BTN_TOOL_PEN", 1), ("EV_ABS", "ABS_X", x), ("EV_ABS", "ABS_Y", y)])
                self.tool = "BTN_TOOL_PEN"
            self._write([("EV_KEY", "BTN_STYLUS", 1)])
            self._write([("EV_KEY", "BTN_STYLUS", 0)])

    def release(self):
        """The device went away mid-stroke: lift the pen so nothing stays drawn."""
        with self.lock:
            if self.touching or self.tool:
                self._handle("out", self.tool or "BTN_TOOL_PEN", 0, 0, 0)
