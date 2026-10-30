#!/usr/bin/env python3
"""Send files, folders or a link to every connected Fedora Pad (tablet, iPhone, iPad).

    send_to_pad.py photo.jpg report.pdf      files (a folder goes as a zip)
    send_to_pad.py --text "https://…"        a link or some text

Used by Files › right-click › Scripts › "Send to Fedora Pad" (Fedora) and by
right-click › Send to › Fedora Pad (Windows). It only drops a small job file in
Fedora Pad's outbox; the running host does the sending.
"""
import json
import os
import sys
import time
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pad_extras import outbox_dir  # noqa: E402


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return 1
    if args[0] == "--text":
        job = {"text": " ".join(args[1:])}
    else:
        job = {"files": [os.path.abspath(a) for a in args if os.path.exists(a)]}
        if not job["files"]:
            print("Nothing to send.")
            return 1
    os.makedirs(outbox_dir(), exist_ok=True)
    name = os.path.join(outbox_dir(), "%d-%s" % (time.time() * 1000, uuid.uuid4().hex[:8]))
    with open(name + ".tmp", "w", encoding="utf-8") as f:
        json.dump(job, f)
    os.replace(name + ".tmp", name + ".json")  # the host only picks up finished jobs
    return 0


if __name__ == "__main__":
    sys.exit(main())
