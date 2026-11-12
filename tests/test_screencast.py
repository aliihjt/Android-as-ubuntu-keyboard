"""Laptop Screen: the pipeline text and a slow phone only getting the newest picture."""
import threading
import time
import unittest

import _path  # noqa: F401
import screencast


class ScreencastTest(unittest.TestCase):
    def test_pipeline(self):
        text = screencast.pipeline_text(7, 42, 960)
        self.assertTrue(text.startswith("pipewiresrc fd=7 path=42 "))
        self.assertIn("width=960", text)
        self.assertIn("framerate=12/1", text)
        self.assertIn("appsink name=sink", text)

    def test_slow_viewer_gets_the_newest(self):
        got, gate = [], threading.Event()

        def send(frame):
            gate.wait(5)  # the phone is busy with the first picture...
            got.append(frame)
        v = screencast.Viewer(send, lambda m: None)
        v.offer(b"1")
        time.sleep(0.1)
        for n in (b"2", b"3", b"4"):  # ...while three more arrive
            v.offer(n)
        gate.set()
        time.sleep(0.3)
        v.close()
        self.assertEqual(got, [b"1", b"4"])  # 2 and 3 were skipped, nothing fell behind


if __name__ == "__main__":
    unittest.main()
