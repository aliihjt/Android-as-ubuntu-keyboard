"""iPhone control: laptop keys and pointer -> Bluetooth keyboard / mouse reports."""
import json
import tempfile
import unittest
import urllib.error
import urllib.request

import _path  # noqa: F401
import iphone_hid as ih
import pad_extras
import universal as uc


class ReportsTest(unittest.TestCase):
    def test_typing_with_shift(self):
        r = ih.Reports()
        self.assertEqual(r.key("KEY_LEFTSHIFT", 1), [(1, bytes([0x02, 0, 0, 0, 0, 0, 0, 0]))])
        self.assertEqual(r.key("KEY_A", 1), [(1, bytes([0x02, 0, 0x04, 0, 0, 0, 0, 0]))])
        self.assertEqual(r.key("KEY_A", 2), [])  # the iPhone repeats by itself
        self.assertEqual(r.key("KEY_A", 0), [(1, bytes([0x02, 0, 0, 0, 0, 0, 0, 0]))])
        self.assertEqual(r.key("KEY_PROG1", 1), [])

    def test_command_space_is_spotlight(self):
        r = ih.Reports()
        r.key("KEY_LEFTMETA", 1)  # the laptop's Super key is the iPhone's ⌘
        self.assertEqual(r.key("KEY_SPACE", 1)[0][1][:3], bytes([0x08, 0, 0x2C]))

    def test_media_keys(self):
        r = ih.Reports()
        self.assertEqual(r.key("KEY_VOLUMEUP", 1), [(3, bytes([0x08]))])
        self.assertEqual(r.key("KEY_VOLUMEUP", 0), [(3, bytes([0]))])

    def test_pointer(self):
        r = ih.Reports()
        out = r.pointer({"a": "move", "dx": 300.6, "dy": -5})
        self.assertEqual(len(out), 3)  # a big move is split: 127 + 127 + 46
        self.assertEqual(sum(int.from_bytes(d[1:2], "big", signed=True) for _, d in out), 300)
        self.assertEqual(r.pointer({"a": "button", "b": "left", "v": 1}), [(2, bytes([1, 0, 0, 0, 0]))])
        self.assertEqual(r.pointer({"a": "scroll", "dx": 0, "dy": 10}), [])  # small: collected
        out = r.pointer({"a": "scroll", "dx": 0, "dy": 35})
        self.assertEqual(out, [(2, bytes([1, 0, 0, 1, 0]))])
        self.assertEqual(r.release()[1], (2, bytes(5)))

    def test_report_map_has_three_reports(self):
        self.assertEqual(ih.REPORT_MAP.count(bytes([0x85])), 3)


class Sink:
    def __init__(self):
        self.keys, self.moves, self.released = [], [], False

    def key(self, name, v):
        self.keys.append((name, v))

    def pointer(self, msg):
        self.moves.append(msg)

    def release(self):
        self.released = True


class IPhoneModeTest(unittest.TestCase):
    def test_hand_over_and_back(self):
        with tempfile.TemporaryDirectory() as d:
            u = uc.Universal(lambda age: None, d, dry_run=True)
            sink = Sink()
            u.control_iphone(sink)
            self.assertEqual(u.mode, "iphone")
            u._key("KEY_H", 1)
            u._out({"a": "move", "dx": 3, "dy": 1})
            self.assertEqual(sink.keys, [("KEY_H", 1)])
            self.assertEqual(sink.moves, [{"a": "move", "dx": 3, "dy": 1}])
            u.check_alive(1e9)  # no tablet heartbeat needed for the iPhone
            self.assertEqual(u.mode, "iphone")
            for k in ("KEY_LEFTCTRL", "KEY_LEFTALT", "KEY_ESC"):
                u._key(k, 1)
            self.assertFalse(u.active)
            self.assertTrue(sink.released)


class CommandRouteTest(unittest.TestCase):
    def test_only_local_scripts(self):
        got = []
        srv = pad_extras.TabServer(lambda *a: None, port=0, commands={"/iphone": lambda t: got.append(t) or "on"}).start()
        port = srv.server_address[1]
        try:
            def post(headers):
                req = urllib.request.Request(f"http://127.0.0.1:{port}/iphone", method="POST", data=b"toggle",
                                             headers=headers)
                try:
                    r = urllib.request.urlopen(req, timeout=5)
                    return r.status, r.read().decode()
                except urllib.error.HTTPError as e:
                    return e.code, ""
            self.assertEqual(post({"X-Fedora-Pad": "1", "Origin": "https://evil.example"})[0], 403)
            self.assertEqual(post({})[0], 403)
            self.assertEqual(post({"X-Fedora-Pad": "1"}), (200, "on"))
            self.assertEqual(got, ["toggle"])
        finally:
            srv.shutdown()
            srv.server_close()


class ConfTest(unittest.TestCase):
    def test_only_the_paired_iphone(self):
        with tempfile.TemporaryDirectory() as d:
            p = d + "/iphone.json"
            with open(p, "w") as f:
                json.dump({"device": "AA:BB:CC:DD:EE:FF"}, f)
            dev = ih.IPhone(p)
            self.assertTrue(dev.allowed({"device": "/org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF"}))
            self.assertFalse(dev.allowed({"device": "/org/bluez/hci0/dev_11_22_33_44_55_66"}))
            self.assertFalse(dev.allowed({}))


if __name__ == "__main__":
    unittest.main()
