"""Pairing the web app and unlocking the laptop must resist guessing."""
import hashlib
import json
import os
import tempfile
import unittest
from unittest import mock

import _path  # noqa: F401
import pad_extras
import webapp


class PairingTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.shown = []
        self.app = webapp.WebApp(self.dir.name, None, None, None, None,
                                 lambda title, text: self.shown.append(text), lambda: [])

    def tearDown(self):
        self.dir.cleanup()

    def code(self):
        return self.app.pair["code"]

    def test_right_code_gives_a_token_once(self):
        self.assertTrue(self.app.request_code())
        code = self.code()
        token = self.app.confirm_code(code, "iPad")
        self.assertTrue(token)
        self.assertTrue(self.app.known(token))
        self.assertIsNone(self.app.confirm_code(code, "again"))
        with open(os.path.join(self.dir.name, "web-devices.json")) as f:
            stored = f.read()
        self.assertNotIn(token, stored)  # only the hash is kept

    def test_five_tries_per_code(self):
        self.app.request_code()
        right = self.code()
        wrong = "%06d" % ((int(right) + 1) % 1000000)
        for _ in range(5):
            self.assertIsNone(self.app.confirm_code(wrong, "x"))
        self.assertIsNone(self.app.confirm_code(right, "x"))

    def test_no_new_code_within_ten_seconds(self):
        self.assertTrue(self.app.request_code())
        self.assertFalse(self.app.request_code())

    def test_hourly_limit_across_new_codes(self):
        now = [1000.0]
        with mock.patch.object(webapp.time, "time", lambda: now[0]):
            for _ in range(4):  # 4 codes x 5 wrong tries = 20
                self.assertTrue(self.app.request_code())
                bad = "%06d" % ((int(self.code()) + 1) % 1000000)
                for _ in range(5):
                    self.app.confirm_code(bad, "x")
                now[0] += 11
            self.assertFalse(self.app.request_code())  # someone is guessing
            now[0] += 3601
            self.assertTrue(self.app.request_code())
            self.assertTrue(self.app.confirm_code(self.code(), "iPad"))

    def test_unknown_token(self):
        self.assertFalse(self.app.known("nope"))


class UnlockTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        salt = os.urandom(16)
        with open(os.path.join(self.dir.name, "unlock.json"), "w") as f:
            json.dump({"salt": salt.hex(), "iter": 1000,
                       "hash": hashlib.pbkdf2_hmac("sha256", b"2468", salt, 1000).hex()}, f)
        self.u = pad_extras.Unlocker(self.dir.name)

    def tearDown(self):
        self.dir.cleanup()

    def test_right_and_wrong_pin(self):
        self.assertIs(self.u.check("2468"), True)
        self.assertEqual(self.u.check("0000"), "Wrong PIN.")

    def test_blocks_get_longer(self):
        now = [1000.0]
        with mock.patch.object(pad_extras.time, "time", lambda: now[0]):
            for _ in range(5):
                self.u.check("0000")
            self.assertIn("Too many", self.u.check("2468"))  # even the right PIN waits
            now[0] += 301
            for _ in range(5):
                self.u.check("0000")
            now[0] += 301
            self.assertIn("Too many", self.u.check("2468"))  # second block is 10 minutes
            now[0] += 300
            self.assertIs(self.u.check("2468"), True)

    def test_not_set_up(self):
        u = pad_extras.Unlocker(os.path.join(self.dir.name, "missing"))
        self.assertFalse(u.enabled())
        self.assertIn("isn't set up", u.check("1"))


if __name__ == "__main__":
    unittest.main()
