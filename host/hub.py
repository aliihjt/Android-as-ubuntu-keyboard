"""The PC as a hub: Fedora Pad devices talk to each other through it.

Shared by the Fedora host and the Windows host. Every connected device (the
Android tablet over the cable or Wi-Fi, an iPhone or iPad web app) joins with
its reply function; the hub then

  - lists the devices                 {"t":"devices?"}  ->  {"t":"devices","list":[...],"me":id}
  - passes messages between them      {"t":"relay","to":id|"*","m":{...}}
                                      the target gets m plus "from" (name) and "fromid"
  - keeps one clipboard for all       {"t":"uclip","s":text}: the PC and every other device
                                      get it; text copied on the PC goes to the devices too
  - keeps data the devices share      {"t":"share","key":k,"value":v} / {"t":"shared?"}
                                      (reading progress, highlights, words), saved on the PC
  - mirrors the tablet's notifications {"t":"notif",...} from the tablet goes to the other
                                      devices and the PC's screen; {"t":"notifs?"} lists them
  - keeps notes the same everywhere   a device's {"t":"setnotes"} goes to the others too
  - tells devices about the PC        event(): download finished, battery low, long task done

A device says who it is with {"t":"iam","name":"Ali's iPad","kind":"ipad"}.
Only devices already allowed to control the PC (cable, paired Wi-Fi, paired
web app) get here, so the hub adds no new way in.
"""
import collections
import json
import os
import secrets
import threading
import time

# what one device may send another through the hub
RELAY_TYPES = {"pushtext", "pushfile", "remote"}
SHARED_KEYS = {"reading", "highlights", "words", "lastread"}
MAX_SHARED = 2 * 1024 * 1024
KINDS = {"android", "ipad", "iphone", "phone", "browser"}


class Hub:
    def __init__(self, conf_dir, pc_name, set_clip=None, latest_clip=None, notify=None):
        self.lock = threading.RLock()
        self.devices = {}        # reply -> {"id", "name", "kind", "since"}
        self.pc_name = pc_name
        self.set_clip = set_clip            # (text): put text on the PC's clipboard
        self.latest_clip = latest_clip      # (): newest text copied on the PC, or None
        self.notify = notify                # (title, text): a notification on the PC's screen
        self.notifs = collections.deque(maxlen=50)  # the tablet's latest notifications
        self.file = os.path.join(conf_dir, "shared.json")
        self.settings_file = os.path.join(conf_dir, "hub.json")
        self.clip = ""                      # the last text that went round
        try:
            with open(self.file, encoding="utf-8") as f:
                self.shared = json.load(f)
            if not isinstance(self.shared, dict):
                self.shared = {}
        except (OSError, ValueError):
            self.shared = {}
        try:
            with open(self.settings_file, encoding="utf-8") as f:
                self.uclip_on = bool(json.load(f).get("uclip", True))
        except (OSError, ValueError):
            self.uclip_on = True

    # ---- who is here

    def join(self, reply, name="Device", kind="browser"):
        with self.lock:
            self.devices[reply] = {"id": secrets.token_hex(4), "name": name, "kind": kind, "since": time.time()}
        self._announce()

    def leave(self, reply):
        with self.lock:
            gone = self.devices.pop(reply, None)
        if gone:
            self._announce()

    def _list(self):
        with self.lock:
            return [{"id": d["id"], "name": d["name"], "kind": d["kind"]} for d in self.devices.values()]

    def _announce(self):
        """Every device gets the new list (with its own id), so Devices pages stay current."""
        lst = self._list()
        for reply, d in self._snapshot():
            self._send(reply, {"t": "devices", "list": lst, "me": d["id"], "pc": self.pc_name, "uclip": self.uclip_on})

    def _snapshot(self):
        with self.lock:
            return list(self.devices.items())

    @staticmethod
    def _send(reply, msg):
        try:
            reply(msg)
        except Exception:  # noqa: BLE001 - a device that went away mustn't stop the others
            pass

    # ---- messages

    def handle(self, msg, reply):
        """True when the message was for the hub (the host then ignores it)."""
        t = msg.get("t")
        if t == "ping":  # devices check the connection is alive (a sleeping PC or lost Wi-Fi shows at once)
            self._send(reply, {"t": "pong", "id": msg.get("id")})
            return True
        if t == "iam":
            with self.lock:
                d = self.devices.get(reply)
                if d is None:
                    return True
                d["name"] = str(msg.get("name") or d["name"])[:40]
                if msg.get("kind") in KINDS:
                    d["kind"] = msg["kind"]
            self._announce()
            return True
        if t == "devices?":
            with self.lock:
                d = self.devices.get(reply)
            if d:
                self._send(reply, {"t": "devices", "list": self._list(), "me": d["id"], "pc": self.pc_name,
                                   "uclip": self.uclip_on})
            return True
        if t == "relay":
            self._relay(msg, reply)
            return True
        if t == "uclip":
            self.copy(str(msg.get("s", ""))[:20000], reply)
            return True
        if t == "uclipset":
            self.uclip_on = bool(msg.get("on"))
            try:
                with open(self.settings_file, "w", encoding="utf-8") as f:
                    json.dump({"uclip": self.uclip_on}, f)
            except OSError:
                pass
            self._announce()
            return True
        if t == "share":
            self._share(msg, reply)
            return True
        if t == "notif":
            self._notif(msg, reply)
            return True
        if t == "notifgone":
            key = str(msg.get("key", ""))
            with self.lock:
                for n in list(self.notifs):
                    if n["key"] == key:
                        self.notifs.remove(n)
            self._others(reply, {"t": "notifgone", "key": key})
            return True
        if t == "notifs?":
            with self.lock:
                lst = list(self.notifs)
            self._send(reply, {"t": "notifs", "list": lst[::-1]})
            return True
        if t == "setnotes" and isinstance(msg.get("list"), list):
            self._others(reply, {"t": "notes", "list": msg["list"]})
            return False  # the host saves them
        if t == "shared?":
            with self.lock:
                data = dict(self.shared)
            self._send(reply, {"t": "shared", "all": data})
            return True
        return False

    def _relay(self, msg, reply):
        m = msg.get("m")
        if not isinstance(m, dict) or m.get("t") not in RELAY_TYPES:
            return
        with self.lock:
            sender = self.devices.get(reply)
            if not sender:
                return
            targets = [r for r, d in self.devices.items()
                       if r != reply and (msg.get("to") in ("*", d["id"]))]
        out = dict(m, **{"from": sender["name"], "fromid": sender["id"]})
        for r in targets:
            self._send(r, out)
        if not targets and (m.get("t") != "pushfile" or m.get("last")):  # once per file, not per piece
            self._send(reply, {"t": "relayfail", "to": msg.get("to")})

    def _others(self, reply, msg):
        for r, _ in self._snapshot():
            if r != reply:
                self._send(r, msg)

    # ---- the tablet's notifications on the other devices and the PC

    def _notif(self, msg, reply):
        with self.lock:
            sender = self.devices.get(reply)
            if not sender:
                return
            n = {"key": str(msg.get("key", ""))[:200], "app": str(msg.get("app", ""))[:60],
                 "title": str(msg.get("title", ""))[:200], "text": str(msg.get("text", ""))[:1000],
                 "reply": bool(msg.get("reply")), "time": int(msg.get("time") or time.time() * 1000),
                 "from": sender["name"], "fromid": sender["id"]}
            for old in list(self.notifs):  # an update of the same notification replaces it
                if old["key"] == n["key"]:
                    self.notifs.remove(old)
            self.notifs.append(n)
        self._others(reply, dict(n, t="notif"))
        if self.notify:
            try:
                self.notify(n["app"] + " · " + sender["name"], (n["title"] + "\n" + n["text"]).strip())
            except Exception:  # noqa: BLE001
                pass

    def event(self, icon, title, text=""):
        """Something on the PC every device should know: download finished, battery low..."""
        for r, _ in self._snapshot():
            self._send(r, {"t": "pcevent", "icon": icon, "title": title, "text": text, "pc": self.pc_name})

    # ---- one clipboard for every device

    def copy(self, text, reply=None, source=None):
        """Text copied on a device (reply) or on the PC (reply None) goes everywhere else."""
        if not self.uclip_on or not text.strip() or text == self.clip:
            return
        self.clip = text
        with self.lock:
            sender = self.devices.get(reply) if reply else None
        name = sender["name"] if sender else (source or self.pc_name)
        if reply is not None and self.set_clip:
            try:
                self.set_clip(text)
            except Exception:  # noqa: BLE001
                pass
        for r, _ in self._snapshot():
            if r != reply:
                self._send(r, {"t": "uclip", "s": text, "from": name})

    def watch_pc_clipboard(self, every=1.5):
        """Sends what is copied on the PC to the devices (needs latest_clip)."""
        if not self.latest_clip:
            return

        def loop():
            seen = None
            while True:
                time.sleep(every)
                if not self.uclip_on or not self.devices:
                    continue
                try:
                    text = self.latest_clip()
                except Exception:  # noqa: BLE001
                    text = None
                if text is None or text == seen:
                    continue
                first = seen is None
                seen = text
                if not first and len(text) <= 20000:  # not what was there before the hub started
                    self.copy(text)
        threading.Thread(target=loop, daemon=True).start()

    # ---- shared data

    def _share(self, msg, reply):
        key, value = msg.get("key"), msg.get("value")
        if key not in SHARED_KEYS or len(json.dumps(value)) > MAX_SHARED:
            return
        with self.lock:
            sender = self.devices.get(reply)
            self.shared[key] = {"value": value, "from": sender["name"] if sender else "", "time": int(time.time() * 1000)}
            entry = self.shared[key]
            try:
                tmp = self.file + ".tmp"
                with open(tmp, "w", encoding="utf-8") as f:
                    json.dump(self.shared, f, ensure_ascii=False)
                os.replace(tmp, self.file)
            except OSError:
                pass
        for r, _ in self._snapshot():
            if r != reply:
                self._send(r, {"t": "shared", "all": {key: entry}})
