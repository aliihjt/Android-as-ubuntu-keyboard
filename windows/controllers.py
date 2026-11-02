"""Steam Big Picture when a game controller connects to this Windows PC.

PS4 / PS5, Xbox, 8BitDo and other controllers on USB or Bluetooth all show up
as HID game controllers (usage page 1, usage 4 joystick or 5 gamepad). The
host checks the list every few seconds; when the first one appears it opens
steam://open/bigpicture, which also starts Steam if it isn't running.
Only Windows' own DLLs are used. Switched on and off from the tablet
(Controller page) or by creating / deleting %APPDATA%/FedoraPad/steam-bigpicture.
"""
import ctypes
import os
import sys
import threading
import time

WINDOWS = os.name == "nt"
if WINDOWS:
    from ctypes import wintypes as wt

    _hid = ctypes.WinDLL("hid")
    _setupapi = ctypes.WinDLL("setupapi", use_last_error=True)
    _kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)

    class GUID(ctypes.Structure):
        _fields_ = [("Data1", wt.DWORD), ("Data2", wt.WORD), ("Data3", wt.WORD), ("Data4", ctypes.c_ubyte * 8)]

    class SP_DEVICE_INTERFACE_DATA(ctypes.Structure):
        _fields_ = [("cbSize", wt.DWORD), ("InterfaceClassGuid", GUID), ("Flags", wt.DWORD),
                    ("Reserved", ctypes.c_size_t)]

    class HIDP_CAPS(ctypes.Structure):
        _fields_ = [("Usage", wt.USHORT), ("UsagePage", wt.USHORT), ("InputReportByteLength", wt.USHORT),
                    ("OutputReportByteLength", wt.USHORT), ("FeatureReportByteLength", wt.USHORT),
                    ("Reserved", wt.USHORT * 17)] + [(n, wt.USHORT) for n in (
                        "NumberLinkCollectionNodes", "NumberInputButtonCaps", "NumberInputValueCaps",
                        "NumberInputDataIndices", "NumberOutputButtonCaps", "NumberOutputValueCaps",
                        "NumberOutputDataIndices", "NumberFeatureButtonCaps", "NumberFeatureValueCaps",
                        "NumberFeatureDataIndices")]

    HDEVINFO = ctypes.c_void_p
    _hid.HidD_GetHidGuid.argtypes = [ctypes.POINTER(GUID)]
    _hid.HidD_GetHidGuid.restype = None
    _hid.HidD_GetPreparsedData.argtypes = [wt.HANDLE, ctypes.POINTER(ctypes.c_void_p)]
    _hid.HidD_GetPreparsedData.restype = wt.BOOLEAN
    _hid.HidD_FreePreparsedData.argtypes = [ctypes.c_void_p]
    _hid.HidD_FreePreparsedData.restype = wt.BOOLEAN
    _hid.HidP_GetCaps.argtypes = [ctypes.c_void_p, ctypes.POINTER(HIDP_CAPS)]
    _hid.HidP_GetCaps.restype = ctypes.c_long
    _hid.HidD_GetProductString.argtypes = [wt.HANDLE, ctypes.c_void_p, wt.ULONG]
    _hid.HidD_GetProductString.restype = wt.BOOLEAN
    _setupapi.SetupDiGetClassDevsW.argtypes = [ctypes.POINTER(GUID), wt.LPCWSTR, wt.HWND, wt.DWORD]
    _setupapi.SetupDiGetClassDevsW.restype = HDEVINFO
    _setupapi.SetupDiEnumDeviceInterfaces.argtypes = [HDEVINFO, ctypes.c_void_p, ctypes.POINTER(GUID), wt.DWORD,
                                                      ctypes.POINTER(SP_DEVICE_INTERFACE_DATA)]
    _setupapi.SetupDiEnumDeviceInterfaces.restype = wt.BOOL
    _setupapi.SetupDiGetDeviceInterfaceDetailW.argtypes = [HDEVINFO, ctypes.POINTER(SP_DEVICE_INTERFACE_DATA),
                                                           ctypes.c_void_p, wt.DWORD, ctypes.POINTER(wt.DWORD),
                                                           ctypes.c_void_p]
    _setupapi.SetupDiGetDeviceInterfaceDetailW.restype = wt.BOOL
    _setupapi.SetupDiDestroyDeviceInfoList.argtypes = [HDEVINFO]
    _setupapi.SetupDiDestroyDeviceInfoList.restype = wt.BOOL
    _kernel32.CreateFileW.argtypes = [wt.LPCWSTR, wt.DWORD, wt.DWORD, ctypes.c_void_p, wt.DWORD, wt.DWORD,
                                      wt.HANDLE]
    _kernel32.CreateFileW.restype = wt.HANDLE
    _kernel32.CloseHandle.argtypes = [wt.HANDLE]
    _kernel32.CloseHandle.restype = wt.BOOL

DIGCF_PRESENT, DIGCF_DEVICEINTERFACE = 0x2, 0x10
FILE_SHARE_READ_WRITE, OPEN_EXISTING = 0x3, 3
HIDP_STATUS_SUCCESS = 0x00110000
INVALID_HANDLE = ctypes.c_void_p(-1).value
GAME_USAGES = (4, 5)  # joystick, gamepad (usage page 1, generic desktop)


def _device_info(path):
    """(usage page, usage, product name) of one HID device, or None."""
    h = _kernel32.CreateFileW(path, 0, FILE_SHARE_READ_WRITE, None, OPEN_EXISTING, 0, None)
    if not h or h == INVALID_HANDLE:
        return None
    try:
        pp = ctypes.c_void_p()
        if not _hid.HidD_GetPreparsedData(h, ctypes.byref(pp)):
            return None
        try:
            caps = HIDP_CAPS()
            if _hid.HidP_GetCaps(pp, ctypes.byref(caps)) & 0xFFFFFFFF != HIDP_STATUS_SUCCESS:
                return None
        finally:
            _hid.HidD_FreePreparsedData(pp)
        name = ctypes.create_unicode_buffer(128)
        if not _hid.HidD_GetProductString(h, name, ctypes.sizeof(name)):
            name.value = ""
        return caps.UsagePage, caps.Usage, name.value or "Game controller"
    finally:
        _kernel32.CloseHandle(h)


def connected_controllers():
    """{device path: name} of the game controllers plugged in or paired now."""
    if not WINDOWS:
        return {}
    guid = GUID()
    _hid.HidD_GetHidGuid(ctypes.byref(guid))
    devs = _setupapi.SetupDiGetClassDevsW(ctypes.byref(guid), None, None, DIGCF_PRESENT | DIGCF_DEVICEINTERFACE)
    if not devs or devs == INVALID_HANDLE:
        return {}
    found = {}
    try:
        index = 0
        while True:
            ifd = SP_DEVICE_INTERFACE_DATA()
            ifd.cbSize = ctypes.sizeof(SP_DEVICE_INTERFACE_DATA)
            if not _setupapi.SetupDiEnumDeviceInterfaces(devs, None, ctypes.byref(guid), index, ctypes.byref(ifd)):
                break
            index += 1
            need = wt.DWORD()
            _setupapi.SetupDiGetDeviceInterfaceDetailW(devs, ctypes.byref(ifd), None, 0, ctypes.byref(need), None)
            if need.value < 8:
                continue
            buf = ctypes.create_string_buffer(need.value)
            # SP_DEVICE_INTERFACE_DETAIL_DATA_W.cbSize: 8 in 64-bit Python, 6 in 32-bit
            ctypes.c_uint32.from_buffer(buf).value = 8 if ctypes.sizeof(ctypes.c_void_p) == 8 else 6
            if not _setupapi.SetupDiGetDeviceInterfaceDetailW(devs, ctypes.byref(ifd), buf, need, None, None):
                continue
            path = ctypes.wstring_at(ctypes.addressof(buf) + 4)
            info = _device_info(path)
            if info and info[0] == 1 and info[1] in GAME_USAGES:
                found[path.lower()] = info[2]
    finally:
        _setupapi.SetupDiDestroyDeviceInfoList(devs)
    return found


def open_big_picture():
    try:
        os.startfile("steam://open/bigpicture")  # starts Steam too if it isn't running
        print("controller connected: opening Steam Big Picture", flush=True)
        return True
    except (OSError, AttributeError) as err:
        print("could not open Steam (is it installed?):", err, file=sys.stderr, flush=True)
        return False


class SteamWatch:
    """Opens Big Picture when the first controller connects (none -> one or more)."""

    def __init__(self, flag_file, interval=2.0, cooldown=60):
        self.flag, self.interval, self.cooldown = flag_file, interval, cooldown

    def enabled(self):
        return os.path.exists(self.flag)

    def set(self, on):
        if on:
            os.makedirs(os.path.dirname(self.flag), exist_ok=True)
            with open(self.flag, "w", encoding="utf-8") as f:
                f.write("on\n")
        elif os.path.exists(self.flag):
            os.remove(self.flag)

    def start(self):
        threading.Thread(target=self._loop, daemon=True).start()

    def _loop(self):
        if not WINDOWS:
            return
        try:
            known = set(connected_controllers())  # already connected at log-in: leave it be
        except OSError:
            known = set()
        last = float("-inf")
        while True:
            time.sleep(self.interval)
            try:
                now = connected_controllers()
            except OSError as err:
                print("controller check failed:", err, file=sys.stderr, flush=True)
                continue
            new = set(now) - known
            if new and not known & set(now) and self.enabled() and time.monotonic() - last > self.cooldown:
                print("new controller:", ", ".join(now[k] for k in new), flush=True)
                if open_big_picture():
                    last = time.monotonic()
            known = set(now)


if __name__ == "__main__":  # python controllers.py  -> lists the controllers Windows sees
    for p, n in connected_controllers().items():
        print(n, " ", p)
