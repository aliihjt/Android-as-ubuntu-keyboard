"""The tablet clean-up list must never remove anything the tablet or Fedora Pad needs."""
import os
import re
import unittest

import _path

NEVER = {
    "io.github.aliihjt.tabletdesk", "com.android.vending", "com.google.android.gms",
    "com.google.android.gsf", "com.android.settings", "com.android.systemui",
    "com.sec.android.app.launcher", "com.samsung.android.honeyboard", "com.google.android.inputmethod.latin",
    "com.google.android.tts", "com.samsung.SMT", "com.sec.android.app.camera",
    "com.google.android.googlequicksearchbox", "com.google.android.apps.translate",
    "com.microsoft.rdc.androidx", "com.anthropic.claude", "ph.spacedesk.beta", "com.gaurav.avnc",
    "com.iisulauncher", "com.android.providers.downloads", "com.android.documentsui",
}


class TabletCleanListTest(unittest.TestCase):
    def test_list(self):
        seen = set()
        with open(os.path.join(_path.HOST, "tablet-clean.txt"), encoding="utf-8") as f:
            for n, line in enumerate(f, 1):
                if not line.strip() or line.startswith("#"):
                    continue
                parts = [p.strip() for p in line.split("|")]
                self.assertEqual(len(parts), 3, f"line {n}")
                group, pkg, what = parts
                self.assertIn(group, ("junk", "optional"), f"line {n}")
                self.assertRegex(pkg, r"^[a-zA-Z][\w]*(\.[\w]+)+$", f"line {n}")
                self.assertTrue(what, f"line {n}")
                self.assertNotIn(pkg, NEVER, f"line {n}")
                self.assertNotIn(pkg, seen, f"line {n}: listed twice")
                seen.add(pkg)

    def test_fedora_pad_helpers_never_listed(self):
        with open(os.path.join(os.path.dirname(_path.HOST), "android", "app", "src", "main",
                               "AndroidManifest.xml"), encoding="utf-8") as f:
            used = set(re.findall(r'<package android:name="([^"]+)"', f.read()))
        with open(os.path.join(_path.HOST, "tablet-clean.txt"), encoding="utf-8") as f:
            text = f.read()
        for pkg in used:
            self.assertNotIn("| " + pkg + " |", text)


if __name__ == "__main__":
    unittest.main()
