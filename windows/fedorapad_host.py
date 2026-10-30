#!/usr/bin/env python3
"""Fedora Pad host for Windows 10 / 11.

The same protocol as host/tabletdesk_host.py (see its header): JSON lines on
127.0.0.1:7777, reached from the tablet through `adb reverse` over the USB
cable. Keys, mouse and text go in with SendInput, so it works in every app,
in any language. Only the Python standard library and Windows' own DLLs are
used; pycaw (optional) gives exact volume levels.

    pythonw fedorapad_host.py            (started by FedoraPad.ps1)
    python  fedorapad_host.py --dry-run  print instead of typing
"""
import argparse
import base64
import ctypes
import json
import os
import shutil
import socket
import socketserver
import subprocess
import sys
import threading
import time
import webbrowser

WINDOWS = os.name == "nt"
if WINDOWS:
    from ctypes import wintypes as wt
    import winreg

    user32 = ctypes.WinDLL("user32", use_last_error=True)
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    gdi32 = ctypes.WinDLL("gdi32")
    dwmapi = ctypes.WinDLL("dwmapi")
    NO_WINDOW = 0x08000000  # CREATE_NO_WINDOW: helper programs open no console
else:  # lets the logic be tested on Linux with --dry-run
    wt = winreg = user32 = kernel32 = gdi32 = dwmapi = None
    NO_WINDOW = 0

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
CONF_DIR = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), "FedoraPad")
HOME = os.path.expanduser("~")

# ---------------------------------------------------------------- input

# key names the tablet sends (Linux style) -> Windows virtual-key codes
VK = {
    "ctrl": 0xA2, "control": 0xA2, "leftctrl": 0xA2, "rightctrl": 0xA3, "cmd": 0xA2,
    "shift": 0xA0, "leftshift": 0xA0, "rightshift": 0xA1,
    "alt": 0xA4, "option": 0xA4, "leftalt": 0xA4, "altgr": 0xA5, "rightalt": 0xA5,
    "super": 0x5B, "meta": 0x5B, "win": 0x5B, "leftmeta": 0x5B, "rightmeta": 0x5C,
    "esc": 0x1B, "escape": 0x1B, "enter": 0x0D, "return": 0x0D,
    "backspace": 0x08, "bksp": 0x08, "tab": 0x09, "space": 0x20,
    "del": 0x2E, "delete": 0x2E, "ins": 0x2D, "insert": 0x2D,
    "home": 0x24, "end": 0x23, "pgup": 0x21, "pageup": 0x21, "pgdn": 0x22, "pagedown": 0x22,
    "left": 0x25, "up": 0x26, "right": 0x27, "down": 0x28,
    "print": 0x2C, "printscreen": 0x2C, "sysrq": 0x2C,
    "capslock": 0x14, "numlock": 0x90, "scrolllock": 0x91, "pause": 0x13,
    "menu": 0x5D, "compose": 0x5D,
    "minus": 0xBD, "equal": 0xBB, "plus": 0xBB, "leftbrace": 0xDB, "rightbrace": 0xDD,
    "backslash": 0xDC, "semicolon": 0xBA, "apostrophe": 0xDE, "grave": 0xC0,
    "comma": 0xBC, "dot": 0xBE, "period": 0xBE, "slash": 0xBF,
    "kpasterisk": 0x6A, "kpplus": 0x6B, "kpminus": 0x6D, "kpdot": 0x6E, "kpslash": 0x6F,
    "kpenter": 0x0D,
    "volup": 0xAF, "volumeup": 0xAF, "voldown": 0xAE, "volumedown": 0xAE, "mute": 0xAD,
    "playpause": 0xB3, "next": 0xB0, "nextsong": 0xB0, "prev": 0xB1, "previoussong": 0xB1,
    "stopcd": 0xB2,
}
for _i in range(10):
    VK[str(_i)] = 0x30 + _i
    VK["kp%d" % _i] = 0x60 + _i
for _i in range(1, 25):
    VK["f%d" % _i] = 0x6F + _i
for _c in "abcdefghijklmnopqrstuvwxyz":
    VK[_c] = ord(_c.upper())
# characters on a US keyboard (used by combos like "ctrl+;")
for _ch, _name in zip("-=[]\\;',./`", ["minus", "equal", "leftbrace", "rightbrace", "backslash",
                                        "semicolon", "apostrophe", "comma", "dot", "slash", "grave"]):
    VK[_ch] = VK[_name]
EXTENDED = {0xA3, 0xA5, 0x5B, 0x5C, 0x5D, 0x2D, 0x2E, 0x24, 0x23, 0x21, 0x22,
            0x25, 0x26, 0x27, 0x28, 0x2C, 0x90, 0x6F}

# GNOME shortcuts on the tablet's pages -> the Windows way to do the same
TRANSLATE = {
    "super+a": "super+s",            # all apps -> Start search
    "super+h": "super+down",         # hide window
    "super+v": "super+v",            # clipboard history (Windows has it too)
    "ctrl+alt+t": "super+x",         # terminal -> power user menu
    "super+period": "super+period",  # emoji panel
    "ctrl+dot": "super+period",
    "print": "super+shift+s",        # screenshot tool
    "shift+print": "super+print",    # whole screen to Pictures
    "super+shift+right": "super+shift+right",
}


def vk_of(name):
    n = name.strip().lower()
    if n.startswith("key_"):
        n = n[4:]
    return VK.get(n)


if WINDOWS:
    ULONG_PTR = ctypes.c_size_t

    class MOUSEINPUT(ctypes.Structure):
        _fields_ = [("dx", wt.LONG), ("dy", wt.LONG), ("mouseData", wt.DWORD),
                    ("dwFlags", wt.DWORD), ("time", wt.DWORD), ("dwExtraInfo", ULONG_PTR)]

    class KEYBDINPUT(ctypes.Structure):
        _fields_ = [("wVk", wt.WORD), ("wScan", wt.WORD), ("dwFlags", wt.DWORD),
                    ("time", wt.DWORD), ("dwExtraInfo", ULONG_PTR)]

    class HARDWAREINPUT(ctypes.Structure):
        _fields_ = [("uMsg", wt.DWORD), ("wParamL", wt.WORD), ("wParamH", wt.WORD)]

    class _U(ctypes.Union):
        _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT)]

    class INPUT(ctypes.Structure):
        _anonymous_ = ("u",)
        _fields_ = [("type", wt.DWORD), ("u", _U)]

    user32.SendInput.argtypes = (wt.UINT, ctypes.POINTER(INPUT), ctypes.c_int)
    user32.SendInput.restype = wt.UINT

KEYEVENTF_EXTENDEDKEY, KEYEVENTF_KEYUP, KEYEVENTF_UNICODE = 0x1, 0x2, 0x4
MOUSE_FLAGS = {"left": (0x2, 0x4), "right": (0x8, 0x10), "middle": (0x20, 0x40)}


class Injector:
    """Sends keys, mouse and text with SendInput."""

    def __init__(self, dry_run=False):
        self.dry_run = dry_run or not WINDOWS
        self.lock = threading.Lock()
        self.move_acc = [0.0, 0.0]
        self.scroll_acc = [0.0, 0.0]
        self.held_keys = set()
        self.held_buttons = set()

    def _send(self, *inputs):
        if self.dry_run:
            return
        arr = (INPUT * len(inputs))(*inputs)
        user32.SendInput(len(inputs), arr, ctypes.sizeof(INPUT))

    def _key(self, vk, down):
        if down:
            self.held_keys.add(vk)
        else:
            self.held_keys.discard(vk)
        if self.dry_run:
            print("key", hex(vk), int(down))
            return
        flags = (0 if down else KEYEVENTF_KEYUP) | (KEYEVENTF_EXTENDEDKEY if vk in EXTENDED else 0)
        i = INPUT(type=1)
        i.ki = KEYBDINPUT(wVk=vk, wScan=user32.MapVirtualKeyW(vk, 0) & 0xFF, dwFlags=flags)
        self._send(i)

    def key(self, name, state):
        vk = vk_of(name)
        if vk is None:
            return
        with self.lock:
            if state in ("down", "click"):
                self._key(vk, True)
            if state in ("up", "click"):
                self._key(vk, False)

    def combo(self, spec):
        spec = TRANSLATE.get(spec.strip().lower(), spec)
        codes = [vk_of(p) for p in spec.split("+") if p.strip()]
        if not codes or None in codes:
            return
        with self.lock:
            for vk in codes:
                self._key(vk, True)
            for vk in reversed(codes):
                self._key(vk, False)

    def text(self, s):
        """Any language: each character goes in as a Unicode key event."""
        with self.lock:
            for ch in s:
                if ch == "\n":
                    self._key(0x0D, True)
                    self._key(0x0D, False)
                    continue
                if self.dry_run:
                    print("char", repr(ch))
                    continue
                data = ch.encode("utf-16-le")
                for k in range(0, len(data), 2):  # emoji are two UTF-16 units
                    unit = int.from_bytes(data[k:k + 2], "little")
                    down, up = INPUT(type=1), INPUT(type=1)
                    down.ki = KEYBDINPUT(wVk=0, wScan=unit, dwFlags=KEYEVENTF_UNICODE)
                    up.ki = KEYBDINPUT(wVk=0, wScan=unit, dwFlags=KEYEVENTF_UNICODE | KEYEVENTF_KEYUP)
                    self._send(down, up)

    def move(self, dx, dy):
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
            i = INPUT(type=0)
            i.mi = MOUSEINPUT(dx=ix, dy=iy, dwFlags=0x1)  # MOUSEEVENTF_MOVE
            self._send(i)

    def button(self, b, state):
        down_flag, up_flag = MOUSE_FLAGS.get(b, MOUSE_FLAGS["left"])
        with self.lock:
            for down in ((True, False) if state == "click" else (True,) if state == "down" else (False,)):
                if down:
                    self.held_buttons.add(b)
                else:
                    self.held_buttons.discard(b)
                if self.dry_run:
                    print("btn", b, int(down))
                    continue
                i = INPUT(type=0)
                i.mi = MOUSEINPUT(dwFlags=down_flag if down else up_flag)
                self._send(i)

    def scroll(self, dx, dy, step=40.0):
        self.scroll_acc[0] += dx
        self.scroll_acc[1] += dy
        nx, ny = int(self.scroll_acc[0] / step), int(self.scroll_acc[1] / step)
        self.scroll_acc[0] -= nx * step
        self.scroll_acc[1] -= ny * step
        if not nx and not ny:
            return
        with self.lock:
            if self.dry_run:
                print("scroll", nx, ny)
                return
            # natural scrolling, like the Fedora version: finger up -> content up
            if ny:
                i = INPUT(type=0)
                i.mi = MOUSEINPUT(mouseData=ctypes.c_uint32(-ny * 120).value, dwFlags=0x800)
                self._send(i)
            if nx:
                i = INPUT(type=0)
                i.mi = MOUSEINPUT(mouseData=ctypes.c_uint32(nx * 120).value, dwFlags=0x1000)
                self._send(i)

    def release_all(self):
        """Let go of everything, so a pulled cable never leaves Ctrl or a click stuck."""
        with self.lock:
            for vk in list(self.held_keys):
                self._key(vk, False)
        for b in list(self.held_buttons):
            self.button(b, "up")


# ---------------------------------------------------------------- helpers

def _run(argv, timeout=8):
    try:
        return subprocess.run(argv, capture_output=True, text=True, timeout=timeout,
                              encoding="utf-8", errors="replace", creationflags=NO_WINDOW).stdout
    except (OSError, subprocess.SubprocessError):
        return ""


def _ps(script, timeout=10):
    """A fixed PowerShell snippet (never anything the tablet sends)."""
    return _run(["powershell", "-NoProfile", "-NonInteractive", "-Command",
                 "[Console]::OutputEncoding=[Text.Encoding]::UTF8;" + script], timeout)


def _start(argv):
    try:
        subprocess.Popen(argv, creationflags=NO_WINDOW, close_fds=True)
    except OSError as err:
        print("could not start", argv[0], err, file=sys.stderr)


def _open(target):
    if WINDOWS:
        try:
            os.startfile(target)
        except OSError as err:
            print("could not open", target, err, file=sys.stderr)
    else:
        print("open", target)


# ---------------------------------------------------------------- commands

NOTES_FILE = os.path.join(CONF_DIR, "notes.json")
NOTES_MD = os.path.join(HOME, "Documents", "Fedora Pad Notes.md")
COMMANDS_FILE = os.path.join(CONF_DIR, "commands.json")


def _shutdown_dialog():
    # Windows' own "Shut Down Windows" box: the user picks and confirms there
    _ps("(New-Object -ComObject Shell.Application).ShutdownWindows()")


def _lock():
    if WINDOWS:
        user32.LockWorkStation()


COMMANDS = {
    "settings": lambda: _open("ms-settings:"),
    "files": lambda: _start(["explorer.exe"]),
    "terminal": lambda: _start(["wt.exe"]) if shutil.which("wt") else _start(["cmd", "/c", "start", "powershell"]),
    "monitor": lambda: _start(["taskmgr.exe"]),
    "about": lambda: _open("ms-settings:about"),
    "lock": _lock,
    "suspend": lambda: _start(["rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0"]),
    "logout": _shutdown_dialog,
    "poweroff": _shutdown_dialog,
    "restart": _shutdown_dialog,
    "ai": lambda: webbrowser.open("https://claude.ai"),
    "notes": lambda: _start(["notepad.exe", NOTES_MD]),
    "editcommands": lambda: _start(["notepad.exe", COMMANDS_FILE]),
}


def run_command(inj, name):
    if name in ("voice", "voicestart", "voicestop"):
        inj.combo("super+h")  # Windows' own voice typing
        return
    fn = COMMANDS.get(name)
    if fn:
        threading.Thread(target=fn, daemon=True).start()


# ---------------------------------------------------------------- settings

try:  # exact volume when pycaw is installed (setup.ps1 does it)
    from ctypes import POINTER, cast
    from comtypes import CLSCTX_ALL
    from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume
except Exception:  # noqa: BLE001 - any import problem means "use the volume keys"
    AudioUtilities = None


def _volume_iface():
    dev = AudioUtilities.GetSpeakers()
    iface = dev.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
    return cast(iface, POINTER(IAudioEndpointVolume))


POWER_SCHEMES = ["a1841308-3541-4fab-bc81-f71556f20b4a",  # power saver
                 "381b4222-f694-41f0-9685-ff5bb260df2e",  # balanced
                 "8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c"]  # high performance
THEME_KEY = r"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize"
_volume_guess = [50]


def set_value(inj, what, value):
    v = max(0, min(100, int(value)))
    if what == "volume":
        if AudioUtilities:
            try:
                vol = _volume_iface()
                vol.SetMute(0, None)
                vol.SetMasterVolumeLevelScalar(v / 100, None)
                return
            except Exception as err:  # noqa: BLE001
                print("volume:", err, file=sys.stderr)
        # without pycaw: down to 0, then up in 2 % steps
        for _ in range(50):
            inj.combo("voldown")
        for _ in range(v // 2):
            inj.combo("volup")
        _volume_guess[0] = v
    elif what == "brightness":
        _ps("(Get-CimInstance -Namespace root/WMI -ClassName WmiMonitorBrightnessMethods)"
            f".WmiSetBrightness(1,{max(v, 1)})")
    elif what == "dark" and WINDOWS:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, THEME_KEY, 0, winreg.KEY_SET_VALUE) as k:
            for name in ("AppsUseLightTheme", "SystemUsesLightTheme"):
                winreg.SetValueEx(k, name, 0, winreg.REG_DWORD, 0 if v else 1)
    elif what == "power" and v in (0, 1, 2):
        _run(["powercfg", "/setactive", POWER_SCHEMES[v]])
    elif what == "micmute":
        inj.combo("super+alt+k")  # Windows 11: mute the mic in calls


def read_state():
    state = {"t": "state"}
    if AudioUtilities:
        try:
            state["volume"] = round(_volume_iface().GetMasterVolumeLevelScalar() * 100)
        except Exception:  # noqa: BLE001
            pass
    else:
        state["volume"] = _volume_guess[0]
    b = _ps("(Get-CimInstance -Namespace root/WMI -ClassName WmiMonitorBrightness"
            " -ErrorAction SilentlyContinue).CurrentBrightness", 6).strip().splitlines()
    if b and b[0].strip().isdigit():
        state["brightness"] = int(b[0])
    if WINDOWS:
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, THEME_KEY) as k:
                state["dark"] = winreg.QueryValueEx(k, "AppsUseLightTheme")[0] == 0
        except OSError:
            pass
    active = _run(["powercfg", "/getactivescheme"]).lower()
    for i, guid in enumerate(POWER_SCHEMES):
        if guid in active:
            state["power"] = i
    state.update(pc_info())
    return state


# ---------------------------------------------------------------- PC numbers

if WINDOWS:
    class MEMORYSTATUSEX(ctypes.Structure):
        _fields_ = [("dwLength", wt.DWORD), ("dwMemoryLoad", wt.DWORD),
                    ("ullTotalPhys", ctypes.c_ulonglong), ("ullAvailPhys", ctypes.c_ulonglong),
                    ("ullTotalPageFile", ctypes.c_ulonglong), ("ullAvailPageFile", ctypes.c_ulonglong),
                    ("ullTotalVirtual", ctypes.c_ulonglong), ("ullAvailVirtual", ctypes.c_ulonglong),
                    ("ullAvailExtendedVirtual", ctypes.c_ulonglong)]

    class SYSTEM_POWER_STATUS(ctypes.Structure):
        _fields_ = [("ACLineStatus", ctypes.c_byte), ("BatteryFlag", ctypes.c_byte),
                    ("BatteryLifePercent", ctypes.c_ubyte), ("SystemStatusFlag", ctypes.c_byte),
                    ("BatteryLifeTime", wt.DWORD), ("BatteryFullLifeTime", wt.DWORD)]

_cpu_last = [0, 0]


def _cpu_percent():
    idle, kern, usr = wt.FILETIME(), wt.FILETIME(), wt.FILETIME()
    kernel32.GetSystemTimes(ctypes.byref(idle), ctypes.byref(kern), ctypes.byref(usr))
    as_int = lambda f: (f.dwHighDateTime << 32) | f.dwLowDateTime  # noqa: E731
    i, total = as_int(idle), as_int(kern) + as_int(usr)  # kernel time includes idle
    d_idle, d_total = i - _cpu_last[0], total - _cpu_last[1]
    _cpu_last[:] = [i, total]
    return round(100 * (1 - d_idle / d_total)) if d_total > 0 else 0


def pc_info():
    info = {}
    if not WINDOWS:
        return info
    try:
        info["cpu"] = _cpu_percent()
    except OSError:
        pass
    m = MEMORYSTATUSEX()
    m.dwLength = ctypes.sizeof(m)
    if kernel32.GlobalMemoryStatusEx(ctypes.byref(m)):
        info["ram"] = int(m.dwMemoryLoad)
    p = SYSTEM_POWER_STATUS()
    if kernel32.GetSystemPowerStatus(ctypes.byref(p)) and p.BatteryLifePercent != 255 and p.BatteryFlag != -128:
        info["battery"] = int(p.BatteryLifePercent)
        info["charging"] = p.ACLineStatus == 1
    return info


def stats():
    out = {"t": "stats"}
    out.update(pc_info())
    try:
        du = shutil.disk_usage(os.environ.get("SystemDrive", "C:") + "\\")
        out["disk"] = round(du.used * 100 / du.total)
        out["disk_free_gb"] = round(du.free / 1e9)
    except OSError:
        pass
    if WINDOWS:
        kernel32.GetTickCount64.restype = ctypes.c_ulonglong
        out["uptime_min"] = int(kernel32.GetTickCount64() // 60000)
    return out


def media_state():
    # Windows has no simple "now playing" for other programs; the buttons still work
    return {"t": "media", "status": "Playing", "title": "Media keys",
            "artist": "Play / pause, next and previous work in any player", "player": "Windows"}


# ---------------------------------------------------------------- clipboard

CF_UNICODETEXT = 13
if WINDOWS:
    user32.GetClipboardData.restype = ctypes.c_void_p
    user32.SetClipboardData.argtypes = (wt.UINT, ctypes.c_void_p)
    user32.SetClipboardData.restype = ctypes.c_void_p
    kernel32.GlobalAlloc.argtypes = (wt.UINT, ctypes.c_size_t)
    kernel32.GlobalAlloc.restype = ctypes.c_void_p
    kernel32.GlobalLock.argtypes = (ctypes.c_void_p,)
    kernel32.GlobalLock.restype = ctypes.c_void_p
    kernel32.GlobalUnlock.argtypes = (ctypes.c_void_p,)


def _open_clipboard():
    for _ in range(10):  # another program may hold it for a moment
        if user32.OpenClipboard(None):
            return True
        time.sleep(0.05)
    return False


def get_clipboard():
    if not WINDOWS or not _open_clipboard():
        return ""
    try:
        h = user32.GetClipboardData(CF_UNICODETEXT)
        if not h:
            return ""
        p = kernel32.GlobalLock(h)
        try:
            return ctypes.wstring_at(p)[:65536]
        finally:
            kernel32.GlobalUnlock(h)
    finally:
        user32.CloseClipboard()


def set_clipboard(text):
    if not WINDOWS or not _open_clipboard():
        return
    try:
        user32.EmptyClipboard()
        data = (text[:65536] + "\0").encode("utf-16-le")
        h = kernel32.GlobalAlloc(0x0002, len(data))  # GMEM_MOVEABLE
        p = kernel32.GlobalLock(h)
        ctypes.memmove(p, data, len(data))
        kernel32.GlobalUnlock(h)
        user32.SetClipboardData(CF_UNICODETEXT, h)  # Windows owns the memory now
    finally:
        user32.CloseClipboard()


# ---------------------------------------------------------------- apps

_apps_cache = [0.0, []]


def all_apps():
    """Every app in the Start menu (also Store apps), from Windows' own list."""
    if time.time() - _apps_cache[0] < 120 and _apps_cache[1]:
        return _apps_cache[1]
    raw = _ps("Get-StartApps | Select-Object Name,AppID | ConvertTo-Json -Compress", 20)
    try:
        items = json.loads(raw) if raw.strip() else []
    except ValueError:
        items = []
    if isinstance(items, dict):
        items = [items]
    apps = [{"id": str(a.get("AppID", "")), "name": str(a.get("Name", ""))} for a in items
            if a.get("AppID") and a.get("Name") and "uninstall" not in str(a.get("Name")).lower()]
    apps.sort(key=lambda a: a["name"].lower())
    _apps_cache[:] = [time.time(), apps]
    return apps


def favourite_apps():
    """The apps pinned to the taskbar (matched by name), else a few usual ones."""
    pinned = os.path.join(os.environ.get("APPDATA", ""), "Microsoft", "Internet Explorer", "Quick Launch",
                          "User Pinned", "TaskBar")
    names = []
    try:
        names = [os.path.splitext(f)[0].lower() for f in os.listdir(pinned) if f.endswith(".lnk")]
    except OSError:
        pass
    apps = all_apps()
    fav = [a for a in apps if a["name"].lower() in names]
    if not fav:
        usual = ("microsoft edge", "google chrome", "firefox", "file explorer", "settings", "terminal")
        fav = [a for a in apps if a["name"].lower() in usual]
    return fav[:10]


def launch_app(app_id):
    if any(a["id"] == app_id for a in all_apps()):  # only apps from the Start menu
        _start(["explorer.exe", "shell:AppsFolder\\" + app_id])


def open_url(url):
    if url.startswith(("https://", "http://")):
        webbrowser.open(url)


# ---------------------------------------------------------------- windows

if WINDOWS:
    WNDENUMPROC = ctypes.WINFUNCTYPE(wt.BOOL, wt.HWND, wt.LPARAM)

    class RECT(ctypes.Structure):
        _fields_ = [("left", wt.LONG), ("top", wt.LONG), ("right", wt.LONG), ("bottom", wt.LONG)]

    class MONITORINFO(ctypes.Structure):
        _fields_ = [("cbSize", wt.DWORD), ("rcMonitor", RECT), ("rcWork", RECT), ("dwFlags", wt.DWORD)]

    MONITORENUMPROC = ctypes.WINFUNCTYPE(wt.BOOL, wt.HMONITOR, wt.HDC, ctypes.POINTER(RECT), wt.LPARAM)
    user32.GetWindowTextLengthW.argtypes = (wt.HWND,)
    user32.GetForegroundWindow.restype = wt.HWND
    user32.GetDC.restype = ctypes.c_void_p
    user32.ReleaseDC.argtypes = (wt.HWND, ctypes.c_void_p)
    gdi32.GetPixel.argtypes = (ctypes.c_void_p, ctypes.c_int, ctypes.c_int)
    gdi32.GetPixel.restype = wt.DWORD
    kernel32.OpenProcess.restype = wt.HANDLE
    kernel32.CloseHandle.argtypes = (wt.HANDLE,)
    kernel32.QueryFullProcessImageNameW.argtypes = (wt.HANDLE, wt.DWORD, wt.LPWSTR, ctypes.POINTER(wt.DWORD))


def _title(hwnd):
    n = user32.GetWindowTextLengthW(hwnd)
    buf = ctypes.create_unicode_buffer(n + 1)
    user32.GetWindowTextW(hwnd, buf, n + 1)
    return buf.value


def _process_name(hwnd):
    pid = wt.DWORD()
    user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
    h = kernel32.OpenProcess(0x1000, False, pid.value)  # PROCESS_QUERY_LIMITED_INFORMATION
    if not h:
        return ""
    try:
        size = wt.DWORD(512)
        buf = ctypes.create_unicode_buffer(512)
        if kernel32.QueryFullProcessImageNameW(h, 0, buf, ctypes.byref(size)):
            return os.path.splitext(os.path.basename(buf.value))[0]
        return ""
    finally:
        kernel32.CloseHandle(h)


PRETTY = {"msedge": "Microsoft Edge", "chrome": "Google Chrome", "firefox": "Firefox",
          "explorer": "File Explorer", "WindowsTerminal": "Terminal", "Code": "VS Code",
          "WINWORD": "Word", "EXCEL": "Excel", "POWERPNT": "PowerPoint", "ApplicationFrameHost": "App"}


def app_name(hwnd):
    p = _process_name(hwnd)
    return PRETTY.get(p, p[:1].upper() + p[1:] if p else "Windows")


def _cloaked(hwnd):
    val = wt.DWORD()
    dwmapi.DwmGetWindowAttribute(hwnd, 14, ctypes.byref(val), ctypes.sizeof(val))  # DWMWA_CLOAKED
    return val.value != 0


def _real_windows():
    found = []

    def cb(hwnd, _):
        if (user32.IsWindowVisible(hwnd) and not user32.GetWindow(hwnd, 4)  # no owner
                and not (user32.GetWindowLongW(hwnd, -20) & 0x80)  # not a tool window
                and _title(hwnd) and not _cloaked(hwnd)):
            found.append(hwnd)
        return True

    user32.EnumWindows(WNDENUMPROC(cb), 0)
    return found


def windows_list():
    if not WINDOWS:
        return {"t": "windows", "list": []}
    fg = user32.GetForegroundWindow()
    out = []
    for hwnd in _real_windows():
        title = _title(hwnd)
        if title in ("Program Manager", "Windows Input Experience"):
            continue
        out.append({"id": str(hwnd), "title": title, "app": app_name(hwnd),
                    "minimized": bool(user32.IsIconic(hwnd)), "focused": hwnd == fg})
    return {"t": "windows", "list": out}


def _hwnd(wid):
    try:
        h = int(wid)
    except ValueError:
        return None
    return h if WINDOWS and h in _real_windows() else None


def focus_window(inj, wid):
    h = _hwnd(wid)
    if not h:
        return
    if user32.IsIconic(h):
        user32.ShowWindow(h, 9)  # SW_RESTORE
    inj.key("alt", "click")  # lets this program hand over the focus
    user32.SetForegroundWindow(h)


def close_window(wid):
    h = _hwnd(wid)
    if h:
        user32.PostMessageW(h, 0x0010, 0, 0)  # WM_CLOSE: the app can still ask to save


def _monitors():
    mons = []

    def cb(hmon, hdc, rect, _):
        info = MONITORINFO()
        info.cbSize = ctypes.sizeof(info)
        user32.GetMonitorInfoW(hmon, ctypes.byref(info))
        mons.append((bool(info.dwFlags & 1), info.rcWork))
        return True

    user32.EnumDisplayMonitors(None, None, MONITORENUMPROC(cb), 0)
    return mons


def move_window(wid, to_tablet):
    h = _hwnd(wid)
    if not h:
        return
    target = [r for primary, r in _monitors() if primary != to_tablet]
    if not target:
        return
    r = target[0]
    user32.ShowWindow(h, 9)
    user32.SetWindowPos(h, None, r.left, r.top, r.right - r.left, r.bottom - r.top, 0x0004 | 0x0040)
    user32.ShowWindow(h, 3)  # SW_MAXIMIZE
    user32.SetForegroundWindow(h)


# ---------------------------------------------------------------- notes, Terminal Pad, files

def load_notes():
    try:
        with open(NOTES_FILE, encoding="utf-8") as f:
            notes = json.load(f)
        return notes if isinstance(notes, list) else []
    except (OSError, ValueError):
        return []


def save_notes(notes):
    clean = []
    for n in notes[:200]:
        if isinstance(n, dict):
            clean.append({"id": str(n.get("id", ""))[:40], "text": str(n.get("text", ""))[:5000],
                          "color": str(n.get("color", "yellow"))[:16]})
    os.makedirs(CONF_DIR, exist_ok=True)
    tmp = NOTES_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(clean, f, ensure_ascii=False, indent=1)
    os.replace(tmp, NOTES_FILE)
    try:
        os.makedirs(os.path.dirname(NOTES_MD), exist_ok=True)
        with open(NOTES_MD, "w", encoding="utf-8") as f:
            f.write("# Fedora Pad Notes\n\n" + "\n\n---\n\n".join(n["text"] for n in clean) + "\n")
    except OSError:
        pass


DEFAULT_COMMANDS = [
    {"name": "System info", "icon": "\U0001F4BB",
     "cmd": "Get-ComputerInfo -Property OsName,OsVersion,CsModel,CsTotalPhysicalMemory | Format-List"},
    {"name": "Disk space", "icon": "\U0001F4BE",
     "cmd": "Get-PSDrive -PSProvider FileSystem | Format-Table Name,"
            "@{n='Used GB';e={[math]::Round($_.Used/1GB)}},@{n='Free GB';e={[math]::Round($_.Free/1GB)}}"},
    {"name": "Busiest programs", "icon": "\U0001F525",
     "cmd": "Get-Process | Sort-Object CPU -Descending | Select-Object -First 12 Name,"
            "@{n='CPU s';e={[math]::Round($_.CPU)}},@{n='MB';e={[math]::Round($_.WS/1MB)}} | Format-Table"},
    {"name": "Network address", "icon": "\U0001F310",
     "cmd": "Get-NetIPAddress -AddressFamily IPv4 | Where-Object IPAddress -ne '127.0.0.1' | "
            "Format-Table InterfaceAlias,IPAddress"},
    {"name": "Update apps (winget)", "icon": "\U0001F4E6",
     "cmd": "winget upgrade --all --silent --accept-source-agreements --accept-package-agreements"},
    {"name": "Update Fedora Pad", "icon": "\U0001F504", "cmd": "git -C '" + REPO + "' pull --ff-only"},
]


def load_commands():
    try:
        with open(COMMANDS_FILE, encoding="utf-8") as f:
            cmds = json.load(f)
        if isinstance(cmds, list):
            return [c for c in cmds if isinstance(c, dict) and c.get("name") and c.get("cmd")]
    except (OSError, ValueError):
        pass
    os.makedirs(CONF_DIR, exist_ok=True)
    with open(COMMANDS_FILE, "w", encoding="utf-8") as f:
        json.dump(DEFAULT_COMMANDS, f, ensure_ascii=False, indent=1)
    return DEFAULT_COMMANDS


_term = {"proc": None}


def run_terminal(name, reply):
    """Runs one saved PowerShell command and streams its output to the tablet."""
    cmd = next((c["cmd"] for c in load_commands() if c["name"] == name), None)
    if cmd is None or (_term["proc"] and _term["proc"].poll() is None):
        reply({"t": "termout", "line": "Another command is still running." if cmd else "Unknown command."})
        reply({"t": "termdone", "code": -1})
        return

    def worker():
        try:
            proc = subprocess.Popen(
                ["powershell", "-NoProfile", "-Command",
                 "[Console]::OutputEncoding=[Text.Encoding]::UTF8;" + cmd],
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                text=True, encoding="utf-8", errors="replace", creationflags=NO_WINDOW)
        except OSError as err:
            reply({"t": "termout", "line": str(err)})
            reply({"t": "termdone", "code": -1})
            return
        _term["proc"] = proc
        for line in proc.stdout:
            reply({"t": "termout", "line": line.rstrip("\r\n")[:2000]})
        reply({"t": "termdone", "code": proc.wait()})

    threading.Thread(target=worker, daemon=True).start()


def stop_terminal():
    proc = _term["proc"]
    if proc and proc.poll() is None:
        _run(["taskkill", "/T", "/F", "/PID", str(proc.pid)])


INBOX = os.path.join(HOME, "Downloads", "Fedora Pad")
_transfers = {}


def receive_file(msg, reply):
    tid = str(msg.get("id", ""))[:64]
    if not tid:
        return
    if tid not in _transfers:
        name = os.path.basename(str(msg.get("name", "file")).replace("\\", "/")).strip().lstrip(".") or "file"
        name = "".join(c for c in name if c not in '<>:"|?*')[:120] or "file"
        os.makedirs(INBOX, exist_ok=True)
        base, ext = os.path.splitext(name)
        path, n = os.path.join(INBOX, name), 1
        while os.path.exists(path):
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


# ---------------------------------------------------------------- ambient light, keyboard language

def ambient_colors():
    if not WINDOWS:
        return {"t": "ambient", "colors": []}
    w, h = user32.GetSystemMetrics(0), user32.GetSystemMetrics(1)  # primary screen
    dc = user32.GetDC(None)
    colors = []
    try:
        for row in range(3):
            for col in range(3):
                c = gdi32.GetPixel(dc, int(w * (col + 0.5) / 3), int(h * (row + 0.5) / 3))
                colors.append([c & 0xFF, (c >> 8) & 0xFF, (c >> 16) & 0xFF] if c != 0xFFFFFFFF else [0, 0, 0])
    finally:
        user32.ReleaseDC(None, dc)
    return {"t": "ambient", "colors": colors}


def set_keyboard_language(lang):
    """Persian keys on the tablet: switch the focused app to Persian ('fa') or English."""
    if not WINDOWS:
        return
    klid = "00000429" if lang == "fa" else "00000409"
    user32.LoadKeyboardLayoutW.restype = ctypes.c_void_p
    hkl = user32.LoadKeyboardLayoutW(klid, 1)  # KLF_ACTIVATE
    if hkl:
        user32.PostMessageW(user32.GetForegroundWindow(), 0x0050, 0, ctypes.c_void_p(hkl))  # WM_INPUTLANGCHANGEREQUEST


# ---------------------------------------------------------------- the protocol

def handle(inj, msg, reply=None):
    t = msg.get("t")
    if t == "move":
        inj.move(float(msg.get("dx", 0)), float(msg.get("dy", 0)))
    elif t == "btn":
        inj.button(str(msg.get("b", "left")), str(msg.get("s", "click")))
    elif t == "scroll":
        inj.scroll(float(msg.get("dx", 0)), float(msg.get("dy", 0)))
    elif t == "combo":
        inj.combo(str(msg.get("keys", "")))
    elif t == "key":
        inj.key(str(msg.get("k", "")), str(msg.get("s", "click")))
    elif t == "text":
        inj.text(str(msg.get("s", "")))
    elif t == "cmd":
        run_command(inj, str(msg.get("name", "")))
    elif t == "set":
        threading.Thread(target=set_value, args=(inj, str(msg.get("what", "")), msg.get("value", 0)),
                         daemon=True).start()
    elif t == "state?" and reply:
        threading.Thread(target=lambda: reply(read_state()), daemon=True).start()
    elif t == "file":
        receive_file(msg, reply)
    elif t == "media?" and reply:
        reply(media_state())
    elif t == "mediactl":
        keys = {"play-pause": "playpause", "next": "next", "previous": "prev"}
        if msg.get("a") in keys:
            inj.combo(keys[msg["a"]])
    elif t == "stats?" and reply:
        reply(stats())
    elif t == "clip?" and reply:
        reply({"t": "clip", "s": get_clipboard()})
    elif t == "setclip":
        set_clipboard(str(msg.get("s", "")))
    elif t == "apps?" and reply:
        threading.Thread(target=lambda: reply({"t": "apps", "list": favourite_apps()}), daemon=True).start()
    elif t == "allapps?" and reply:
        threading.Thread(target=lambda: reply({"t": "allapps", "list": all_apps()}), daemon=True).start()
    elif t == "launch":
        threading.Thread(target=launch_app, args=(str(msg.get("id", "")),), daemon=True).start()
    elif t == "url":
        open_url(str(msg.get("u", "")))
    elif t == "windows?" and reply:
        reply(windows_list())
    elif t == "winfocus":
        focus_window(inj, str(msg.get("id", "")))
    elif t == "winclose":
        close_window(str(msg.get("id", "")))
    elif t in ("wintablet", "winlaptop"):
        move_window(str(msg.get("id", "")), t == "wintablet")
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
    elif t == "ambient?" and reply:
        reply(ambient_colors())
    elif t == "lang" and msg.get("id") in ("fa", "en"):
        set_keyboard_language(msg["id"])
    # "mode", "cursor", "ping": nothing to do on Windows (spacedesk shows the screen)


class Clients:
    def __init__(self):
        self.lock = threading.Lock()
        self.files = set()
        self.last_app = ""

    def broadcast(self, msg):
        data = (json.dumps(msg) + "\n").encode()
        with self.lock:
            for f in list(self.files):
                try:
                    f.write(data)
                    f.flush()
                except (OSError, ValueError):
                    self.files.discard(f)


def watch_focus(clients):
    """Tells the tablet which app is in front (for the app-specific shortcuts)."""
    while WINDOWS:
        try:
            name = app_name(user32.GetForegroundWindow())
        except OSError:
            name = ""
        if name and name != clients.last_app:
            clients.last_app = name
            clients.broadcast({"t": "app", "name": name})
        time.sleep(0.7)


def make_handler(inj, clients):
    class Handler(socketserver.StreamRequestHandler):
        def handle(self):
            self.request.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            inj.release_all()
            print("tablet connected", flush=True)

            def reply(obj):
                with clients.lock:
                    try:
                        self.wfile.write((json.dumps(obj) + "\n").encode())
                        self.wfile.flush()
                    except (OSError, ValueError):
                        pass

            reply({"t": "hello", "host": socket.gethostname(), "session": "windows", "os": "windows",
                   "user": os.environ.get("USERNAME", "")})
            if clients.last_app:
                reply({"t": "app", "name": clients.last_app})
            with clients.lock:
                clients.files.add(self.wfile)
            try:
                for line in self.rfile:
                    try:
                        handle(inj, json.loads(line), reply)
                    except (ValueError, TypeError, OSError) as err:
                        print("bad message:", err, file=sys.stderr)
            except OSError:
                pass
            finally:
                with clients.lock:
                    clients.files.discard(self.wfile)
                inj.release_all()
                print("tablet disconnected", flush=True)

    return Handler


# ---------------------------------------------------------------- phone / iPad web app

WEB_FLAG = os.path.join(CONF_DIR, "web")


def _lan_ips():
    ips = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith(("127.", "169.254.")):
                ips.add(ip)
    except OSError:
        pass
    return sorted(ips)


def _openssl():
    found = shutil.which("openssl")
    if found:
        return found
    for base in (os.environ.get("ProgramFiles", r"C:\Program Files"), os.environ.get("LOCALAPPDATA", "")):
        for p in (os.path.join(base, "Git", "usr", "bin", "openssl.exe"),
                  os.path.join(base, "Programs", "Git", "usr", "bin", "openssl.exe")):
            if os.path.exists(p):
                return p
    return "openssl"


def _message(title, text):
    """A box on the PC's screen (for the pairing code)."""
    script = ("Add-Type -AssemblyName PresentationFramework;"
              "[System.Windows.MessageBox]::Show(" + json.dumps(text) + "," + json.dumps(title) + ")")
    threading.Thread(target=_ps, args=(script, 130), daemon=True).start()


def start_web_app(inj, clients):
    sys.path.insert(0, os.path.join(REPO, "host"))
    try:
        import webapp
    except ImportError as err:
        print("web app unavailable:", err, file=sys.stderr)
        return

    def hello():
        return {"t": "hello", "host": socket.gethostname(), "session": "windows", "os": "windows",
                "user": os.environ.get("USERNAME", "")}

    def opened(ws):
        with clients.lock:
            clients.files.add(ws)
        if clients.last_app:
            ws.send({"t": "app", "name": clients.last_app})

    def closed(ws):
        with clients.lock:
            clients.files.discard(ws)

    webapp.WebApp(CONF_DIR, lambda msg, reply: handle(inj, msg, reply), hello, opened, closed,
                  _message, _lan_ips, _openssl()).start()


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = False  # on Windows "reuse" would let two copies share the port
    daemon_threads = True


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--port", type=int, default=7777)
    ap.add_argument("--dry-run", action="store_true", help="print events instead of sending them")
    args = ap.parse_args()
    if WINDOWS:
        try:  # exact pointer and screen positions on high-DPI screens
            ctypes.WinDLL("shcore").SetProcessDpiAwareness(2)
        except (OSError, AttributeError):
            pass
    inj = Injector(dry_run=args.dry_run)
    clients = Clients()
    threading.Thread(target=watch_focus, args=(clients,), daemon=True).start()
    if os.path.exists(WEB_FLAG):
        start_web_app(inj, clients)
    try:
        srv = Server(("127.0.0.1", args.port), make_handler(inj, clients))
    except OSError:
        sys.exit("Fedora Pad is already running (port %d is in use)." % args.port)
    with srv:
        print(f"Fedora Pad host for Windows listening on 127.0.0.1:{args.port}", flush=True)
        srv.serve_forever()


if __name__ == "__main__":
    main()
