"""Pen Tablet (Windows): the tablet or iPhone as a drawing pen for the laptop.

Windows 10 (1809) and 11 can make a pretend pen ("synthetic pointer device"),
so Krita, Photoshop, OneNote, Whiteboard and Paint see a real pen with pressure,
an eraser end and a side button (right click). The same messages as host/pen.py:
  {"t":"pen","s":"down"|"move"|"up"|"hover"|"out","x":0.5,"y":0.5,"p":0.6,"tool":"pen"|"eraser"}
  {"t":"pen","s":"right"}   a right click where the pen is
Positions are fractions 0..1 of the laptop's main screen.
"""
import ctypes
import os
import sys
import threading

WINDOWS = os.name == "nt"

PT_PEN = 3
POINTER_FEEDBACK_DEFAULT = 1
MAX_PRESSURE = 1024

# POINTER_FLAG_*
INRANGE, INCONTACT, FIRSTBUTTON, SECONDBUTTON = 0x2, 0x4, 0x10, 0x20
PRIMARY, CONFIDENCE = 0x2000, 0x4000
DOWN, UPDATE, UP = 0x10000, 0x20000, 0x40000
# PEN_FLAG_* and PEN_MASK_*
BARREL, INVERTED, ERASER = 0x1, 0x2, 0x4
PEN_MASK_PRESSURE = 0x1
# POINTER_BUTTON_CHANGE_TYPE
NONE, FIRST_DOWN, FIRST_UP, SECOND_DOWN, SECOND_UP = 0, 1, 2, 3, 4


class POINT(ctypes.Structure):
    _fields_ = [("x", ctypes.c_int32), ("y", ctypes.c_int32)]


class RECT(ctypes.Structure):
    _fields_ = [("left", ctypes.c_int32), ("top", ctypes.c_int32), ("right", ctypes.c_int32),
                ("bottom", ctypes.c_int32)]


class POINTER_INFO(ctypes.Structure):
    _fields_ = [("pointerType", ctypes.c_uint32), ("pointerId", ctypes.c_uint32), ("frameId", ctypes.c_uint32),
                ("pointerFlags", ctypes.c_uint32), ("sourceDevice", ctypes.c_void_p), ("hwndTarget", ctypes.c_void_p),
                ("ptPixelLocation", POINT), ("ptHimetricLocation", POINT),
                ("ptPixelLocationRaw", POINT), ("ptHimetricLocationRaw", POINT),
                ("dwTime", ctypes.c_uint32), ("historyCount", ctypes.c_uint32), ("InputData", ctypes.c_int32),
                ("dwKeyStates", ctypes.c_uint32), ("PerformanceCount", ctypes.c_uint64),
                ("ButtonChangeType", ctypes.c_int32)]


class POINTER_PEN_INFO(ctypes.Structure):
    _fields_ = [("pointerInfo", POINTER_INFO), ("penFlags", ctypes.c_uint32), ("penMask", ctypes.c_uint32),
                ("pressure", ctypes.c_uint32), ("rotation", ctypes.c_uint32), ("tiltX", ctypes.c_int32),
                ("tiltY", ctypes.c_int32)]


class POINTER_TOUCH_INFO(ctypes.Structure):
    _fields_ = [("pointerInfo", POINTER_INFO), ("touchFlags", ctypes.c_uint32), ("touchMask", ctypes.c_uint32),
                ("rcContact", RECT), ("rcContactRaw", RECT), ("orientation", ctypes.c_uint32),
                ("pressure", ctypes.c_uint32)]


class _INFO(ctypes.Union):
    _fields_ = [("touchInfo", POINTER_TOUCH_INFO), ("penInfo", POINTER_PEN_INFO)]


class POINTER_TYPE_INFO(ctypes.Structure):
    _fields_ = [("type", ctypes.c_uint32), ("u", _INFO)]


def clamp(v, lo=0.0, hi=1.0):
    try:
        v = float(v)
    except (TypeError, ValueError):
        return lo
    return lo if v != v else max(lo, min(hi, v))  # v != v: NaN


class Frames:
    """The pen's state -> the frames Windows wants: (pointer flags, pen flags, button change, x, y, pressure)."""

    def __init__(self):
        self.in_range = False
        self.touching = False
        self.eraser = False
        self.x = self.y = 0

    def _frame(self, flags, button=NONE, pressure=0, barrel=False):
        pen = (INVERTED | (ERASER if flags & INCONTACT else 0)) if self.eraser else 0
        return (flags, pen | (BARREL if barrel else 0), button, self.x, self.y, pressure)

    def _lift(self, out):
        if self.touching:
            out.append(self._frame(INRANGE | UP, FIRST_UP))
            self.touching = False

    def _leave(self, out):
        self._lift(out)
        if self.in_range:
            out.append(self._frame(UPDATE))  # no INRANGE: the pen went away
            self.in_range = False

    def step(self, state, x, y, pressure=0.6, eraser=False):
        out = []
        if state in ("down", "move", "hover", "right") and self.in_range and eraser != self.eraser:
            self._leave(out)  # pen <-> eraser: the old end goes away first
        if state in ("down", "move", "hover", "right"):
            self.eraser = eraser
        self.x, self.y = x, y
        p = max(1, round(clamp(pressure) * MAX_PRESSURE))
        if state in ("down", "move", "hover"):
            if not self.in_range:
                out.append(self._frame(INRANGE | UPDATE))
                self.in_range = True
            if state == "hover":
                self._lift(out)
                out.append(self._frame(INRANGE | UPDATE))
            elif self.touching:
                out.append(self._frame(INRANGE | INCONTACT | FIRSTBUTTON | UPDATE, NONE, p))
            else:
                out.append(self._frame(INRANGE | INCONTACT | FIRSTBUTTON | DOWN, FIRST_DOWN, p))
                self.touching = True
        elif state == "up":
            self._lift(out)
        elif state == "out":
            self._leave(out)
        elif state == "right":  # a tap with the side button held = right click
            self._lift(out)
            if not self.in_range:
                out.append(self._frame(INRANGE | UPDATE))
                self.in_range = True
            out.append(self._frame(INRANGE | INCONTACT | SECONDBUTTON | DOWN, SECOND_DOWN, 512, barrel=True))
            out.append(self._frame(INRANGE | UP, SECOND_UP, 0, barrel=True))
        return out


class Pen:
    def __init__(self, dry_run=False, screen=None):
        self.dry_run = dry_run or not WINDOWS
        self.screen = screen    # () -> (width, height) of the laptop's main screen, in pixels
        self.frames = Frames()
        self.device = None
        self.frame_id = 0
        self.lock = threading.Lock()
        self.log = []           # frames a dry run would have sent (tests)
        self.failed = False

    def _size(self):
        try:
            w, h = self.screen() if self.screen else (1920, 1080)
            return max(1, int(w)), max(1, int(h))
        except (TypeError, ValueError, OSError):
            return 1920, 1080

    def _inject(self, frames):
        if self.dry_run:
            self.log.extend(frames)
            return
        user32 = ctypes.WinDLL("user32", use_last_error=True)
        if self.device is None:
            user32.CreateSyntheticPointerDevice.restype = ctypes.c_void_p
            user32.CreateSyntheticPointerDevice.argtypes = [ctypes.c_uint32, ctypes.c_uint32, ctypes.c_uint32]
            user32.InjectSyntheticPointerInput.argtypes = [ctypes.c_void_p, ctypes.POINTER(POINTER_TYPE_INFO),
                                                           ctypes.c_uint32]
            self.device = user32.CreateSyntheticPointerDevice(PT_PEN, 1, POINTER_FEEDBACK_DEFAULT)
            if not self.device:
                raise OSError("this Windows can't make a pen (Windows 10 version 1809 or newer is needed)")
        for flags, pen_flags, button, x, y, pressure in frames:
            self.frame_id += 1
            info = POINTER_TYPE_INFO(type=PT_PEN)
            pi = info.u.penInfo
            pi.pointerInfo.pointerType = PT_PEN
            pi.pointerInfo.pointerId = 1
            pi.pointerInfo.frameId = self.frame_id
            pi.pointerInfo.pointerFlags = flags | PRIMARY | CONFIDENCE
            pi.pointerInfo.ptPixelLocation = POINT(x, y)
            pi.pointerInfo.ButtonChangeType = button
            pi.penFlags = pen_flags
            pi.penMask = PEN_MASK_PRESSURE
            pi.pressure = pressure
            if not user32.InjectSyntheticPointerInput(self.device, ctypes.byref(info), 1):
                raise OSError("Windows refused the pen (error %d)" % ctypes.get_last_error())

    def handle(self, msg):
        w, h = self._size()
        x = round(clamp(msg.get("x")) * (w - 1))
        y = round(clamp(msg.get("y")) * (h - 1))
        with self.lock:
            frames = self.frames.step(msg.get("s"), x, y, msg.get("p", 0.6), msg.get("tool") == "eraser")
            if not frames:
                return
            try:
                self._inject(frames)
            except OSError as err:
                if not self.failed:
                    print("pen:", err, file=sys.stderr)
                self.failed = True

    def release(self):
        """The device went away mid-stroke: lift the pen so nothing stays drawn."""
        with self.lock:
            frames = self.frames.step("out", self.frames.x, self.frames.y)
            if frames:
                try:
                    self._inject(frames)
                except OSError:
                    pass
