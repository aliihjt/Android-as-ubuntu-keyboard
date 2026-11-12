"""The iPhone in the laptop's ecosystem, over Bluetooth (Fedora), like a Mac or a watch:

  notifications  every iPhone notification (Telegram, WhatsApp, SMS, calls) shows on the
                 laptop and the tablet: Apple Notification Center Service (ANCS)
  music          the iPhone is a music player in GNOME's top bar (MPRIS "iPhone"): play,
                 pause, next, previous, title and artist, from the laptop, its media keys or
                 the tablet's Media page: Apple Media Service (AMS)
  battery        the iPhone's battery in the top bar and the Batteries widget (Battery Service)

It uses the pairing made by host/iphone-setup.sh. The laptop advertises "Fedora Pad" with
Apple's notification and media services requested, the iPhone connects, and on the first
time asks "Allow Fedora Pad to receive notifications?". Nothing is sent to the iPhone but
the requests the services need.
"""
import struct
import sys
import threading

try:
    from gi.repository import Gio, GLib
except ImportError:
    Gio = GLib = None

ANCS = "7905f431-b5ce-4e99-a40f-4b1e122d00d0"
ANCS_NS = "9fbf120d-6301-42d9-8c58-25e699a21dbd"     # Notification Source (notify)
ANCS_CP = "69d1d8f3-45e1-49a8-9821-9bbdfdaad9d9"     # Control Point (write)
ANCS_DS = "22eac6e9-24d6-4bb5-be44-b36ace7c7bfb"     # Data Source (notify)
AMS = "89d3502b-0f36-433a-8ef4-c502ad55f8dc"
AMS_RC = "9b3c81d8-57b1-4a8a-b8df-0e56f7ca51c2"      # Remote Command (write)
AMS_EU = "2f7cabce-808d-411f-9a0c-bb92ba96c102"      # Entity Update (write, notify)
BATTERY_LEVEL = "00002a19-0000-1000-8000-00805f9b34fb"
ADV = "/io/github/aliihjt/fedorapad/ecosystem/advertisement"

# ---------------------------------------------------------------- ANCS (no Bluetooth here: testable)

CATEGORIES = {0: "", 1: "📞 Incoming call", 2: "📞 Missed call", 3: "Voicemail", 4: "", 5: "📅",
              6: "✉️", 7: "", 8: "", 9: "", 10: "", 11: ""}
APPS = {"ph.telegra.Telegraph": "Telegram", "net.whatsapp.WhatsApp": "WhatsApp",
        "com.apple.MobileSMS": "Messages", "com.apple.mobilephone": "Phone", "com.apple.mobilemail": "Mail",
        "com.apple.mobilecal": "Calendar", "com.apple.reminders": "Reminders", "com.burbn.instagram": "Instagram",
        "com.google.Gmail": "Gmail", "com.apple.facetime": "FaceTime", "org.whispersystems.signal": "Signal",
        "com.hammerandchisel.discord": "Discord", "com.atebits.Tweetie2": "X", "ir.eitaa.messenger": "Eitaa",
        "ir.bale.app": "Bale", "ir.rubika.rubika": "Rubika", "com.soroush.app": "Soroush"}
ATTR_APP, ATTR_TITLE, ATTR_SUBTITLE, ATTR_MESSAGE = 0, 1, 2, 3


def parse_source(data):
    """A Notification Source event -> {"event": 0 added / 1 modified / 2 removed, "uid", "category",
    "silent", "existing"}; "existing" marks notifications that were there before connecting."""
    data = bytes(data)
    if len(data) < 8:
        return None
    event, flags, category, _count, uid = struct.unpack("<BBBBI", data[:8])
    return {"event": event, "uid": uid, "category": category,
            "silent": bool(flags & 1), "existing": bool(flags & 4)}


def ask_attributes(uid):
    """Control Point command: the app, title, subtitle and message of a notification."""
    return bytes([0]) + struct.pack("<I", uid) + bytes([ATTR_APP]) + bytes([ATTR_TITLE]) + struct.pack("<H", 120) \
        + bytes([ATTR_SUBTITLE]) + struct.pack("<H", 120) + bytes([ATTR_MESSAGE]) + struct.pack("<H", 400)


class DataSource:
    """Collects Data Source pieces (a reply can come in several) into whole replies."""

    WANTED = (ATTR_APP, ATTR_TITLE, ATTR_SUBTITLE, ATTR_MESSAGE)

    def __init__(self):
        self.buf = b""

    def feed(self, data):
        """Returns (uid, {attribute: text}) when a whole reply has arrived, else None."""
        self.buf += bytes(data)
        if len(self.buf) < 5:
            return None
        if self.buf[0] != 0:  # not a notification reply: drop it
            self.buf = b""
            return None
        uid = struct.unpack("<I", self.buf[1:5])[0]
        pos, attrs = 5, {}
        for _ in self.WANTED:
            if len(self.buf) < pos + 3:
                return None  # more pieces to come
            aid, size = self.buf[pos], struct.unpack("<H", self.buf[pos + 1:pos + 3])[0]
            if len(self.buf) < pos + 3 + size:
                return None
            attrs[aid] = self.buf[pos + 3:pos + 3 + size].decode("utf-8", "replace")
            pos += 3 + size
        self.buf = self.buf[pos:]
        return uid, attrs


def notification_text(attrs, category):
    """(app, title, text) for the laptop and the tablet."""
    app_id = attrs.get(ATTR_APP, "")
    app = APPS.get(app_id) or (app_id.rsplit(".", 1)[-1].capitalize() if app_id else "iPhone")
    title = attrs.get(ATTR_TITLE, "")
    sub = attrs.get(ATTR_SUBTITLE, "")
    if sub:
        title = f"{title} · {sub}" if title else sub
    lead = CATEGORIES.get(category, "")
    if category in (1, 2):  # calls: the caller's name is the title
        title = f"{lead}: {title}" if title else lead
    return app, title, attrs.get(ATTR_MESSAGE, "")


# ---------------------------------------------------------------- AMS (music)

PLAYER, QUEUE, TRACK = 0, 1, 2
COMMANDS = {"play": 0, "pause": 1, "play-pause": 2, "next": 3, "previous": 4, "volume-up": 5, "volume-down": 6}


def parse_entity(data):
    """An Entity Update -> (entity, attribute, text)."""
    data = bytes(data)
    if len(data) < 3:
        return None
    return data[0], data[1], data[3:].decode("utf-8", "replace")


class Music:
    """What the iPhone plays, from AMS updates: name of the player, playing or not, title, artist."""

    def __init__(self):
        self.player = ""
        self.playing = False
        self.title = ""
        self.artist = ""
        self.album = ""

    def update(self, entity, attr, text):
        if entity == PLAYER and attr == 0:
            self.player = text
        elif entity == PLAYER and attr == 1:  # "state,rate,elapsed": state 1 = playing
            self.playing = text.split(",")[0] == "1"
        elif entity == TRACK:
            if attr == 0:
                self.artist = text
            elif attr == 1:
                self.album = text
            elif attr == 2:
                self.title = text


# ---------------------------------------------------------------- MPRIS: the iPhone as a player on the laptop

MPRIS_XML = """<node>
  <interface name="org.mpris.MediaPlayer2">
    <method name="Raise"/><method name="Quit"/>
    <property name="CanQuit" type="b" access="read"/><property name="CanRaise" type="b" access="read"/>
    <property name="HasTrackList" type="b" access="read"/><property name="Identity" type="s" access="read"/>
    <property name="SupportedUriSchemes" type="as" access="read"/>
    <property name="SupportedMimeTypes" type="as" access="read"/>
  </interface>
  <interface name="org.mpris.MediaPlayer2.Player">
    <method name="Next"/><method name="Previous"/><method name="Pause"/><method name="PlayPause"/>
    <method name="Stop"/><method name="Play"/>
    <method name="Seek"><arg type="x" direction="in"/></method>
    <method name="SetPosition"><arg type="o" direction="in"/><arg type="x" direction="in"/></method>
    <method name="OpenUri"><arg type="s" direction="in"/></method>
    <property name="PlaybackStatus" type="s" access="read"/><property name="Metadata" type="a{sv}" access="read"/>
    <property name="CanGoNext" type="b" access="read"/><property name="CanGoPrevious" type="b" access="read"/>
    <property name="CanPlay" type="b" access="read"/><property name="CanPause" type="b" access="read"/>
    <property name="CanSeek" type="b" access="read"/><property name="CanControl" type="b" access="read"/>
    <property name="Rate" type="d" access="read"/><property name="Volume" type="d" access="read"/>
    <property name="Position" type="x" access="read"/>
  </interface>
</node>"""


def mpris_props(music):
    meta = {"mpris:trackid": GLib.Variant("o", "/io/github/aliihjt/fedorapad/iphone/track"),
            "xesam:title": GLib.Variant("s", music.title or "iPhone"),
            "xesam:artist": GLib.Variant("as", [music.artist] if music.artist else []),
            "xesam:album": GLib.Variant("s", music.album)}
    return {
        "PlaybackStatus": GLib.Variant("s", "Playing" if music.playing else "Paused"),
        "Metadata": GLib.Variant("a{sv}", meta),
        "CanGoNext": GLib.Variant("b", True), "CanGoPrevious": GLib.Variant("b", True),
        "CanPlay": GLib.Variant("b", True), "CanPause": GLib.Variant("b", True),
        "CanSeek": GLib.Variant("b", False), "CanControl": GLib.Variant("b", True),
        "Rate": GLib.Variant("d", 1.0), "Volume": GLib.Variant("d", 1.0), "Position": GLib.Variant("x", 0),
    }


# ---------------------------------------------------------------- the connection

class Ecosystem:
    """Keeps the laptop advertising for the paired iPhone and talks to its Apple services when it connects."""

    def __init__(self, device, on_notification=None, on_battery=None):
        self.mac = (device or "").upper()
        self.on_notification = on_notification   # (app, title, text)
        self.on_battery = on_battery             # (level or None when gone)
        self.ctx = self.bus = self.session = None
        self.adapter = self.dev = None
        self.chars = {}                          # uuid -> characteristic path
        self.subs = []
        self.ds = DataSource()
        self.pending = {}                        # uid -> category, while its text is asked for
        self.music = Music()
        self.mpris_id = None
        self.mpris_name = None
        self.ready = False

    def start(self):
        if Gio is None or not self.mac:
            return
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        self.ctx = GLib.MainContext()
        self.ctx.push_thread_default()
        try:
            self.bus = Gio.bus_get_sync(Gio.BusType.SYSTEM, None)
            self.session = Gio.bus_get_sync(Gio.BusType.SESSION, None)
        except GLib.Error:
            return
        self.bus.signal_subscribe("org.bluez", "org.freedesktop.DBus.Properties", "PropertiesChanged", None, None,
                                  Gio.DBusSignalFlags.NONE, self._changed)
        self.bus.signal_subscribe("org.bluez", "org.freedesktop.DBus.ObjectManager", "InterfacesAdded", "/", None,
                                  Gio.DBusSignalFlags.NONE, lambda *a: self._later(self._look))
        self._advertise()
        self._look()
        src = GLib.timeout_source_new_seconds(30)  # Bluetooth turned off and on, adapter back: try again
        src.set_callback(lambda *a: (self._advertise(), self._look(), True)[-1])
        src.attach(self.ctx)
        GLib.MainLoop(self.ctx).run()

    def _later(self, fn):
        src = GLib.idle_source_new()
        src.set_callback(lambda *a: (fn(), False)[1])
        src.attach(self.ctx)
        return False

    def _objects(self):
        try:
            res = self.bus.call_sync("org.bluez", "/", "org.freedesktop.DBus.ObjectManager", "GetManagedObjects",
                                     None, GLib.VariantType("(a{oa{sa{sv}}})"), Gio.DBusCallFlags.NONE, 5000, None)
            return res.unpack()[0]
        except GLib.Error:
            return {}

    def _advertise(self):
        """'Fedora Pad', asking for Apple's notification and media services: the paired iPhone connects."""
        if self.adapter:
            return
        objs = self._objects()
        self.adapter = next((p for p, i in objs.items() if "org.bluez.LEAdvertisingManager1" in i), None)
        if not self.adapter:
            return
        node = Gio.DBusNodeInfo.new_for_xml("""<node><interface name="org.bluez.LEAdvertisement1">
          <method name="Release"/><property name="Type" type="s" access="read"/>
          <property name="SolicitUUIDs" type="as" access="read"/><property name="LocalName" type="s" access="read"/>
          </interface></node>""")
        props = {"Type": GLib.Variant("s", "peripheral"), "SolicitUUIDs": GLib.Variant("as", [ANCS, AMS]),
                 "LocalName": GLib.Variant("s", "Fedora Pad")}
        try:
            self.bus.register_object(ADV, node.interfaces[0],
                                     lambda c, s, p, i, m, a, inv: inv.return_value(None),
                                     lambda c, s, p, i, prop: props.get(prop), None)
        except GLib.Error:
            pass  # registered before (Bluetooth came back)

        def done(bus, res):
            try:
                bus.call_finish(res)
            except GLib.Error as err:
                self.adapter = None
                print("iPhone: advertising:", err.message, file=sys.stderr)
        self.bus.call("org.bluez", self.adapter, "org.bluez.LEAdvertisingManager1", "RegisterAdvertisement",
                      GLib.Variant("(oa{sv})", (ADV, {})), None, Gio.DBusCallFlags.NONE, 5000, None, done)

    def _look(self):
        """Finds the paired iPhone's services once it is connected and they are resolved."""
        objs = self._objects()
        dev = next((p for p, i in objs.items() if str(i.get("org.bluez.Device1", {}).get("Address", "")).upper()
                    == self.mac), None)
        if not dev or not objs[dev]["org.bluez.Device1"].get("ServicesResolved"):
            if self.ready:
                self._gone()
            return False
        chars = {}
        for path, ifaces in objs.items():
            ch = ifaces.get("org.bluez.GattCharacteristic1")
            if ch and path.startswith(dev + "/"):
                chars[str(ch.get("UUID", "")).lower()] = path
        if self.ready and chars == self.chars:
            return False
        self.dev, self.chars, self.ready = dev, chars, True
        print("iPhone: connected (" + ", ".join(n for n, u in (("notifications", ANCS_NS), ("music", AMS_EU),
                                                               ("battery", BATTERY_LEVEL)) if u in chars) + ")",
              flush=True)
        if ANCS_DS in chars and ANCS_NS in chars:  # Data Source on first, then Notification Source
            self._call(chars[ANCS_DS], "StartNotify", None,
                       lambda out: self._call(chars[ANCS_NS], "StartNotify", None))
        if AMS_EU in chars:  # updates on first, then: tell me the player, play state, artist, album and title
            def ask(out):
                self._write(chars[AMS_EU], bytes([PLAYER, 0, 1]))
                self._write(chars[AMS_EU], bytes([TRACK, 0, 1, 2]))
                self._mpris(True)
            self._call(chars[AMS_EU], "StartNotify", None, ask)
        if BATTERY_LEVEL in chars:
            self._call(chars[BATTERY_LEVEL], "StartNotify", None)
            self._call(chars[BATTERY_LEVEL], "ReadValue", GLib.Variant("(a{sv})", ({},)), self._battery_read)
        return False

    def _gone(self):
        self.ready, self.chars, self.pending = False, {}, {}
        self.ds = DataSource()
        self._mpris(False)
        if self.on_battery:
            self.on_battery(None)
        print("iPhone: disconnected", flush=True)

    def _call(self, path, method, args, then=None):
        def done(bus, res):
            try:
                out = bus.call_finish(res)
                if then:
                    then(out)
            except GLib.Error as err:
                if "NotPermitted" not in err.message:  # "already notifying" etc. are fine
                    print("iPhone:", method, err.message, file=sys.stderr)
        self.bus.call("org.bluez", path, "org.bluez.GattCharacteristic1", method, args, None,
                      Gio.DBusCallFlags.NONE, 10000, None, done)

    def _write(self, path, data):
        self._call(path, "WriteValue", GLib.Variant("(aya{sv})", (data, {})))

    def _battery_read(self, out):
        value = out.unpack()[0]
        if value and self.on_battery:
            self.on_battery(int(value[0]))

    def _changed(self, conn, sender, path, iface, signal, params):
        iface_name, changed, _ = params.unpack()
        if iface_name == "org.bluez.Device1" and ("ServicesResolved" in changed or "Connected" in changed):
            self._later(self._look)
            return
        if iface_name != "org.bluez.GattCharacteristic1" or "Value" not in changed or not self.ready:
            return
        value = bytes(changed["Value"])
        if path == self.chars.get(ANCS_NS):
            ev = parse_source(value)
            # new ones only: not those already on the iPhone when it connected, not silent ones
            if ev and ev["event"] == 0 and not ev["existing"] and not ev["silent"] and ANCS_CP in self.chars:
                self.pending[ev["uid"]] = ev["category"]
                self._write(self.chars[ANCS_CP], ask_attributes(ev["uid"]))
        elif path == self.chars.get(ANCS_DS):
            done = self.ds.feed(value)
            if done:
                uid, attrs = done
                category = self.pending.pop(uid, 0)
                if self.on_notification:
                    try:
                        self.on_notification(*notification_text(attrs, category))
                    except Exception as err:  # noqa: BLE001
                        print("iPhone notification:", err, file=sys.stderr)
        elif path == self.chars.get(AMS_EU):
            ent = parse_entity(value)
            if ent:
                self.music.update(*ent)
                self._mpris_changed()
        elif path == self.chars.get(BATTERY_LEVEL) and value and self.on_battery:
            self.on_battery(int(value[0]))

    # ---- the iPhone as a music player on the laptop (MPRIS)

    def command(self, name):
        """play / pause / play-pause / next / previous / volume-up / volume-down on the iPhone."""
        if name in COMMANDS and AMS_RC in self.chars:
            self._later(lambda: self._write(self.chars[AMS_RC], bytes([COMMANDS[name]])))

    def _mpris(self, on):
        if on and self.mpris_id is None:
            node = Gio.DBusNodeInfo.new_for_xml(MPRIS_XML)
            path = "/org/mpris/MediaPlayer2"
            ids = []
            for iface in node.interfaces:
                ids.append(self.session.register_object(path, iface, self._mpris_call, self._mpris_get, None))
            self.mpris_id = ids
            self.mpris_name = Gio.bus_own_name_on_connection(self.session, "org.mpris.MediaPlayer2.iphone",
                                                             Gio.BusNameOwnerFlags.NONE, None, None)
        elif not on and self.mpris_id is not None:
            Gio.bus_unown_name(self.mpris_name)
            for i in self.mpris_id:
                self.session.unregister_object(i)
            self.mpris_id = self.mpris_name = None

    def _mpris_get(self, conn, sender, path, iface, prop):
        if iface == "org.mpris.MediaPlayer2":
            return {"CanQuit": GLib.Variant("b", False), "CanRaise": GLib.Variant("b", False),
                    "HasTrackList": GLib.Variant("b", False), "Identity": GLib.Variant("s", "iPhone"),
                    "SupportedUriSchemes": GLib.Variant("as", []),
                    "SupportedMimeTypes": GLib.Variant("as", [])}.get(prop)
        return mpris_props(self.music).get(prop)

    def _mpris_call(self, conn, sender, path, iface, method, params, inv):
        name = {"Play": "play", "Pause": "pause", "PlayPause": "play-pause", "Stop": "pause",
                "Next": "next", "Previous": "previous"}.get(method)
        if name:
            self.command(name)
        inv.return_value(None)

    def _mpris_changed(self):
        if self.mpris_id is None:
            return
        props = mpris_props(self.music)
        try:
            self.session.emit_signal(None, "/org/mpris/MediaPlayer2", "org.freedesktop.DBus.Properties",
                                     "PropertiesChanged", GLib.Variant("(sa{sv}as)", (
                                         "org.mpris.MediaPlayer2.Player",
                                         {"PlaybackStatus": props["PlaybackStatus"], "Metadata": props["Metadata"]},
                                         [])))
        except GLib.Error:
            pass

