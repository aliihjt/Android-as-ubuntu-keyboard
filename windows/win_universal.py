"""Universal Control (Windows): the laptop's mouse, touchpad and keyboard move on to the tablet.

The same as on Fedora (host/universal.py, whose messages and safety rules this
reuses): push the pointer past the laptop screen's edge on the tablet's side
and the mouse and keyboard work on the tablet; move off the tablet's other edge,
or press Ctrl+Alt+Esc, to come back.

On Windows it listens with "low-level hooks" (the way Windows lets a program see
every mouse move and key). While the tablet has control, Windows doesn't get the
moves and keys; the pointer waits in the middle of the laptop screen and how far
the mouse moves from there is sent to the tablet. What Fedora Pad itself types
for the tablet (SendInput) is never caught.
"""
import os
import queue
import sys
import threading
import time

import universal as uc

WINDOWS = os.name == "nt"

WM_MOUSEMOVE, WM_LBUTTONDOWN, WM_LBUTTONUP = 0x200, 0x201, 0x202
WM_RBUTTONDOWN, WM_RBUTTONUP, WM_MBUTTONDOWN, WM_MBUTTONUP = 0x204, 0x205, 0x207, 0x208
WM_MOUSEWHEEL, WM_XBUTTONDOWN, WM_XBUTTONUP, WM_MOUSEHWHEEL = 0x20A, 0x20B, 0x20C, 0x20E
WM_KEYDOWN, WM_KEYUP, WM_SYSKEYDOWN, WM_SYSKEYUP = 0x100, 0x101, 0x104, 0x105
BUTTONS = {WM_LBUTTONDOWN: ("left", 1), WM_LBUTTONUP: ("left", 0), WM_RBUTTONDOWN: ("right", 1),
           WM_RBUTTONUP: ("right", 0), WM_MBUTTONDOWN: ("middle", 1), WM_MBUTTONUP: ("middle", 0)}
LLMHF_INJECTED, LLKHF_INJECTED, LLKHF_EXTENDED = 0x1, 0x10, 0x1

# Windows virtual-key codes -> the Linux key names host/universal.py turns into Android keys
VK_NAMES = {
    0x08: "KEY_BACKSPACE", 0x09: "KEY_TAB", 0x0D: "KEY_ENTER", 0x13: "KEY_PAUSE", 0x14: "KEY_CAPSLOCK",
    0x1B: "KEY_ESC", 0x20: "KEY_SPACE", 0x21: "KEY_PAGEUP", 0x22: "KEY_PAGEDOWN", 0x23: "KEY_END",
    0x24: "KEY_HOME", 0x25: "KEY_LEFT", 0x26: "KEY_UP", 0x27: "KEY_RIGHT", 0x28: "KEY_DOWN",
    0x2D: "KEY_INSERT", 0x2E: "KEY_DELETE", 0x5B: "KEY_LEFTMETA", 0x5C: "KEY_RIGHTMETA",
    0xA0: "KEY_LEFTSHIFT", 0xA1: "KEY_RIGHTSHIFT", 0xA2: "KEY_LEFTCTRL", 0xA3: "KEY_RIGHTCTRL",
    0xA4: "KEY_LEFTALT", 0xA5: "KEY_RIGHTALT", 0x10: "KEY_LEFTSHIFT", 0x11: "KEY_LEFTCTRL", 0x12: "KEY_LEFTALT",
    0xAD: "KEY_MUTE", 0xAE: "KEY_VOLUMEDOWN", 0xAF: "KEY_VOLUMEUP", 0xB0: "KEY_NEXTSONG",
    0xB1: "KEY_PREVIOUSSONG", 0xB3: "KEY_PLAYPAUSE",
    0xBA: "KEY_SEMICOLON", 0xBB: "KEY_EQUAL", 0xBC: "KEY_COMMA", 0xBD: "KEY_MINUS", 0xBE: "KEY_DOT",
    0xBF: "KEY_SLASH", 0xC0: "KEY_GRAVE", 0xDB: "KEY_LEFTBRACE", 0xDC: "KEY_BACKSLASH",
    0xDD: "KEY_RIGHTBRACE", 0xDE: "KEY_APOSTROPHE",
    0x6A: "KEY_KPASTERISK", 0x6B: "KEY_KPPLUS", 0x6D: "KEY_MINUS", 0x6E: "KEY_DOT", 0x6F: "KEY_SLASH",
}
for _i in range(26):
    VK_NAMES[0x41 + _i] = "KEY_" + chr(0x41 + _i)
for _i in range(10):
    VK_NAMES[0x30 + _i] = "KEY_%d" % _i
    VK_NAMES[0x60 + _i] = "KEY_%d" % _i  # number pad digits
for _i in range(12):
    VK_NAMES[0x70 + _i] = "KEY_F%d" % (_i + 1)


def key_name(vk, extended=False):
    if vk == 0x0D and extended:
        return "KEY_KPENTER"
    return VK_NAMES.get(vk)


def wheel_delta(mouse_data):
    """The signed wheel amount in the high word of mouseData (120 = one notch)."""
    d = (mouse_data >> 16) & 0xFFFF
    return d - 0x10000 if d & 0x8000 else d


class WinUniversal(uc.Universal):
    def __init__(self, conf_dir, screen):
        """screen: () -> {"monitors": [...], "x","y","w","h"}: the laptop's main screen."""
        self.screen_info = screen
        super().__init__(self._screens, conf_dir, dry_run=True)  # no evdev on Windows: the hooks do its work
        self.push = 0.0
        self.anchor = None          # where the pointer waits while the tablet has control
        self.pressed = set()
        self.outbox = queue.Queue()
        self.hooks = []

    def _screens(self, max_age=0):
        try:
            return self.screen_info()
        except (OSError, TypeError, ValueError):
            return None

    # messages go out from their own thread: a hook must answer Windows at once
    def _send(self, msg):
        if self.target:
            self.outbox.put(msg)

    def _sender(self):
        while True:
            msg = self.outbox.get()
            uc.Universal._send(self, msg)

    # ---- taking and giving back

    def _grab(self):
        sc = self._screens() or {}
        self.anchor = (sc.get("x", 0) + sc.get("w", 1920) // 2, sc.get("y", 0) + sc.get("h", 1080) // 2)
        self.pressed.clear()
        self._set_cursor(*self.anchor)

    def _ungrab(self):
        self.push = 0.0
        if self.anchor is None:
            return
        self.anchor = None
        sc = self._screens() or {}
        x, w = sc.get("x", 0), sc.get("w", 1920)
        y = sc.get("y", 0) + round(self.edge_y * sc.get("h", 1080))
        self._set_cursor(x + w - 40 if self.conf["side"] == "right" else x + 40, y)  # back where it left

    @staticmethod
    def _set_cursor(x, y):
        if WINDOWS:
            import ctypes
            ctypes.windll.user32.SetCursorPos(int(x), int(y))

    # ---- what the hooks see (True: Windows doesn't get it)

    def on_mouse(self, kind, x, y, mouse_data=0, injected=False):
        if injected:
            return False
        if not self.active:
            if kind == WM_MOUSEMOVE and self.conf["on"] and self.target:
                sc = dict(self._screens() or {}, px=x, py=y)
                if self.edge(sc):
                    right = sc.get("x", 0) + sc.get("w", 0) - 1
                    left = sc.get("x", 0)
                    beyond = x - right if self.conf["side"] == "right" else left - x
                    self.push += max(1, beyond)  # Windows may or may not let the position pass the edge
                    if self.push >= uc.PUSH_MOUSE:
                        self.enter()
                        return self.active
                else:
                    self.push = 0.0
            return False
        if self.mode != "tablet":
            return False
        speed = self.conf.get("speed", 1.0)
        if kind == WM_MOUSEMOVE:
            if self.anchor and (x, y) != self.anchor:
                dx, dy = x - self.anchor[0], y - self.anchor[1]
                self._out({"a": "move", "dx": round(dx * speed, 1), "dy": round(dy * speed, 1)})
        elif kind in BUTTONS:
            b, v = BUTTONS[kind]
            self._out({"a": "button", "b": b, "v": v})
        elif kind == WM_MOUSEWHEEL:  # + = wheel away from you = the page moves down, as on Fedora
            self._out({"a": "scroll", "dx": 0, "dy": round(wheel_delta(mouse_data) / 120 * uc.WHEEL_PX)})
        elif kind == WM_MOUSEHWHEEL:
            self._out({"a": "scroll", "dx": -round(wheel_delta(mouse_data) / 120 * uc.WHEEL_PX), "dy": 0})
        return True

    def on_key(self, kind, vk, extended=False, injected=False):
        if injected or not self.active or self.mode != "tablet":
            return False
        down = kind in (WM_KEYDOWN, WM_SYSKEYDOWN)
        name = key_name(vk, extended)
        if name is None:
            return True
        if down:
            value = 2 if name in self.pressed else 1
            self.pressed.add(name)
        else:
            value = 0
            self.pressed.discard(name)
        self._key(name, value)  # Ctrl+Alt+Esc gives control back here
        return True

    # ---- Windows

    def start(self):
        if not WINDOWS:
            return
        threading.Thread(target=self._sender, daemon=True).start()
        threading.Thread(target=self._alive_loop, daemon=True).start()
        threading.Thread(target=self._hook_loop, daemon=True).start()

    def _alive_loop(self):
        while True:
            self.check_alive(time.monotonic())
            time.sleep(0.25)

    def _hook_loop(self):
        import ctypes
        from ctypes import wintypes as wt

        user32 = ctypes.WinDLL("user32", use_last_error=True)
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)

        class MSLLHOOKSTRUCT(ctypes.Structure):
            _fields_ = [("pt", wt.POINT), ("mouseData", wt.DWORD), ("flags", wt.DWORD), ("time", wt.DWORD),
                        ("dwExtraInfo", ctypes.c_size_t)]

        class KBDLLHOOKSTRUCT(ctypes.Structure):
            _fields_ = [("vkCode", wt.DWORD), ("scanCode", wt.DWORD), ("flags", wt.DWORD), ("time", wt.DWORD),
                        ("dwExtraInfo", ctypes.c_size_t)]

        HOOKPROC = ctypes.WINFUNCTYPE(ctypes.c_ssize_t, ctypes.c_int, wt.WPARAM, wt.LPARAM)
        user32.CallNextHookEx.argtypes = [ctypes.c_void_p, ctypes.c_int, wt.WPARAM, wt.LPARAM]
        user32.CallNextHookEx.restype = ctypes.c_ssize_t
        user32.SetWindowsHookExW.argtypes = [ctypes.c_int, HOOKPROC, ctypes.c_void_p, wt.DWORD]
        user32.SetWindowsHookExW.restype = ctypes.c_void_p
        kernel32.GetModuleHandleW.restype = ctypes.c_void_p

        def mouse(n, w, lp):
            if n == 0:
                try:
                    m = ctypes.cast(lp, ctypes.POINTER(MSLLHOOKSTRUCT)).contents
                    if self.on_mouse(int(w), m.pt.x, m.pt.y, m.mouseData, bool(m.flags & LLMHF_INJECTED)):
                        return 1
                except Exception as err:  # noqa: BLE001 - a hook must never break the laptop's mouse
                    print("Universal Control:", err, file=sys.stderr)
            return user32.CallNextHookEx(None, n, w, lp)

        def keyboard(n, w, lp):
            if n == 0:
                try:
                    k = ctypes.cast(lp, ctypes.POINTER(KBDLLHOOKSTRUCT)).contents
                    if self.on_key(int(w), k.vkCode, bool(k.flags & LLKHF_EXTENDED), bool(k.flags & LLKHF_INJECTED)):
                        return 1
                except Exception as err:  # noqa: BLE001
                    print("Universal Control:", err, file=sys.stderr)
            return user32.CallNextHookEx(None, n, w, lp)

        self.hooks = [HOOKPROC(mouse), HOOKPROC(keyboard)]  # kept, or Python frees them
        module = kernel32.GetModuleHandleW(None)
        if not (user32.SetWindowsHookExW(14, self.hooks[0], module, 0)       # WH_MOUSE_LL
                and user32.SetWindowsHookExW(13, self.hooks[1], module, 0)):  # WH_KEYBOARD_LL
            print("Universal Control off: Windows didn't allow the mouse/keyboard hooks", file=sys.stderr)
            return
        msg = wt.MSG()
        while user32.GetMessageW(ctypes.byref(msg), None, 0, 0) > 0:  # hooks need this thread waiting for messages
            user32.TranslateMessage(ctypes.byref(msg))
            user32.DispatchMessageW(ctypes.byref(msg))
