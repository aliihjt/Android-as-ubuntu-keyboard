"""Notes, received files and Terminal Pad: the code both hosts share."""
import base64
import json
import os
import sys
import tempfile
import threading
import unittest

import _path  # noqa: F401
import pad_extras


class NotesTest(unittest.TestCase):
    def test_save_and_load_persian(self):
        with tempfile.TemporaryDirectory() as d:
            n = pad_extras.Notes(os.path.join(d, "conf", "notes.json"), os.path.join(d, "docs", "notes.md"))
            self.assertEqual(n.load(), [])
            n.save([{"id": 1, "text": "سلام دنیا", "color": "blue"}, "junk", {"text": "two"}])
            self.assertEqual([x["text"] for x in n.load()], ["سلام دنیا", "two"])
            with open(os.path.join(d, "docs", "notes.md"), encoding="utf-8") as f:
                self.assertIn("سلام دنیا", f.read())
            n.save(None)  # not a list: saved as no notes, like before
            self.assertEqual(n.load(), [])


class InboxTest(unittest.TestCase):
    def piece(self, tid, data, last=False, name="photo.jpg"):
        return {"id": tid, "name": name, "data": base64.b64encode(data).decode(), "last": last}

    def test_pieces_make_one_file_and_names_never_clash(self):
        with tempfile.TemporaryDirectory() as d:
            done, replies = [], []
            box = pad_extras.Inbox(d, done.append)
            box.receive(self.piece("a", b"hello "), replies.append)
            box.receive(self.piece("a", b"world", last=True), replies.append)
            box.receive(self.piece("b", b"again", last=True), replies.append)
            with open(os.path.join(d, "photo.jpg"), "rb") as f:
                self.assertEqual(f.read(), b"hello world")
            self.assertEqual(done, ["photo.jpg", "photo (1).jpg"])
            self.assertEqual(replies[-1]["t"], "filedone")

    def test_unsafe_names(self):
        self.assertEqual(pad_extras.safe_name("../../.bashrc"), "bashrc")
        self.assertEqual(pad_extras.safe_name("C:\\Windows\\evil.exe"), "evil.exe")
        self.assertEqual(pad_extras.safe_name('a<b>:"c|?*.txt'), "abc.txt")
        self.assertEqual(pad_extras.safe_name(""), "file")
        self.assertEqual(pad_extras.safe_name("..."), "file")

    def test_stale_transfer_is_dropped(self):
        with tempfile.TemporaryDirectory() as d:
            box = pad_extras.Inbox(d)
            box.receive(self.piece("old", b"x"))
            box.transfers["old"][2] -= 3600
            box.receive(self.piece("new", b"y", last=True, name="other.txt"))
            self.assertNotIn("old", box.transfers)
            self.assertFalse(os.path.exists(os.path.join(d, "photo.jpg.part")))


class TerminalTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.dir.name, "commands.json")
        self.term = pad_extras.Terminal(self.path, [{"name": "hi", "cmd": "print('hi')"}],
                                        lambda cmd: [sys.executable, "-c", cmd], lambda p: p.kill())

    def tearDown(self):
        self.dir.cleanup()

    def run_and_wait(self, name):
        got, finished = [], threading.Event()

        def reply(m):
            got.append(m)
            if m["t"] == "termdone":
                finished.set()
        self.term.run(name, reply)
        self.assertTrue(finished.wait(10))
        return got

    def test_defaults_written_and_run(self):
        got = self.run_and_wait("hi")
        self.assertTrue(os.path.exists(self.path))
        self.assertEqual(got[0], {"t": "termout", "line": "hi"})
        self.assertEqual(got[-1], {"t": "termdone", "code": 0})

    def test_unknown_command(self):
        self.assertEqual(self.run_and_wait("nope")[0]["line"], "Unknown command.")

    def test_broken_file_is_kept(self):
        with open(self.path, "w") as f:
            f.write("[{oops")
        self.assertEqual(self.term.commands()[0]["name"], "hi")
        with open(self.path) as f:
            self.assertEqual(f.read(), "[{oops")

    def test_one_at_a_time(self):
        with open(self.path, "w") as f:
            json.dump([{"name": "slow", "cmd": "import time; time.sleep(1)"}], f)
        first = threading.Event()
        self.term.run("slow", lambda m: m["t"] == "termdone" and first.set())
        second = []
        self.term.run("slow", second.append)
        self.assertEqual(second[0]["line"], "Another command is still running.")
        self.term.stop()
        self.assertTrue(first.wait(10))


if __name__ == "__main__":
    unittest.main()
