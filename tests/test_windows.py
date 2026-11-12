"""Windows host features that can be checked on any computer: notifications, pen, Universal Control."""
import ctypes
import os
import sqlite3
import sys
import tempfile
import unittest

import _path  # noqa: F401

WIN = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "windows")
if WIN not in sys.path:
    sys.path.insert(0, WIN)

import win_notifs  # noqa: E402
import win_pen  # noqa: E402
import win_universal as wu  # noqa: E402

TOAST = ('<toast launch="x"><visual><binding template="ToastGeneric"><text>Ali</text><text>Hello &amp; '
         'see you</text><text placement="attribution">via Google Chrome</text></binding></visual></toast>')


class NotificationsTest(unittest.TestCase):
    def test_app_names(self):
        self.assertEqual(win_notifs.app_name("Chrome"), "Google Chrome")
        self.assertEqual(win_notifs.app_name("Microsoft.WindowsTerminal_8wekyb3d8bbwe!App"), "Terminal")
        self.assertEqual(win_notifs.app_name("308046B0AF4A39CB"), "Firefox")
        self.assertEqual(win_notifs.app_name(r"{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\WindowsPowerShell\v1.0"
                                             r"\powershell.exe"), "Powershell")
        self.assertEqual(win_notifs.app_name("Microsoft.Office.OUTLOOK.EXE.15"), "Outlook")
        self.assertEqual(win_notifs.app_name("5319275A.WhatsAppDesktop_cv1g1gvanyjgm!App"), "Whats App Desktop")
        self.assertEqual(win_notifs.app_name(""), "App")

    def test_toast_text(self):
        self.assertEqual(win_notifs.toast_text(TOAST.encode()), ("Ali", "Hello & see you"))
        old = '<toast><visual><binding template="ToastText02"><text id="1">T</text><text id="2">B</text></binding>' \
              '</visual></toast>'
        self.assertEqual(win_notifs.toast_text(old), ("T", "B"))
        self.assertEqual(win_notifs.toast_text("<toast><text>Broken"), ("", ""))
        self.assertEqual(win_notifs.toast_text("<toast><text>A</text><text>B</text"), ("A", ""))

    def test_watcher_sends_only_new(self):
        d = tempfile.mkdtemp()
        path = os.path.join(d, "wpndatabase.db")
        db = sqlite3.connect(path)
        db.execute("CREATE TABLE NotificationHandler (RecordId INTEGER PRIMARY KEY, PrimaryId TEXT)")
        db.execute("CREATE TABLE Notification (Id INTEGER PRIMARY KEY, HandlerId INTEGER, Type TEXT, Payload BLOB)")
        db.execute("INSERT INTO NotificationHandler VALUES (1, 'Chrome'), (2, 'Fedora Pad')")
        db.execute("INSERT INTO Notification VALUES (1, 1, 'toast', ?)", (TOAST.encode(),))
        db.commit()
        got = []
        w = win_notifs.Watcher(lambda *n: got.append(n), path)
        self.assertEqual(w.poll(), 0)  # the old one is not sent
        db.execute("INSERT INTO Notification VALUES (2, 1, 'toast', ?)", (TOAST.replace("Ali", "Sara").encode(),))
        db.execute("INSERT INTO Notification VALUES (3, 2, 'toast', ?)", (TOAST.encode(),))  # Fedora Pad's own
        db.execute("INSERT INTO Notification VALUES (4, 1, 'tile', ?)", (TOAST.encode(),))
        db.commit()
        self.assertEqual(w.poll(), 1)
        self.assertEqual(got, [("Google Chrome", "Sara", "Hello & see you")])
        self.assertEqual(w.poll(), 0)
        db.close()


class PenTest(unittest.TestCase):
    def test_structures_match_windows(self):
        if ctypes.sizeof(ctypes.c_void_p) != 8:
            self.skipTest("sizes are for 64-bit Windows")
        self.assertEqual(ctypes.sizeof(win_pen.POINTER_INFO), 96)
        self.assertEqual(ctypes.sizeof(win_pen.POINTER_PEN_INFO), 120)
        self.assertEqual(ctypes.sizeof(win_pen.POINTER_TOUCH_INFO), 144)
        self.assertEqual(ctypes.sizeof(win_pen.POINTER_TYPE_INFO), 152)

    def flags(self, frames):
        return [f[0] for f in frames]

    def test_stroke(self):
        f = win_pen.Frames()
        down = f.step("down", 10, 20, 0.5)
        self.assertEqual(self.flags(down), [win_pen.INRANGE | win_pen.UPDATE,
                                            win_pen.INRANGE | win_pen.INCONTACT | win_pen.FIRSTBUTTON | win_pen.DOWN])
        self.assertEqual(down[1][2], win_pen.FIRST_DOWN)
        self.assertEqual(down[1][5], 512)
        move = f.step("move", 11, 21, 1.0)
        self.assertEqual(self.flags(move), [win_pen.INRANGE | win_pen.INCONTACT | win_pen.FIRSTBUTTON | win_pen.UPDATE])
        self.assertEqual(move[0][3:], (11, 21, 1024))
        up = f.step("up", 11, 21)
        self.assertEqual(self.flags(up), [win_pen.INRANGE | win_pen.UP])
        self.assertEqual(self.flags(f.step("out", 11, 21)), [win_pen.UPDATE])
        self.assertEqual(f.step("up", 0, 0), [])

    def test_eraser_and_right_click(self):
        f = win_pen.Frames()
        f.step("down", 1, 1, 0.5)
        frames = f.step("down", 2, 2, 0.5, eraser=True)  # the other end: the pen lifts and leaves first
        self.assertEqual(self.flags(frames)[:2], [win_pen.INRANGE | win_pen.UP, win_pen.UPDATE])
        self.assertEqual(frames[-1][1], win_pen.INVERTED | win_pen.ERASER)
        f.step("out", 2, 2)
        right = f.step("right", 5, 5)
        self.assertEqual([r[2] for r in right], [win_pen.NONE, win_pen.SECOND_DOWN, win_pen.SECOND_UP])
        self.assertTrue(all(r[1] & win_pen.BARREL for r in right[1:]))

    def test_dry_run_and_release(self):
        p = win_pen.Pen(dry_run=True, screen=lambda: (1001, 501))
        p.handle({"t": "pen", "s": "down", "x": 1, "y": 0.5, "p": 0.25})
        self.assertEqual(p.log[-1][3:], (1000, 250, 256))
        p.release()
        self.assertEqual([f[0] for f in p.log[-2:]], [win_pen.INRANGE | win_pen.UP, win_pen.UPDATE])


class UniversalTest(unittest.TestCase):
    def setUp(self):
        self.screen = {"monitors": [0], "x": 0, "y": 0, "w": 1920, "h": 1080}
        self.u = wu.WinUniversal(tempfile.mkdtemp(), lambda: dict(self.screen))
        self.sent = []
        self.reply = self.sent.append  # one object: the tablet is known by its reply function
        self.u.handle({"t": "ucready", "on": True}, self.reply)

    def drain(self):
        out = []
        while not self.u.outbox.empty():
            out.append(self.u.outbox.get())
        return out

    def push_over(self):
        for _ in range(60):
            if self.u.on_mouse(wu.WM_MOUSEMOVE, 1919, 500):
                return True
        return False

    def test_crossing_and_moving(self):
        self.assertFalse(self.u.on_mouse(wu.WM_MOUSEMOVE, 1000, 500))
        self.assertTrue(self.push_over())
        self.assertTrue(self.u.active)
        enter = self.drain()[0]
        self.assertEqual(enter["a"], "enter")
        self.assertAlmostEqual(enter["y"], 500 / 1080, places=3)
        self.assertEqual(self.u.anchor, (960, 540))
        self.assertTrue(self.u.on_mouse(wu.WM_MOUSEMOVE, 965, 538))
        self.assertTrue(self.u.on_mouse(wu.WM_LBUTTONDOWN, 960, 540))
        self.assertTrue(self.u.on_mouse(wu.WM_MOUSEWHEEL, 960, 540, (0x10000 - 120) << 16))
        self.assertEqual(self.drain(), [{"a": "move", "dx": 5, "dy": -2}, {"a": "button", "b": "left", "v": 1},
                                        {"a": "scroll", "dx": 0, "dy": -90}])
        self.assertFalse(self.u.on_mouse(wu.WM_MOUSEMOVE, 0, 0, injected=True))  # Fedora Pad's own SendInput

    def test_keys_and_ctrl_alt_esc(self):
        self.assertFalse(self.u.on_key(wu.WM_KEYDOWN, 0x41))  # not crossed: the laptop types
        self.push_over()
        self.drain()
        self.assertTrue(self.u.on_key(wu.WM_KEYDOWN, 0x41))
        self.assertTrue(self.u.on_key(wu.WM_KEYDOWN, 0x41))
        self.assertTrue(self.u.on_key(wu.WM_KEYUP, 0x41))
        self.assertEqual([m["v"] for m in self.drain()], [1, 2, 0])
        for vk in (0xA2, 0xA4, 0x1B):
            self.u.on_key(wu.WM_KEYDOWN, vk)
        self.assertFalse(self.u.active)
        self.assertEqual(self.drain()[-1], {"a": "leave"})

    def test_not_while_second_screen_or_off(self):
        self.screen["monitors"] = [0, 1]
        self.assertFalse(self.push_over())
        self.screen["monitors"] = [0]
        self.u.handle({"t": "ucset", "on": False}, self.reply)
        self.assertFalse(self.push_over())

    def test_tablet_gone(self):
        self.push_over()
        self.u.gone(self.reply)
        self.assertFalse(self.u.active)
        self.assertIsNone(self.u.target)

    def test_wheel_and_keys(self):
        self.assertEqual(wu.wheel_delta(120 << 16), 120)
        self.assertEqual(wu.wheel_delta((0x10000 - 240) << 16), -240)
        self.assertEqual(wu.key_name(0x0D, extended=True), "KEY_KPENTER")
        self.assertEqual(wu.key_name(0x5A), "KEY_Z")


if __name__ == "__main__":
    unittest.main()
