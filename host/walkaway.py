#!/usr/bin/env python3
"""Lock the laptop when your iPhone walks away (Fedora, Bluetooth).

The iPhone, once paired with the laptop (GNOME Settings › Bluetooth), keeps
sending small Bluetooth signals. BlueZ recognises them as that iPhone, with
their strength (RSSI). When none comes strong enough for a while, the laptop
locks. It never unlocks by itself.

Settings: ~/.config/tabletdesk/walkaway.json, made by host/walkaway-setup.sh:
  {"on": true, "device": "AA:BB:CC:DD:EE:FF", "name": "Ali's iPhone",
   "rssi": -78,        weaker than this counts as away (null: only when not seen at all)
   "after": 60}        seconds away before the laptop locks

  python3 host/walkaway.py --test AA:BB:...   shows the iPhone's signal for 30 seconds
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

CONF = os.path.expanduser("~/.config/tabletdesk/walkaway.json")
SCAN_ON, SCAN_OFF = 8, 7   # listen 8 s, rest 7 s: Bluetooth headphones keep playing smoothly


def load(path=CONF):
    try:
        with open(path, encoding="utf-8") as f:
            c = json.load(f)
        return c if isinstance(c, dict) else {}
    except (OSError, ValueError):
        return {}


class Presence:
    """Decides when to lock, from the times the iPhone was seen near (no Bluetooth here: testable)."""

    def __init__(self, threshold=None, after=60):
        self.threshold = threshold   # dBm: weaker counts as away; None = only "not seen at all"
        self.after = after
        self.near_at = None          # last time it was seen near
        self.locked = False          # locked for this absence: not again until it comes back

    def seen(self, rssi, now):
        if self.threshold is None or rssi is None or rssi >= self.threshold:
            self.near_at = now
            self.locked = False

    def should_lock(self, now):
        """True once per absence: after it was near, then away for `after` seconds."""
        if self.near_at is None or self.locked or now - self.near_at < self.after:
            return False
        self.locked = True
        return True


class Watcher:
    """BlueZ on the system bus: listens for the paired iPhone and calls lock() when it's away."""

    def __init__(self, lock, conf_path=CONF):
        self.lock, self.conf_path = lock, conf_path
        self.conf = {}
        self.presence = Presence()
        self.bus = None
        self.sub = None
        self.path = None

    def start(self):
        if Gio is None:
            return
        threading.Thread(target=self._run, daemon=True).start()

    def _reload(self):
        conf = load(self.conf_path)
        if conf != self.conf:
            self.conf = conf
            self.presence = Presence(conf.get("rssi"), int(conf.get("after") or 60))
            self._follow(conf.get("device") if conf.get("on") else None)

    def _run(self):
        ctx = GLib.MainContext()
        ctx.push_thread_default()
        try:
            self.bus = Gio.bus_get_sync(Gio.BusType.SYSTEM, None)
        except GLib.Error:
            return
        while True:
            self._reload()
            if self.path:
                self._scan(True)
                self._spin(ctx, SCAN_ON)
                self._scan(False)
                self._spin(ctx, SCAN_OFF)
                if self.presence.should_lock(time.monotonic()):
                    print("walk-away: " + self.conf.get("name", "the phone") + " is away, locking", flush=True)
                    try:
                        self.lock()
                    except Exception as err:  # noqa: BLE001
                        print("walk-away lock failed:", err, file=sys.stderr)
            else:
                self._spin(ctx, 10)

    @staticmethod
    def _spin(ctx, seconds):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            ctx.iteration(False)
            time.sleep(0.1)

    def _follow(self, mac):
        if self.sub is not None:
            self.bus.signal_unsubscribe(self.sub)
            self.sub = None
        self.path = self._device_path(mac) if mac else None
        if self.path:
            self.sub = self.bus.signal_subscribe("org.bluez", "org.freedesktop.DBus.Properties", "PropertiesChanged",
                                                 self.path, None, Gio.DBusSignalFlags.NONE, self._changed)

    def _device_path(self, mac):
        try:
            res = self.bus.call_sync("org.bluez", "/", "org.freedesktop.DBus.ObjectManager", "GetManagedObjects",
                                     None, GLib.VariantType("(a{oa{sa{sv}}})"), Gio.DBusCallFlags.NONE, 3000, None)
        except GLib.Error:
            return None
        for path, ifaces in res.unpack()[0].items():
            dev = ifaces.get("org.bluez.Device1")
            if dev and str(dev.get("Address", "")).upper() == mac.upper():
                return path
        return None

    def _adapter(self):
        return self.path.rsplit("/", 1)[0] if self.path else None

    def _scan(self, on):
        """Listen for low-energy signals (the iPhone's). Skipped while Bluetooth is off."""
        adapter = self._adapter()
        try:
            if on:
                self.bus.call_sync("org.bluez", adapter, "org.bluez.Adapter1", "SetDiscoveryFilter",
                                   GLib.Variant("(a{sv})", ({"Transport": GLib.Variant("s", "le"),
                                                             "DuplicateData": GLib.Variant("b", True)},)),
                                   None, Gio.DBusCallFlags.NONE, 3000, None)
                self.bus.call_sync("org.bluez", adapter, "org.bluez.Adapter1", "StartDiscovery",
                                   None, None, Gio.DBusCallFlags.NONE, 3000, None)
            else:
                self.bus.call_sync("org.bluez", adapter, "org.bluez.Adapter1", "StopDiscovery",
                                   None, None, Gio.DBusCallFlags.NONE, 3000, None)
        except GLib.Error:
            if on:  # Bluetooth off or busy: don't count this as "away"
                self.presence.near_at = None if self.presence.locked else time.monotonic()

    def _changed(self, conn, sender, path, iface, signal, params):
        changed = params.unpack()[1]
        if "RSSI" in changed or changed.get("Connected"):
            self.presence.seen(changed.get("RSSI"), time.monotonic())


def test(mac, seconds=30):
    """Shows the phone's signal live, for host/walkaway-setup.sh. Exit code 0 when it was seen."""
    if Gio is None:
        print("python3-gobject is missing: sudo dnf install python3-gobject")
        return 2
    seen = []
    w = Watcher(lambda: None)
    w.bus = Gio.bus_get_sync(Gio.BusType.SYSTEM, None)
    w.path = w._device_path(mac)
    if not w.path:
        print("That device isn't paired with this laptop.")
        return 1

    def changed(conn, sender, path, iface, signal, params):
        rssi = params.unpack()[1].get("RSSI")
        if rssi is not None:
            seen.append(rssi)
            print(f"  signal {rssi} dBm  " + "█" * max(1, min(30, (rssi + 100) // 2)), flush=True)
    w.bus.signal_subscribe("org.bluez", "org.freedesktop.DBus.Properties", "PropertiesChanged", w.path, None,
                           Gio.DBusSignalFlags.NONE, changed)
    w._scan(True)
    ctx = GLib.MainContext.default()
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        ctx.iteration(False)
        time.sleep(0.05)
    w._scan(False)
    if seen:
        print(f"Seen {len(seen)} times, strongest {max(seen)} dBm, weakest {min(seen)} dBm.")
    return 0 if seen else 3


if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "--test":
        sys.exit(test(sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 30))
    print(__doc__)
