"""Extras shared by the Fedora host and the Windows host.

  Outbox     AirDrop: files and links put in the outbox folder (by send_to_pad.py,
             the "Send to Fedora Pad" menu) go to every connected tablet / phone
  Sleeper    sleep timers: "sleep in 30 minutes", "every night at 23:30", with a
             minute's warning that can be cancelled
  ClipList   clipboard history kept in memory (the Windows host fills it)
  wake_info  this PC's network cards, for waking it from the tablet
"""
import glob
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import uuid

WINDOWS = os.name == "nt"
CHUNK = 48 * 1024
MAX_FILE = 2 * 1024 ** 3  # 2 GB


def data_dir():
    if WINDOWS:
        return os.path.join(os.environ.get("LOCALAPPDATA", os.path.expanduser("~")), "FedoraPad")
    return os.path.expanduser("~/.cache/fedora-pad")


def outbox_dir():
    return os.path.join(data_dir(), "outbox")


# ---------------------------------------------------------------- AirDrop

class Outbox:
    def __init__(self, broadcast, count, notify):
        self.broadcast = broadcast  # (msg) -> sent to every tablet / phone
        self.count = count          # () -> how many are connected
        self.notify = notify        # (title, text) on the PC
        os.makedirs(outbox_dir(), exist_ok=True)

    def start(self):
        threading.Thread(target=self._loop, daemon=True).start()

    def _loop(self):
        while True:
            for job in sorted(glob.glob(os.path.join(outbox_dir(), "*.json"))):
                try:
                    with open(job, encoding="utf-8") as f:
                        data = json.load(f)
                except (OSError, ValueError):
                    data = {}
                try:
                    os.remove(job)
                except OSError:
                    continue
                try:
                    self._send(data)
                except Exception as err:  # noqa: BLE001 - one bad job mustn't stop the outbox
                    print("send to tablet failed:", err, file=sys.stderr)
            time.sleep(0.5)

    def _send(self, job):
        if not self.count():
            self.notify("Fedora Pad", "No tablet or phone is connected. Open Fedora Pad on it and try again.")
            return
        if job.get("text"):
            self.broadcast({"t": "pushtext", "s": str(job["text"])[:100000]})
            self.notify("Sent to Fedora Pad", str(job["text"])[:80])
            return
        sent = []
        for path in job.get("files", [])[:50]:
            path = str(path)
            tmp = None
            if os.path.isdir(path):  # a folder goes as one zip
                tmp = tempfile.mkdtemp()
                path = shutil.make_archive(os.path.join(tmp, os.path.basename(path.rstrip("/\\")) or "folder"),
                                           "zip", path)
            try:
                if not os.path.isfile(path) or os.path.getsize(path) > MAX_FILE:
                    continue
                self._stream(path)
                sent.append(os.path.basename(path))
            finally:
                if tmp:
                    shutil.rmtree(tmp, ignore_errors=True)
        if sent:
            self.notify("Sent to Fedora Pad", ", ".join(sent)[:120])

    def _stream(self, path):
        import base64
        fid, name, size = uuid.uuid4().hex, os.path.basename(path), os.path.getsize(path)
        with open(path, "rb") as f:
            at = 0
            while True:
                data = f.read(CHUNK)
                at += len(data)
                last = at >= size
                self.broadcast({"t": "pushfile", "id": fid, "name": name, "size": size,
                                "data": base64.b64encode(data).decode(), "last": last})
                if last:
                    break


# ---------------------------------------------------------------- sleep timers

class Sleeper:
    def __init__(self, conf_dir, suspend, notify):
        self.file = os.path.join(conf_dir, "sleep.json")
        self.suspend, self.notify = suspend, notify
        self.until = 0.0          # one-off timer (epoch seconds), 0 = none
        self.warning_until = 0.0  # going to sleep at this time unless cancelled
        self.done_day = ""
        try:
            with open(self.file) as f:
                self.nightly = str(json.load(f).get("nightly", ""))
        except (OSError, ValueError):
            self.nightly = ""

    def start(self):
        threading.Thread(target=self._loop, daemon=True).start()

    def status(self):
        return {"t": "sleep", "nightly": self.nightly, "in_until": int(self.until * 1000),
                "warning_until": int(self.warning_until * 1000)}

    def sleep_in(self, minutes):
        m = max(0, min(24 * 60, int(minutes)))
        self.until = time.time() + m * 60 if m else 0.0
        if not m:
            self.warning_until = 0.0
        return self.status()

    def set_nightly(self, hhmm):
        hhmm = str(hhmm).strip()
        if hhmm and not (len(hhmm) == 5 and hhmm[2] == ":" and hhmm[:2].isdigit() and hhmm[3:].isdigit()
                         and int(hhmm[:2]) < 24 and int(hhmm[3:]) < 60):
            return self.status()
        self.nightly = hhmm
        os.makedirs(os.path.dirname(self.file), exist_ok=True)
        with open(self.file, "w") as f:
            json.dump({"nightly": hhmm}, f)
        return self.status()

    def cancel(self):
        self.until = self.warning_until = 0.0
        self.done_day = time.strftime("%Y-%m-%d")  # tonight's nightly sleep is skipped too
        return self.status()

    def _loop(self):
        while True:
            self.tick(time.time(), time.strftime("%H:%M"), time.strftime("%Y-%m-%d"))
            time.sleep(5)

    def tick(self, now, hhmm, today):
        due = self.until and now >= self.until
        if self.nightly and hhmm == self.nightly and self.done_day != today:
            due = True
        if due and not self.warning_until:
            self.warning_until = now + 60
            self.done_day = today
            self.notify("Going to sleep in 1 minute", "Cancel it in Fedora Pad › Wake & Sleep.")
        if self.warning_until and now >= self.warning_until:
            self.until = self.warning_until = 0.0
            self.suspend()


# ---------------------------------------------------------------- clipboard history (Windows)

class ClipList:
    def __init__(self, size=50):
        self.items, self.size, self.lock = [], size, threading.Lock()

    def add(self, text):
        if not text or len(text) > 20000:
            return
        with self.lock:
            self.items = [c for c in self.items if c["text"] != text]
            self.items.insert(0, {"text": text, "time": int(time.time() * 1000)})
            del self.items[self.size:]

    def clear(self):
        with self.lock:
            self.items = []

    def list(self):
        with self.lock:
            return list(self.items)


# ---------------------------------------------------------------- waking this PC

def wake_info():
    """Network cards that can wake the PC: name, MAC, Wi-Fi or not, broadcast address."""
    cards = []
    if WINDOWS:
        out = subprocess.run(["powershell", "-NoProfile", "-Command",
                              "Get-NetAdapter -Physical | Select-Object Name,MacAddress,Status,"
                              "@{n='Wifi';e={$_.PhysicalMediaType -like '*802.11*'}} | ConvertTo-Json -Compress"],
                             capture_output=True, text=True, creationflags=0x08000000).stdout
        try:
            items = json.loads(out) if out.strip() else []
        except ValueError:
            items = []
        for a in items if isinstance(items, list) else [items]:
            mac = str(a.get("MacAddress", "")).replace("-", ":").lower()
            if mac:
                cards.append({"name": a.get("Name", ""), "mac": mac, "wifi": bool(a.get("Wifi")),
                              "up": a.get("Status") == "Up", "broadcast": "255.255.255.255"})
        return cards
    for dev in sorted(glob.glob("/sys/class/net/*")):
        name = os.path.basename(dev)
        if not os.path.exists(os.path.join(dev, "device")):  # virtual: docker, bridges, VPNs...
            continue
        try:
            with open(os.path.join(dev, "address")) as f:
                mac = f.read().strip()
            with open(os.path.join(dev, "operstate")) as f:
                up = f.read().strip() == "up"
        except OSError:
            continue
        brd = ""
        try:
            out = subprocess.run(["ip", "-4", "-o", "addr", "show", name], capture_output=True, text=True).stdout
            parts = out.split()
            if "brd" in parts:
                brd = parts[parts.index("brd") + 1]
        except OSError:
            pass
        cards.append({"name": name, "mac": mac, "wifi": os.path.exists(os.path.join(dev, "wireless")),
                      "up": up, "broadcast": brd or "255.255.255.255"})
    return cards


def hostname():
    return socket.gethostname()


# ---------------------------------------------------------------- screen mirroring helpers

class Proc:
    """One helper program at a time (scrcpy, uxplay): start, stop, is it running,
    and why it stopped."""

    def __init__(self):
        self.log = None
        self.p = None
        self.error = ""
        self.lock = threading.Lock()

    def running(self):
        return self.p is not None and self.p.poll() is None

    def start(self, argv, env=None, log=None):
        self.log = log
        with self.lock:
            self._stop()
            self.error = ""
            try:
                self.p = subprocess.Popen(argv, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                          stderr=subprocess.PIPE, text=True, errors="replace",
                                          creationflags=0x08000000 if WINDOWS else 0)  # no console window
            except OSError as err:
                self.p = None
                self.error = str(err)
                return False
            p = self.p
        threading.Thread(target=self._watch, args=(p,), daemon=True).start()
        time.sleep(1.5)  # most problems show up at once
        return self.running()

    def _watch(self, p):
        _, err = p.communicate()
        if getattr(self, "log", None):  # the whole story, for when something goes wrong
            try:
                with open(self.log, "w", encoding="utf-8") as f:
                    f.write(" ".join(map(str, p.args)) + "\n\n" + (err or ""))
            except OSError:
                pass
        lines = [l for l in (err or "").splitlines() if l.strip()]
        with self.lock:
            if p is self.p and p.returncode not in (0, None):
                self.error = (lines[-1] if lines else "it closed")[-200:]

    def stop(self):
        with self.lock:
            self._stop()

    def _stop(self):
        if self.p is not None and self.p.poll() is None:
            self.p.terminate()
            try:
                self.p.wait(3)
            except subprocess.TimeoutExpired:
                self.p.kill()
        self.p = None


def airplay_pin(conf_dir):
    """A fixed 4-digit PIN the iPhone asks for the first time (then it's remembered)."""
    path = os.path.join(conf_dir, "airplay-pin")
    try:
        with open(path) as f:
            pin = f.read().strip()
        if len(pin) == 4 and pin.isdigit():
            return pin
    except OSError:
        pass
    import secrets
    pin = "%04d" % secrets.randbelow(10000)
    os.makedirs(conf_dir, exist_ok=True)
    with open(path, "w") as f:
        f.write(pin)
    return pin


def tablet_on_cable(adb):
    """True when the tablet is plugged in and allowed (adb sees it); else a reason."""
    try:
        out = subprocess.run([adb, "get-state"], capture_output=True, text=True, timeout=8,
                             creationflags=0x08000000 if WINDOWS else 0)
    except (OSError, subprocess.SubprocessError):
        return "The Android tools (adb) aren't installed."
    state = out.stdout.strip()
    if state == "device":
        return True
    if "unauthorized" in (out.stdout + out.stderr):
        return "On the tablet, tap Allow (USB debugging), then try again."
    return "Plug the tablet in with the USB cable: Show on PC works over the cable."


# ---------------------------------------------------------------- the PC tells the devices what happened

def events_dir():
    return os.path.join(data_dir(), "events")


def battery():
    """(percent, charging) of the laptop battery, or None (desktop PC, unknown)."""
    if WINDOWS:
        import ctypes

        class Status(ctypes.Structure):
            _fields_ = [("ac", ctypes.c_byte), ("flag", ctypes.c_byte), ("percent", ctypes.c_byte),
                        ("saver", ctypes.c_byte), ("life", ctypes.c_ulong), ("full", ctypes.c_ulong)]
        st = Status()
        if not ctypes.windll.kernel32.GetSystemPowerStatus(ctypes.byref(st)) or st.percent < 0 or st.flag & 128:
            return None
        return st.percent, st.ac == 1
    for bat in sorted(glob.glob("/sys/class/power_supply/BAT*")):
        try:
            with open(os.path.join(bat, "capacity")) as f:
                pct = int(f.read())
            with open(os.path.join(bat, "status")) as f:
                charging = f.read().strip() in ("Charging", "Full", "Not charging")
            return pct, charging
        except (OSError, ValueError):
            continue
    return None


class PcEvents:
    """Downloads that finished, the battery running low or full, and long terminal
    commands that ended (host/status-setup.sh) go to every device as {"t":"pcevent"}."""
    SKIP = (".part", ".crdownload", ".download", ".tmp", ".partial", ".opdownload")

    def __init__(self, event, downloads=None):
        self.event = event  # (icon, title, text)
        self.downloads = downloads or os.path.join(os.path.expanduser("~"), "Downloads")
        os.makedirs(events_dir(), exist_ok=True)

    def start(self):
        threading.Thread(target=self._loop, daemon=True).start()

    def _files(self):
        out = {}
        try:
            for e in os.scandir(self.downloads):
                if e.is_file() and not e.name.startswith(".") and not e.name.lower().endswith(self.SKIP):
                    out[e.name] = e.stat().st_size
        except OSError:
            pass
        return out

    def _loop(self):
        known = set(self._files())  # what was there before: not news
        growing = {}
        low_told = full_told = False
        while True:
            time.sleep(4)
            # downloads: a new file whose size stopped changing
            now = self._files()
            for name, size in now.items():
                if name in known:
                    continue
                if growing.get(name) == size:
                    known.add(name)
                    growing.pop(name, None)
                    mb = size / 1e6
                    self.event("⬇️", "Download finished", name + ("  ·  %.1f MB" % mb if mb >= 0.1 else ""))
                else:
                    growing[name] = size
            known &= set(now)
            # battery
            b = battery()
            if b:
                pct, charging = b
                if pct <= 15 and not charging and not low_told:
                    low_told = True
                    self.event("🪫", "Laptop battery low", "%d %% — plug in the charger" % pct)
                if charging or pct > 20:
                    low_told = False
                if pct >= 100 and charging and not full_told:
                    full_told = True
                    self.event("🔋", "Laptop fully charged", "You can unplug it")
                if pct < 95:
                    full_told = False
            # long commands (and anything else that drops a note in the events folder)
            for path in sorted(glob.glob(os.path.join(events_dir(), "*.json")))[:20]:
                try:
                    with open(path, encoding="utf-8") as f:
                        e = json.load(f)
                except (OSError, ValueError):
                    e = None
                try:
                    os.remove(path)
                except OSError:
                    continue
                if isinstance(e, dict) and e.get("title"):
                    self.event(str(e.get("icon", "✅"))[:4], str(e["title"])[:120], str(e.get("text", ""))[:300])


# ---------------------------------------------------------------- unlock the laptop from a device (Fedora)

class Unlocker:
    """Checks the unlock PIN set with host/unlock-setup.sh (only a salted hash is kept),
    and stops guessing: 5 wrong tries lock it for 5 minutes, and each new block after
    that lasts twice as long (up to a day), until the right PIN is given."""

    TRIES = 5
    FIRST_BLOCK = 300
    LONGEST_BLOCK = 24 * 3600

    def __init__(self, conf_dir):
        self.file = os.path.join(conf_dir, "unlock.json")
        self.fails, self.until, self.blocks = 0, 0.0, 0
        self.lock = threading.Lock()

    def enabled(self):
        return os.path.exists(self.file)

    def check(self, pin):
        import hashlib
        import hmac
        with self.lock:
            if time.time() < self.until:
                return "Too many wrong tries: wait %d minutes." % ((self.until - time.time()) // 60 + 1)
            try:
                with open(self.file) as f:
                    d = json.load(f)
                want = hashlib.pbkdf2_hmac("sha256", str(pin).encode(), bytes.fromhex(d["salt"]), int(d["iter"])).hex()
            except (OSError, ValueError, KeyError, TypeError):
                return "Unlock isn't set up: run ./host/unlock-setup.sh on the laptop."
            if hmac.compare_digest(want, str(d.get("hash", ""))):
                self.fails, self.blocks = 0, 0
                return True
            self.fails += 1
            if self.fails >= self.TRIES:
                wait = min(self.FIRST_BLOCK * 2 ** self.blocks, self.LONGEST_BLOCK)
                self.fails, self.until, self.blocks = 0, time.time() + wait, self.blocks + 1
            return "Wrong PIN."


# ---------------------------------------------------------------- the tablet finds the PC on Wi-Fi

BEACON_PORT = 7779


def beacon(port, fingerprint, name):
    """Says "Fedora Pad is here" on the home network every few seconds, so a paired
    tablet finds the PC again when its Wi-Fi address changes. Only the address goes
    out: the tablet still checks the PC's certificate and sends its secret token."""
    def loop():
        msg = json.dumps({"t": "fedorapad", "port": port, "fp": fingerprint, "host": name}).encode()
        while True:
            targets = {"255.255.255.255"}
            try:
                for card in wake_info():
                    if card.get("up") and card.get("broadcast"):
                        targets.add(card["broadcast"])
            except Exception:  # noqa: BLE001 - a missing tool mustn't stop the beacon
                pass
            try:
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                    for t in targets:
                        try:
                            s.sendto(msg, (t, BEACON_PORT))
                        except OSError:
                            pass
            except OSError:
                pass
            time.sleep(3)
    threading.Thread(target=loop, daemon=True).start()
