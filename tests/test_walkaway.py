"""Walk-away lock: when to lock, from the times the iPhone was heard."""
import unittest

import _path  # noqa: F401
import walkaway


class PresenceTest(unittest.TestCase):
    def test_locks_once_after_the_phone_leaves(self):
        p = walkaway.Presence(threshold=-80, after=60)
        self.assertFalse(p.should_lock(0))          # never seen: nothing to do
        p.seen(-60, 10)
        self.assertFalse(p.should_lock(50))         # 40 s: not yet
        p.seen(-95, 55)                              # weak: counts as away
        self.assertTrue(p.should_lock(71))          # 61 s since it was near
        self.assertFalse(p.should_lock(200))        # only once per absence
        p.seen(-70, 300)                             # back: the next absence locks again
        self.assertFalse(p.should_lock(320))
        self.assertTrue(p.should_lock(361))

    def test_only_when_not_heard_at_all(self):
        p = walkaway.Presence(threshold=None, after=60)
        p.seen(-99, 0)                               # any signal counts as near
        self.assertFalse(p.should_lock(59))
        self.assertTrue(p.should_lock(61))

    def test_settings_file(self):
        self.assertEqual(walkaway.load("/nonexistent/walkaway.json"), {})


if __name__ == "__main__":
    unittest.main()
