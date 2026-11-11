import json
import os
import tempfile
import unittest

import _path  # noqa: F401
import hub


class Device:
    """A fake device: collects what the hub sends it."""

    def __init__(self):
        self.got = []

    def __call__(self, msg):
        self.got.append(msg)

    def last(self, t):
        return next((m for m in reversed(self.got) if m.get("t") == t), None)


class HubTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.pc_clip = []
        self.hub = hub.Hub(self.dir.name, "pc", set_clip=self.pc_clip.append)
        self.tablet, self.phone = Device(), Device()
        self.hub.join(self.tablet, "Tablet", "android")
        self.hub.join(self.phone, "Phone", "iphone")

    def tearDown(self):
        self.dir.cleanup()

    def test_devices_list_reaches_everyone(self):
        lst = self.phone.last("devices")["list"]
        self.assertEqual({d["name"] for d in lst}, {"Tablet", "Phone"})

    def test_iam_renames_and_ignores_unknown_kind(self):
        self.hub.handle({"t": "iam", "name": "Ali's iPhone", "kind": "toaster"}, self.phone)
        me = next(d for d in self.tablet.last("devices")["list"] if d["name"] == "Ali's iPhone")
        self.assertEqual(me["kind"], "iphone")

    def test_relay_only_allowed_types(self):
        phone_id = self.phone.last("devices")["me"]
        self.hub.handle({"t": "relay", "to": phone_id, "m": {"t": "pushtext", "s": "hi"}}, self.tablet)
        self.assertEqual(self.phone.last("pushtext")["from"], "Tablet")
        self.hub.handle({"t": "relay", "to": phone_id, "m": {"t": "unlock", "pin": "1"}}, self.tablet)
        self.assertIsNone(self.phone.last("unlock"))

    def test_relay_to_missing_device_fails(self):
        self.hub.handle({"t": "relay", "to": "nobody", "m": {"t": "pushtext", "s": "x"}}, self.tablet)
        self.assertIsNotNone(self.tablet.last("relayfail"))

    def test_universal_clipboard(self):
        self.hub.handle({"t": "uclip", "s": "copied"}, self.tablet)
        self.assertEqual(self.phone.last("uclip")["s"], "copied")
        self.assertIsNone(self.tablet.last("uclip"))
        self.assertEqual(self.pc_clip, ["copied"])

    def test_clipboard_off(self):
        self.hub.handle({"t": "uclipset", "on": False}, self.tablet)
        self.hub.handle({"t": "uclip", "s": "secret"}, self.tablet)
        self.assertIsNone(self.phone.last("uclip"))
        with open(os.path.join(self.dir.name, "hub.json")) as f:
            self.assertFalse(json.load(f)["uclip"])

    def test_shared_data_saved_and_sent(self):
        self.hub.handle({"t": "share", "key": "words", "value": ["salam"]}, self.tablet)
        self.assertEqual(self.phone.last("shared")["all"]["words"]["value"], ["salam"])
        again = hub.Hub(self.dir.name, "pc")
        self.assertEqual(again.shared["words"]["value"], ["salam"])

    def test_shared_data_unknown_key_or_device(self):
        self.hub.handle({"t": "share", "key": "passwords", "value": 1}, self.tablet)
        self.hub.handle({"t": "share", "key": "words", "value": 1}, Device())
        self.assertEqual(self.hub.shared, {})

    def test_notification_with_bad_time(self):
        self.hub.handle({"t": "notif", "key": "k", "app": "Telegram", "title": "A", "time": "soon"}, self.tablet)
        n = self.phone.last("notif")
        self.assertEqual(n["app"], "Telegram")
        self.assertIsInstance(n["time"], int)
        self.hub.handle({"t": "notifgone", "key": "k"}, self.tablet)
        self.assertEqual(list(self.hub.notifs), [])

    def test_device_that_fails_does_not_stop_others(self):
        def broken(msg):
            raise OSError("gone")
        self.hub.join(broken, "Broken")
        self.hub.handle({"t": "uclip", "s": "still works"}, self.tablet)
        self.assertEqual(self.phone.last("uclip")["s"], "still works")

    def test_leave(self):
        self.hub.leave(self.phone)
        self.assertEqual([d["name"] for d in self.tablet.last("devices")["list"]], ["Tablet"])


if __name__ == "__main__":
    unittest.main()


class HandoffHubTest(unittest.TestCase):
    def test_tab_goes_to_devices_and_is_remembered(self):
        with tempfile.TemporaryDirectory() as d:
            h = hub.Hub(d, "pc")
            tablet = Device()
            h.join(tablet, "Tablet", "android")
            h.set_tab("https://example.com/", "Example")
            self.assertEqual(tablet.last("pctab")["url"], "https://example.com/")
            phone = Device()
            h.join(phone, "Phone", "iphone")
            h.handle({"t": "pctab?"}, phone)  # a device that comes later asks
            self.assertEqual(phone.last("pctab")["title"], "Example")
