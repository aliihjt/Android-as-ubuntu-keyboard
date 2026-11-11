"""The laptop's notifications, for the tablet and the iPhone (Fedora).

Listens on the session bus with `busctl --user monitor` to the two places
apps send notifications to on GNOME:
  org.freedesktop.Notifications.Notify      most apps (Chrome, notify-send...)
  org.gtk.Notifications.AddNotification      GNOME and Flatpak apps (Telegram...)
and calls on_notification(app, title, text) for each new one. Fedora Pad's
own notifications are skipped, so a message from the tablet shown on the
laptop doesn't go back to the tablet.
"""
import json
import shutil
import subprocess
import sys
import threading
import time

OWN = {"fedora pad", "fedorapad", "tabletdesk"}
SAME_WITHIN = 3.0  # seconds: the same notification sent twice is shown once


def parse(line):
    """(app, title, text) from one line of `busctl monitor --json=short`, or None."""
    try:
        m = json.loads(line)
    except ValueError:
        return None
    if not isinstance(m, dict) or m.get("type") != "method_call":
        return None
    data = (m.get("payload") or {}).get("data")
    if not isinstance(data, list):
        return None
    member = m.get("member")
    if member == "Notify" and len(data) >= 5:  # (app_name, replaces_id, icon, summary, body, ...)
        app, title, text = data[0], data[3], data[4]
    elif member == "AddNotification" and len(data) >= 3 and isinstance(data[2], dict):  # (app_id, id, {..})
        app = data[0]
        title = _variant(data[2].get("title"))
        text = _variant(data[2].get("body"))
    else:
        return None
    app, title, text = (str(x or "").strip() for x in (app, title, text))
    app = _app_name(app)
    if not title and not text or app.lower() in OWN:
        return None
    return app[:60], title[:200], text[:1000]


def _variant(v):
    return v.get("data", "") if isinstance(v, dict) else (v or "")


def _app_name(app):
    """'org.telegram.desktop' -> 'Telegram', 'google-chrome' -> 'Google Chrome'."""
    if "." in app and " " not in app:
        parts = [p for p in app.split(".") if p.lower() not in ("org", "com", "io", "net", "desktop", "gnome", "github")]
        app = parts[0] if parts else app
    return app.replace("-", " ").replace("_", " ").strip().title() if app.islower() else app


class Watcher:
    def __init__(self, on_notification):
        self.on_notification = on_notification
        self.last = (None, 0.0)

    def feed(self, line):
        """One line of busctl output; True when it was passed on."""
        n = parse(line)
        if not n:
            return False
        now = time.time()
        if n == self.last[0] and now - self.last[1] < SAME_WITHIN:
            return False
        self.last = (n, now)
        try:
            self.on_notification(*n)
        except Exception as err:  # noqa: BLE001 - a device that went away mustn't stop the watcher
            print("laptop notification not sent:", err, file=sys.stderr)
        return True

    def start(self):
        if not shutil.which("busctl"):
            print("laptop notifications: busctl is missing, not shared", file=sys.stderr)
            return
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        argv = ["busctl", "--user", "monitor", "--json=short",
                "org.freedesktop.Notifications", "org.gtk.Notifications"]
        while True:
            try:
                with subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                      text=True, encoding="utf-8", errors="replace") as p:
                    for line in p.stdout:
                        self.feed(line)
            except OSError as err:
                print("laptop notifications:", err, file=sys.stderr)
                return
            time.sleep(10)  # the session bus restarted (log out / in): listen again
