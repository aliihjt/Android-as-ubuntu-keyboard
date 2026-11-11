"""The laptop's notifications: reading busctl's output and passing them on once."""
import json
import unittest

import _path  # noqa: F401
import pc_notifs


def notify(app, title, body, member="Notify"):
    if member == "Notify":
        data = [app, 0, "", title, body, [], {"urgency": {"type": "y", "data": 1}}, -1]
        sig = "susssasa{sv}i"
    else:
        data = [app, "id1", {"title": {"type": "s", "data": title}, "body": {"type": "s", "data": body}}]
        sig = "ssa{sv}"
    return json.dumps({"type": "method_call", "member": member, "payload": {"type": sig, "data": data}})


class ParseTest(unittest.TestCase):
    def test_freedesktop(self):
        self.assertEqual(pc_notifs.parse(notify("google-chrome", "Download complete", "file.pdf")),
                         ("Google Chrome", "Download complete", "file.pdf"))

    def test_gtk_flatpak(self):
        self.assertEqual(pc_notifs.parse(notify("org.telegram.desktop", "Ali", "سلام", "AddNotification")),
                         ("Telegram", "Ali", "سلام"))

    def test_skips_own_replies_and_junk(self):
        self.assertIsNone(pc_notifs.parse(notify("Fedora Pad", "From the tablet", "x")))
        self.assertIsNone(pc_notifs.parse(notify("app", "", "")))
        self.assertIsNone(pc_notifs.parse("not json"))
        self.assertIsNone(pc_notifs.parse(json.dumps({"type": "method_return", "payload": {"data": [1]}})))
        self.assertIsNone(pc_notifs.parse(json.dumps({"type": "method_call", "member": "CloseNotification",
                                                      "payload": {"data": [3]}})))

    def test_watcher_sends_once(self):
        got = []
        w = pc_notifs.Watcher(lambda *n: got.append(n))
        line = notify("Thunderbird", "New mail", "Hello")
        self.assertTrue(w.feed(line))
        self.assertFalse(w.feed(line))  # sent twice within a moment: shown once
        self.assertEqual(got, [("Thunderbird", "New mail", "Hello")])


if __name__ == "__main__":
    unittest.main()
