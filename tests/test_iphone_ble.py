"""iPhone ecosystem: Apple's notification (ANCS) and media (AMS) formats, and the hub."""
import struct
import tempfile
import unittest

import _path  # noqa: F401
import hub
import iphone_ble as ib


def attr(aid, text):
    b = text.encode()
    return bytes([aid]) + struct.pack("<H", len(b)) + b


class AncsTest(unittest.TestCase):
    def test_source_event(self):
        ev = ib.parse_source(bytes([0, 0, 4, 1]) + struct.pack("<I", 77))
        self.assertEqual(ev, {"event": 0, "uid": 77, "category": 4, "silent": False, "existing": False})
        self.assertTrue(ib.parse_source(bytes([0, 4, 4, 1, 1, 0, 0, 0]))["existing"])
        self.assertIsNone(ib.parse_source(b"\x00\x01"))

    def test_ask_attributes(self):
        cmd = ib.ask_attributes(77)
        self.assertEqual(cmd[:5], bytes([0]) + struct.pack("<I", 77))
        self.assertEqual(cmd[5], ib.ATTR_APP)  # the app id has no length limit

    def test_reply_in_pieces(self):
        reply = bytes([0]) + struct.pack("<I", 77) + attr(0, "ph.telegra.Telegraph") + attr(1, "علی") \
            + attr(2, "") + attr(3, "سلام، کجایی؟")
        ds = ib.DataSource()
        self.assertIsNone(ds.feed(reply[:9]))   # the iPhone sends long replies in several pieces
        self.assertIsNone(ds.feed(reply[9:20]))
        uid, attrs = ds.feed(reply[20:])
        self.assertEqual(uid, 77)
        self.assertEqual(ib.notification_text(attrs, 4), ("Telegram", "علی", "سلام، کجایی؟"))

    def test_calls_and_unknown_apps(self):
        self.assertEqual(ib.notification_text({0: "com.apple.mobilephone", 1: "Mom", 3: ""}, 1),
                         ("Phone", "📞 Incoming call: Mom", ""))
        self.assertEqual(ib.notification_text({0: "com.example.coolapp", 1: "Hi"}, 0)[0], "Coolapp")


class AmsTest(unittest.TestCase):
    def test_music(self):
        m = ib.Music()
        for data in (bytes([0, 0, 0]) + b"Music", bytes([0, 1, 0]) + b"1,1.0,12.5",
                     bytes([2, 0, 0]) + "Googoosh".encode(), bytes([2, 2, 0]) + "Pol".encode()):
            m.update(*ib.parse_entity(data))
        self.assertEqual((m.player, m.playing, m.artist, m.title), ("Music", True, "Googoosh", "Pol"))
        m.update(*ib.parse_entity(bytes([0, 1, 0]) + b"0,0.0,12.5"))
        self.assertFalse(m.playing)
        self.assertEqual(ib.COMMANDS["next"], 3)


class Device:
    def __init__(self):
        self.got = []

    def __call__(self, m):
        self.got.append(m)


class HubTest(unittest.TestCase):
    def test_iphone_notifications_and_battery(self):
        with tempfile.TemporaryDirectory() as d:
            h = hub.Hub(d, "laptop")
            tablet, iphone = Device(), Device()
            h.join(tablet, "Tablet", "android")
            h.join(iphone, "iPhone", "iphone")
            h.iphone_notification("Telegram", "Ali", "Salam")
            self.assertEqual(tablet.got[-1]["app"], "iPhone · Telegram")
            self.assertFalse(any(m.get("t") == "pcnotif" for m in iphone.got))  # not back to the iPhone
            h.set_battery("iPhone", "iphone", 64)
            self.assertEqual(tablet.got[-1]["list"][-1], {"name": "iPhone", "kind": "iphone", "level": 64,
                                                          "charging": False})
            h.set_battery("iPhone", "iphone", None)
            self.assertEqual(tablet.got[-1]["list"], [])


if __name__ == "__main__":
    unittest.main()
