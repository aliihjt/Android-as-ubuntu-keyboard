"""Pen Tablet: device messages -> pen events (a dry run records them)."""
import unittest

import _path  # noqa: F401
import pen


def flat(log):
    return [ev for batch in log for ev in batch]


class PenTest(unittest.TestCase):
    def test_stroke(self):
        p = pen.Pen(dry_run=True)
        p.handle({"t": "pen", "s": "down", "x": 0.5, "y": 0.25, "p": 0.5})
        p.handle({"t": "pen", "s": "move", "x": 0.6, "y": 0.25, "p": 0.5})
        p.handle({"t": "pen", "s": "up"})
        ev = flat(p.log)
        self.assertEqual(ev[0], ("EV_KEY", "BTN_TOOL_PEN", 1))
        self.assertIn(("EV_ABS", "ABS_X", round(0.5 * pen.MAX)), ev)
        self.assertIn(("EV_KEY", "BTN_TOUCH", 1), ev)
        self.assertEqual(ev.count(("EV_KEY", "BTN_TOUCH", 1)), 1)  # the touch starts once
        self.assertEqual(ev[-1], ("EV_KEY", "BTN_TOUCH", 0))
        self.assertFalse(p.touching)

    def test_eraser_swaps_tool_and_release_lifts(self):
        p = pen.Pen(dry_run=True)
        p.handle({"t": "pen", "s": "down", "x": 0.1, "y": 0.1})
        p.handle({"t": "pen", "s": "down", "x": 0.1, "y": 0.1, "tool": "eraser"})
        ev = flat(p.log)
        self.assertIn(("EV_KEY", "BTN_TOOL_PEN", 0), ev)
        self.assertIn(("EV_KEY", "BTN_TOOL_RUBBER", 1), ev)
        p.release()  # the tablet went away mid-stroke
        self.assertFalse(p.touching)
        self.assertIsNone(p.tool)

    def test_right_click_and_bad_numbers(self):
        p = pen.Pen(dry_run=True)
        p.handle({"t": "pen", "s": "right", "x": "nan", "y": None})
        ev = flat(p.log)
        self.assertIn(("EV_KEY", "BTN_STYLUS", 1), ev)
        self.assertIn(("EV_KEY", "BTN_STYLUS", 0), ev)
        self.assertIn(("EV_ABS", "ABS_X", 0), ev)

    def test_laptop_screen_inside_a_wider_desktop(self):
        # second screen to the right: desktop 3840 wide, the laptop screen is the left half
        p = pen.Pen(dry_run=True, screen=lambda: {"sw": 3840, "sh": 1080, "x": 0, "y": 0, "w": 1920, "h": 1080})
        self.assertEqual(p._position(1.0, 1.0), (round(0.5 * pen.MAX), pen.MAX))


if __name__ == "__main__":
    unittest.main()
