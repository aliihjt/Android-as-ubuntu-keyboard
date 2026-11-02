"""Game controllers through the tablet (Fedora / Linux).

A PS4 DualShock 4, Xbox or any other controller paired with the tablet shows
up on Fedora as a real controller: the tablet's Controller page sends its
buttons and sticks, and this module turns them into a virtual gamepad with
/dev/uinput (the same device the keyboard and trackpad already use). Steam,
SDL games, emulators and browsers see it like a controller plugged into the
laptop. Rumble from the game is sent back and shakes the controller.

Tablet -> host:
  {"t":"padadd","p":0,"kind":"xbox|ps","name":"Wireless Controller"}  plug in player p (0-3)
  {"t":"pad","p":0,"b":5,"lx":0.1,"ly":-1,"rx":0,"ry":0,"lt":0,"rt":0.5,"hx":0,"hy":1}
      b = buttons bitmask in BUTTONS order; sticks -1..1 (down / right = +), triggers 0..1, d-pad -1/0/1
  {"t":"padrm","p":0}                                                  unplug player p
Host -> tablet:
  {"t":"padok","p":0,"kind":"xbox","name":"…"}  or  {"t":"padok","p":0,"error":"…"}
  {"t":"padrumble","p":0,"s":0.8,"w":0.3,"ms":200}   strong / weak motor 0..1, ms 0 = stop
"""

import select
import threading

try:
    from evdev import AbsInfo, UInput, ecodes as e
except ImportError:  # --dry-run on machines without python3-evdev
    AbsInfo = UInput = e = None

# bit order of "b": face buttons by position (a = bottom, b = right, x = left, y = top)
BUTTONS = ["a", "b", "x", "y", "l1", "r1", "l2", "r2", "select", "start", "mode", "l3", "r3"]
MAX_PLAYERS = 4

# Each look copies what the real kernel driver reports for that controller, so
# SDL, Steam and the games' own controller lists treat it as the real thing.
PROFILES = {
    # xpad driver, wired Xbox 360 pad: understood by practically every game
    "xbox": {
        "name": "Microsoft X-Box 360 pad", "vendor": 0x045E, "product": 0x028E, "version": 0x0114,
        "buttons": {"a": "BTN_A", "b": "BTN_B", "x": "BTN_X", "y": "BTN_Y", "l1": "BTN_TL", "r1": "BTN_TR",
                    "select": "BTN_SELECT", "start": "BTN_START", "mode": "BTN_MODE",
                    "l3": "BTN_THUMBL", "r3": "BTN_THUMBR"},
        "stick": (-32768, 32767, 16, 128),
    },
    # hid-playstation driver, DualShock 4 (v2) over USB: games show ✕ ○ □ △
    "ps": {
        "name": "Sony Interactive Entertainment Wireless Controller", "vendor": 0x054C, "product": 0x09CC,
        "version": 0x8100,
        "buttons": {"a": "BTN_SOUTH", "b": "BTN_EAST", "x": "BTN_WEST", "y": "BTN_NORTH",
                    "l1": "BTN_TL", "r1": "BTN_TR", "l2": "BTN_TL2", "r2": "BTN_TR2",
                    "select": "BTN_SELECT", "start": "BTN_START", "mode": "BTN_MODE",
                    "l3": "BTN_THUMBL", "r3": "BTN_THUMBR"},
        "stick": (0, 255, 0, 0),
    },
}
STICKS = (("lx", "ABS_X"), ("ly", "ABS_Y"), ("rx", "ABS_RX"), ("ry", "ABS_RY"))
TRIGGERS = (("lt", "ABS_Z"), ("rt", "ABS_RZ"))
HATS = (("hx", "ABS_HAT0X"), ("hy", "ABS_HAT0Y"))


def _num(v, lo, hi):
    try:
        v = float(v)
    except (TypeError, ValueError):
        return 0.0
    return 0.0 if v != v else max(lo, min(hi, v))  # v != v: NaN


class Pad:
    """One virtual controller on Fedora."""

    def __init__(self, kind, player, reply, dry_run=False):
        self.kind, self.player, self.reply = kind, player, reply
        prof = PROFILES[kind]
        self.name = prof["name"]
        self.dry_run = dry_run or UInput is None
        self.lock = threading.Lock()
        self.last = {}            # evdev code -> last written value
        self.effects = {}         # rumble effect id -> (strong, weak, ms)
        self.gain = 1.0
        self.closed = False
        lo, hi, fuzz, flat = prof["stick"]
        self.stick_range = (lo, hi)
        if self.dry_run:
            self.ui = None
            self.buttons = dict(prof["buttons"])
            self.axes = {k: c for k, c in STICKS + TRIGGERS + HATS}
            print(f"pad {player}: plugged in as {self.name} (dry run)", flush=True)
            return
        self.buttons = {k: e.ecodes[c] for k, c in prof["buttons"].items()}
        self.axes = {k: e.ecodes[c] for k, c in STICKS + TRIGGERS + HATS}
        absinfo = [(e.ecodes[c], AbsInfo(value=(lo + hi + 1) // 2, min=lo, max=hi, fuzz=fuzz, flat=flat,
                                         resolution=0)) for _, c in STICKS]
        absinfo += [(e.ecodes[c], AbsInfo(value=0, min=0, max=255, fuzz=0, flat=0, resolution=0))
                    for _, c in TRIGGERS]
        absinfo += [(e.ecodes[c], AbsInfo(value=0, min=-1, max=1, fuzz=0, flat=0, resolution=0))
                    for _, c in HATS]
        caps = {e.EV_KEY: sorted(set(self.buttons.values())), e.EV_ABS: absinfo, e.EV_FF: [e.FF_RUMBLE, e.FF_GAIN]}
        try:
            self.ui = UInput(caps, name=self.name, vendor=prof["vendor"], product=prof["product"],
                             version=prof["version"], bustype=e.BUS_USB, phys=f"fedorapad/pad{player}",
                             max_effects=16)
        except TypeError:  # older python3-evdev: no max_effects (and so no rumble)
            caps.pop(e.EV_FF)
            self.ui = UInput(caps, name=self.name, vendor=prof["vendor"], product=prof["product"],
                             version=prof["version"], bustype=e.BUS_USB, phys=f"fedorapad/pad{player}")
        print(f"pad {player}: plugged in as {self.name}", flush=True)
        threading.Thread(target=self._rumble_loop, daemon=True).start()

    # ---- tablet -> game ----

    def _scale_stick(self, v):
        lo, hi = self.stick_range
        return int(round(lo + (v + 1) / 2 * (hi - lo)))

    def update(self, msg):
        try:
            bits = int(msg.get("b", 0))
        except (TypeError, ValueError):
            bits = 0
        values = {}
        for i, name in enumerate(BUTTONS):
            if name in self.buttons:
                values[self.buttons[name]] = 1 if bits >> i & 1 else 0
        for k, _ in STICKS:
            values[self.axes[k]] = self._scale_stick(_num(msg.get(k, 0), -1, 1))
        for k, _ in TRIGGERS:
            values[self.axes[k]] = int(round(_num(msg.get(k, 0), 0, 1) * 255))
        for k, _ in HATS:
            values[self.axes[k]] = int(round(_num(msg.get(k, 0), -1, 1)))
        self._write(values)

    def neutral(self):
        """Everything let go and centred (a dropped cable must not leave a button held)."""
        self.update({})

    def _write(self, values):
        with self.lock:
            if self.closed:
                return
            changed = [(c, v) for c, v in values.items() if self.last.get(c) != v]
            if not changed:
                return
            self.last.update(changed)
            if self.dry_run:
                print(f"pad {self.player}:", " ".join(f"{c}={v}" for c, v in changed), flush=True)
                return
            for code, v in changed:
                kind = e.EV_KEY if code in self.buttons.values() else e.EV_ABS
                self.ui.write(kind, code, v)
            self.ui.syn()

    # ---- game -> controller rumble ----

    def _rumble_loop(self):
        while not self.closed:
            try:
                ready, _, _ = select.select([self.ui.fd], [], [], 0.5)
                if not ready:
                    continue
                for ev in self.ui.read():
                    self._ff_event(ev)
            except (OSError, ValueError, TypeError):
                return

    def _ff_event(self, ev):
        if ev.type == e.EV_UINPUT and ev.code == e.UI_FF_UPLOAD:
            up = self.ui.begin_upload(ev.value)
            up.retval = 0
            eff = up.effect
            if eff.type == e.FF_RUMBLE:
                r = eff.u.ff_rumble_effect
                self.effects[eff.id] = (r.strong_magnitude / 65535, r.weak_magnitude / 65535, eff.ff_replay.length)
            self.ui.end_upload(up)
        elif ev.type == e.EV_UINPUT and ev.code == e.UI_FF_ERASE:
            er = self.ui.begin_erase(ev.value)
            er.retval = 0
            self.effects.pop(er.effect_id, None)
            self.ui.end_erase(er)
        elif ev.type == e.EV_FF and ev.code == e.FF_GAIN:
            self.gain = max(0.0, min(1.0, ev.value / 0xFFFF))
        elif ev.type == e.EV_FF and ev.code in self.effects:
            strong, weak, ms = self.effects[ev.code]
            if ev.value > 0 and (strong or weak):
                # length 0 = until the game stops it; the tablet caps it at 10 s
                self._send_rumble(strong * self.gain, weak * self.gain, min(ms or 10000, 10000))
            else:
                self._send_rumble(0, 0, 0)

    def _send_rumble(self, strong, weak, ms):
        try:
            self.reply({"t": "padrumble", "p": self.player, "s": round(strong, 3), "w": round(weak, 3), "ms": ms})
        except Exception:  # noqa: BLE001 - the tablet went away; rumble is optional
            pass

    def close(self):
        self.neutral()
        with self.lock:
            self.closed = True
            if self.ui is not None:
                try:
                    self.ui.close()
                except OSError:
                    pass
        print(f"pad {self.player}: unplugged", flush=True)


class Gamepads:
    """Every connected tablet's controllers. owner = that connection's reply function."""

    def __init__(self, dry_run=False):
        self.dry_run = dry_run
        self.lock = threading.Lock()
        self.pads = {}  # (owner, player) -> Pad

    def message(self, owner, msg, reply):
        t = msg.get("t")
        try:
            player = int(msg.get("p", 0))
        except (TypeError, ValueError):
            return
        if not 0 <= player < MAX_PLAYERS:
            return
        key = (owner, player)
        if t == "pad":
            pad = self.pads.get(key)
            if pad:
                pad.update(msg)
        elif t == "padadd":
            kind = "ps" if msg.get("kind") == "ps" else "xbox"
            with self.lock:
                old = self.pads.pop(key, None)
            if old and old.kind == kind:  # same look again (e.g. reconnect): keep the device
                with self.lock:
                    self.pads[key] = old
                old.reply = reply
                old.neutral()
                reply({"t": "padok", "p": player, "kind": kind, "name": old.name})
                return
            if old:
                old.close()
            try:
                pad = Pad(kind, player, reply, self.dry_run)
            except (OSError, PermissionError) as err:
                reply({"t": "padok", "p": player, "error": f"Cannot create the controller ({err}). "
                                                        "Run host/setup.sh once and log out/in."})
                return
            with self.lock:
                self.pads[key] = pad
            reply({"t": "padok", "p": player, "kind": kind, "name": pad.name})
        elif t == "padrm":
            with self.lock:
                pad = self.pads.pop(key, None)
            if pad:
                pad.close()

    def drop(self, owner):
        """The tablet disconnected: unplug its controllers."""
        with self.lock:
            gone = [k for k in self.pads if k[0] is owner]
            pads = [self.pads.pop(k) for k in gone]
        for pad in pads:
            pad.close()
