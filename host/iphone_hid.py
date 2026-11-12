#!/usr/bin/env python3
"""Control the iPhone from the laptop (Fedora): the laptop becomes a Bluetooth
keyboard and mouse for the paired iPhone, like plugging a keyboard into it.

Super+Shift+I on the laptop hands the laptop's mouse, touchpad and keyboard to
the iPhone (through host/universal.py, which grabs them); Ctrl+Alt+Esc gives
them back. The iPhone shows a round pointer (iOS AssistiveTouch) and types
what you type. Watch its screen on the laptop with AirPlay (host/mirror-setup.sh).

How: a Bluetooth Low Energy "HID over GATT" device, the standard every iPhone
accepts for keyboards and mice, made with BlueZ's D-Bus API (no root needed).
Only a paired, encrypted device can receive keys (BlueZ "encrypt-notify"), pairing is
confirmed on both screens, and the laptop only advertises while pairing or controlling.

  python3 host/iphone_hid.py --pair     pairing window (3 minutes), from iphone-setup.sh
  python3 host/iphone_hid.py --toggle   start / stop controlling (the Super+Shift+I shortcut)
"""
import json
import os
import sys
import threading
import time

try:
    from gi.repository import Gio, GLib
except ImportError:
    Gio = GLib = None

CONF = os.path.expanduser("~/.config/tabletdesk/iphone.json")
APP = "/io/github/aliihjt/fedorapad/hid"
ADV = APP + "/advertisement"
NAME = "Fedora Pad"

# ---------------------------------------------------------------- HID reports (no Bluetooth: testable)

# report 1: keyboard (8 modifier bits, 6 keys, 5 LEDs back); report 2: mouse (3 buttons,
# x, y, wheel, horizontal pan); report 3: media keys (consumer control) and Home
REPORT_MAP = bytes([
    0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x01,
    0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
    0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
    0x95, 0x05, 0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x91, 0x02,
    0x95, 0x01, 0x75, 0x03, 0x91, 0x01,
    0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
    0xC0,
    0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, 0x02, 0x09, 0x01, 0xA1, 0x00,
    0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
    0x95, 0x01, 0x75, 0x05, 0x81, 0x01,
    0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
    0x05, 0x0C, 0x0A, 0x38, 0x02, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06,
    0xC0, 0xC0,
    0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, 0x03, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08,
    0x09, 0xB5, 0x09, 0xB6, 0x09, 0xCD, 0x09, 0xE9, 0x09, 0xEA, 0x09, 0xE2, 0x0A, 0x23, 0x02, 0x0A, 0x21, 0x02,
    0x81, 0x02, 0xC0,
])

MODIFIERS = {"KEY_LEFTCTRL": 0x01, "KEY_LEFTSHIFT": 0x02, "KEY_LEFTALT": 0x04, "KEY_LEFTMETA": 0x08,
             "KEY_RIGHTCTRL": 0x10, "KEY_RIGHTSHIFT": 0x20, "KEY_RIGHTALT": 0x40, "KEY_RIGHTMETA": 0x80}
# media keys -> bit in report 3: next, previous, play/pause, volume up, down, mute, Home, Spotlight
MEDIA = {"KEY_NEXTSONG": 0, "KEY_PREVIOUSSONG": 1, "KEY_PLAYPAUSE": 2, "KEY_VOLUMEUP": 3,
         "KEY_VOLUMEDOWN": 4, "KEY_MUTE": 5, "KEY_HOMEPAGE": 6, "KEY_SEARCH": 7}


def _hid_keys():
    keys = {}
    for i, c in enumerate("ABCDEFGHIJKLMNOPQRSTUVWXYZ"):
        keys["KEY_" + c] = 0x04 + i
    for i, c in enumerate("1234567890"):
        keys["KEY_" + c] = 0x1E + i
    for i in range(12):
        keys["KEY_F%d" % (i + 1)] = 0x3A + i
    keys.update({
        "KEY_ENTER": 0x28, "KEY_ESC": 0x29, "KEY_BACKSPACE": 0x2A, "KEY_TAB": 0x2B, "KEY_SPACE": 0x2C,
        "KEY_MINUS": 0x2D, "KEY_EQUAL": 0x2E, "KEY_LEFTBRACE": 0x2F, "KEY_RIGHTBRACE": 0x30,
        "KEY_BACKSLASH": 0x31, "KEY_SEMICOLON": 0x33, "KEY_APOSTROPHE": 0x34, "KEY_GRAVE": 0x35,
        "KEY_COMMA": 0x36, "KEY_DOT": 0x37, "KEY_SLASH": 0x38, "KEY_CAPSLOCK": 0x39,
        "KEY_INSERT": 0x49, "KEY_HOME": 0x4A, "KEY_PAGEUP": 0x4B, "KEY_DELETE": 0x4C, "KEY_END": 0x4D,
        "KEY_PAGEDOWN": 0x4E, "KEY_RIGHT": 0x4F, "KEY_LEFT": 0x50, "KEY_DOWN": 0x51, "KEY_UP": 0x52,
        "KEY_102ND": 0x64, "KEY_KPENTER": 0x58,
    })
    return keys


HID_KEYS = _hid_keys()
WHEEL_PX = 40   # scrolling: laptop pixels per wheel step on the iPhone


def _s8(v):
    return max(-127, min(127, int(v))) & 0xFF


class Reports:
    """The laptop's keys and pointer -> HID reports [(report id, bytes)], like a real keyboard + mouse."""

    def __init__(self):
        self.mods = 0
        self.keys = []      # up to 6 keys held
        self.buttons = 0
        self.media = 0
        self.frac = [0.0, 0.0]
        self.scroll = [0.0, 0.0]

    def keyboard(self):
        return 1, bytes([self.mods, 0] + (self.keys + [0] * 6)[:6])

    def key(self, name, value):
        if name in MODIFIERS:
            bit = MODIFIERS[name]
            self.mods = self.mods | bit if value else self.mods & ~bit
            return [self.keyboard()]
        if name in MEDIA:
            if value == 2:
                return []
            bit = 1 << MEDIA[name]
            self.media = self.media | bit if value else self.media & ~bit
            return [(3, bytes([self.media]))]
        code = HID_KEYS.get(name)
        if code is None or value == 2:  # the iPhone repeats a held key itself
            return []
        if value and code not in self.keys and len(self.keys) < 6:
            self.keys.append(code)
        elif not value and code in self.keys:
            self.keys.remove(code)
        else:
            return []
        return [self.keyboard()]

    def mouse(self, dx=0, dy=0, wheel=0, pan=0):
        return 2, bytes([self.buttons, _s8(dx), _s8(dy), _s8(wheel), _s8(pan)])

    def pointer(self, msg):
        """A message from host/universal.py's Touchpad / Mouse: move, button or scroll."""
        a = msg.get("a")
        if a == "button":
            bit = {"left": 1, "right": 2, "middle": 4}.get(msg.get("b"), 0)
            self.buttons = self.buttons | bit if msg.get("v") else self.buttons & ~bit
            return [self.mouse()]
        if a == "move":
            self.frac[0] += float(msg.get("dx") or 0)
            self.frac[1] += float(msg.get("dy") or 0)
            out = []
            while abs(self.frac[0]) >= 1 or abs(self.frac[1]) >= 1:  # big moves: several reports
                dx = max(-127, min(127, int(self.frac[0])))
                dy = max(-127, min(127, int(self.frac[1])))
                self.frac[0] -= dx
                self.frac[1] -= dy
                out.append(self.mouse(dx, dy))
            return out
        if a == "scroll":  # + dy = the page moves down = wheel up
            self.scroll[0] += float(msg.get("dx") or 0) / WHEEL_PX
            self.scroll[1] += float(msg.get("dy") or 0) / WHEEL_PX
            wheel, pan = int(self.scroll[1]), int(self.scroll[0])
            if not wheel and not pan:
                return []
            self.scroll[0] -= pan
            self.scroll[1] -= wheel
            return [self.mouse(wheel=wheel, pan=-pan)]
        return []

    def release(self):
        """Everything up: nothing stays held on the iPhone when control ends."""
        self.mods, self.keys, self.buttons, self.media = 0, [], 0, 0
        self.frac, self.scroll = [0.0, 0.0], [0.0, 0.0]
        return [self.keyboard(), self.mouse(), (3, bytes([0]))]


# ---------------------------------------------------------------- the Bluetooth device (BlueZ GATT server)

def load(path=CONF):
    try:
        with open(path, encoding="utf-8") as f:
            c = json.load(f)
        return c if isinstance(c, dict) else {}
    except (OSError, ValueError):
        return {}


def _uuid(short):
    return "0000%04x-0000-1000-8000-00805f9b34fb" % short


class _Char:
    def __init__(self, path, uuid, flags, value=b"", service=None, report=None):
        self.path, self.uuid, self.flags, self.value = path, uuid, flags, bytes(value)
        self.service, self.report = service, report  # report: (id, 1 input / 2 output)
        self.descriptors = []
        self.notifying = False


class IPhone:
    """A Bluetooth keyboard + mouse for the paired iPhone. Thread-safe: key() and pointer() are
    called from host/universal.py's input thread; Bluetooth runs in its own thread."""

    def __init__(self, conf_path=CONF, on_status=None):
        self.conf_path = conf_path
        self.conf = load(conf_path)
        self.on_status = on_status       # (text): a message for the laptop's screen
        self.reports = Reports()
        self.lock = threading.Lock()
        self.bus = None
        self.ctx = None
        self.adapter = None
        self.chars = {}                  # path -> _Char
        self.inputs = {}                 # report id -> _Char
        self.services = []               # (path, uuid)
        self.advertising = False
        self.app_on = False              # the keyboard + mouse registered with BlueZ (only while needed)
        self.controlling = False
        self.pairing_until = 0.0
        self.ready = threading.Event()

    # ---- the outside: what host/universal.py calls

    def key(self, name, value):
        with self.lock:
            out = self.reports.key(name, value)
        self._notify(out)

    def pointer(self, msg):
        with self.lock:
            out = self.reports.pointer(msg)
        self._notify(out)

    def release(self):
        """Control ended: let go of every key and button, then stop being a keyboard (the
        iPhone hides its own keyboard while one is connected) and stop advertising."""
        self.controlling = False
        with self.lock:
            out = self.reports.release()
        self._notify(out)
        self._after(1500, self._off)

    def begin(self):
        """Control starts: be a keyboard + mouse and advertise, so the paired iPhone reconnects at once."""
        self.controlling = True
        self._later(lambda: (self._app(True), self._advertise(True)))

    def _off(self):
        if not self.controlling and time.monotonic() >= self.pairing_until:
            self._app(False)
            self._advertise(False)
        return False

    def _app(self, on):
        """Shows (or hides) the keyboard + mouse to the iPhone. Not blocking: BlueZ reads the
        app back (GetManagedObjects) before it answers, and this thread serves those reads."""
        if on == self.app_on or not self.adapter:
            return
        self.app_on = on

        def done(bus, res):
            try:
                bus.call_finish(res)
            except GLib.Error as err:
                self.app_on = not on
                print("iPhone control: Bluetooth refused:", err.message, file=sys.stderr)
        method = "RegisterApplication" if on else "UnregisterApplication"
        args = GLib.Variant("(oa{sv})", (APP, {})) if on else GLib.Variant("(o)", (APP,))
        self.bus.call("org.bluez", self.adapter, "org.bluez.GattManager1", method,
                      args, None, Gio.DBusCallFlags.NONE, 10000, None, done)

    def connected(self):
        return any(c.notifying for c in self.inputs.values())

    # ---- Bluetooth

    def start(self):
        if Gio is None:
            print("iPhone control: python3-gobject is missing", file=sys.stderr)
            return False
        threading.Thread(target=self._run, daemon=True).start()
        return self.ready.wait(10)

    def _later(self, fn):
        if self.ctx is not None:
            self.ctx.invoke_full(GLib.PRIORITY_DEFAULT, lambda: (fn(), False)[1])

    def _after(self, ms, fn):
        """fn() once, ms from now, in the Bluetooth thread."""
        if self.ctx is not None:
            src = GLib.timeout_source_new(int(ms))
            src.set_callback(lambda *a: (fn(), False)[1])
            src.attach(self.ctx)

    def _run(self):
        self.ctx = GLib.MainContext()
        self.ctx.push_thread_default()
        try:
            self.bus = Gio.bus_get_sync(Gio.BusType.SYSTEM, None)
            self.adapter = self._find_adapter()
            if not self.adapter:
                print("iPhone control: no Bluetooth adapter", file=sys.stderr)
                return
            self._build()
            self._export()
        except GLib.Error as err:
            print("iPhone control: Bluetooth refused:", err.message, file=sys.stderr)
            return
        self.ready.set()
        GLib.MainLoop(self.ctx).run()

    def _find_adapter(self):
        res = self.bus.call_sync("org.bluez", "/", "org.freedesktop.DBus.ObjectManager", "GetManagedObjects",
                                 None, GLib.VariantType("(a{oa{sa{sv}}})"), Gio.DBusCallFlags.NONE, 5000, None)
        for path, ifaces in res.unpack()[0].items():
            if "org.bluez.GattManager1" in ifaces and "org.bluez.LEAdvertisingManager1" in ifaces:
                return path
        return None

    def _build(self):
        """Device Information, Battery and the HID service, as a keyboard + mouse has them."""
        def service(n, uuid):
            path = f"{APP}/service{n}"
            self.services.append((path, _uuid(uuid)))
            return path

        def char(svc, n, uuid, flags, value=b"", report=None):
            c = _Char(f"{svc}/char{n}", _uuid(uuid), flags, value, svc, report)
            self.chars[c.path] = c
            if report:
                d = _Char(c.path + "/desc0", _uuid(0x2908), ["read"], bytes(report), c.path)
                c.descriptors.append(d)
                self.chars[d.path] = d
                if report[1] == 1:
                    self.inputs[report[0]] = c
            return c

        info = service(0, 0x180A)
        char(info, 0, 0x2A50, ["read"], bytes([0x02, 0x09, 0x12, 0x1E, 0x7A, 0x00, 0x01]))  # PnP ID
        char(info, 1, 0x2A29, ["read"], NAME.encode())                                          # maker
        bat = service(1, 0x180F)
        char(bat, 0, 0x2A19, ["read", "notify"], bytes([100]))
        hid = service(2, 0x1812)
        char(hid, 0, 0x2A4A, ["read"], bytes([0x11, 0x01, 0x00, 0x02]))                        # HID info
        char(hid, 1, 0x2A4B, ["encrypt-read"], REPORT_MAP)                                     # report map
        char(hid, 2, 0x2A4E, ["read", "write-without-response"], bytes([0x01]))                # report mode
        char(hid, 3, 0x2A4C, ["write-without-response"], bytes([0x00]))                        # control point
        char(hid, 4, 0x2A4D, ["encrypt-read", "encrypt-notify"], bytes(8), report=(1, 1))              # keyboard
        char(hid, 5, 0x2A4D, ["encrypt-read", "write", "write-without-response", "encrypt-write"],
             bytes(1), report=(1, 2))                                                           # LEDs
        char(hid, 6, 0x2A4D, ["encrypt-read", "encrypt-notify"], bytes(5), report=(2, 1))              # mouse
        char(hid, 7, 0x2A4D, ["encrypt-read", "encrypt-notify"], bytes(1), report=(3, 1))              # media keys

    # ---- D-Bus objects BlueZ reads

    XML = """<node>
      <interface name="org.freedesktop.DBus.ObjectManager">
        <method name="GetManagedObjects"><arg type="a{oa{sa{sv}}}" direction="out"/></method>
      </interface>
      <interface name="org.bluez.GattService1">
        <property name="UUID" type="s" access="read"/><property name="Primary" type="b" access="read"/>
      </interface>
      <interface name="org.bluez.GattCharacteristic1">
        <method name="ReadValue"><arg type="a{sv}" direction="in"/><arg type="ay" direction="out"/></method>
        <method name="WriteValue"><arg type="ay" direction="in"/><arg type="a{sv}" direction="in"/></method>
        <method name="StartNotify"/><method name="StopNotify"/>
        <property name="UUID" type="s" access="read"/><property name="Service" type="o" access="read"/>
        <property name="Flags" type="as" access="read"/><property name="Value" type="ay" access="read"/>
      </interface>
      <interface name="org.bluez.GattDescriptor1">
        <method name="ReadValue"><arg type="a{sv}" direction="in"/><arg type="ay" direction="out"/></method>
        <property name="UUID" type="s" access="read"/><property name="Characteristic" type="o" access="read"/>
        <property name="Flags" type="as" access="read"/>
      </interface>
      <interface name="org.bluez.LEAdvertisement1">
        <method name="Release"/>
        <property name="Type" type="s" access="read"/><property name="ServiceUUIDs" type="as" access="read"/>
        <property name="Appearance" type="q" access="read"/><property name="LocalName" type="s" access="read"/>
        <property name="Discoverable" type="b" access="read"/>
      </interface>
    </node>"""

    def _props(self, path, iface):
        if iface == "org.bluez.GattService1":
            uuid = dict(self.services)[path]
            return {"UUID": GLib.Variant("s", uuid), "Primary": GLib.Variant("b", True)}
        c = self.chars[path]
        if iface == "org.bluez.GattCharacteristic1":
            return {"UUID": GLib.Variant("s", c.uuid), "Service": GLib.Variant("o", c.service),
                    "Flags": GLib.Variant("as", c.flags), "Value": GLib.Variant("ay", c.value)}
        return {"UUID": GLib.Variant("s", c.uuid), "Characteristic": GLib.Variant("o", c.service),
                "Flags": GLib.Variant("as", c.flags)}

    def _adv_props(self):
        return {"Type": GLib.Variant("s", "peripheral"), "ServiceUUIDs": GLib.Variant("as", [_uuid(0x1812)]),
                "Appearance": GLib.Variant("q", 0x03C0), "LocalName": GLib.Variant("s", NAME),
                "Discoverable": GLib.Variant("b", True)}

    def managed_objects(self):
        objs = {}
        for path, _ in self.services:
            objs[path] = {"org.bluez.GattService1": self._props(path, "org.bluez.GattService1")}
        for path, c in self.chars.items():
            iface = "org.bluez.GattDescriptor1" if c.uuid == _uuid(0x2908) else "org.bluez.GattCharacteristic1"
            objs[path] = {iface: self._props(path, iface)}
        return objs

    def _export(self):
        node = Gio.DBusNodeInfo.new_for_xml(self.XML)
        ifaces = {i.name: i for i in node.interfaces}
        self.bus.register_object(APP, ifaces["org.freedesktop.DBus.ObjectManager"], self._call, None, None)
        for path, _ in self.services:
            self.bus.register_object(path, ifaces["org.bluez.GattService1"], self._call, self._get, None)
        for path, c in self.chars.items():
            name = "org.bluez.GattDescriptor1" if c.uuid == _uuid(0x2908) else "org.bluez.GattCharacteristic1"
            self.bus.register_object(path, ifaces[name], self._call, self._get, None)
        self.bus.register_object(ADV, ifaces["org.bluez.LEAdvertisement1"], self._call, self._get, None)

    def _get(self, conn, sender, path, iface, prop):
        props = self._adv_props() if path == ADV else self._props(path, iface)
        return props.get(prop)

    def allowed(self, options):
        """Only the paired iPhone (any device during the pairing window)."""
        device = options.get("device", "") if isinstance(options, dict) else ""
        if time.monotonic() < self.pairing_until:
            return True
        mac = (self.conf.get("device") or "").upper().replace(":", "_")
        return bool(mac) and str(device).upper().endswith("DEV_" + mac)

    def _remember(self, device_path):
        """The first device that pairs is the iPhone; the pairing window closes at once."""
        mac = device_path.rsplit("dev_", 1)[-1].replace("_", ":")
        self.pairing_until = 0.0
        self._after(15000, self._off)  # paired: keyboard off soon
        if self.conf.get("device") != mac:
            self.conf = {"device": mac}
            try:
                os.makedirs(os.path.dirname(self.conf_path), exist_ok=True)
                with open(self.conf_path, "w", encoding="utf-8") as f:
                    json.dump(self.conf, f)
            except OSError:
                pass
            print("iPhone control: paired with", mac, flush=True)

    def _call(self, conn, sender, path, iface, method, params, inv):
        if method == "GetManagedObjects":
            return inv.return_value(GLib.Variant("(a{oa{sa{sv}}})", (self.managed_objects(),)))
        if method == "Release":
            self.advertising = False
            return inv.return_value(None)
        c = self.chars.get(path)
        if c is None:
            return inv.return_dbus_error("org.bluez.Error.Failed", "unknown")
        args = params.unpack()
        if method == "ReadValue":
            opts = args[0] if isinstance(args[0], dict) else {}
            # reading an encrypted value means the link is paired: that device is the iPhone
            if time.monotonic() < self.pairing_until and opts.get("device") and any("encrypt" in f for f in c.flags):
                self._remember(opts["device"])
            if c.report and not self.allowed(opts):
                return inv.return_dbus_error("org.bluez.Error.NotAuthorized", "not the paired iPhone")
            offset = int(args[0].get("offset", 0)) if isinstance(args[0], dict) else 0
            return inv.return_value(GLib.Variant("(ay)", (c.value[offset:],)))
        if method == "WriteValue":
            c.value = bytes(args[0])  # LEDs (caps lock), report mode: kept, nothing to do
            return inv.return_value(None)
        if method == "StartNotify":
            c.notifying = True
            if c.report and self.on_status:
                self.on_status("iPhone connected")
            return inv.return_value(None)
        if method == "StopNotify":
            c.notifying = False
            return inv.return_value(None)
        return inv.return_dbus_error("org.bluez.Error.NotSupported", method)

    def _notify(self, reports):
        """Sends reports to the iPhone: a changed Value on a notifying characteristic."""
        if self.bus is None:
            return
        for rid, data in reports:
            c = self.inputs.get(rid)
            if c is None:
                continue
            c.value = data
            if c.notifying:
                try:
                    self.bus.emit_signal(None, c.path, "org.freedesktop.DBus.Properties", "PropertiesChanged",
                                         GLib.Variant("(sa{sv}as)", ("org.bluez.GattCharacteristic1",
                                                                     {"Value": GLib.Variant("ay", data)}, [])))
                except GLib.Error:
                    pass

    def _advertise(self, on):
        if on == self.advertising or not self.adapter:
            return
        method = "RegisterAdvertisement" if on else "UnregisterAdvertisement"
        args = GLib.Variant("(oa{sv})", (ADV, {})) if on else GLib.Variant("(o)", (ADV,))
        self.advertising = on

        def done(bus, res):  # not blocking: BlueZ reads the advertisement's properties first
            try:
                bus.call_finish(res)
            except GLib.Error as err:
                self.advertising = not on
                print("iPhone control: advertising:", err.message, file=sys.stderr)
        self.bus.call("org.bluez", self.adapter, "org.bluez.LEAdvertisingManager1", method,
                      args, None, Gio.DBusCallFlags.NONE, 5000, None, done)

    def pair(self, seconds=180):
        """The pairing window: any iPhone may pair and is remembered as *the* iPhone."""
        self.pairing_until = time.monotonic() + seconds
        self.conf = {}  # a new pairing replaces the old iPhone
        self._later(lambda: (self._app(True), self._advertise(True)))
        self._after((seconds + 2) * 1000, self._off)


def toggle(what="toggle"):
    """For the Super+Shift+I shortcut: asks Fedora Pad (running on this laptop) to start or stop."""
    import urllib.error
    import urllib.request
    req = urllib.request.Request("http://127.0.0.1:7783/iphone", method="POST", data=what.encode(),
                                 headers={"X-Fedora-Pad": "1", "Content-Type": "text/plain"})
    try:
        return urllib.request.urlopen(req, timeout=5).read().decode()
    except (urllib.error.URLError, OSError) as err:
        return "Fedora Pad isn't running (" + str(err) + ")"


def main():
    if "--toggle" in sys.argv:
        print(toggle())
    elif "--pair" in sys.argv:
        print(toggle("pair"))
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
