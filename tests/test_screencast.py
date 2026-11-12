"""Laptop Screen: the pipeline text and a slow phone only getting the newest picture."""
import tempfile
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

    def test_three_ways(self):
        gl = screencast.pipeline_text(7, 42, 960, method="gl")
        self.assertIn("glupload ! glcolorconvert ! gldownload", gl)
        self.assertNotIn("always-copy", gl)
        mem = screencast.pipeline_text(7, 42, 960, method="memory")
        self.assertIn("always-copy=true", mem)
        self.assertNotIn("always-copy", screencast.pipeline_text(7, 42, 960, props=()))  # an older PipeWire
        shell = screencast.pipeline_text(None, None, 960, method="shell")
        self.assertTrue(shell.startswith("appsrc name=src"))
        self.assertNotIn("videorate", shell)
        for text in (gl, mem, shell):
            self.assertIn("format=I420 ! jpegenc name=enc", text)

    def test_black_picture(self):
        w, h = 64, 48
        self.assertTrue(screencast.flat(bytes([16]) * (w * h * 3 // 2), w, h))       # black
        self.assertTrue(screencast.flat(bytes([235]) * (w * h * 3 // 2), w, h))      # all white
        picture = bytearray([16]) * (w * h * 3 // 2)
        picture[20 * w + 10] = 200  # one bright spot: text, an icon
        self.assertFalse(screencast.flat(bytes(picture), w, h))
        self.assertTrue(screencast.flat(b"", w, h))

    def test_judge(self):
        j = screencast.Judge()
        self.assertIsNone(j.verdict(1))
        for _ in range(6):
            j.frame(True)
        self.assertIsNone(j.verdict(2))          # give it a moment
        self.assertEqual(j.verdict(4.5), "black")
        j.frame(False)
        self.assertEqual(j.verdict(9), "good")   # once it shows the screen, it stays
        self.assertEqual(screencast.Judge().verdict(9), "nothing")

    def test_remembers_the_way_that_worked(self):
        if screencast.Gst is None:
            self.skipTest("no GStreamer here")
        screencast.Gst.init(None)
        cast = screencast.ScreenCast(tempfile.mkdtemp())
        self.assertEqual(cast._methods()[-2:], ["memory", "shell"])
        cast._remember("shell")
        self.assertEqual(cast._methods()[0], "shell")

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
