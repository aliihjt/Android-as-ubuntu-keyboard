"""The laptop's notifications, for the tablet and the iPhone (Windows).

Windows keeps every notification ("toast") in a small database:
  %LOCALAPPDATA%\\Microsoft\\Windows\\Notifications\\wpndatabase.db
This reads it every 2 seconds (read-only, nothing is changed) and calls
on_notification(app, title, text) for each new toast. Only toasts that
arrive after Fedora Pad starts are sent, never old ones.
"""
import os
import re
import shutil
import sqlite3
import sys
import tempfile
import threading
import time
import xml.etree.ElementTree as ET

OWN = {"fedora pad", "fedorapad", "tabletdesk"}
POLL_S = 2.0
QUERY = ("SELECT n.Id, h.PrimaryId, n.Payload FROM Notification n "
         "LEFT JOIN NotificationHandler h ON n.HandlerId = h.RecordId "
         "WHERE n.Type = 'toast' AND n.Id > ? ORDER BY n.Id LIMIT 50")

# app ids Windows uses -> the name people know
KNOWN = {
    "chrome": "Google Chrome", "msedge": "Microsoft Edge", "308046b0af4a39cb": "Firefox",
    "microsoft.windows.explorer": "File Explorer", "windows.systemtoast.securityandmaintenance": "Windows Security",
    "microsoft.windows.defender": "Windows Security", "windows.defender.securitycenter": "Windows Security",
    "microsoft.outlookforwindows": "Outlook", "microsoft.office.outlook.exe.15": "Outlook",
    "telegramdesktop.telegramdesktop": "Telegram", "telegram.telegramdesktop": "Telegram",
    "microsoft.windowsstore": "Microsoft Store", "microsoft.windowsterminal": "Terminal",
}
DROP = {"microsoft", "windows", "com", "org", "io", "net", "desktop", "app", "exe", "github"}


def default_path():
    base = os.environ.get("LOCALAPPDATA", os.path.expanduser("~"))
    return os.path.join(base, "Microsoft", "Windows", "Notifications", "wpndatabase.db")


def app_name(aumid):
    """'Microsoft.WindowsTerminal_8wekyb3d8bbwe!App' -> 'Terminal', 'C:\\...\\powershell.exe' -> 'Powershell'."""
    a = str(aumid or "").strip()
    if not a:
        return "App"
    a = a.split("!")[0]
    if "\\" in a or "/" in a:  # a program's path
        a = re.split(r"[\\/]", a)[-1]
        a = a[:-4] if a.lower().endswith(".exe") else a
    a = re.sub(r"_[a-z0-9]{13}$", "", a)  # Store apps: _<publisher id>
    if a.lower() in KNOWN:
        return KNOWN[a.lower()]
    if re.fullmatch(r"[0-9A-Fa-f]{16}|\{[0-9A-Fa-f-]+\}", a):
        return "App"
    parts = [p for p in re.split(r"[.\s]+", a) if p and p.lower() not in DROP]
    name = parts[-1] if len(parts) > 1 and parts[0][:1].isdigit() else (parts[0] if parts else a)
    name = re.sub(r"(?<=[a-z])(?=[A-Z])", " ", name)  # "WindowsTerminal" -> "Windows Terminal"
    return (name.replace("-", " ").replace("_", " ").strip().title() if name.islower() else name)[:60]


def toast_text(payload):
    """The toast's XML -> (title, text). The first <text> is the title, the rest the message."""
    if isinstance(payload, bytes):
        payload = payload.decode("utf-8", "replace")
    payload = str(payload or "").lstrip("\ufeff").strip()
    lines = []
    try:
        root = ET.fromstring(payload)
        binding = root.find(".//binding")
        for el in (binding if binding is not None else root).iter("text"):
            if el.get("placement") == "attribution":  # "via Google Chrome": not the message
                continue
            t = "".join(el.itertext()).strip()
            if t:
                lines.append(t)
    except ET.ParseError:
        import html
        lines = [html.unescape(re.sub(r"<[^>]+>", "", m)).strip()
                 for m in re.findall(r"<text[^>]*>(.*?)</text>", payload, re.S)]
        lines = [x for x in lines if x]
    if not lines:
        return "", ""
    return lines[0][:200], "\n".join(lines[1:])[:1000]


def read_new(conn, after):
    """[(id, app, title, text)] for toasts newer than id `after`."""
    out = []
    for row_id, aumid, payload in conn.execute(QUERY, (after,)):
        title, text = toast_text(payload)
        app = app_name(aumid)
        own = {app.lower(), str(aumid or "").strip().lower(), title.lower()} & OWN
        if (title or text) and not own:
            out.append((row_id, app, title, text))
        else:
            out.append((row_id, None, None, None))  # skipped, but remembered as seen
    return out


def newest_id(conn):
    row = conn.execute("SELECT MAX(Id) FROM Notification").fetchone()
    return int(row[0] or 0) if row else 0


class Watcher:
    def __init__(self, on_notification, path=None):
        self.on_notification = on_notification
        self.path = path or default_path()
        self.last_id = None
        self.copy_dir = None

    def _connect(self):
        """Read-only. If Windows holds the file too tightly, read a fresh copy of it instead."""
        try:
            conn = sqlite3.connect("file:%s?mode=ro" % self.path.replace("\\", "/"), uri=True, timeout=2)
            conn.execute("SELECT 1 FROM Notification LIMIT 1")
            return conn
        except sqlite3.Error:
            pass
        if self.copy_dir is None:
            self.copy_dir = tempfile.mkdtemp(prefix="fedorapad-notifs-")
        copy = os.path.join(self.copy_dir, "wpn.db")
        for ext in ("", "-wal", "-shm"):
            try:
                shutil.copyfile(self.path + ext, copy + ext)
            except OSError:
                if ext == "":
                    raise
                try:
                    os.remove(copy + ext)
                except OSError:
                    pass
        return sqlite3.connect(copy, timeout=2)

    def poll(self):
        """One look at the database; returns how many notifications were passed on."""
        conn = self._connect()
        try:
            if self.last_id is None:  # first look: only what comes after now
                self.last_id = newest_id(conn)
                return 0
            sent = 0
            for row_id, app, title, text in read_new(conn, self.last_id):
                self.last_id = max(self.last_id, row_id)
                if app is None:
                    continue
                try:
                    self.on_notification(app, title, text)
                    sent += 1
                except Exception as err:  # noqa: BLE001 - a device that went away mustn't stop the watcher
                    print("laptop notification not sent:", err, file=sys.stderr)
            return sent
        finally:
            conn.close()

    def start(self):
        if not os.path.exists(self.path):
            print("laptop notifications: Windows' notification list wasn't found, not shared", file=sys.stderr)
            return
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        failed = 0
        while True:
            try:
                self.poll()
                failed = 0
            except (sqlite3.Error, OSError) as err:
                failed += 1
                if failed == 3:
                    print("laptop notifications:", err, file=sys.stderr)
            time.sleep(POLL_S if failed < 3 else 30)
