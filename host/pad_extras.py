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
