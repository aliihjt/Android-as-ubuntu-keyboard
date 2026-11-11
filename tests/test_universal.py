"""Universal Control: the laptop's devices -> messages for the tablet, and the hand-over."""
import tempfile
import unittest

import _path  # noqa: F401
import universal as uc


class KeysTest(unittest.TestCase):
    def test_names(self):
        self.assertEqual(uc.android_key("KEY_A"), "KEYCODE_A")
        self.assertEqual(uc.android_key("KEY_7"), "KEYCODE_7")
        self.assertEqual(uc.android_key("KEY_BACKSPACE"), "KEYCODE_DEL")
        self.assertEqual(uc.android_key("KEY_LEFTCTRL"), "KEYCODE_CTRL_LEFT")
        self.assertEqual(uc.android_key("KEY_DOT"), "KEYCODE_PERIOD")
        self.assertIsNone(uc.android_key("KEY_PROG1"))


class TouchpadTest(unittest.TestCase):
    def setUp(self):
        self.out = []
        self.tp = uc.Touchpad(self.out.append, res_x=10, res_y=10)

    def feed(self, *events, t=0.0):
        for ev in events:
            self.tp.event(*ev, t)
        self.tp.event("EV_SYN", "SYN_REPORT", 0, t)

    def test_one_finger_moves(self):
        self.feed(("EV_KEY", "BTN_TOUCH", 1), ("EV_KEY", "BTN_TOOL_FINGER", 1), ("EV_ABS", "ABS_X", 100), ("EV_ABS", "ABS_Y", 100))
        self.feed(("EV_ABS", "ABS_X", 120), ("EV_ABS", "ABS_Y", 110), t=0.05)  # 2 mm right, 1 mm down
        self.assertEqual(self.out[-1], {"a": "move", "dx": round(2 * uc.TOUCHPAD_PX_PER_MM, 1),
                                        "dy": round(1 * uc.TOUCHPAD_PX_PER_MM, 1)})
        self.assertAlmostEqual(self.tp.push, 2.0)

    def test_tap_is_a_click_and_two_finger_tap_a_right_click(self):
        self.feed(("EV_KEY", "BTN_TOUCH", 1), ("EV_KEY", "BTN_TOOL_FINGER", 1), ("EV_ABS", "ABS_X", 100))
        self.feed(("EV_KEY", "BTN_TOUCH", 0), ("EV_KEY", "BTN_TOOL_FINGER", 0), t=0.1)
        self.assertEqual(self.out[-2:], [{"a": "button", "b": "left", "v": 1}, {"a": "button", "b": "left", "v": 0}])
        self.feed(("EV_KEY", "BTN_TOUCH", 1), ("EV_KEY", "BTN_TOOL_DOUBLETAP", 1), t=1.0)
        self.feed(("EV_KEY", "BTN_TOUCH", 0), ("EV_KEY", "BTN_TOOL_DOUBLETAP", 0), t=1.1)
        self.assertEqual(self.out[-1], {"a": "button", "b": "right", "v": 0})

    def test_slow_touch_is_not_a_tap(self):
        self.feed(("EV_KEY", "BTN_TOUCH", 1), ("EV_KEY", "BTN_TOOL_FINGER", 1))
        self.feed(("EV_KEY", "BTN_TOUCH", 0), t=0.8)
        self.assertEqual(self.out, [])

    def test_two_fingers_scroll(self):
        self.feed(("EV_KEY", "BTN_TOUCH", 1), ("EV_KEY", "BTN_TOOL_DOUBLETAP", 1), ("EV_ABS", "ABS_Y", 100))
        self.feed(("EV_ABS", "ABS_Y", 150), t=0.05)  # 5 mm down: the page follows
        self.assertEqual(self.out[-1], {"a": "scroll", "dx": 0, "dy": round(5 * uc.TOUCHPAD_PX_PER_MM)})


class MouseTest(unittest.TestCase):
    def test_move_wheel_buttons(self):
        out = []
        m = uc.Mouse(out.append)
        m.event("EV_REL", "REL_X", 5, 0)
        m.event("EV_REL", "REL_Y", -2, 0)
        m.event("EV_SYN", "SYN_REPORT", 0, 0)
        m.event("EV_REL", "REL_WHEEL", 1, 0)
        m.event("EV_KEY", "BTN_RIGHT", 1, 0)
        self.assertEqual(out, [{"a": "move", "dx": 5, "dy": -2}, {"a": "scroll", "dx": 0, "dy": uc.WHEEL_PX},
                               {"a": "button", "b": "right", "v": 1}])
        self.assertEqual(m.push, 5)


class HandOverTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.u = uc.Universal(lambda age: None, self.dir.name, dry_run=True)
        self.got = []
        self.tablet = self.got.append

    def tearDown(self):
        self.dir.cleanup()

    def test_edge_only_with_one_monitor(self):
        sc = {"x": 0, "y": 0, "w": 1920, "h": 1080, "px": 1919, "py": 540, "monitors": [{}]}
        self.assertTrue(self.u.edge(sc))
        self.assertAlmostEqual(self.u.edge_y, 0.5)
        self.assertFalse(self.u.edge(dict(sc, px=1000)))
        self.assertFalse(self.u.edge(dict(sc, monitors=[{}, {}])))  # tablet is a second screen
        self.assertFalse(self.u.edge(None))

    def test_enter_ack_keys_exit(self):
        u = self.u
        u.handle({"t": "ucready", "on": True}, self.tablet)
        u.enter()
        self.assertTrue(u.active)
        self.assertEqual(self.got[-1]["a"], "enter")
        u.handle({"t": "uc", "a": "entered"}, self.tablet)
        u._key("KEY_A", 1)
        self.assertEqual(self.got[-1], {"t": "uc", "a": "key", "k": "KEYCODE_A", "v": 1})
        u.handle({"t": "uc", "a": "exit"}, self.tablet)
        self.assertFalse(u.active)

    def test_ctrl_alt_esc_and_silence_give_devices_back(self):
        u = self.u
        u.handle({"t": "ucready", "on": True}, self.tablet)
        u.enter()
        u._key("KEY_LEFTCTRL", 1)
        u._key("KEY_LEFTALT", 1)
        u._key("KEY_ESC", 1)
        self.assertFalse(u.active)
        self.assertEqual(self.got[-1]["a"], "leave")
        u.enter()
        u.check_alive(u.enter_at + uc.ACK_S + 0.1)  # the tablet never answered
        self.assertFalse(u.active)

    def test_settings_and_other_devices(self):
        u = self.u
        replies = []
        u.handle({"t": "ucset", "on": False, "side": "left"}, replies.append)
        self.assertEqual(replies[-1]["side"], "left")
        u.handle({"t": "ucready", "on": True}, self.tablet)
        u.enter()  # switched off: never starts
        self.assertFalse(u.active)
        again = uc.Universal(lambda age: None, self.dir.name, dry_run=True)
        self.assertEqual(again.conf["side"], "left")  # saved
        self.assertFalse(again.handle({"t": "other"}, self.tablet))
        u.handle({"t": "ucready", "on": True}, self.tablet)
        u.gone(self.tablet)
        self.assertIsNone(u.target)


if __name__ == "__main__":
    unittest.main()
